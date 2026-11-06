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

- [x] 标记 Task 进入持久队列、Attempt 创建、Agent/Worker 分配、Lease 创建及 NATS 发布的事务边界。
- [x] 查清当前固定槽位的并发度、失败重试、租约续期、任务取消和副作用幂等语义。
- [x] 画出每个可用槽位的最大活动 Attempt 数，以及可用于 Tenant fairness 的现有持久化字段。
- [x] 若无法原子领取 Task/Attempt/Lease，先记录最小 schema/事务变更，再进入调度实现。

**完成证据（2026-11-06 固定上游源码 commit `0156f2c1fecdc868cb4a0f4f13fb068bb4162834`）：** `TaskAssignmentScheduler` 由单一 `scheduler_leases` 租约激活，每秒扫描；`TaskRepository.findIdsByPhase(QUEUED)` 当前按 `priority DESC, created_at ASC, id ASC` 全局分页，`queuedTaskOffset` 是内存偏移，仅避免无容量旧页长期挡住新任务，没有 Tenant fairness。单项分配在一个数据库事务中锁 Task、选 Agent、更新 Task phase、插入 Attempt/Assignment/Agent Lease，并通过 outbox 记录 `TaskAssigned`；Worker 侧 NATS 只是事件通知，DB 是队列和状态事实来源。`findReadyMatchingInTaskProject` 只按 Task/Worker Tenant+Project scope 匹配并锁定 READY Agent，没有排除有效活动 `agent_leases`；Team 路径则已调用 `countActiveForAgent`。因此目前 Project scope 批次可能把同一仍为 READY 的 Agent 连续分配给多个任务，槽位单活动 Attempt 不变量尚未被所有路径保证。Attempt Lease 使用配置时长（当前默认 30s）续租；`TaskAssignmentScheduler` 负责到期 Lease 恢复。可复用的持久 scope 是 `resource_scopes` 的 TASK/WORKER 行；tasks 本身没有 Tenant 列，Tenant queue 必须从 TASK scope 解析。缺 scope 的全局池回退只适用于匿名 local-dev，必须在共享池入口拒绝。现有 Testcontainers `FoundationRepositoryIT` 仅证明无 scope 全局回退和 scoped Tenant/Project 匹配，没有验证同 Agent 已有活动 Lease 时拒绝再次分配。

调度算法实现前的先行容量修复：已按 TDD 在 pinned-source 隔离副本中先运行回归测试并复现失败（活动 Lease 时仍选中 READY Worker），然后在 Project scope Agent 选择 SQL 加入有效活动 Lease 检查；同一 Testcontainers PostgreSQL 测试确认活动 Lease 返回空，标记 RELEASED 后重新选中该 Worker。Team 路径已有活动 Lease 检查与 Agent 行锁。干净上游副本应用全部 Compose patch 的 `patch --dry-run` 已通过；本地 PostgreSQL 集成测试分别实跑 1/1 通过：活动 Lease/释放可重用，以及两个并发任务只产生一个活动 Lease。实现和两项 PostgreSQL 测试已纳入 Compose Control Plane hardening patch；新增纯 `TenantFairQueue` weighted deficit 轮询组件，测试锁定 A:B 权重 2:1 长期分配比例、活动上限、空队列/配额阻塞及非法配置。第一次算法测试暴露轮转游标导致的 1:1 退化，修正后 4/4 通过。纯组件和所有测试已纳入 Compose Control Plane hardening patch；构建门禁分别要求 PostgreSQL 两项、调度单测四项零失败/错误/跳过。调度算法尚未接入数据库 Candidate 加载/TaskAssignmentScheduler，也未实现持久化 Tenant 权重/并发/队列配额，不能称为端到端公平派发已完成。

### 任务 1A：实现 Tenant-neutral Worker 与 Attempt capability（公平调度前置门槛）

- [ ] Enrollment 为固定共享槽位签发 Tenant-neutral Workload 身份；不复用某个 Tenant 管理员长期 Session 作为共享池身份。
- [ ] Task/Attempt/Lease 原子领取时生成随机 Attempt capability，仅存哈希，明文只交给已认证的目标 Worker；租约释放、取消、超时/恢复时撤销。
- [ ] Control Plane TaskEvent、文件/Artifact/workspace API 从 capability 派生 Attempt 和 Task scope，拒绝 Worker 请求自报 Tenant；覆盖缺失、过期、伪造、跨 Attempt、跨 Tenant 测试。
- [ ] Credential Proxy 以 capability 解析活跃 Attempt 与 TASK scope，并按 Tenant/Project 选择 Provider 凭据；长期 Worker Session 不承载 Tenant modelCredentialRef。
- [ ] Control Plane 原子签发/轮换 capability，并通过不落明文数据库的 Worker 领取协议传递；Attempt/Lease 结束时撤销或自然失效。
- [ ] TaskEvent、Artifact、workspace 请求统一验证 capability；清理上下文/workspace 后才释放共享槽位。
- [ ] 同一固定槽位连续执行 Tenant A/B Attempt，验证 workspace、凭据引用、事件和 Artifact 不能串用；完成清理后才允许再领取。
- [ ] 固定上游补丁、Enrollment、Credential Proxy 各自 CI 硬门禁验证上述合同，零跳过。

**完成证据：** A/B 交替 Attempt 的 PostgreSQL/HTTP/代理集成测试和真实 Worker POC；旧的 scope 相等校验只有在新的 Attempt capability 校验已验证替代后才能移除。

**进度更新（2026-11-06）：** Credential Proxy 已实现 Attempt-bound token 数据解析原型，token hash 绑定 Agent/Task/Attempt/Lease；Tenant/Project/Team scope 从 Task scope 派生，只有对应 Assignment/Attempt/Lease 仍活动时认证和重验证才成功。旧 Tenant-bound Enrollment token 保持严格 scope 校验。Credential Proxy 模块全量 48 项测试通过（PostgreSQL Testcontainers 10 项在本机 Docker 实际运行；0 failure/error/skipped）。当前集成 fixture 直接写入 capability 行；Control Plane 尚未在分配事务中签发，也没有安全 Worker 领取协议，TaskEvent/Artifact/workspace 尚未验证 capability。因此共享池门槛未完成；不能先移除现有 Worker/Task scope guard。

### 任务 2：定义并测试租户公平调度器

- [x] 为公平轮转和 Tenant 活动上限写纯单元测试：权重高的 Tenant 长期获得相应更多机会，但任何 Tenant 都不能绕过并发上限。
- [ ] 将纯调度器接入 Control Plane 持久队列读取，确保每 Tenant 的候选列表按优先级/入队时间排序，并在租户资格耗尽后选择其他可运行租户。
- [x] PostgreSQL 回归：Agent 已有活动 Lease 时 Project scope 选择为空；Lease 释放后重新可选。
- [x] 两个 Task 并发分配的事务竞争测试：恰好一个任务被派发，且同一 Agent 只有一个活动 Lease。
- [ ] 覆盖只有一个 Tenant、有多个 Tenant、空队列、过额任务和任务取消后额度释放。
- [x] 先实现确定性的加权 deficit 轮询组件，不在算法内读取数据库或调用 NATS。
- [ ] 决定公平游标和 deficit 跨 Control Plane 重启的持久化策略；不能让进程重启长期偏向 UUID 排序靠前的 Tenant。
- [ ] 通过调度端口把合格 Tenant 的下一条任务与全局可用槽位连接起来。

**完成证据：** 将算法测试加入上游临时源码补丁，`build-control-plane-hardening.py` 的报告门禁精确检查测试数和零跳过；算法测试重复运行稳定，排队顺序和配额拒绝原因可解释。

### 任务 3：实现持久化原子领取和配额

- [ ] 设计带迁移的 Tenant 调度配置/额度持久化；为权重、最大活动 Attempt、最大队列深度设置范围校验。
- [ ] `TaskRepository.findIdsByPhase` 在共享 Worker 模式拒绝无 TASK scope 的任务；只允许无认证的 local-dev 继续使用全局池回退。
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
