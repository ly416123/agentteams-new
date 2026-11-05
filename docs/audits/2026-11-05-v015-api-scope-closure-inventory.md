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
| Team、membership、policy、revision 与 deployment | `/api/v1/teams/**` | Controller 部分检查项目 scope；Service 使用 `resource_scopes` 检查 Team/Worker 可见性 | 已追踪主要 service 边界；需核验管理角色、未认证开发模式及缺 scope 配置时的 fail-closed |
| Conversation 与 Memory | Conversation 由 Task/Timeline 服务访问；`/api/v1/memory/**` | Conversation Service 显式接收 `ExecutionContext`；Memory Controller 从 Principal 解析上下文 | 已追踪核心服务/查询链；仍需核对所有调用入口、检索路径与后台写入 |
| MCP Server、Connection 与 Route | `/api/v1/mcp-servers/**`、`/api/v1/mcp/connections/**` | Server 通过 `resource_scopes`；Connection 使用 `ExecutionContext` ownership；Route 要求上下文 | 已追踪管理主路径；需核对 discovery/probe 的 outbound 与凭据边界、Attempt 调用授权和 Route 租户绑定 |
| Evaluation、Artifact Quality 与 Webhook | `/api/v1/evaluations/**`、`/api/v1/artifact-quality/**`、`/api/v1/webhooks/**` | Controller 未见直接调用 | 待确认数据归属、导出可见范围、投递/查询过滤及租户配置授权 |
| Organization / Tenant 管理 | `/api/v1/management/organizations/**`、`tenants/**`、`memberships/**`、`integrations/**`、`users/**`、`authorizations/**` | 管理授权另有入口 | 待分别验证 Platform Admin、Organization Admin、Tenant Admin 与普通 Project member 的边界 |
| 配置与 Matrix Room binding | `/api/v1/config/**`、Matrix binding 回调 | Controller 直接 scope 调用不完整 | 待判明 public/runtime 配置与租户配置，验证 Matrix room 与 Tenant/Project 映射不可伪造 |
| Sandbox 与内部服务接口 | `/api/v1/sandboxes/**`、`/internal/v1/artifacts/**`、`/internal/v1/token-ledger/**`、`/internal/v1/task-state-consistency/**` | Sandbox 有直接调用；其余为内部入口 | 待验证网络/服务身份、内部路由是否可从宿主公开入口访问、后台任务的显式 scope 与最小权限 |
| Skills、审计、用量与配额 | `/api/v1/skills/**`、`/api/v1/audit-events`、`/api/v1/usage/**`、`/api/v1/projects/**/quota` | Skill 通过可见性 scope；Project/Quota 有项目授权服务；Audit/Usage 需在服务层按 principal 过滤 | 待逐读写、导出和汇总查询确认 scope 聚合与最小权限 |

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

对 v0.1.5 固定 tag 的迁移目录做只读扫描，没有发现业务迁移启用 `ENABLE ROW LEVEL SECURITY`、`FORCE ROW LEVEL SECURITY` 或 `CREATE POLICY`。因此当前业务表的租户边界是应用层 SQL/Service scope 检查，不是数据库全表 RLS。数据模型也混合三种模式：Conversation、Scheduled Task、MCP Connection、Token Ledger 等直接存 organization/tenant/project；Task、Agent、Team 等通过 `resource_scopes` 建立 scope；Task File、Artifact 等子对象依赖父 Task/Attempt 关联。`resource_scopes` 本身无指向各业务表的外键，跨资源一致性由写入服务维护。全表 RLS 的前置工作应先完成非 owner/非 superuser Control Plane 运行角色、可信事务 scope 注入、关键父子复合约束/触发器与逐表越权测试；只给现有 owner 角色加 policy 不形成有效隔离。

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

## 已追踪的异步执行与 Scheduled Task 链

- `NatsExecutionEventConsumer` 是可信内部消费者，事件数据本身不是授权凭据。TASK transition 会在 `ExecutionEventService` 回查 Task、Attempt、Lease，并验证 `taskId == attempt.taskId`、Attempt/Lease 双向 ID 关系和事件 actor 等于 Lease 所属 Agent；REJECTION 和 LEASE_RENEWAL 也检查相同执行身份关系。transition 附带 Artifact 需属于同一 Task 与当前 Attempt。2026-11-06 对固定 v0.1.5 深入追踪发现原版 `TASK_EVENT` 过程观测支路没有这组绑定校验：Gateway 只匹配连接 Agent 与 metadata Agent，Control Plane 观测适配器随后只按消息 Task ID 读取目标租户 scope 并可写入 Run/Process Event/Webhook。`agentteams-new` 为该固定源码维护加固补丁，并通过 Tenant A Worker→Gateway→NATS→Control Plane Consumer→Tenant B Task 的负向端到端测试：旧版新增 1 条 Run，加固版在 Consumer ACK 后 Run、Process Event、Webhook delivery 均为 0，CI [42111461762](https://github.com/ly416123/agentteams-new/actions/runs/42111461762) 全绿。仅加固派生镜像具备此项修复；未加固 v0.1.5 镜像仍不得用于多租户部署。`CONFIG_APPLIED` 仍需独立负向验收。JetStream 重投递/幂等不替代上述关联授权。
- Task Event 数据查询 SQL 将 aggregate id 与 `resource_scopes` 的 tenant/team、Project 记录以及调用者 active membership 联合过滤；HTTP `TaskService.events()` 先通过 `get(taskId)` scope guard，之后只把已认证 Principal 交给查询。`TaskEventController` 对 SSE payload 再做敏感字段与 credential 字符串脱敏。还需在固定镜像跨 Tenant/撤销 membership 后做重放和游标测试。
- CONFIG_APPLIED 分支由 `ConfigDeploymentService` 验证 snapshot 版本、binding 的 Agent/snapshot 归属、apply event ID 和观察版本，再记录 ACK；Team deployment ACK 需命中对应的 Team member binding + Agent + snapshot/apply generation，冻结绑定作为 Gateway/NATS 内部身份的授权栅栏。仍需伪造 binding/Agent/snapshot 组合的集成负向用例覆盖。
- `OutboxRelay` 通过持久 Outbox store 领取 due 事件并发布，按聚合 ID/版本排序；Relay 自身负责投递语义，不承担租户授权。消费侧需依据父级资源重验，因此 Outbox payload/subject 不能作为权限来源。
- Scheduled Task HTTP 创建/列表/读取/暂停/恢复/触发等入口从当前 Principal 校验 tenant/project；配置 Repository 的查询与更新按 organization/tenant/project 条件过滤。后台 `ScheduledTaskScheduler` 通过单活 Scheduler lease 领取全局 due schedule，再从每条 schedule 的持久化 scope 构造受限 Principal，写入 Task spec 并调用正常 `TaskService.create`，run ID/Task ID 通过 schedule occurrence 做幂等关联。该后台角色是系统调度身份，须单独测试伪造/损坏 schedule scope、跨 Tenant 查询输入、lease 并发和重复触发恢复。
- Task recovery checkpoint/state repository 多按 Task ID 查询，授权边界依赖触发者先通过 Task scope，或后台恢复器仅基于数据库事实执行。尚未确认所有恢复/一致性/质量消费者都在执行写入前验证父 Task/Attempt scope；这些消费者及其幂等/重试边界仍是 2A 未闭环项。
- Assignment 与 Scheduled Task、Sandbox lifecycle、Webhook delivery、Usage budget、Dashboard alert、Worker operation recovery 等多项周期任务通过数据库 Scheduler lease 单活执行；这是并发/重复触发控制，不等于租户授权。Artifact Quality 与 Matrix outbox 使用逐行租约领取事件。Matrix 发送前会在事务中按消息 tenant 与 binding ID 重读并验证 Room binding 仍有效。其余投递作业仍需确认 row payload 的 parent resource 与 tenant 关系及失效/retry 语义。
- Task State Consistency 使用全局批扫描，按 Task/Run snapshot 核验事实并记录 drift；内部查询由 token 保护，但 worker-facing 服务不能因此获得该审计接口。Sandbox lifecycle 按已持久化的 sandbox/task/attempt 记录调用 Provider，创建阶段从 Task/Attempt 派生数据；需在新路径确认强隔离租户没有因缺少 sandbox Provider 而降级至普通容器。

## 已追踪的 Team、MCP 与管理/内部边界

- `TeamController` 对带 Project 的操作委托 `TeamService.requireProjectScope`；`TeamService` 在资源创建时把 `TEAM` scope 绑定到当前 Principal，读取和修改再通过 `resource_scopes.requireVisible("TEAM", id)`，Worker 成员可见性也单独检查 `WORKER` scope。源码还为 `api.enabled=false` 的本地/开发模式保留无 Principal 路径，所以该模式不能拿来证明多租户隔离；生产 Compose 必须开启认证并验证缺失 scope repository 时拒绝受保护操作。成员角色与 Team 管理动作仍需逐项映射并补跨 Project/Tenant 负向测试。
- `McpServerService` 对创建的 Server 绑定 `MCP_SERVER` scope，列表/读取按 scope 可见性过滤；它与 tenant-owned MCP Connection 不是同一权限模型。`McpConnectionController` 的管理读写从 Principal 解析 `ExecutionContext`；Service 校验 Connection 的 organization/tenant 和 connector ID 前缀归属与上下文相符，查询带上下文。Route 列表、注册和心跳要求上下文。Discovery、连接探测、凭据引用解析及 Agent/Attempt 运行时调用仍需追出单独的网络与密钥边界。
- `ProjectController` 委托 `ProjectAuthorizationService` 与 invitation service；组织管理服务按 Platform Admin、Organization Admin 与显式 permission 检查入口。需要用普通项目成员、Tenant Admin、Organization Admin、Platform Admin 四类身份建立矩阵，不能只用 Controller route coverage 测试代替角色验证。
- Internal Quota、Token Ledger、Task State Consistency 等端点使用 `X-AgentTeams-Internal-Token`，其中 Task State Consistency 使用常量时间比较；该共享 bearer token 不是 Tenant 身份。部署上必须通过私有网络入口隔离、各服务独立轮换凭据、严禁 worker-facing ingress 暴露这些路由，并在应用层验证请求中的 Task/Run scope 与真实父记录一致。
- Skill 列表/读写通过 `SKILL` resource scope 可见性过滤；创建时允许全局或 Tenant/Organization ownership，须继续确认创建者权限、发布/扫描审批、包下载 URL 与后台扫描任务的 Tenant 归属。Audit、Usage 和 quota 的查询/汇总也需要确认先绑定 caller 可见项目集合，再做聚合，避免通过计数或筛选侧信道泄露其他 Tenant 信息。
- `JdbcAuditQueryService` 在有 Principal 时把 audit 查询限定为 Principal 的 tenant/project；Organization/Platform management 权限由 `ManagementAuthorizationService` 单独检查角色。Tenant/Organization 管理数据允许对应管理角色跨 Project 查询，这是有意的管理面权限，必须与普通 Project member 的响应集合做区分验收。
- Usage HTTP 路径的源码闭环：`UsageController` 的 summary、CSV export 与 dimensions completeness 都委托 `UsageQueryService`；summary totals/groups、timeseries 与 completeness 均调用 `ScopeFilter.current()`。Principal 存在时查询绑定 token Tenant 与 Project（Project 同时兼容 UUID 和名称到规范 ID 的解析），用户提供的 task/provider/model 条件只作为附加 AND 条件。`ApiAuthenticationFilter` 覆盖 `/api/v1/usage/**`，`ApiAuthorizationPolicy` 为 GET 要求 `USAGE_READ`、export 要求 `USAGE_EXPORT`；启用过滤器后缺失/无效 Bearer 返回 401。
- **部署前提：** `ControlPlaneConfiguration` 仅在 `agentteams.security.api.enabled=true` 时注册这个过滤器；`UsageQueryService.ScopeFilter.current()` 在 Principal 缺失时故意返回空 SQL scope，以支持部署级后台查询与未启用 API auth 的开发模式。因此 Usage Controller/Service 本身不是 fail-closed 边界。`agentteams-new` 的 Compose 仅在 `API_AUTH_ENABLED=true` 时同时开启 Control Plane API/OIDC 认证，并要求 Worker 槽位大于 0 时必须为 true；零 Worker 的开发部署仍可关闭认证。生产多租户部署必须显式启用 API auth，不能因只启动共享服务就省略该项。
- **测试缺口：** `UsageQueryServiceTest` 覆盖带 Principal 时 Tenant/Project SQL scope，也覆盖无 Principal 的未约束查询；当前未看到把匿名 HTTP 请求、OIDC filter 启用状态与 `/usage/summary`、`/export`、`/dimensions/completeness` 串起来的部署态拒绝测试。timeseries 是 QueryService 方法，本 Controller 未暴露对应路由。因此这里是有明确配置前提的源码授权链，不是生产环境 A/B Tenant HTTP 验收证据。
- Usage Budget Repository 对单租户写读使用 tenant/project 复合条件；周期任务由单活 lease 领取所有 active policy 时，需要确认每个 event 保留 policy 的 Tenant/Project 且告警 delivery 无跨 scope 汇总。Webhook Repository 的订阅查询按 organization/tenant/project 过滤；delivery worker 是系统身份，发送前要以 delivery row 所属订阅/secretRef 做一致性校验和 SSRF 保护。

## v0.1.5 Skill 私有包跨 scope 路由缺口及派生补丁处理

**证据级别：** 原始 v0.1.5 发布源码调用链确认；加固派生补丁已有 Controller 负向单测，尚未在运行中的 Control Plane 上重放带 OIDC 身份的跨 Tenant HTTP 请求。

- `SkillController.preparePackageUpload()`、`completePackageUpload()` 与 `downloadPackage()` 直接调用 `SkillPackageStorageService`，没有先调用 `SkillService.getSkill()` 或 `ResourceScopeRepository.requireVisible()`。
- `SkillPackageStorageService.versionForSkill()` 只按 `versionId` 读取版本并比较路径中的 `skillId` 与 `version.skillId()`；它没有读取 `PrincipalContext`、Tenant/Project membership 或 `SKILL` scope。知道有效 Skill/Version ID 的已认证用户因此可以请求私有包的 presigned upload/download URL 或确认上传。
- 同一 `SkillService` 的 `getSkill()`、版本列表、publish/review/disable 会执行 Skill scope 检查，因此这是包对象入口与其余 Skill API 授权不一致，不是证明所有 Skill API 均无授权。
- `SkillControllerTest.preparesAndCompletesPackageUploadWithoutAcceptingCallerStorageKey()` 只验证忽略客户端 storage key 并返回 URL；该测试使用 standalone Controller，没有身份、Tenant A/B 或 scope 拒绝断言。

**修复状态：** 原始 v0.1.5 发布源码仍存在上述路径缺口。`agentteams-new` 维护的固定 v0.1.5 派生补丁已在 `preparePackageUpload()`、`completePackageUpload()`、`downloadPackage()` 调用存储服务前执行 `service.getSkill(skillId)`，由 `SkillService` 的 `SKILL` scope guard 在任何存储 URL 签发前拒绝越权。新增 `SkillControllerTest.rejectsCrossScopePackageUploadCompletionAndDownloadBeforeIssuingStorageUrls` 覆盖三条路由均返回 403，且 `SkillPackageStorageService` 零交互；类内 6/6、0 skipped，已加入固定派生镜像构建门禁。该证据关闭了派生补丁路径的已知 Controller 绕过，但仍是服务层 Controller 测试，不是已认证真实 HTTP 的 Tenant A/B 集成验收；Skill 包 A/B 端到端验收仍列为多租户门槛，不能据此宣称复用 v0.1.5 的整套 API 已满足隔离。

## 证据级别与后续门槛

- **源码发现：** v0.1.5 有 Project membership、`resource_scopes` 和应用层授权路径；`tasks` 等核心实体并非都直接存 Tenant/Project/Team 列。已有 Enrollment RLS 仅保护 `agents` 与 `agent_sessions` 的受限路径。
- **尚未证明：** 上表所有 Controller 与服务调用链均无越权；MCP、Team、Skill、Audit/Usage 和管理 API 的所有读写、汇总及下载路径均执行正确 Tenant/Project/Team 检查；NATS TASK_EVENT/CONFIG_APPLIED 与所有恢复/一致性/质量消费者对篡改关联 ID 一律拒绝；所有逐行 Outbox 的 payload tenant 与父资源一致；固定 Compose 中 A/B Tenant 并发与同 Worker 连续 Attempt 的 Conversation/Workspace/Tool/Artifact 无残留；v0.1.5 业务 schema 没有 RLS，未来 RLS 方案尚未实现或验证。
- **下一步 2A：** Conversation/Memory、Artifact/File、NATS 主执行事件、Scheduled Task、Team/MCP、组织管理和部分单活/Outbox 作业已追踪，但尚未形成逐方法仓储/迁移/测试闭环。TASK_EVENT、Skill 包私有路由的已发现缺口和 ConfigApplied Agent/binding 关系已有固定补丁门禁；新增的 Gateway gRPC→JetStream→Control Plane 负向测试已覆盖跨租户 CONFIG_APPLIED 冒认 binding，消费者 ACK 后目标 apply 保持 PENDING（2/2 消息链门禁，含 TASK_EVENT）。仍需完成恢复/质量/其他后台消费者、Skill/Audit/Usage 和内部端点的父级校验，再按身份矩阵核验角色；为每族记录有效身份、scope 来源、拒绝响应、现有测试和缺失测试。
- **2A 出口条件：** 所有可访问 API 与异步消费者都映射到真实表和 scope 来源；每项未授权路径有可复现负向用例或明确的修复任务；任何尚未被证明的共享 Control Plane 能力继续标记为未验收。
- **环境边界：** L5 的 v0.1.5 IdP issuer/JWKS 是 HTTP，当前只读 RBAC 不能确认 HTTPS Ingress；不能用 L5 旧 Worker Secret 替代新项目 Provider 凭据。上述源码盘点不宣称运行时多租户验收。
