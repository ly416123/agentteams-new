# 固定容量共享 Worker 的租户公平调度设计

## 背景

`agentteams-new` 采用固定数量的 Compose Worker 槽位，多租户任务共享这组执行资源。Task、Attempt、Assignment 与 Lease 已有持久化基础，但现有验收没有证明长任务负载下租户之间能公平获得 Worker，也没有定义所有槽位都被长 Attempt 占满时的等待和恢复边界。

## 目标

- 固定 Worker 数量，不按任务动态创建 Pod/容器或扩缩 Worker。
- 队列中的任务按 Tenant 公平派发，并为每租户设置可配置的活动 Attempt 上限和排队额度。
- 长任务异步运行，通过 Attempt/Lease 状态、心跳、超时和幂等恢复管理；调用方通过 Task ID 查询，不等待同步 HTTP 请求完成。
- 确保租户隔离仍由规范 Tenant/Project/Team scope、RLS、Attempt workspace 和凭证代理授权提供，调度顺序不改变授权边界。
- 验收一个租户持续提交长任务时，其他租户仍能在固定容量允许的条件下开始短任务。

## 方案

Control Plane 以持久化任务记录为任务事实来源，维护按 Tenant 分区的待调度队列。调度器只挑选满足全局槽位、租户并发上限、队列配额和资源约束的任务，再创建/推进 Attempt 与 Lease；Worker 固定槽位必须以有效活动 Lease 原子声明，任何匹配路径都不能向同一 Worker 同时分配第二个活动 Attempt。若部署多个 Control Plane 实例，必须通过数据库事务和行级锁保证同一槽位/租户额度不会被重复占用。若 NATS 用于唤醒或分发，只承载可重放通知，不能成为另一份不一致的任务状态来源。

租户间采用加权公平轮询（Weighted Deficit Round Robin）选择下一条可运行任务；每个 Tenant 内按原有 priority 从高到低、同优先级按入队时间顺序，同 Tenant 的排队任务不能越过其并发上限。权重及并发/排队额度由配置或管理面策略提供，缺省行为必须明确且有上限。单任务 CPU、内存、执行时限等资源限制独立于队列权重。没有可验证 Tenant/Project scope 的任务只能出现在明确关闭 API OIDC 的 `local-dev` 单租户档位；共享 Worker 池必须 fail closed，不能把无 scope 任务派到任意槽位；有 scope 的跨租户复用必须有有效 Attempt capability，不能靠放宽长期 Worker scope 匹配。

公平调度不能抢占已运行的 Attempt。若所有固定槽位都已被长任务占用，新 Tenant 的任务必须等待某个 Attempt 正常完成、达到执行时限、被协作式取消/检查点让出，或经故障恢复释放 Lease。系统不得承诺超过这些边界的等待时限。需要严格等待上限时，必须保留容量，或让长工作流在安全检查点分段执行。

Attempt 心跳和 Lease 到期用于发现失联 Worker；超时策略区分可重试与终态失败。副作用型工具调用须有幂等键或明确的检查点协议，避免恢复时重复执行。每 Tenant 队列/并发额度、活动 Attempt、排队时长和租约恢复均需可观测。

## Worker 身份与跨租户 Attempt 授权

共享池是物理槽位共享，允许同一个 Tenant-neutral Worker 槽位先后执行不同 Tenant 的 Attempt。当前 v0.1.5 的逻辑 Worker 仍绑定单 Tenant/Project/Team，不能把已有 scope 相等检查直接删掉来宣称共享池完成。落地前必须先有 Attempt capability：Worker 长期身份只证明共享池成员资格；调度器在原子领取 Task/Attempt/Lease 时生成短时 capability，所有 Worker 任务事件、Artifact/workspace 和模型代理请求均由服务端按 Attempt 与持久 TASK scope 重新授权。Tenant/Project/Team 从 TASK scope 派生，不接受 Worker 自报。Lease 释放、超时或取消立即撤销 capability；模型代理按 Attempt Tenant 解析凭据，Worker session 不再固定某 Tenant 的 provider secret。Attempt 完成后先清理其 workspace 和内存上下文，再释放槽位。

实测确认当前上游实现尚不满足上述合同：普通 Worker 派发、TaskEvent、Enrollment Session 和 Credential Proxy 都要求长期 Worker scope 等于 Task scope；代理凭据的 modelCredentialRef 也绑定到该长期 scope。审计细节见[共享 Worker 跨租户边界审计](../../audits/2026-11-06-shared-worker-tenant-boundary.md)。因此 Attempt capability 与 Tenant-neutral slot 是公平调度接入持久队列之前的前置安全阶段。

### Attempt capability 实现进度

Credential Proxy 已增加第二种身份模式：原 Enrollment 凭据继续绑定 Tenant/Project/Team；Attempt 凭据绑定 Agent + Attempt + Lease，数据库从 TASK `resource_scopes` 派生 Tenant/Project/Team，并在每次代理鉴权及流式转发前确认同一活动 Assignment/Attempt/Lease。新增内部签发 API，使用 Gateway session hash 验证 Agent，只允许对该 Agent 当前活动的 Attempt 签发；Provider credential ref 从部署侧可信映射解析，Worker 不能自报 Tenant 或凭据引用。数据库仅保留新 token 的 SHA-256，Attempt token 只能在 Lease 有效时使用，签发时到期时间不晚于当前 Lease。Credential Proxy 全量 54 项测试通过（13 项 PostgreSQL 用例、3 项签发 API 用例，零跳过）。

这只是 Credential Proxy 的一段能力，**尚不代表共享 Worker 已端到端打通**。当前仍缺 Control Plane 分配事务关联和 Worker 领取/续领协议（不能把明文 bearer token 写入普通持久 Outbox）、受保护的内部传输、TaskEvent/Artifact/workspace 全链路验证 Attempt/Lease，以及 Attempt 结束后的上下文/workspace 清理。长任务 token 到期时间不超过签发时 Lease；Worker 尚未实现续领，因此 Lease 续期后的长任务模型调用尚未打通。Provider credential ref 映射也必须由部署方可信配置。以上完成并通过双租户真实 Worker 验收前，禁止把 Worker 的长期 scope 检查移除，也不能把当前 Tenant-bound Worker 宣称为 Tenant-neutral 共享槽位。

## 并发运行模型

Java 虚拟线程不作为调度公平或租户隔离机制。上游 Runtime 已有 `maxConcurrency` 上限；AgentScope Runtime 以 task-id 索引独立 Execution，并订阅每个任务的事件流，因此执行层具备有界并行的形态。但本项目当前 Control Plane 对每个逻辑 Worker/Agent 强制最多一个活动 Lease，调度侧不会向同一 Agent 并发派发；这是当前容量模型的硬约束，不能因 Runtime 支持并发就认为同一 Pod 已支持多租户并跑。

第一阶段按一个逻辑 Worker slot 同时执行一个 Attempt，使用持久队列、Tenant 公平派发及每租户并发上限；任务占用 slot 期间不抢占。若要在同一 Pod 开多个执行 lane，需先把“每 Agent 唯一活动 Lease”改为固定数量 lane 的原子占用，例如 `(agent_id, lane_id)` 唯一活动 Lease，并让 Control Plane 与 Runtime 共用同一个 lane 容量；同时补齐每 Attempt workspace、配置/模型/工具调用身份、取消、完成清理和资源隔离验收。长任务阻塞所有 lane 时仍只能等待、检查点让出或终止，公平队列不能抢占运行中任务。

当前 AgentScope 调用已采用事件流/异步订阅；因此不先引入协程框架或把所有执行改成虚拟线程。先采样长任务中模型/工具 I/O 等待、CPU、堆外/工作区内存、OS 线程和连接池使用。如果 I/O 等待期间确实占用大量平台线程，再仅对阻塞适配层评估 Java 21 虚拟线程；必须由 Semaphore、连接池、租户配额和 CPU/内存限制给并发设上限。CPU 密集任务继续使用有界执行器及 CPU 限额；不可信工具/代码需由子进程或明确的沙箱 Provider 隔离。

## 验收标准

1. 固定至少 2 个 Worker 槽位：Tenant A 先运行一个受控长 Attempt，Tenant B 随后提交短任务；在 A 的 Attempt 仍运行时，B 的任务能由另一个空闲槽位开始，且 A 不超过其配置并发上限。
2. 同 Tenant 超出活动并发上限的任务保持持久排队，不丢失、不重复派发；Tenant B 可获得调度机会；单个 Worker 固定槽位在并发分配和重试下最多持有一个有效活动 Lease。
3. 覆盖全局槽位被占满的情况：B 的任务不被错误标记运行；槽位释放或租约恢复后，公平调度继续，且无跨 Tenant 的 Task/Attempt/Artifact/workspace/credential 可见性。
4. Tenant-neutral Worker 槽位可依次承接 A/B 租户 Attempt；无有效 Attempt capability 的任务事件、Artifact/workspace 或模型请求全部拒绝，A 的 capability 无法访问 B 的 Attempt/凭据。
5. 停止/重启一个 Worker 后，过期 Lease 和 Attempt 按策略恢复；幂等键/检查点规则避免重复副作用。
6. 记录每 Tenant 排队时长、活动数、配额拒绝数与 Attempt 恢复数；CPU/内存压力不能突破容器资源上限。
7. 虚拟线程仅在单独的 I/O/CPU 混合基准显示收益且无安全上下文传播问题后，进入独立实现决策。

## 非目标

- 不引入按任务动态创建 Worker、Kubernetes、分布式跨主机调度或自动扩缩容。
- 不以虚拟线程、协程或队列分区替代租户数据隔离和不可信代码沙箱。
- 不对全槽位长时间饱和作出不可兑现的任务等待时限承诺。
