# v0.1.5 TASK_EVENT 跨任务 scope 校验缺口

**日期：** 2026-11-06
**审计对象：** `agentteams-java` v0.1.5，源码 commit
`0156f2c1fecdc868cb4a0f4f13fb068bb4162834`
**范围：** Worker gRPC `TaskEventReport` → Gateway → JetStream →
Control Plane 观测与持久化
**限制：** 只读 GitHub 固定 commit 源码；未修改 `agentteams-java`，未向
L5 或本机 POC 注入事件。以下为源码链路确认，未做运行态利用复现。

## 结论

发现一个可由已认证 Worker 触发的跨任务事件写入缺口。Gateway 只验证上报的
`agent_id` 等于当前连接 Agent；后续 Control Plane `TASK_EVENT` 观测支路没有把
`attempt_id`、`lease_id`、`agent_id` 与目标 Task 的有效 Assignment/Lease 联合
校验。它按消息里的 `task_id` 读取该 Task 的租户 scope，再以该 scope 创建或复用
Run、追加过程事件，并排入 Webhook。

因此，一个合法 Worker 若提交了另一个租户的有效 `task_id`，可以尝试把自己构造的
过程事件写入目标 Task 的可见 Timeline，并触发该 Task 租户的 Webhook 投递。该链路
不直接读取目标 Task 内容；本审计确认的是跨租户数据完整性与事件投递边界，不声称
已证明机密数据读取。该行为绕过了多租户验收矩阵要求的“ID 猜测、伪造 Attempt/Lease
均拒绝”。

## 源码链路

1. Gateway `taskEventReport()` 验证
   `connection.agentId == metadata.agentId`，然后把消息内 Task/Attempt/Lease ID
   和连接 Agent ID 发给执行事件端口：
   [ControlPlaneGatewayApplicationHandler.java@v0.1.5](https://github.com/ly416123/agentteams-java/blob/0156f2c1fecdc868cb4a0f4f13fb068bb4162834/agent-gateway/src/main/java/io/agentteams/gateway/ControlPlaneGatewayApplicationHandler.java#L175-L195)。
2. Control Plane NATS Consumer 将 `TASK_EVENT` 转给
   `executionEvents.taskEventReport(taskId, command)`；成功后 ACK：
   [NatsExecutionEventConsumer.java@v0.1.5](https://github.com/ly416123/agentteams-java/blob/0156f2c1fecdc868cb4a0f4f13fb068bb4162834/control-plane/src/main/java/io/agentteams/controlplane/outbox/NatsExecutionEventConsumer.java#L313-L320)。
3. `ControlPlaneExecutionEventAdapter` 传递 `attemptId`，但丢弃 command 中的
   `leaseId` 和 `agentId`，再进入 observation adapter：
   [ControlPlaneExecutionEventAdapter.java@v0.1.5](https://github.com/ly416123/agentteams-java/blob/0156f2c1fecdc868cb4a0f4f13fb068bb4162834/control-plane/src/main/java/io/agentteams/controlplane/application/ControlPlaneExecutionEventAdapter.java#L95-L101)。
4. `observed()` 仅校验事件类型白名单、Payload 大小和 JSON；随后
   `recordProcess(taskId, runId, ...)` 按 `taskId` 继续处理：
   [ControlPlaneTaskExecutionObservationAdapter.java@v0.1.5](https://github.com/ly416123/agentteams-java/blob/0156f2c1fecdc868cb4a0f4f13fb068bb4162834/control-plane/src/main/java/io/agentteams/controlplane/application/ControlPlaneTaskExecutionObservationAdapter.java#L197-L221)。
5. `recordProcess()` 仅以 `taskId` 读取 scope，创建/校验 Run 并追加过程事件；同一
   scope 还被用于 Webhook enqueue：
   [ControlPlaneTaskExecutionObservationAdapter.java@v0.1.5](https://github.com/ly416123/agentteams-java/blob/0156f2c1fecdc868cb4a0f4f13fb068bb4162834/control-plane/src/main/java/io/agentteams/controlplane/application/ControlPlaneTaskExecutionObservationAdapter.java#L242-L305)。
6. `contextForTask()` 从 `resource_scopes` 只按 `TASK + resource_id` 解析目标
   Tenant/Project/Team；没有连接 Attempt、Lease 或 Agent：
   [JdbcTaskRunObservationRepository.java@v0.1.5](https://github.com/ly416123/agentteams-java/blob/0156f2c1fecdc868cb4a0f4f13fb068bb4162834/control-plane/src/main/java/io/agentteams/controlplane/task/JdbcTaskRunObservationRepository.java#L34-L47)。
   `ensureRun()` 会检查 Run ID 已有记录的 Task 和 scope，但允许新 Run ID 以传入的
   Task/scope 创建；这不是 Assignment 所有权校验：
   [JdbcTaskRunObservationRepository.java@v0.1.5](https://github.com/ly416123/agentteams-java/blob/0156f2c1fecdc868cb4a0f4f13fb068bb4162834/control-plane/src/main/java/io/agentteams/controlplane/task/JdbcTaskRunObservationRepository.java#L109-L152)。

作为对照，Task 状态迁移、Lease renewal 与 REJECTION 会在
`ExecutionEventService` 中验证 Task ↔ Attempt ↔ Lease ↔ Agent 关系；
`TASK_EVENT` 使用独立观测支路，没有复用这些检查。

## 测试覆盖边界

v0.1.5 的 Gateway `TaskEventReportHandlingTest` 覆盖了伪造其他 Agent ID、Payload
超限和发布异常；它没有验证“连接 Agent 的有效 Lease 是否属于消息中的 Task/Attempt”。
Control Plane observation adapter 也缺少跨 Tenant Task ID + 任意 Run/Attempt/Lease
关联的负向持久化测试。以上是测试缺口，不代表已执行运行态攻击。

## 修复要求与新项目影响

- 接受过程事件前，以数据库事实联合验证 Task、Attempt、Lease、Agent 的关系和
  当前有效状态；任何一个 ID 不匹配、Lease 已失效或 Attempt 已终态均拒绝持久化。
- 将 actor/Lease 身份保留到 Control Plane 观测边界，不能在 Gateway 转发后丢弃。
- PostgreSQL 集成测试覆盖 Tenant A Worker 对 Tenant B Task ID 的事件提交，以及
  同租户错误 Attempt、错误 Agent、失效 Lease；拒绝时不得新增 Run、Process Event
  或 Webhook delivery。
- 过程事件仍可按既有 best-effort 语义丢弃，不能因拒绝事件关闭承载终态状态迁移的
  gRPC 流。
- 因 `agentteams-new` 当前固定复用 v0.1.5 Control Plane/Gateway，这个缺口阻断其
  多租户强隔离验收。API OIDC、Enrollment RLS 和 Credential Proxy 授权不能补上
  此异步写入边界。按仓库边界，本轮只在 `agentteams-new` 记录并门禁，不修改
  `agentteams-java`；解除阻断需要换用已修复且经固定镜像负向测试验证的上游版本，或
  将该观测处理路径迁入新项目并纳入新的执行身份授权边界。
