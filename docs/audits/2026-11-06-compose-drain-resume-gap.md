# Compose 固定 Worker drain / resume 边界审计

**日期：** 2026-11-06
**范围：** `agentteams-java` 固定 Worker v0.1.5（source commit `0156f2c1fecdc868cb4a0f4f13fb068bb4162834`）在 `agentteams-new` Compose 固定槽位部署中的 Worker 生命周期。只读检查源码和计划，不访问业务数据或 Secret。

## 结论

v0.1.5 有持久化 `DRAIN` 与 `TERMINATE` 操作，但它们不是可逆的 Compose 停机/重启协议。当前不应把它们包装成 `drain-worker.sh` 并宣称可安全滚动重启：

1. `POST /api/v1/agents/{id}/operations/drain` 将 Agent phase 设为 `DRAINING`；任务分配只选择 `READY` Worker，因此新任务会停止分配。相应的 `WorkerOperationServiceTest.drainingWorkerIsExcludedFromNewAssignments` 覆盖此边界。
2. DRAIN operation 初始保持 `PENDING`。公开 API 没有按 Agent 查询活动 Lease 数的运维接口；Task execution API 需要逐 Task 查询，不能作为可靠的全量 drain 完成信号。
3. `WorkerOperationService.terminate` 在数据库事务中检查活动 Lease。Lease 清零时，它把先前 DRAIN operation 标为 `DRAINED`，同时把 Agent phase 置为 `TERMINATED`。这表示永久终止流程，不适用于同一固定槽位原地重启。
4. Gateway 注册会保留 `DRAINING` phase；canonical Agent 的注册允许状态不含 `TERMINATED`。因此当前没有可靠的“停止后复用同一 Agent ID 并恢复 READY”的 Compose 控制面闭环。

## 安全影响

- 直接 `docker compose restart worker-N` 可用于进程崩溃/重连演练，但不能作为有活动任务时的优雅升级证明；当前 Worker shutdown hook 会停止 runtime。
- 先调用 DRAIN 再用 TERMINATE 等待 Lease 清零会永久终止 Agent，不能用作日常重启流程。
- 对备份操作，仍需暂停外部任务生产者并由操作者确认没有活动 Attempt；仅看到 DRAIN operation 创建成功不等于已排空。

## 落地要求

在 `agentteams-new` 的 pinned-source patch 中补齐可审计、可恢复的固定槽位生命周期协议，再添加 Compose 命令：

1. 将 DRAINED 变成独立于永久 TERMINATE 的状态；由 Control Plane 在锁定 Agent 行的事务中检查活动 Lease 数，并可被状态查询接口可靠读取。
2. 新增受 OIDC scope 保护的 `resume`/`activate` 操作，只允许已注册且 Gateway 在线的同一 Agent 从 `DRAINING` 回到 `READY`；需原子校验无活动旧 Lease，防止恢复与任务分配竞争。
3. Compose 流程为：请求 drain → 等待 DRAINED → 停止/升级固定容器 → 等待 Gateway 握手 → 请求 activate → 验证 READY。任何超时都不得强制终止活动 Attempt；明确输出 operation ID 和下一步恢复命令。
4. PostgreSQL 集成测试覆盖活动 Lease 阻止排空完成、并发任务派发不能越过 DRAINING、重启后重新激活、跨租户主体无权操作，并要求测试零跳过。

在此之前，Compose 只可验收 Worker 进程非优雅重启后的 Lease 过期恢复与重新注册；“优雅 drain/restart”保持未通过。
