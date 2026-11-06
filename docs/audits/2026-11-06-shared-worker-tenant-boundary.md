# 固定共享 Worker 池跨租户边界审计

## 结论

当前 v0.1.5 复用路径不能让一个逻辑 Worker 槽位在不同 Tenant 间复用。Compose 中一个容器进程可承载的固定 Worker 不是 Tenant-neutral 的执行槽位：Agent/Worker 会话和模型代理身份都绑定单一 Tenant/Project/Team。只修改公平队列或删除 scope 相等判断，都会造成“看起来是共享池、运行时仍租户专属”或让长期凭证失去租户约束。

## 代码证据

- pinned Control Plane 0156f2c1fecdc868cb4a0f4f13fb068bb4162834 的 AgentRepository.findReadyMatchingInTaskProject 仅接受 worker_scope.tenant_id = task_scope.tenant_id 且 worker_scope.project_id = task_scope.project_id 的 Worker。
- 同一 pinned-source 的 JdbcTaskEventScopeGuard 要求 Worker scope 与 Task scope 的 Tenant、Project、Team 全部相等，事件投影才被接受。
- agentteams-new/worker-enrollment 的 Session 签发需要调用者对 Worker scope 有项目管理成员身份；Session 读取 Agent 当前 scope，模型代理凭据把该 scope 与模型凭据引用一起持久化。
- credential-proxy 的 credential_proxy_active_attempts(proxy_credential_id) 同时要求 Enrollment 当前 scope、Worker scope、Task scope 与代理凭据 Tenant/Project/Team 全部相同；Java AttemptExecutionContextResolver 再次逐字段核对。
- 所以当前模型代理 token 既是会话级又是 Tenant/Project 级。简单放宽 Worker scope 会破坏任务授权来源，而且会使一个 Worker 绑定的 modelCredentialRef 被错误地用于其他 Tenant。

## 目标安全模型

物理容量由固定的、Tenant-neutral 的执行槽位共享；业务授权在每个 Attempt 建立：

1. Worker 长期身份只证明“这是获准进入共享池的执行槽位”，没有 Tenant 数据读取权、Provider 凭据或任务枚举权限。
2. Control Plane 在单个原子领取事务中绑定 slot → task → attempt → lease → tenant/project/team，并签发不可伪造、短时、可撤销的 Attempt capability。Tenant 只能由持久 TASK scope 派生，不能由 Worker 请求字段声明。
3. Worker 的任务事件、文件/Artifact 访问及模型调用都必须携带该 capability；服务端重新验证其 Agent/slot、Attempt、Lease、Task scope 和有效期。租约释放/超时后 capability 立即失效。
4. Credential Proxy 以 Attempt 的可信 Tenant/Project/Team 选择相应 Provider 凭据引用；长期 Worker session 不固定 Tenant secret。凭据仍由代理或受控 Secret Source 读取，不投放到 Worker。
5. 每个 Attempt 使用独立 workspace 和清理/恢复状态；同一个槽位在前一 Attempt 完成并清理前不得复用。所有对外副作用仍需幂等键/检查点。

## 实现顺序

先在 Enrollment、Control Plane TaskEvent/Artifact、Credential Proxy 中实现 Attempt capability 的签发、哈希存储、撤销和越权拒绝测试；然后将固定 Worker 表示为 Tenant-neutral slot，更新调度器匹配条件；最后再接 Tenant 公平队列、租户并发/排队配额和 Compose 长短任务端到端测试。Capability 不存在、失效、与 Attempt 不一致、Lease 已释放或跨 Task 使用均须 fail closed。

## 尚未证明

当前 L5 合成 Tenant A/B Enrollment 验收只证明项目 scope 会话隔离；并未证明 Tenant-neutral Worker 在共享 Pod 上安全承接 A/B Attempt。真实 OIDC、Attempt capability、跨租户槽位重用、每 Tenant Provider Secret 选择与清理恢复均未完成。
