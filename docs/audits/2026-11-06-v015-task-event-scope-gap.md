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
- 在加固补丁完成前，这个缺口阻断 `agentteams-new` 多租户强隔离验收；API OIDC、
  Enrollment RLS 和 Credential Proxy 授权不能替代此异步写入边界。后续由新仓库维护的
  固定 v0.1.5 补丁关闭该 `TASK_EVENT` 路径，真实事件链负向验收见下文。此结论不覆盖
  `CONFIG_APPLIED`、其他异步消费者或整体隔离。按仓库边界只在 `agentteams-new` 维护补丁
  和门禁，不修改 `agentteams-java`。

## `agentteams-new` 派生 Control Plane 加固进展（2026-11-06）

没有修改 `agentteams-java` 工作树。`agentteams-new` 新增固定补丁 `deploy/compose/patches/control-plane-v0.1.5-task-event-scope.patch`，由 `deploy/compose/build-control-plane-hardening.py` 从 v0.1.5 源码 commit `0156f2c1fecdc868cb4a0f4f13fb068bb4162834` 构建派生镜像。补丁在 Control Plane 的 TASK_EVENT 观测适配器前加入数据库守卫：联合检查 Assignment、Attempt、Lease、Agent 及 WORKER/TASK Tenant/Project/Team scope；只接受仍有效的状态，并在同一事务中对关系行加共享锁，随后执行既有 best-effort 观测投影。失配事件不会调用观测投影；数据库故障继续向消费者传播，避免将基础设施错误当成授权拒绝。

构建脚本执行三个必需测试门禁：事件适配器单测 3/3；PostgreSQL 守卫测试 4/4、0 skipped；新增的真实 Gateway→JetStream→Control Plane 负向端到端测试 1/1、0 skipped。该用例启动真实 Gateway 与 Control Plane Spring 应用、PostgreSQL 和 NATS，先让 Tenant A Worker 取得并推进自己的活动 Assignment，再经 gRPC 提交指向 Tenant B Task、使用伪造 Attempt ID 的 `TASK_EVENT`。用例等待 NATS 消费者 ACK 后断言 Tenant B Task 的 Run、Process Event、Webhook delivery 均为 0。红灯复现使用未打补丁的固定 v0.1.5 源码，同一事件新增了 1 条 Run；补丁源码上同测通过，三类副作用均为 0。测试通过固定 v0.1.5 源码补丁纳入构建器，构建器要求 Failsafe 报告恰为 1 个测试、0 跳过/失败/错误；本机 Colima 复验通过时使用 `TESTCONTAINERS_RYUK_DISABLED=true`，因为默认 Ryuk 在 2 GB Colima 曾启动超时并导致门禁正确失败，GitHub CI 保留默认 Ryuk。

补丁同时把上游 Control Plane Dockerfile 的 Maven/JRE 基础镜像固定到 Docker proxy 镜像 digest；派生镜像标签记录上游 commit `0156f2c1fecdc868cb4a0f4f13fb068bb4162834` 与补丁 SHA-256 `73b279a244ba67497032abfd08106a9e3b7687c63611af03bf0dde3d52607292`。随后审计发现 Skill 包的上传准备、上传完成和下载三条 Controller 路由直接调用存储服务，绕过了 `SkillService.getSkill()` 中的资源 scope 校验。新增回归用例先在未修复版本得到红灯（跨 scope 上传准备返回 500 且触达存储边界），修复后在所有三条路由调用存储操作前强制解析并校验 Skill 可见性；`SkillControllerTest` 为 6/6、0 skipped。另在 `ConfigDeploymentServiceTest` 增加负向测试：已认证 Agent 提交属于其他 Agent 的 binding 时必须拒绝，且不得读取 apply 记录或写入 apply/resource 结果；该类 7/7、0 skipped。固定构建脚本现将这些测试加入补丁应用后的强制 Maven 门禁，并核验预期测试数、失败数、错误数和跳过数。

`self-hosted-small` 配置门禁只接受这个固定派生镜像，启动脚本会先构建或复用本地校验标签匹配的镜像；`local-dev` 零 Worker POC 仍可使用原固定镜像。上述补丁关闭 TASK_EVENT 异步写入边界和 Skill 包三条 HTTP 路由的对象 scope 绕过；不等同于全量 Control Plane API 的 A/B Tenant 验收，也不等同于真实 HTTPS OIDC 登录、Worker Enrollment 或 Provider 联调验收，这些门槛仍未完成。

固定上游 commit 的最终构建成功：适配器 3/3、PostgreSQL 守卫 4/4、ConfigApplied 关系拒绝 7/7、Skill scope 拒绝 6/6，均 0 skipped；真实 Gateway→NATS→Control Plane 负向端到端 1/1、0 skipped。测试时发现上游 POM 的 macOS `colima-testcontainers` profile 会覆盖构建器设置的 Docker endpoint 并指向默认 Colima；新仓库构建器已在单测命令显式关闭该 profile。最终运行日志确认 Testcontainers 使用 `unix:///Users/gecko/.colima/agentteams-poc/docker.sock`（2 GB 隔离实例），测试结束无遗留容器。补丁 SHA 为 `73b279a244ba67497032abfd08106a9e3b7687c63611af03bf0dde3d52607292`，派生镜像 OCI 标签与此 SHA 和固定上游 commit 匹配。

该镜像已部署到 `agentteams-new-validation` 零 Worker `local-dev` Compose POC。切换前数据库确认 `task_attempts=0`、未过期活动 Lease=0；只重建 Control Plane，保留 PostgreSQL/NATS 数据卷及其余服务。切换后 `/actuator/health` 为 HTTP 200/`UP`，容器使用镜像 ID `sha256:d36147bea18b3810…`、RestartCount=0，Flyway schema 保持 v121；Enrollment、Credential Proxy、Gateway、PostgreSQL 和 NATS 均仍运行，未遗留 Testcontainers 容器。此 POC 关闭 API OIDC 且无 Worker，只证明新派生镜像在现有本地部署中启动/连接成功，不构成真实 OIDC、真实 Worker Attempt 或 A/B Tenant 验收。

真实 Gateway→NATS→Control Plane 事件链负向验收中，固定未加固 v0.1.5 基线会新增 1 条 Run；加固版等待 Consumer ACK 后，Tenant B Task 的 Run、Process Event、Webhook delivery 均为 0。该测试在 CI run [42111461762](https://github.com/ly416123/agentteams-new/actions/runs/42111461762) 和本轮 CI [42118311167](https://github.com/ly416123/agentteams-new/actions/runs/42118311167) 均通过。本轮 CI 包含新 Java/Compose 门禁、硬化 Control Plane 构建测试及 Credential Proxy、Worker Enrollment 镜像构建，job 全部成功。ConfigApplied 新证据覆盖服务层 Agent/binding 关系拒绝与无持久化副作用，尚未覆盖完整 Gateway→NATS→Control Plane 事件链。真实 HTTPS OIDC 登录、受认证 A/B Tenant HTTP/API 和真实 Worker/Provider 验收仍未完成，不能据此宣布全部多租户隔离通过。
