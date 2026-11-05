# ADR 0002：按数据库活动 Lease 授权模型代理请求

- **状态：** Accepted
- **日期：** 2026-11-05
- **适用范围：** 固定容量 Worker，单个 Agent 的最大并发任务数为 1

## 背景

固定 v0.1.5 Worker 的模型 Token Source 不接收 Task、Attempt、Lease 或 `ExecutionContext`，模型 HTTP 请求也不包含由 Worker runtime 注入的可信执行标识。请求中的自报 header/body 不能作为 Tenant 或 Attempt 授权依据。Compose 已将 Worker 并发设为 1，Task/Attempt/Assignment/Lease 与 Tenant scope 存在 PostgreSQL 中。

## 决策

Credential Proxy 使用独立于 Gateway Session 的 Worker 模型代理凭据识别 Agent。凭据仅存 SHA-256；认证结果绑定 Agent 与 Tenant/Project/Team scope。代理对每个模型请求按该 Agent 查询 PostgreSQL 当前 Assignment、Attempt、Lease 和 Task `resource_scopes`，不接受客户端指定的租户或 Attempt。

只有查询结果恰好包含一条有效执行时才创建内部 `AttemptExecutionContext`。Assignment、Attempt 和 Lease 必须处于活动状态，Lease 未过期，Task scope 必须与代理凭据绑定的 Agent scope 完全一致，并且存在对应的模型凭证引用。无活动执行、多条活动执行、撤销/过期凭据、缺 scope 或关系不一致时均拒绝请求。内部上下文最多有效 5 分钟，且不能晚于数据库 Lease 到期时间。

代理自身解析上游模型 Secret 并转发请求。Worker 只持有代理凭据，不持有模型供应商 Secret。代理凭据与 Gateway Session 使用不同 audience、哈希记录和撤销路径；撤销 Gateway Session 同时使其关联的模型代理凭据失效。日志、事件和 Trace 不保存请求 Authorization、上游 Secret、提示词或模型响应正文。

## 固定并发约束

`AGENTTEAMS_MAX_CONCURRENT_TASKS=1` 是此方案的安全前提。Resolver 拒绝零条或多条活动 Assignment；不从“最新任务”或客户端字段中猜测当前 Attempt。开放单 Agent 并发前，必须由新 Worker runtime 将 Gateway 可信 Assignment 转换成每个 Attempt 独立的短时 capability，并重新评审此决策。

## 实施顺序

1. Enrollment 签发独立模型代理凭据，数据库只保存哈希，并与 Gateway Session、Agent scope 关联。
2. Credential Proxy 以无登录函数/最小数据库角色验证凭据，并解析唯一有效的 Assignment/Attempt/Lease 与 Task scope。
3. Credential Source 按数据库授权的 Tenant/Project 解析上游 Secret；Worker 网络只允许访问代理端点。
4. Responses API 代理支持流式转发、超时、取消、限流和模型 allowlist；流式期间每秒复核活动 Attempt/Lease，上下文撤销、变化、过期或数据库不可用时关闭上游流；数据库或 Secret 解析失败时 fail-closed。
5. 在隔离 PostgreSQL 和固定 digest Worker 上验收签发、Attempt 归属、租约过期、撤销、Tenant A/B 交叉访问、流式响应和日志脱敏。

## 拒绝的授权输入

- 客户端提交的 `tenantId`、`taskId`、`attemptId`、`leaseId` header 或 JSON 字段。
- Gateway Session 本身直接作为模型代理 bearer token。
- 根据最近一次 Task、缓存中的旧 Lease 或 Worker 自报 scope 推断授权。
