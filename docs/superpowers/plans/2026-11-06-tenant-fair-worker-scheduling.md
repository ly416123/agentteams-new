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

- [x] Enrollment 为固定共享槽位签发 Tenant-neutral Workload 身份；不复用某个 Tenant 管理员长期 Session 作为共享池身份（slot session 端点、哈希存储、受限 DB 验证函数、Gateway 独立身份键及 L5 AMD64 gRPC 握手已完成；Worker 启动与调度绑定仍待后续项）。
- [ ] Task/Attempt/Lease 原子领取时生成随机 Attempt capability，仅存哈希，明文只交给已认证的目标 Worker；租约释放、取消、超时/恢复时撤销。
- [ ] Control Plane TaskEvent、文件/Artifact/workspace API 从 capability 派生 Attempt 和 Task scope，拒绝 Worker 请求自报 Tenant；覆盖缺失、过期、伪造、跨 Attempt、跨 Tenant 测试。
- [x] Credential Proxy 以 capability 解析活跃 Attempt 与 TASK scope，并按 Tenant/Project 选择 Provider 凭据；长期 Worker Session 不承载 Tenant modelCredentialRef。
- [ ] Control Plane 原子签发/轮换 capability，并通过不落明文数据库的 Worker 领取协议传递；Attempt/Lease 结束时撤销或自然失效。
- [ ] TaskEvent、Artifact、workspace 请求统一验证 capability；清理上下文/workspace 后才释放共享槽位。
- [ ] 同一固定槽位连续执行 Tenant A/B Attempt，验证 workspace、凭据引用、事件和 Artifact 不能串用；完成清理后才允许再领取。
- [ ] 固定上游补丁、Enrollment、Credential Proxy 各自 CI 硬门禁验证上述合同，零跳过。
- [x] Credential Proxy 根据 Gateway session、活动 Assignment/Attempt/Lease 和部署侧 Tenant 凭据映射签发 Attempt token；数据库仅存 token hash，运行角色无表访问权限。
- [ ] Worker 串行领取/续领 Attempt token，并通过受保护的内部传输交付；长任务在 Lease 续期后须能继续调用模型代理。

**完成证据：** A/B 交替 Attempt 的 PostgreSQL/HTTP/代理集成测试和真实 Worker POC；旧的 scope 相等校验只有在新的 Attempt capability 校验已验证替代后才能移除。

**进度更新（2026-11-06）：** Credential Proxy 已实现 Attempt-bound token 校验和内部签发 API。Worker 以 Gateway session bearer 证明 Agent 身份，只有该 Agent 当前活动的 Task/Attempt/Lease 可签发；Tenant/Project/Team scope 从 Task scope 派生，Provider credential ref 从部署侧可信映射读取，数据库仅存新 token 的 SHA-256。旧 Tenant-bound Enrollment token 保持严格 scope 校验。Worker 补丁现通过按模型请求获取不缓存的 Attempt token 接入该 API；精确 Worker 测试 31 项、Credential Proxy 全量 54 项（PostgreSQL Testcontainers 13 项及签发 HTTP 3 项）通过，零失败/跳过。L5 固定 Tenant A 槽位已用该 Worker 镜像启动并进入 `READY`，Credential Proxy healthy；没有静态模型密钥配置。L5 验证曾发现槽位 Session 到期，现通过独立测试 Keycloak 重新 Enrollment 为 7 天并确认撤销/过期状态；凭据到期后的自动 Session 轮换尚未实现。PR commit `3894f1c` 的 CI run `42193166952` 全部通过。仍缺 Control Plane 领取事务向 Worker 交付 Attempt capability、任务事件/Artifact/workspace 统一校验 capability、Lease 延期后的真实模型请求及 A/B 连续执行验收；当前 L5 只有 Worker ready，没有真实任务闭环。因此共享池门槛未完成，不能移除现有 Worker/Task scope guard。

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

**源码盘点：** Runtime 已存在 `maxConcurrency`，`AgentScopeRuntime` 用 task-id 映射独立 Execution 并订阅异步事件流；但本批 Control Plane 安全补丁把每个逻辑 Worker/Agent 限为一个活动 Lease。故当前已支持的是“有界 Runtime 并发接口”，不是“同一 Agent/Pod 多 Attempt 调度”。引入同 Pod 多执行 lane 前，必须同步实现固定 lane 原子租用、租户额度、每 Attempt workspace/凭据/会话边界和终态清理；仅调大 `maxConcurrency` 不会生效且可能绕过数据库容量约束。

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

### 阶段复核（2026-11-06）

- PR #1 当前 head 为 `2ddacfa031f97a70c0fec940ecd2c95d8a73a029`。CI run `42197110621` 全部成功；工作流的 `GatewaySessionHandshakeTest` 门禁要求 pinned Gateway gRPC 握手 5 项实际执行且零跳过。历史 Gateway 握手拒绝缺陷已在 v0.1.5 修复；本次 run 未复现握手失败。
- 当前 L5 SSH 目标为 K3s 单节点，管理员 kubeconfig 可通过 SSH 使用；固定 Compose 栈由 `compose-daemon` 管理。现有容器的 Worker 补丁 SHA-256 与仓库补丁一致，Credential Proxy healthy，Control Plane readiness 为 `UP`。Compose 配置校验通过，当前固定容量为 1 个 Worker 槽位。
- **更正：**此前从本机 `127.0.0.1:18080` 发出的 403/404 探测实际命中了本地 Colima Control Plane，而非 L5 DIND；该组结果不作为 L5 证据。应从 `agentteams-new-validation` 的 `compose-daemon` 网络直接请求 L5 Control Plane。
- 初始复核时 Compose 数据库只有 1 个 Tenant A DRAFT Task、1 个 Agent、0 个活动 Attempt/Lease，固定 2 槽位长短任务、Tenant B 项目、Worker A/B 交替执行和 Lease 恢复尚未验收。后续 L5 API 验收已建立 Tenant B 测试组织、Project 与 OWNER membership；具体 A/B HTTP 证据见下方复核记录。
- A/B Project 与 Agent API 的正向及交叉租户拒绝已验收，但 Task/Artifact/workspace API、Worker session、Attempt capability、凭证代理和共享槽位连续复用仍未验收；不得将 Project/Agent API 结果扩大解释为共享 Worker 隔离完成。

**L5 后续复核（2026-11-06）：** 用户已明确授权可使用 L5 资源；SSH `ly@192.168.122.55` 能访问 `agentteams-new-validation` 的 `compose-daemon`，本机 Docker CLI 经 `127.0.0.1:23765` 操作 DIND。L5 上原 Control Plane 镜像补丁摘要对应较早提交；使用当前工作树构建脚本重建 amd64 派生镜像，Control Plane 45 项必需测试及 Gateway/NATS/Control Plane 事件链门禁通过，零失败、零跳过。先对 L5 Postgres 做了权限 0600 的 custom-format 一致性快照 `/tmp/agentteams-new-l5-pre-v122.dump`（517572 bytes，`pg_restore --list` 校验通过），再仅重建 Control Plane；数据库由 Flyway v121 前进到 v122，其他服务与数据卷未重建。新镜像 revision 为 `0156f2c1fecdc868cb4a0f4f13fb068bb4162834`，补丁摘要与当前文件一致（`6893a8b3daf9dcae173e2856690b1701a639eff57dd959b68ea7112aae597b91`），容器 RestartCount=0，健康检查 UP。

**L5 API 认证与 Project scope 验收（2026-11-06）：**通过 `compose-daemon` Pod 内 Docker 发布端口直连目标 Control Plane；无 Bearer 与无效 Bearer 对 `/api/v1/projects` 均为 HTTP 401。Spring 启动日志显示 `apiAuthenticationFilter urls=[/api/*] order=-2147483648`，实际 Jar 字节码也确认缺失凭证分支返回 401。之前的 403 是本机 Colima 目标误判，已更正；L5 未发现认证过滤器未注册问题。测试 Keycloak 为 A/B 分别签发新鲜 300 秒 token，`iss`、`aud` 及 A=`tenant-a/project-a/team-a`、B=`tenant-b/project-b/team-b` claims 符合 API 配置。A 的 `/api/v1/projects` 返回 200 且仅含 `project-a`；B 首次请求返回 403，是因为 L5 DB 没有 `tenant-b` 组织映射。为在隔离验证环境构造有效双租户样本，在已有 L5 测试组织下通过 SQL 加入 Tenant B、Project B 及 B 的 OWNER membership（tenant UUID `11000000-0000-0000-0000-000000000003`，project UUID `1c5336d7-6071-45e4-90e1-66801572bdc4`）；之后 B 的 `/api/v1/projects` 返回 200 且仅含 `project-b`。A/B 查询各自项目的 `/api/v1/agents?projectId=...` 均返回 200，交叉查询均返回 403。数据库仍只有 1 个原有 Task、0 个活动 Attempt/Lease；未创建 Task、未触发模型请求、未调整 Worker 数量。此证据验证了 HTTP Project/Agent scope，尚不覆盖 Task/API artifact、Worker session、Attempt capability、凭证代理或槽位 A/B 连续复用。

**当前后续步骤：**保持 L5 标准 Compose 配置；把上述 A/B HTTP 对照固化成可重复验收脚本/CI 测试，并继续 Task/Artifact/workspace 的 Tenant/Attempt capability 读写拒绝矩阵。当前 Control Plane 镜像已更新到本分支补丁摘要，Flyway v122、容器健康；数据库预更新快照 `/tmp/agentteams-new-l5-pre-v122.dump` 保留供恢复。只有 Attempt capability、凭证路由及执行清理边界通过后，才把固定 2 槽位长短任务和 A/B 连续 Worker 复用列为安全验收。

**Artifact 活动 Lease 边界修复（2026-11-06）：**在 pinned 上游隔离副本中先为 `InternalArtifactUploadController` 增加过期 Attempt Lease 回归用例；未改生产逻辑时请求仍得到 HTTP 200（测试红灯，缺陷复现）。随后在 prepare 与 complete 共用的 Attempt 归属路径增加 `RUNNING` phase、非空且未过期 `leaseExpiresAt` 检查，过期请求返回 HTTP 409；注入现有 UTC `Clock` bean 以保证边界可测。`InternalArtifactUploadControllerTest` 8/8 通过、零失败/错误/跳过。该门禁已纳入 `build-control-plane-hardening.py`，完整门禁 53 个必需 Control Plane 测试及 2 个 Gateway/NATS 端到端测试全部零失败/错误/跳过，脚本退出码 0；Linux/amd64 镜像构建成功。补丁 SHA-256 `47b4df52f0ff26a2a0951471c7780093863010df0d5ca3f82923da788e9b4bd6`，源码 revision `0156f2c1fecdc868cb4a0f4f13fb068bb4162834`，镜像 ID `sha256:31b522efec32dff7ba00aea59c6eb1784c108c1f61465bda0515044561558b59`。

**L5 部署与运行核验（2026-11-06）：**镜像导入 L5 DIND 后仅强制重建 `agentteams-new-l5-control-plane-1`，其他容器及数据卷未重建。新 CP 容器运行、RestartCount=0，镜像 ID 与本机产物一致，补丁 SHA-256 与当前 patch 一致；启动日志显示 Spring Boot 成功，`/actuator/health/readiness` 返回 `UP`。无 Bearer 的 `/api/v1/projects` 仍返回 HTTP 401。Postgres 最近迁移为 `122|success=true`，数据计数维持 2 tenants、2 projects、1 task、0 attempts、0 leases。初次启动后立即探测遇到连接重置，约 12 秒后日志显示应用启动；再次 readiness 探测成功。没有新建 Task、触发模型调用或修改 Worker 数量。

**安全边界仍未完成：**本次 Artifact 修复阻断过期 Lease 申请新的上传 URL；已经签发的对象存储预签名 URL 在其有效期内仍可使用，且当前 Worker TaskEvent 仍依赖已认证 Gateway Session + 数据库活动 Assignment/Attempt/Lease 与 scope 校验，并未实现随机 Attempt capability 的签发、哈希持久化、明文仅面向 Worker 传输及统一 Artifact/workspace capability 验证。需要继续设计不把明文 capability 写入 Outbox/Gateway 持久命令表的投递通道，并补齐真实 Attempt 端到端测试；其后才执行固定槽位 Tenant A/B 连续复用和 Lease 恢复验收。

**Gateway Artifact 会话身份绑定（2026-11-06）：**在固定上游 `0156f2c1fecdc868cb4a0f4f13fb068bb4162834` 的隔离副本中，先复现“有效 Worker bearer 可在请求体伪造其他 Agent ID，旧 handler 仍调用 Control Plane”的缺陷，再改为 Gateway 以 bearer SHA-256 查找 `AgentSessionStore`，验证 session 尚有效且 session Agent ID 与 Artifact 请求 Agent ID 完全一致；缺失、无效、过期或跨 Agent session 均返回 UNAUTHENTICATED，且不会调用 Control Plane。`ArtifactUploadHandlerTest` 10/10、`ArtifactUploadWiringTest` 2/2 通过，零失败/错误/跳过。移除了 Artifact handler 对共享静态 Worker token 的身份依赖；构建脚本 `build-gateway-hardening.py` 现在固定上游 revision、应用补丁、核验测试报告并将补丁 SHA 写入 OCI 镜像标签，`start-workers.sh` 在共享池/小型部署路径先构建该 Gateway。补丁 SHA-256 `e3309a8d49a373007697c241f61e819c4271a95c16c404baa5e4019dc4ebc34d`。

**L5 Gateway 部署验收（2026-11-06）：**按用户授权使用 `ly@192.168.122.55` 上的 `agentteams-new-validation`，新 Gateway 镜像 ID `sha256:54bb58a1df59ef707d5310b904ee9beb11842be7006b68e5ecf01155f0b11e65`，平台 `linux/amd64`，镜像标签中的补丁摘要与仓库补丁一致。首次 Compose 重建时临时 OIDC overlay 明确固定了旧 Gateway image，检测到后另建只用于此次验证的 overlay 并再次重建 Gateway；最终容器使用 `agentteams-new/gateway:v0.1.5-session-bound-artifacts-0156f2c`，状态 running，`/actuator/health` 为 `UP`。已有 Worker 经短暂 gRPC GOAWAY/连接拒绝后自动重新握手并报告 `READY`。只重建 Gateway，没有重建数据库、Control Plane、Worker 或数据卷；前后计数保持 2 tenants、2 projects、1 task、0 attempts、0 leases、1 agent、5 sessions。未创建新任务或触发模型调用。

**当前安全结论与剩余工作：**Artifact unary 请求现在证明了“哪个已认证 Agent session 发出请求”，Control Plane 仍额外验证请求对应正在运行且 Lease 未过期的 Attempt。此改造不是随机 per-Attempt capability，也不自动隔离同一 Agent session 中的并发 Attempt；workspace API、Artifact 已签发预签名 URL 撤销、Worker A/B 共享槽位连续复用、Credential Proxy 续期和公平队列端到端场景仍未完成。继续按任务 1A 中的 capability 交付/撤销设计和任务 2 的真实共享池测试推进，不将本次 Gateway 修复表述为完整多租户隔离验收。

**Capability 交付约束补充（2026-11-06）：**Gateway 的 `CommandDeliveryService.deliver` 会先把 TaskAssigned `ServerMessage` 写入 `CommandReplayPort` 持久队列，再尝试推送；因此不能把 capability 放进 TaskAssigned protobuf、NATS/outbox 事件或 Gateway command store。实施方向改为 Worker 收到不含秘密的 assignment 后，在当前已认证 Gateway 通道上发起 AcquireAttemptCapability；Gateway 向 CP 请求一次性明文，CP 在事务中锁定并验证活动 Assignment/Attempt/Lease 与 scope，只持久化 SHA-256，Gateway 将明文直接回给该在线 Worker 且不得写入日志/持久队列。重复获取应轮换旧 hash，使断线重试可恢复；事件/Artifact/凭据调用提交此 capability，CP 同时校验 token hash 与实时 Lease。此流程仍需解决 tenant-neutral Worker session 与现有 `connection.agentId == assignment.agentId` 模型的边界：共享槽位不能继续借用某租户 Agent 的长期身份，必须先明确独立 Workload 身份到活动 Attempt 的授权关系，再落 schema/protocol 与端到端测试。

**身份模型决定（待实现）：**拆分“租户内 Agent/任务执行配置”和“物理 Worker slot 会话身份”。新增 tenant-neutral `worker_slot_id`/Workload session 作为 Gateway 连接及命令重放键；活动 `task_assignment` 绑定一个 slot，仍记录所属 task、租户 Agent、attempt、lease。Worker 的长期凭证仅证明其属于某固定容量 slot；对每个 Assignment 的全部数据面权限必须另由短期 Attempt capability 授权。Gateway 在该 slot 上收取/发送命令，Control Plane 从 capability 解析 attempt→task→tenant/project，不再把连接的长期 Agent 身份当作任务归属。slot 空闲前必须完成 Attempt 终态、workspace 清理和 capability 撤销。这个模型允许固定 Pod 顺序执行 Tenant A/B 工作而不把 A 的凭据带给 B；同时意味着 Enrollment、协议路由、Assignment/Lease schema、CP scope guard、Artifact、Credential Proxy 和 Worker SDK 需协同修改，不能只加 token 列。

**现有 Credential Proxy 能力盘点：**代码已有随机 `atp_` 模型代理 token，哈希存于 `attempt_model_proxy_credentials`，SQL 函数以 Agent session、活动 Assignment/Attempt/Lease 和 Tenant 凭据映射作校验；但它是模型代理专用凭证、仍要求 Agent session 身份，并且同一 Attempt/Lease 只有一个未撤销活动 token。Worker 目前每次模型调用都会申请新 token。因此不能直接把现有 `atp_` 当作事件/Artifact/workspace 的稳定通用 capability；下一步应保留模型调用 token 与数据面 Attempt capability 的明确区分，或设计可安全轮换而不导致长任务事件失效的共享验证合同。

**Tenant-neutral Worker slot session 基础（2026-11-06）：**按 TDD 增加 `WorkerSlotSessionServiceTest`，先验证专属 OIDC permission、只能为 ACTIVE slot 签发、DB 只保存 bearer SHA-256、吊销必须匹配 slot，4/4 通过；`WorkerSlotSessionControllerSecurityTest` 验证未认证拒绝、缺少 `worker:slot:session:manage` 被拒、签发不要求 Tenant claims 且响应 `no-store`，3/3 通过。`worker-enrollment-rls.sql` 新增无 Tenant/Project/Team 的 `worker_slots`/`worker_slot_sessions`，Enrollment runtime 仅可调用受限 SECURITY DEFINER 函数，不能直接读 session 表；PostgreSQL Testcontainers 实测表权限、哈希写入及吊销，`JdbcWorkerSessionRepositoryPostgresTest` 10/10、零跳过通过。Compose DB setup 可从 `WORKER_SLOT_IDS_JSON` 预置 slot UUID，README 已明确这只是基础设施：当前 Gateway session authenticator、命令路由和 Worker 仍使用 Agent session/Agent ID；尚未将 slot token 接入实际 gRPC 会话或分配流程。

**本地验证边界：**`docker compose ... config --quiet`、Enrollment shell `sh -n` 通过。Worker Enrollment 模块 43 项非 Gateway 握手测试全部通过、零失败/错误/跳过；完整 44 项运行中，唯一的 Gateway handshake Testcontainers 用例因本机 ARM64 Docker 无法及时启动 pinned AMD64 Gateway image，等待约 142 秒后在容器监听端口阶段失败（未进入 handshake 断言）。PR #1 既有 Linux CI 握手门禁此前已通过；该本机架构限制不作为本次 slot session 代码回归，但后续仍需在 L5/AMD64 验证真实 slot handshake。

**L5 amd64 Gateway 握手复验（2026-11-06）：**用户授权使用 L5 资源后，确认 SSH、`agentteams-new-validation` 命名空间及其中的 Compose Docker daemon 可用；daemon 为 `linux/x86_64`，当前身份有创建验证命名空间 Pod 的权限。为避免改动项目服务，复用已有 Enrollment Java 镜像作为一次性 JUnit 运行容器，通过挂载的 Docker socket 执行本机编译的 Gateway 握手测试类；Ryuk 禁用，测试结束后确认 Testcontainers 容器/网络无残留，并显式停止因 JUnit 后台线程未退出的运行器容器。`GatewaySessionHandshakeTest` 使用其固定的 GHCR digest 镜像和 Postgres 16，5/5 通过、0 跳过、0 失败。L5 现有 Pod 仍只有 `compose-daemon`，Gateway 保持运行；没有重建应用、改数据库或数据卷。此前本机 ARM64 容器启动超时的验证缺口已关闭。

**L5 tenant-neutral slot 握手验收（2026-11-06）：**Gateway 上游固定 revision `0156f2c1fecdc868cb4a0f4f13fb068bb4162834` 的隔离补丁新增 `WORKER_SLOT` 身份、独立 `worker-slot:<slotId>` Gateway state key，以及通过受限 SECURITY DEFINER 函数按 token SHA-256 查找活动 slot session；不会创建租户 Agent 行，且 slot 会话不能调用 Agent artifact unary API 或提交未获 Attempt capability 的任务执行事件。构建门禁要求 Artifact、Authenticator、Session Store、Connection Registry、Agent State Store、Inbound Event Handler 共 43 个 Gateway 单测全部实际执行且零失败/跳过，amd64 镜像构建成功。将镜像只导入 L5 测试 tag `agentteams-new/gateway:test-worker-slot-20261106`（image ID `sha256:7db3c40fc8f7ccc0708d02e1130411766f434996912b13aa7d4b5628e0708cbd`），未替换正在运行的 Gateway；L5 运行服务镜像仍为 `sha256:54bb58a1df59ef707d5310b904ee9beb11842be7006b68e5ecf01155f0b11e65`。在 L5 DIND 中用真实 PostgreSQL 16 与该测试 Gateway 执行 `GatewaySessionHandshakeTest`，6/6 成功、0 跳过、0 失败；结束后测试容器和测试网络已回收，L5 应用容器保持原状。Enrollment 模块 `test-compile` 成功，相关 PostgreSQL/RLS、Service、Controller 测试此前为 17/17 通过，零失败/错误/跳过。**范围限制：**这是 slot bearer 到 Gateway 身份认证和隔离状态键的握手验证；Worker 启动尚未使用 slot bearer，Control Plane 尚未把 Assignment 绑定 slot，命令投递、Attempt capability、事件/Artifact/workspace/凭证代理也未形成共享槽位的完整租户隔离闭环。因此不能据此宣称共享池已可安全执行 A/B 租户任务。

**共享槽位协议约束（2026-11-06，基于 pinned source 静态核验）：**Worker slot session 已能以 slot UUID 完成 Gateway Hello，但当前 Control Plane 的 `TaskAssigned` outbox payload/`TaskAssignedCommandHandler` 仍以租户 `agentId` 作为 Gateway durable command recipient；Worker 的任务事件与 Credential Proxy 也继续以 Agent session 证明身份。简单将 Compose 的 `AGENTTEAMS_AGENT_ID` 替换为 slot UUID 会造成命令 recipient 不匹配，并不能授权事件或模型调用。后续协议需显式区分 `tenantAgentId`（Attempt 归属和任务配置）与 `workerSlotId`（Gateway recipient 和物理执行容量），Assignment/Lease 事务同时绑定二者；不能把明文 capability 放进 NATS/outbox/command replay。Worker 收到持久、无秘密的 Assignment 后，必须通过已认证 slot channel 向 Gateway 申请短时 Attempt capability；Gateway 经内部认证向 Control Plane 原子签发/轮换，数据库只存 hash，随后只将明文响应给当前在线 slot。TaskEvent、Artifact/workspace 与 Credential Proxy 请求统一以 capability 验证活动 Attempt/Lease 并从 TASK scope 解析租户。该约束改变了实现顺序：先做 slot-aware Assignment 路由和绑定测试，再接 capability 领取/验证，随后改各数据面与代理；当前 slot handshake 不允许提前启用共享任务派发。

**Gateway slot-aware Assignment 投递前置（2026-11-06）：**基于 pinned Gateway 补丁增加可选 `workerSlotId` outbox 字段解析；存在时 durable recipient 设为 `worker-slot:<UUID>`，无该字段时维持原 Agent 路由。先新增回归测试并看到旧实现把命令发给 Tenant Agent 的失败，再实现路由；另为 `JdbcCommandEventStore` 增加 slot recipient 与租户业务 Agent metadata 分离的测试，先观察到旧实现覆盖 `metadata.agent_id` 的失败，随后修复：命令序号/回放按 slot key 存储，`TaskAssigned.metadata.agent_id` 保留租户 Agent ID。对应 Gateway 单测 65/65 通过（原 43 项加 22 项，零失败/错误/跳过），amd64 镜像构建成功；补丁 SHA-256 `0207acfcc057b304673ffa31346cd39678b1fb2684cb1578a1dc4ce2c33d95b2`。镜像仅导入 L5 tag `agentteams-new/gateway:test-worker-slot-routing-20261106`，image ID `sha256:7497573988166f727f453a09246277198a168026c1cb740af5d4a3852538e7ee`；L5 运行中的 Gateway image ID 仍为 `sha256:54bb58a1df59ef707d5310b904ee9beb11842be7006b68e5ecf01155f0b11e65`。用新镜像在 L5 DIND 运行真实 Gateway/PostgreSQL `GatewaySessionHandshakeTest` 6/6 通过，零失败/跳过，结束后测试容器已清理。**当前仅 Gateway 识别并路由带 `workerSlotId` 的 Assignment；Control Plane 还没有在 Assignment/Lease 事务中选取和绑定 slot，也尚未向 outbox 产出此字段，因此真实任务派发和共享池数据面仍未启用。**

**Control Plane worker-slot schema 前置（2026-11-06）：**新增 V123 `worker_slots` 与 `task_assignments.worker_slot_id`，对每个槽位活动 Assignment 建立部分唯一索引；同时为 `FoundationRepositoryIT` 增加迁移断言，并在 Enrollment runtime RLS 中只读开放槽位状态、继续禁止 runtime 直接读取 slot session。首次构建发现测试补丁把新 `@Test` 插入已有 `@Test` 后导致编译错误，修复注解插入位置后重跑。Control Plane 硬化构建门禁在禁用 Testcontainers Ryuk（Colima socket 挂载限制）后，PostgreSQL 测试与 TaskEventScope 端到端门禁通过，精确校验 54 个 Control Plane 测试和 2 个集成测试均零失败、零错误、零跳过；V123 在 PostgreSQL 16 上通过全部 115 条迁移。候选镜像仅更新 L5 DIND 中的候选 tag `agentteams-new/control-plane:v0.1.5-task-event-scope-guard-0156f2c`，image ID `sha256:59d011edc0027446fb546f40adbb4a8be6469231c45579ba360dc326bd056da1`，源码 revision `0156f2c1fecdc868cb4f4f13fb068bb4162834`，补丁 SHA-256 `6e9d4000e34459a0327cccc72cbf54233146543b15e79cc5c055432e6658ea5f`。在 L5 独立临时 Docker network/Postgres 中启动该镜像，Flyway 全量迁移至 v123，Tomcat 启动、Hikari 连接建立且容器无重启；临时容器和网络已删除。现有 Control Plane 继续使用原 image ID `sha256:31b522efec32dff7ba00aea59c6eb1784c108c1f61465bda0515044561558b59`，并保留 `...-precheck-20261106` 回滚 tag；其它验证服务均保持运行。为避免在镜像构建容器内重复 Maven 编译造成 BuildKit 长时间无进展，硬化构建脚本现在先在可观测的 Maven 门禁中完成测试和 Spring Boot repackaging，再以轻量 runtime-only 镜像封装已验证 JAR。**此步骤只完成 schema 和镜像启动兼容性，不包括 slot 选择、Assignment/Lease 事务绑定和 outbox 字段产出；下一步继续做 Control Plane slot-aware 原子领取。**

**Control Plane slot-aware Assignment（2026-11-06）：**在硬化 Control Plane 补丁中增加可选 `agentteams.scheduler.worker-slots.enabled`（Compose `WORKER_SLOT_SCHEDULING_ENABLED`，默认 `false`）。启用时，Task、Attempt、Assignment、Agent Lease 与 `worker_slot_id` 在同一事务里提交；按 `worker_slots.status='ACTIVE'`、无活动 Assignment 选槽，并使用 `FOR UPDATE SKIP LOCKED` 避免多个 Scheduler 锁到同一槽位；容量耗尽时抛出可重试分配错误，事务回滚，Task 仍为 QUEUED。持久 Assignment 保留租户逻辑 `agent_id`，TaskAssigned payload 增加 `workerSlotId`，供已完成 slot recipient 路由改造的 Gateway 投递。新增 PostgreSQL 测试覆盖 slot 绑定/outbox 路由、容量耗尽回滚和两个任务竞争同一槽位；另将旧 Lease 恢复测试改用两个逻辑 Agent，以符合单 Agent 单活动 Lease 约束。`TaskAssignmentServiceTest` 8/8 通过。完整 Control Plane 构建门禁 62/62 通过、零跳过，Gateway→NATS→Control Plane 的 TaskEvent scope 集成 2/2 通过、零跳过；构建脚本校验上游 `v0.1.5` 标签仍解析到固定 commit `0156f2c1fecdc868cb4f4f13fb068bb4162834`，解决 GitHub tarball API 直接按 SHA 请求返回 404 的构建问题。候选镜像 image ID `sha256:37c098295854635337736f926ded1659fcda499e75d574335f261abde7aae76f`，补丁 SHA-256 `c685609c9d43a7bef96f870eb6f7db35708123676d01737d09f7daad1f7a6ec2`，仅导入 L5 DIND 候选 tag。L5 独立临时 Postgres 空库迁移到 v123，确认 `worker_slots`、Assignment FK 列和活动槽位部分唯一索引存在；候选服务正常启动，0 次重启。L5 原 Control Plane 仍运行 image ID `sha256:31b522efec32dff7ba00aea59c6eb1784c108c1f61465bda0515044561558b59`；Gateway、Worker、Enrollment、Credential Proxy、Postgres、NATS 均未变更。上一版 schema-only 候选保留在 `...-guard-slot-schema-20261106`，原运行镜像保留在 `...-guard-0156f2c-precheck-20261106`。临时数据库容器和网络已清理。**槽位分配开关保持默认关闭**：Worker 仍未用 slot bearer 启动，也尚未实现在线 slot 对 Attempt capability 的获取与续期，以及 TaskEvent/Artifact/workspace/Credential Proxy 的 capability 校验；所以目前只能确认派发记录/路由前置，不可启动真实共享租户任务。下一步实现 slot 通道上的 Attempt capability 获取与签发/存哈希，再串接数据面校验，之后才可做 Tenant A/B 真实任务闭环。

**L5 授权后的运行基线复核（2026-11-06）：**用户再次明确 L5 可供当前需求使用，并允许在出现具体权限缺口时提出授权请求。SSH `ly@192.168.122.55` 成功，`ly` 可查询 `agentteams-new-validation` 命名空间并在其中 `docker exec`；现有 `compose-daemon` Pod 为 Running。DIND 中 Control Plane、Gateway、Worker、Enrollment、Credential Proxy、PostgreSQL、NATS 均为 Up/healthy；Worker 容器当前只有 1 个。运行中的 Control Plane 仍是旧镜像 ID `sha256:31b522efec32dff7ba00aea59c6eb1784c108c1f61465bda0515044561558b59`。对**当前 Compose 数据库**只读查询显示成功 Flyway 版本最高为 `99`，`tasks=1`、`task_attempts=0`、活动且未释放的 `agent_leases=0`。因此之前 v122/v123 的记录分别对应先前运行状态或独立候选/临时数据库验证，不能作为当前 Compose 数据库已应用 v123 的证据；当前 Compose 数据库尚未升级到含 worker-slot Assignment 绑定的 v123。此次只读复核没有安装 Docker、重建 kubeconfig、重建容器、执行迁移或修改数据。后续若要在 L5 部署迁移候选，必须先对当前 v99 数据库进行新的备份与升级影响核验，并在共享执行 capability 闭环完成前保持 `WORKER_SLOT_SCHEDULING_ENABLED=false`。

**Attempt capability 数据库签发复验（2026-11-06）：**继续使用用户授权的 L5 DIND 与独立 Testcontainers PostgreSQL，不触碰 L5 Compose 数据库或业务容器。首轮 20 项中 17 项通过，暴露 Assignment/Lease 行锁缺少窄列更新权限；补充只允许非登录 `credential_proxy_query_owner` 更新锁定表主键列，并为开启 RLS 的 `worker_slots` 增加专用 UPDATE policy 后，签发路径通过。随后将 Enrollment slot-session 查询辅助函数的测试夹具改为撤销 PUBLIC 执行，Credential Proxy SQL 显式授权给 SECURITY DEFINER 函数所有者；最终 `JdbcActiveAttemptRepositoryPostgresTest` 与 `AttemptCapabilityIssueControllerTest` 共 20/20 通过，0 失败、0 跳过。Enrollment 的 `JdbcWorkerSessionRepositoryPostgresTest` 增加了 context 函数合同与 Gateway runtime 拒绝断言，在 L5 跑到 10/10 通过，0 失败、0 跳过。`git diff --check` 通过，Testcontainers PostgreSQL 自动回收，现有 Compose PostgreSQL 与服务均保持原状。**此证据只覆盖 capability 签发和 hash 存储；Worker 领取、Capability 传递/续租、TaskEvent/Artifact/workspace 校验与真实 A/B Attempt 尚未连通。**

**Model Proxy capability 闭环复验（2026-11-06）：**新增集成用例首次在 L5 返回 `403 model_proxy_request_denied`，数据流追踪确认 token 认证已切换到 capability identity，但 `credential_proxy_active_attempts(UUID)` 仍只查旧 Worker/Attempt 凭据，未把 capability ID 作为查询入口。按失败用例补充 SQL 分支：将 capability 绑定的 slot session、活动 slot、精确 Assignment/Task/Attempt/Lease、TASK scope 与已启用的 Tenant Provider ref 一并校验，返回活跃 Attempt 候选；重新验证 L5 Testcontainers PostgreSQL 下 `JdbcActiveAttemptRepositoryPostgresTest` 19/19 通过，覆盖模型请求鉴权、租约验证、Provider 凭据选择与转发，零失败/跳过；`AttemptCapabilityIssueControllerTest` 本机 3/3 通过。Worker upstream patch 对 pinned v0.1.5 源码 `0156f2c1fecdc868cb4a0f4f13fb068bb4162834` 的 `patch --dry-run` 通过；L5 候选 Worker image `sha256:b6b3bfac705fe97e1b83462fa15d27c6b1bc00a8826975bf68d6127e01c4339c` 标签摘要与当前 patch `0ca4b0e5080b45a241c364b599b9078dbb5db5bcf6d4f0c191ae7e7fb9e44d9b` 匹配。候选镜像未部署，运行中的 L5 Worker 仍是旧 image `sha256:8fd325d1b6e69b57831656280f11988baa78baa3d29dc01db9bb1eacbcfa3f6d`，Worker-slot 调度保持关闭；L5 临时测试 runner 和 Testcontainers 数据库已清理，Compose 服务与数据库未修改。**剩余关键门槛仍是 Control Plane 将 slot-bound capability 随 Assignment 安全交付给 Worker、TaskEvent/Artifact/workspace 一致验证、长任务轮约续期与 Tenant A/B 实际串行闭环。**

**Gateway Attempt 事件校验（2026-11-06）：**为 slot identity 的 TaskAccepted/Progress/Heartbeat/Completed/Failed/TaskEventReport 增加短期 Attempt capability 校验；校验绑定 Gateway 认证的 Worker slot、租户逻辑 Agent、Task、Attempt、Lease 与有效 Assignment。数据库通过仅返回 boolean 的 `SECURITY DEFINER` 函数开放校验，Gateway `agentteams` login 无 capability 表权限；验证成功后 Gateway 在事件存储和应用/NATS 路由前清除 capability 字段。新增 Gateway 单测覆盖缺少/错误 capability 拒绝、正确 capability 接受、token 不向后续路由传播。Pinned Gateway 门禁 64/64 通过，零失败/错误/跳过；L5 Testcontainers PostgreSQL 的精确 live capability 与“不能直读表”集成用例 1/1 通过。`gateway-v0.1.5-session-bound-artifacts.patch` SHA-256 为 `76ad0984922d8a69d38e1c607ea90f209e71952bd7d90b01141d147f221f7e83`，可在 pinned upstream 基线上 dry-run。Gateway 候选镜像在本机使用已缓存的 `eclipse-temurin:21-jre` 构建，并导入 L5 DIND：`agentteams-new/gateway:v0.1.5-session-bound-artifacts-0156f2c`，image ID `sha256:d06ff26e8a53cd30b8a357ea5f3602f1f76f7b046c2d3edae9075432ada2bb48`；标签 revision 与补丁摘要均匹配。L5 Docker Hub 直连超时，未改变 L5 代理/网络配置。候选镜像尚未部署，运行栈/数据库未变。**下一门槛：**将同一 Attempt capability 缓存/续期来源接入 Worker 模型请求和所有 Task execution events；补 TaskAssigned 的持久 Assignment ID 投递；之后才可部署候选并做真实 A/B 共享槽位闭环。

**Assignment capability 传递、共享续期与 Artifact 边界（2026-11-07）：**新增 Gateway TaskAssigned `assignmentId` protobuf 透传，确保 Worker 能绑定持久 Assignment；Worker runtime 为每个 Assignment/Attempt/Lease 维护共享 capability token source，模型 Proxy 请求、Gateway Task execution events 和 Artifact prepare/complete 复用同一 token，距离到期 30 秒内续签，并在终态释放缓存。修复前的回归用例分别复现 TaskAssigned 丢失 Assignment ID、连续模型请求重新签发并撤销前一个 token、Assignment 改变被误判为重复派发；修复后的 Worker/Runtime 门禁共 52 项通过（Worker 模块 36 项，Runtime 16 项），Pinned Gateway 门禁 65 项通过，全部零失败/错误/跳过。Worker patch SHA-256 `c7a37aa9ac9b94df5954bcae0ff2ca76c3a7e453237d5559dcace1c013c6bac3`；候选 Worker image `agentteams-new/agent-worker:v0.1.5-attempt-capability-0156f2c` 已导入 L5，image config ID `sha256:33480a88a1e82c3a132b827d3431b4b32a10bc35203e5668be77245f13dcb5dc`，源码 revision `0156f2c1fecdc868cb4a0f4f13fb068bb4162834`，OCI patch label 与当前 SHA 匹配。Gateway 对 Artifact prepare/complete 的 slot session 增加了 logical Agent/Task/Attempt/Lease 元数据一致性检查和 Attempt capability 验证；缺失或错误 capability 不会调用 Control Plane。候选 Gateway 已导入 L5，image config ID `sha256:e772e4a904ef5fe6acd81598c3982ad92cb6bc460ac0f537698a91fd0d749bcf`，patch SHA `e956ef6829a3565a6396660ffe6700b578480fbfdd132dd486d3f6034793ba2b`。L5 Testcontainers PostgreSQL 中 `modelProxyAcceptsSlotAttemptCapabilityAndSelectsProviderFromTaskScope` 与 `gatewayValidatesOnlyMatchingLiveAttemptCapabilityWithoutTableAccess` 各 1/1 通过。Compose 已改用固定 slot UUID 与 capability issue URL，新增 `enroll-worker-slot.sh`（只将 `wslot_` session 写入权限 0600 的 Worker env file），启动校验要求 slot 列表顺序一致且 session token 类型正确；Compose YAML、shell 语法和 `git diff --check` 通过。L5 原 Compose 应用、数据库及数据卷未改动；候选镜像未部署，Worker-slot 调度仍关闭。**剩余验收：**workspace 访问的 Attempt capability 边界、Control Plane slot-aware 原子分配+公平队列、slot 级 drain/恢复、长任务/租约失效、Tenant A/B 在同一固定 Worker 上的真实连续复用闭环。


**L5 slot session 前缀兼容修复与签发验收（2026-11-07）：**真实 Enrollment 响应暴露 slot-session service 误用了普通 Worker `SecureWorkerTokenGenerator`，生成 `ats_`，与 slot contract/Worker 配置要求的 `wslot_` 不匹配。按 TDD 增加回归用例，先复现前缀失败，再新增独立 `WorkerSlotTokenGenerator`/`SecureWorkerSlotTokenGenerator`（32 个随机字节、Base64URL 无填充、`wslot_` 前缀），仅 slot-session service 注入该生成器；普通 Worker 仍通过原生成器产生 `ats_`。本机普通 Session 与 slot Session 定向测试 18/18 通过；OIDC/PostgreSQL Enrollment 集成 `OidcJwtIntegrationTest` 8/8、`JdbcWorkerSessionRepositoryPostgresTest` 10/10 通过（Ryuk 在本机镜像拉取等待，禁用 Ryuk 后正常执行）。全量 Enrollment 模块 50 项中除下述 Gateway 启动集成外，其余 44 项通过；GatewaySessionHandshakeTest 6 项在 Colima x86 镜像仿真下失败：Gateway 容器 Spring Boot 初始化超过握手测试时限，尚未监听 gRPC 端口，客户端收到 localhost 连接拒绝；这不是已通过的 Gateway 验收证据，需后续在 L5/原生 amd64 runner 单独重跑。

候选 Enrollment JAR SHA-256 `1be252856f4af8155a01f12c8c9757c5b2d4840574f8c7aee1cd94da4c1f4207`，L5 候选镜像 `agentteams-new/worker-enrollment:v0.1.5-slot-session-wslot-1be252856f4a`，image ID `sha256:3bbdbeca00be6b672a5094e24730c06abed5a66a7acb57730533422a69978521`。仅重建 `agentteams-new-l5-worker-enrollment-1`，健康 `healthy`、重启 0；原 Keycloak、Control Plane、Gateway、Credential Proxy、Worker、PostgreSQL/NATS 容器及数据卷没有重建。用 L5 临时 Keycloak 26 realm clone 签发的 tenant-neutral OIDC 主体（仅 `worker:slot:session:manage`、无 tenant claim）调用 slot endpoint 返回 HTTP 201，session TTL 1 小时，bearer 格式 `wslot_` + 43 个 Base64URL 字符；通过 SHA-256 对照确认 DB 只存 hash，活动 session 当时恰为 1 个。随后通过同一 Enrollment API 吊销验证 session，最终活动 slot session 为 0；恢复用 Keycloak clone、导出的 realm/H2 副本、临时签名凭据和临时容器/网络/数据卷均已清理，保留原 Keycloak 及共用 OIDC trust volume。L5 当前 task 仍 1、attempt 0、活动 lease 0，Worker 数仍为 1，slot scheduling 仍关闭。**该步骤只验证凭据类型生成和 Enrollment session 存储/吊销；尚未把新的 session 配置给 Worker、做 Gateway slot handshake、在线 slot 身份路由或 Tenant A/B 执行闭环。**
