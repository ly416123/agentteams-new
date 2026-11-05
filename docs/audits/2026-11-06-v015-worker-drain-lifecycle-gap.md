# v0.1.5 固定 Worker Drain 生命周期缺口

**核验日期：** 2026-11-06  
**源码基线：** `agentteams-java` 发布源码 commit `0156f2c1fecdc868cb4a0f4f13fb068bb4162834`（GitHub 文件接口只读读取；没有使用本机含未提交改动的 checkout 作为证据）。

## 结论

v0.1.5 的 `DRAIN` 能阻止 Worker 接收新任务，但没有可恢复的 drain 完成/取消闭环。Drain 操作会把 Agent 标记为 `DRAINING`，新的派发查询只选择 `READY` Worker 并排除活动生命周期操作。活动 Lease 结束后，操作不会自行进入 `DRAINED`；当前代码仅在随后提交 `TERMINATE` 且活动 Lease 为 0 时才把旧 Drain 标记为 `DRAINED`，并同时把 Agent 标记为 `TERMINATED`。

因此，把 Compose `stop`、Worker `close()` 或 `TERMINATE` 当作固定槽位的普通排空操作不安全。正常 Worker 注册只允许 `PROVISIONING/OFFLINE/READY/DRAINING` 状态，因此 `TERMINATED` 不能通过注册恢复；当前关闭实现也会停止 Runtime、关闭通道并 `shutdownNow`，没有 drain-aware 的等待过程。固定槽位缺少无损停机后复用的控制面状态转换。

## 源码证据

- `control-plane/src/main/java/io/agentteams/controlplane/worker/WorkerOperationService.java`：Drain 请求允许 `READY`、`BUSY`、`PROVISIONING`，随后更新 Agent 到 `DRAINING`；代码中只有处理 `TERMINATE` 时、检查无活动 Lease 后才将活动 Drain 标记为 `DRAINED`。同一终止请求会把 Agent 更新成 `TERMINATED`。
- `control-plane/src/main/java/io/agentteams/controlplane/persistence/AgentRepository.java`：无 Team 派发查询要求 `a.phase = 'READY'`，并排除状态为 `PENDING/RUNNING` 的活动 Worker 操作；Team 分派同样仅选 `READY` Worker。
- `agent-gateway/src/main/java/io/agentteams/gateway/JdbcAgentStateStore.java`：新 Worker 注册仅接受 `PROVISIONING/OFFLINE/READY/DRAINING`，保留 `DRAINING`；不接受 `TERMINATED`。
- `agent-worker/src/main/java/io/agentteams/worker/AgentTeamsWorker.java`：`close()` 依次关闭健康服务、停止 Runtime、关闭 channel client/channel，并对 gRPC channel 与 scheduler 执行 `shutdownNow`。
- `control-plane/src/main/java/io/agentteams/controlplane/api/AgentController.java`：对外提供 Drain 与 Terminate 请求，但没有 resume/undrain/cancel-drain 路由。

## 对 Compose 固定容量路径的影响

当前 `deploy/compose/README.md` 已要求备份前由操作者确认没有活动 Attempt，并注明没有专用 Worker drain 命令。这个限制仍成立；虽然复用的 Control Plane 暴露 `POST /api/v1/agents/{id}/operations/drain`，该接口不能完成可复用槽位的正常排空。

在新仓库获得自己的 Control Plane/Worker 应用装配前，不应增加会调用 `TERMINATE` 或直接杀进程并声称“安全 drain”的脚本。新生命周期 API 至少需要分别支持：

1. `READY/BUSY → DRAINING`：立即停止新派发，并保留当前 Attempt 继续运行。
2. Lease 数归零后，由服务端或 Worker 可重复确认 `DRAINED`；重复请求幂等，过期操作租约可恢复。
3. `DRAINING/DRAINED → READY`：显式、审计化恢复固定槽位；不重新创建 Agent 身份，不重置 Session/Attempt 历史。
4. `TERMINATED` 保留为单独、破坏性终止操作；不得将其与普通部署滚动重启混用。
5. 对并发派发、Lease 过期回收、Worker 进程中断、重启重连及跨 Tenant/Team scope 做 PostgreSQL 集成和真实 Compose 验收。

该缺口并不阻止继续完成 2A Scope 逐方法审计、Credential Proxy 授权测试及 API 多租户负向测试；但它阻止把当前 v0.1.5 镜像宣称为具备安全固定槽位 drain/恢复能力，也阻止批次 4 的停机恢复门槛通过。
