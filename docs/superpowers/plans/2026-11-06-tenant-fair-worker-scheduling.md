# 固定容量共享 Worker 租户公平调度实施计划

> **面向 AI 代理的工作者：** 按批次执行，并在每批后审查；代码步骤先写失败测试，再实现。不得修改 `agentteams-java`。

**目标：** 在固定 Worker 容量内持久、公平地派发多租户长短任务，并验证租户并发上限、Lease 恢复与执行隔离。

**架构：** Control Plane 维护持久 Tenant 队列和公平调度状态，以数据库事务原子地领取全局槽位及 Tenant 并发额度；NATS 仅用于可重放的唤醒/派发通知。Worker 数量保持静态，Attempt/Lease 是运行和恢复事实来源。

**技术栈：** 现有 Java 21 Control Plane/Worker、PostgreSQL、NATS JetStream、Docker Compose Testcontainers。

---

## 文件职责

- 修改 `deploy/compose/patches/control-plane-v0.1.5-task-event-scope.patch`：给固定上游 Control Plane 增加 Tenant 公平队列、并发额度和原子领取逻辑；上游源码只在构建脚本的临时目录展开，不直接编辑 `agentteams-java`。
- 修改同一补丁中的 Control Plane 调度端口、持久化适配器和调度服务：隔离公平算法与数据库锁/通知实现。
- 修改同一补丁中的 PostgreSQL 集成测试：验证多实例并发领取、租户上限、权重轮转、租约恢复和 RLS/scope。
- 修改 `deploy/compose/build-control-plane-hardening.py`：验证新增集成测试真实执行且零跳过，并将补丁摘要写入镜像标签。
- 修改 `deploy/compose/compose.yaml`、`deploy/compose/.env.example`、`deploy/compose/validate-config.sh`：暴露固定容量调度策略及 fail-closed 配置校验。
- 修改 `verification/test_compose_fixed_worker_capacity.py`、`verification/test_compose_worker_restart.py`：用可控长短任务验证共享池公平性和恢复。
- 更新 `deploy/compose/README.md`、`docs/plans/2026-11-04-agentteams-new-bootstrap.md`：记录配额语义、饱和限制、操作方式和证据。

## 执行任务

### 任务 1：盘点现有派发路径与锁边界

- [ ] 标记 Task 进入持久队列、Attempt 创建、Agent/Worker 分配、Lease 创建及 NATS 发布的事务边界。
- [ ] 查清当前固定槽位的并发度、失败重试、租约续期、任务取消和副作用幂等语义。
- [ ] 画出每个可用槽位的最大活动 Attempt 数，以及可用于 Tenant fairness 的现有持久化字段。
- [ ] 若无法原子领取 Task/Attempt/Lease，先记录最小 schema/事务变更，再进入调度实现。

**完成证据：** `control-plane-v0.1.5-task-event-scope.patch` 中的派发调用链、当前 Schema/SQL、现有 `WorkerDrainLifecycleTest`/TaskEvent 集成测试，以及一个能复现队列/租约状态的 Compose 场景。

### 任务 2：定义并测试租户公平调度器

- [ ] 为公平轮转和 Tenant 活动上限写纯单元测试：权重高的 Tenant 长期获得相应更多机会，但任何 Tenant 都不能绕过并发上限。
- [ ] 覆盖只有一个 Tenant、有多个 Tenant、空队列、过额任务和任务取消后额度释放。
- [ ] 先实现确定性的加权公平轮询组件，不在算法内读取数据库或调用 NATS。
- [ ] 通过调度端口把合格 Tenant 的下一条任务与全局可用槽位连接起来。

**完成证据：** 将算法测试加入上游临时源码补丁，`build-control-plane-hardening.py` 的报告门禁精确检查测试数和零跳过；算法测试重复运行稳定，排队顺序和配额拒绝原因可解释。

### 任务 3：实现持久化原子领取和配额

- [ ] 设计带迁移的 Tenant 调度配置/额度持久化；为权重、最大活动 Attempt、最大队列深度设置范围校验。
- [ ] 为任务领取、Attempt、Lease 和配额计数设计一个可回滚数据库事务；并发实例通过 `FOR UPDATE SKIP LOCKED` 或等价行锁避免双领取。
- [ ] 对 Task/Attempt 的 Tenant/Project/Team scope 做服务端解析；不接受 Worker 提供的租户身份覆盖调度记录。
- [ ] 覆盖取消、重试、Attempt 终态、Worker 失联/Lease 到期时配额释放和幂等恢复。
- [ ] NATS 发布失败后依靠 outbox 或可重放状态补发，避免“数据库已领取但事件丢失”。

**完成证据：** PostgreSQL 集成测试并发运行多个 Scheduler，Task/Lease/额度都无重复或负计数，跨租户查询依旧被拒。

### 任务 4：固定容量 Compose 长短任务端到端验收

- [ ] 启动固定 2 槽位，不执行 Compose scale 或动态容器创建。
- [ ] Tenant A 先领取一个可控长任务；A 达到每租户并发上限后继续提交任务，使其排队。
- [ ] Tenant B 提交短任务；验证它在剩余固定槽位开始时，A 的长任务仍在运行。
- [ ] 占满全部槽位后再提交 B：验证 B 留在队列；释放一个 Attempt 或让过期 Lease 恢复后，验证 B 随公平规则启动。
- [ ] 对照 Postgres/NATS/Worker 日志验证 Task scope、Attempt、Lease、workspace 和凭证代理上下文都指向正确 Tenant。
- [ ] 重启单个 Worker 固定槽位；验证旧 Lease 清理、Attempt 重试策略、幂等副作用及 Tenant 额度恢复。

**完成证据：** 可重复的 Compose 验收脚本、请求/响应及 DB 状态快照；清理后固定容量栈仍健康。若无法为真实业务任务构造幂等/检查点测试夹具，应将该能力缺口列为上线门槛。

### 任务 5：长任务资源与运行模型基准

- [ ] 为 CPU、内存、Attempt wall-clock、心跳和取消设置硬上限及明确失败状态。
- [ ] 选择代表性 I/O 密集、CPU 密集和长工具等待任务，测量现有执行器的 CPU、RSS、线程数、租户排队时间和任务吞吐。
- [ ] 单独比较 Java 虚拟线程与当前执行器；验证 ThreadLocal/安全 Principal、租户上下文、取消传播和连接池背压。
- [ ] 只有 I/O 负载达到明确收益且上下文传播通过测试，才提出虚拟线程实现提交；CPU 任务保持有界执行器，不可信代码仍要求沙箱。

**完成证据：** 可复现的基准输入和数据；不得以虚拟线程替代公平配额、Lease 恢复或进程隔离。

### 任务 6：运营说明和验收门禁

- [ ] 文档化配置项、缺省值、队列满/配额满行为、优先级/权重变更和人工取消方法。
- [ ] 暴露按 Tenant 统计的排队时长、活动 Attempt、饱和时间、配额拒绝与重试指标，不记录 prompt、凭证或跨租户数据。
- [ ] 更新 CI：公平算法、PostgreSQL 原子领取、Compose 固定容量长短任务和恢复测试必须执行，集成测试不允许跳过。
- [ ] 明确单 Worker 槽位下任务只能串行；全容量长 Attempt 未到安全让出点前，不承诺短任务等待上限。

**完成证据：** CI 所有必需 job 成功，测试零跳过，操作手册与实际配置一致。
