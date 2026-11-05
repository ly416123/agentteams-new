# Control Plane v0.1.5 API 与 scope 闭环盘点

**日期：** 2026-11-05  
**审计对象：** `agentteams-java` tag `v0.1.5`，源码 commit `0156f2c1fecdc868cb4a0f4f13fb068bb4162834`  
**目的：** 为 `agentteams-new` 批次 2A 列出真实 HTTP、事件与内部作业边界，确定哪些行为必须在固定镜像上验收，哪些能力必须移入新仓库。  
**方式：** 通过 GitHub `v0.1.5` 源码目录与文件只读检查；没有修改 `agentteams-java`，没有连接数据库、调用 L5 API 或读取 Secret。

## 范围结论

新仓库目前只实现 Worker Enrollment 与 Credential Proxy；任务、Attempt、Conversation、Artifact、Team、Memory、MCP、Outbox 和多数管理 API 都由固定 v0.1.5 Control Plane 镜像提供。因而新仓库当前的 Enrollment/Proxy RLS 与 Token 测试不能代表整条请求链的 Tenant 隔离。

Controller 源码中能直接看到 `PrincipalContext`、`ProjectApiAuthorization` 或 `ResourceScope` 调用的入口，不代表其余 Controller 没有授权；有些通过 Service、Repository、过滤器或可信内部身份检查。反过来，路由存在也不能证明其完整授权链已覆盖。下表将“Controller 未见直接调用”标为待跟踪项，不据此判定漏洞或通过。

## HTTP 入口清单

| 资源边界 | v0.1.5 路由族 | Controller 直接 scope 调用 | 2A 状态 |
|---|---|---|---|
| Agent / Worker 与操作 | `/api/v1/agents`、`/api/v1/agents/{id}/operations/**` | 可见 | 待关联 Agent/操作 API 的管理角色、Tenant membership 与数据库查询负向测试 |
| Task 生命周期与列表 | `/api/v1/tasks`、`/api/v1/tasks/{id}/**` | 部分可见 | 待覆盖读取、创建、修改、取消、排队、重试、暂停、归档、审批、统计与过滤参数 |
| Task execution、process 与恢复 | `/api/v1/tasks/{taskId}/execution`、`runs/**`、`process-events`、`progress`、`result`、`tree`、`decisions`、`recovery` | Controller 未见直接调用；Execution/Recovery 先调 `TaskService.get()`；Process/Result/Tree/Decisions 通过 `ExecutionContextResolver` | 主路径已追到 Task scope/context 检查；仍需逐个核对 repository 与后台恢复身份 |
| Subtask、Adjustment 与来源证据 | `/api/v1/tasks/{taskId}/subtasks/**`、`/api/v1/tasks/{id}/adjustments`、`source-evidence` | 部分可见 | 待验证父 Task scope 继承、子资源创建一致性及读取/修改权限 |
| Task Event 流 | `/api/v1/tasks/{taskId}/events` | Controller 未见直接调用；`TaskService.events()` 先调用 `get()`，再向 Repository 传入已认证 Principal | 代码路径有 Task scope 过滤；需补充/确认事件路由的跨 Tenant 负向验收与重放边界 |
| Artifact、Task File 与结果审查 | `/api/v1/tasks/{taskId}/attempts/{attemptId}/artifacts/**`、`/api/v1/tasks/{taskId}/files/**`、`/api/v1/tasks/{id}/results/**`、`/api/v1/artifacts` | Artifact/File 可见父资源检查；结果服务检查 Task scope | 已追踪父 Task/Attempt 的主要调用链；仍需验证每个读写端点、租户冗余列一致性、URL 签发与对象 key 猜测 |
| Scheduled Task | `/api/v1/scheduled-tasks/**` | Controller 未见直接调用 | 待追踪创建者/Project scope、派生 Task 与后台触发身份 |
| Team、membership、policy、revision 与 deployment | `/api/v1/teams/**` | Controller 未见直接调用 | 待逐路由核验管理者角色、成员授权、revision 与 deployment 的 Team scope |
| Conversation 与 Memory | Conversation 由 Task/Timeline 服务访问；`/api/v1/memory/**` | Conversation Service 显式接收 `ExecutionContext`；Memory Controller 从 Principal 解析上下文 | 已追踪核心服务/查询链；仍需核对所有调用入口、检索路径与后台写入 |
| MCP Server、Connection 与 Route | `/api/v1/mcp-servers/**`、`/api/v1/mcp/connections/**` | Controller 未见直接调用 | 待区分项目级配置管理与 Attempt 级调用；核对凭据访问、server/tool allowlist 与 Route 心跳身份 |
| Evaluation、Artifact Quality 与 Webhook | `/api/v1/evaluations/**`、`/api/v1/artifact-quality/**`、`/api/v1/webhooks/**` | Controller 未见直接调用 | 待确认数据归属、导出可见范围、投递/查询过滤及租户配置授权 |
| Organization / Tenant 管理 | `/api/v1/management/organizations/**`、`tenants/**`、`memberships/**`、`integrations/**`、`users/**`、`authorizations/**` | 管理授权另有入口 | 待分别验证 Platform Admin、Organization Admin、Tenant Admin 与普通 Project member 的边界 |
| 配置与 Matrix Room binding | `/api/v1/config/**`、Matrix binding 回调 | Controller 直接 scope 调用不完整 | 待判明 public/runtime 配置与租户配置，验证 Matrix room 与 Tenant/Project 映射不可伪造 |
| Sandbox 与内部服务接口 | `/api/v1/sandboxes/**`、`/internal/v1/artifacts/**`、`/internal/v1/token-ledger/**`、`/internal/v1/task-state-consistency/**` | Sandbox 有直接调用；其余为内部入口 | 待验证网络/服务身份、内部路由是否可从宿主公开入口访问、后台任务的显式 scope 与最小权限 |

入口名称来自固定 tag 中的 `control-plane/.../api`、`artifact`、`memory`、`mcp`、`organization`、`sandbox`、`token`、`quality` 与 `task` 源码目录。上表是路由族清单，不代替逐方法的调用图与负向测试。

## 持久化与异步边界

| 资源 | v0.1.5 事实源/实现 | 需要闭环的授权关系 |
|---|---|---|
| Tenant、Project、Project Membership | `tenants`、`projects`、`project_memberships` | OIDC subject → 当前 membership/role；撤销后新请求立即拒绝 |
| Agent / Team | `agents`、`teams`、`team_memberships` 与 `resource_scopes` | Agent、Team 的 Tenant/Project/Team 父子一致性；Enrollment 已独立加固 Agent/Session 子集 |
| Task / Attempt / Assignment / Lease | `tasks`、`task_attempts`、`task_assignments`、`agent_leases`、`resource_scopes` | Task scope 是父级事实；Assignment 中 Agent 同 scope；Attempt 与有效 Lease 同一执行 |
| Conversation / Message / Memory | `conversations`、`conversation_messages_cp`、Memory 相关表 | Message 冗余 scope 必须与父 Conversation 一致；检索必须带已授权的 Task/Attempt 上下文 |
| Artifact / Task File / Result | `artifacts`、`task_files`、`task_result_manifests`、`task_result_artifacts` | 每次上传、完成、读取和 URL 签发都从数据库父资源重验 scope；对象 key 不能授权 |
| MCP 与集成凭据 | `mcp_servers`、`mcp_connections`、`mcp_connector_routes`、Organization integration credential 表 | 配置管理 scope 与运行时 Attempt scope 分开；Worker 不获得上游凭据 |
| Outbox / Domain Event / 后台 Job | `outbox_events`、`domain_events`、各类 reconciliation/recovery/quality 作业表 | 消费者从父资源回查 scope；payload/header 不单独授予权限；重投递保持幂等 |
| PostgreSQL 运行身份 | Compose 当前让 Control Plane 使用初始化用户 `agentteams` | 必须先拆迁移/初始化与运行身份；超级用户/表 owner 会绕过普通 RLS，单独加 policy 不够 |

异步路径包括 Outbox Relay、NATS execution event consumer、Task recovery/checkpoint、Scheduled Task、Team deployment timeout、Agent presence consistency、Artifact quality delivery 和内部一致性检查。它们不能复用“HTTP Principal 缺省即系统全权”的隐式规则；每种作业需有明确的系统身份和父资源 scope 验证。

## 已追踪的 Task API 调用链

- `TaskController` 创建时从 OIDC `PrincipalContext` 校验请求中的 spec scope；读取、取消、队列、重试、暂停、审批、归档和删除路径都通过 `TaskService.get()` / 可见性校验或列表 Repository 的 Principal scope 查询。
- `TaskService.get()` 读取 Task 后调用 `ResourceScopeRepository.requireVisible("TASK", id)`；`events()` 先调用 `get()`，随后把当前 Principal 传给 `DomainEventRepository.findTaskEvents(...)`。Task Event Controller 不自行信任客户端提供的 Tenant 字段。
- `TaskExecutionController` 的 execution、runs、checkpoints、recovery 路径都先调用 `TaskService.get(taskId)`，随后才读取对应元数据。固定源码有 `TaskExecutionControllerTest.doesNotReadExecutionMetadataWhenTaskIsOutsideCallerScope` 负向测试。
- `TaskProcessController` 从 `PrincipalContext` 解析 `ExecutionContext`，事件、进度、结果、树和决策服务均接收该上下文；公开路径只允许 requester-visible 数据。该上下文解析/每个 repository 的父 Task/Run 关系仍要逐方法核实。
- `TaskResultVersionService.list/get` 调用 Task 可见性检查；review 在事务前检查 Task scope，并要求 `TASK_APPROVE` 权限。当前只读检查未发现 Task Result 内容在授权前返回的路径。

固定 tag 中可见的测试包括 `TaskServiceScopeTest`（创建绑定 caller scope、跨 Project 读取拒绝）、`TaskExecutionControllerTest`（越权时不读取执行元数据）、`TaskEventControllerTest`（cursor 与敏感字段脱敏）、`TaskProcessControllerTest`（process 可见性）和 `TaskResultVersionServiceTest`（审批权限/结果规则）。`ApiRouteAuthorizationCoverageTest` 检查每个 public Controller mapping 都有一个显式身份/授权边界；这不是逐资源 Tenant scope 证明。当前没有从这些单测证据推导全 API 族或 NATS/后台作业已通过。

## 已追踪的 Conversation、Memory 与 Artifact/File 链

- Conversation 的 `ConversationService` 在 create/find/archive/message append/list/task binding 操作中都要求 `ExecutionContext`。JDBC Conversation 查询按 ID、tenant、project 和 team（含 nullable team）联合过滤。Message append/list 先要求父 Conversation 在当前 scope 内，写入也带 tenant；但仍需检查所有 Repository SQL 是否同时以父级校验及 scope 过滤，尤其是幂等键查找、重试和任何非 HTTP 调用入口。`ConversationServiceTest.rejectsCrossTenantConversationReadsBeforeAppending` 有跨 Tenant 读/写拒绝的可见测试证据。
- Memory 列表用 Principal 解析的 `ExecutionContext` 查询 organization/tenant/project，并对每条记录再执行 `MemoryScopeVisibility.visibleToList`。治理服务按 memory ID + organization + tenant 读取，再校验 project/team/task/user-private 可见性；冻结、删除、导出要求 `memory:govern`，操作记录绑定 organization、tenant、actor、reason 和幂等键。仍需核对所有 Memory 检索/写入实现与后台任务，而不只管理 Controller。
- `TaskFileController` 的上传、附件登记、列表、内容和下载都先调用 `TaskService.get(taskId)`，有 Principal 时再对 Task spec 执行 `PrincipalContext.requireScope`，随后文件服务按 Task ID 查询记录；文件对象 key 由 Task ID/文件 ID 构成，下载经服务端存储读取。其余 service/repository 的非 HTTP 入口和租户冗余字段一致性待核。
- `ArtifactController` 的 list/get/upload prepare/complete/download 都先验证 Attempt 存在且其 `taskId` 与路径 Task ID 一致；认证请求还调用 `TaskService.get(taskId)`，Artifact ID 路径另有 `resource_scopes.requireVisible("ARTIFACT", id)`。响应会生成短期下载 URL。可见代码以父 Task 和 Attempt 关系作为 scope 链；该方法没有直接比对 Attempt 冗余 Tenant/Project/Team 字段，仍需证明写入时的一致性约束、Artifact scope bind 与下载 URL 生命周期，且内部调用不得跳过父资源检查。
- `/api/v1/artifacts` 元数据列表按 `resource_scopes.visible("TASK", taskId)` 过滤；需要检查分页/计数、过滤条件与下载签发是否同样 scoped。固定源码可见 Conversation 跨租户 Service 测试及 TaskFile/Artifact Controller 测试，但目前未确认它们对每条越权变体均有负向断言。

## 证据级别与后续门槛

- **源码发现：** v0.1.5 有 Project membership、`resource_scopes` 和应用层授权路径；`tasks` 等核心实体并非都直接存 Tenant/Project/Team 列。已有 Enrollment RLS 仅保护 `agents` 与 `agent_sessions` 的受限路径。
- **尚未证明：** 上表所有 Controller 与服务调用链均无越权；MCP、Team/Scheduled Task 与管理 API 的所有写入/读取均执行 Tenant/Project/Team 检查；NATS/后台消费者对篡改关联 ID 一律拒绝；固定 Compose 中 A/B Tenant 并发与同 Worker 连续 Attempt 的 Conversation/Workspace/Tool/Artifact 无残留；全业务表 RLS 可强制执行。
- **下一步 2A：** Conversation/Memory 与 Artifact/File 的主要服务边界已追踪，但尚未形成逐方法仓储/迁移/测试闭环。继续完成其余端点和异步调用，再审 MCP、Team/Scheduled Task/Management/Internal；为每族记录有效身份、scope 来源、拒绝响应、现有测试和缺失测试。
- **2A 出口条件：** 所有可访问 API 与异步消费者都映射到真实表和 scope 来源；每项未授权路径有可复现负向用例或明确的修复任务；任何尚未被证明的共享 Control Plane 能力继续标记为未验收。
- **环境边界：** L5 的 v0.1.5 IdP issuer/JWKS 是 HTTP，当前只读 RBAC 不能确认 HTTPS Ingress；不能用 L5 旧 Worker Secret 替代新项目 Provider 凭据。上述源码盘点不宣称运行时多租户验收。
