# 复用 Control Plane 的租户 scope 与 RLS 就绪度审计

**日期：** 2026-11-05  
**审计对象：** `agentteams-java` Control Plane v0.1.5，源码 commit `0156f2c1fecdc868cb4a0f4f13fb068bb4162834`  
**方式：** 只读检查迁移 SQL、Repository、授权服务和 Compose 连接配置；未修改 `agentteams-java`，未读取 L5 数据库表或 Secret 内容。另通过 L5 的 `readonly-pod-logs` 上下文检查 Pod 元信息与非敏感环境配置。

## 结论

当前 Control Plane 通过应用层 OIDC Principal、Project membership 和 `resource_scopes` 做多租户授权。其业务数据表不是统一携带 Tenant/Project/Team 列：Task 等数据依赖独立 scope 映射表，Conversation 则保存自身 scope。固定 v0.1.5 的 Repository 会把 `ExecutionContext` 放进部分查询条件，但源码没有给 PostgreSQL 事务设置 scope GUC。审计时 Compose 把 `agentteams` 同时设为 PostgreSQL 初始化用户和 Control Plane 数据库用户；后续 `agentteams-new` Compose 改为 `agentteams_runtime` 非超级用户运行 SQL，`agentteams` 仅供 Flyway 与数据库初始化。

因此，单在 `agentteams-new` 增加其他业务表的 RLS policy，不能形成完整隔离：当前 Control Plane 超级用户可绕过所有 RLS；即使先把它降权，表 owner 在未启用 `FORCE ROW LEVEL SECURITY` 时也会绕过策略。若启用 FORCE，现有请求又没有设置策略所需的事务 scope。要完成全系统 RLS，必须连同非 superuser/非 owner 运行角色、请求事务上下文、后台任务身份及所有读写路径一起改造。当前仓库只给独立 `worker_enrollment` 角色启用了 `agents` 和 `agent_sessions` RLS；这不覆盖 Control Plane。

**实现跟进（2026-11-05）：** 固定容量 Compose 为 Control Plane 增加独立 `agentteams_runtime` 登录角色；数据库初始化服务先行创建该角色并授予现有及后续迁移对象所需权限，Flyway 继续使用 `agentteams`。由于 Enrollment 启用 `agents` 和 `agent_sessions` 的 RLS，策略文件为 Control Plane 运行角色增加兼容性的全表策略，保留旧版应用层授权行为。该运行角色没有 `BYPASSRLS`，但这些表的访问并未按 Tenant scope 限制，不能视作租户隔离已完成。目标分支已通过 PostgreSQL 16 临时实例验证初始化脚本、非超级用户身份、现有表读写和后续建表权限；Enrollment 的 13 项 PostgreSQL/OIDC/Gateway 集成门禁定向执行时 13/13 通过；13 项 Compose/容量/OIDC 契约测试、Compose 配置解析、shell 语法和 `git diff --check` 通过。完整 Enrollment `verify` 的 28 项测试中 27 项通过，固定 amd64 Gateway 在 arm64 Colima 上启动超时。推送提交 `7015e20` 后，PR [#1 CI run 42021472658](https://github.com/ly416123/agentteams-new/actions/runs/42021472658) 全绿，包含 PostgreSQL 集成门禁、Compose 校验和 Credential Proxy 镜像构建。

## 数据模型与 scope 归属

| 资源 | 数据表与关联 | 当前 scope 依据 | RLS 设计约束 |
|---|---|---|---|
| Tenant、Project、成员关系 | `tenants`、`projects`、`project_memberships` | Project 和 membership 行有 `tenant_id`；Membership 还绑定 `project_id` 与 `subject` | 可直接按 Tenant/Project GUC 限制；成员授权仍需应用层校验 |
| Agent / Worker | `agents`；所有权另存 `resource_scopes` | `resource_scopes(resource_type, resource_id)` 中的 Tenant/Project/Team；Worker 类型使用 `WORKER` | Policy 需联查 scope 行，插入时需保证 scope 与 Project/Team 关系一致 |
| Team | `teams`、`team_tasks`；所有权另存 `resource_scopes` | Team UUID 通过 `TEAM` scope 行归属 | 不能只按客户端传来的 Team UUID 过滤 |
| Task | `tasks`；所有权另存 `resource_scopes` | `TASK` scope 行；列表查询联查 Tenant、Project、Team 和当前有效 Membership | Task 主表自身没有 Tenant/Project/Team 列；写策略需在 `WITH CHECK` 中验证 scope 绑定 |
| Attempt / Assignment / Lease | `task_attempts`、`task_assignments`、`agent_leases` 通过 `task_id`、`attempt_id`、`agent_id` 关联 | 从父 Task 的 `resource_scopes` 继承，并验证 Assignment 中 Agent 同属该 scope | 子表策略必须沿 FK 关系检查，不能只相信子行可写的 `task_id` |
| Artifact / Task File | `artifacts`、`task_files` 通过 `task_id` / `attempt_id` 关联 | 从父 Task 和 Attempt 继承；对象 key 只是存储定位符 | 上传、查询、下载签发都要校验同一父 Task scope |
| Conversation / Message | `conversations` 有 `tenant_id/project_id/team_id`；`conversation_messages_cp` 关联 `conversation_id`，并冗余 `tenant_id` | Conversation 自身 scope；Message 应继承 Conversation | Message 的冗余 Tenant 必须由数据库或受信写路径与父行校验 |
| Domain Event / Outbox | `domain_events`、`outbox_events` 保存聚合 ID 与 JSON payload | 表结构没有独立 Tenant/Project/Team 列 | 消费者须回查已授权聚合；payload/header 不能独立授予访问权。若要 RLS，应先加稳定 scope 列和写入一致性约束 |
| Worker Session | `agent_sessions.agent_id → agents.id` | 从 Agent `metadata.scope` 得到 Tenant/Project/Team | 新增 Enrollment RLS 已通过 Agent 关联策略限制；仅适用于受限 Enrollment 角色 |

主要实现依据：

- `control-plane/src/main/resources/db/migration/V1__foundation.sql`：Task、Attempt、Assignment、Lease、Event、Outbox 和 Artifact 表结构。
- `control-plane/src/main/resources/db/migration/V5__teams.sql`：Team、Team membership、Team Task 与 Team assignment 关系。
- `control-plane/src/main/resources/db/migration/V25__project_authorization.sql`：Project 与 Project Membership 结构。
- `control-plane/src/main/resources/db/migration/V29__resource_scopes.sql`：scope-neutral 资源的多态所有权表。
- `control-plane/src/main/resources/db/migration/V60__organization_tenant_execution_policy.sql`：Organization、Tenant、Tenant Membership 与 legacy tenant mapping 结构。
- `control-plane/src/main/resources/db/migration/V94__task_files.sql` 与 `V96__conversation_unified_protocol.sql`：Task File、Conversation 和 Message 结构。
- `control-plane/src/main/java/io/agentteams/controlplane/security/ResourceScopeRepository.java`：创建资源时绑定 scope；读取资源时验证 Tenant、Project、Team 和有效 Membership。
- `control-plane/src/main/java/io/agentteams/controlplane/security/ResourceAuthorizationService.java` 与 `ProjectApiAuthorization.java`：Project Membership 与角色权限检查。
- `control-plane/src/main/java/io/agentteams/controlplane/persistence/TaskRepository.java`：Task 列表查询按 scope 和有效 Membership 过滤；同时存在供内部工作流使用的 ID 查询、恢复和更新方法，需分别确认调用身份。
- `deploy/compose/compose.yaml`：`POSTGRES_USER=agentteams` 与 Control Plane 的 `SPRING_DATASOURCE_USERNAME=agentteams` 共用同一个初始化数据库账号。

## 已有保护与未覆盖点

- OIDC 验证器检查 issuer、audience，并将 Tenant/Project/Team claims 映射成 Principal；Project 角色和有效 Membership 再决定操作权限。
- Task 列表查询显式联查 `resource_scopes`、Project 和 Membership。若资源没有 scope 绑定，带 Principal 的可见性检查不会返回可见。
- `resource_scopes` 是多态表，没有指向各业务表的外键。父子资源一致性需要应用事务或新增数据库约束保证。
- `ResourceScopeRepository.visible()` 在 `PrincipalContext` 为空时允许通过。这与身份认证关闭时的开发/系统调用兼容；因此生产多租户保证依赖 Control Plane OIDC 开关和 HTTP 请求身份上下文均正确启用。
- 内部调度、恢复、Outbox 消费会执行无 HTTP Principal 的数据库操作。全系统 RLS 必须为这些作业定义显式最小权限身份，不能把空 scope 当成跨租户通配符。
- 对话记录有直接 scope 字段，但 Message 表又冗余 Tenant 字段；对象存储、缓存、Worker 本地 workspace 和工具会话不由 PostgreSQL RLS 覆盖。
- 以上是源码/迁移路径审计，不等于已运行双 Tenant 负向集成测试，也不证明每个 Controller 路径都没有遗漏。

## RLS 结论与实施顺序

1. 保持当前 Enrollment RLS：独立角色、非表 owner、无 `BYPASSRLS`、事务局部 scope、缺失 scope 默认拒绝。Enrollment 不读取 `agents.metadata`；数据库触发器同步 scope 窄列表。触发函数归属只拥有该索引的 `NOLOGIN`、非 superuser、无 `BYPASSRLS` 角色，写 Agent 的服务角色无权直接改 scope 索引。
2. 在使用固定 Control Plane 前，以真实 OIDC Principal 对 Task、Attempt、Conversation、Artifact、MCP/Skill/Model 及管理 API 执行跨 Tenant/Project/Team ID 猜测和变更负向测试；检查所有 Controller、后台消费者与运维端点。
3. 若要求 PostgreSQL 本身强制全系统隔离，先拆分超级用户初始化/迁移账号与非 superuser、非 owner 的 Control Plane 运行角色；再在目标运行时实现每请求事务级 `ExecutionContext` 注入，并给后台消费者独立的系统 scope。
4. 按实际外键逐表启用 RLS：父子资源使用 `USING` 与 `WITH CHECK`；`resource_scopes` 资源验证真实父 Project；Outbox 先显式携带并验证 scope。连接池同物理连接跨 Tenant 复用、无 scope、写入越权和角色绕过测试均纳入门禁。
5. 在上述工作完成前，部署说明应继续明确：当前是应用层多租户授权加 Enrollment 表 RLS，不得称为全系统数据库强隔离。

## 凭证代理接入边界

### L5 运行资源核验（2026-11-05，只读）

`readonly-pod-logs` 可列出 `agentteams` 命名空间 Pod，并查看其描述和日志；不能读取 Secret/ConfigMap，也不能列出 Deployment、StatefulSet、Service 或 Ingress。Keycloak Pod 运行 `quay.io/keycloak/keycloak:25.0.6`，使用 `--http-port=8080`，Pod 描述中的 hostname 为 HTTP。Control Plane Pod 的非敏感环境配置确认 realm `agentteams`、issuer `http://192.168.122.55:30082/realms/agentteams`、内部 JWKS `http://keycloak:8080/realms/agentteams/protocol/openid-connect/certs`、audience `agentteams-api`，以及 Tenant/Project/Team/Permissions claim 分别为 `tenant`、`project`、`team`、`permissions`。Worker Pod 描述确认模型访问 Token Secret 名称为 `agentteams-chatgpt-oauth-session`，只读挂载路径 `/var/run/agentteams/chatgpt/token`；未读取 Secret 数据。该环境没有可直接用于新 Compose 的本机模型凭据文件，且当前 OIDC issuer/JWKS 不满足新项目的 HTTPS 生产契约。下一次真实联调需要运维提供可信 HTTPS issuer/JWKS 地址（或为 L5 配置 HTTPS 入口），以及在运行新项目的主机上创建/指定独立模型凭据文件；不应复制 L5 Secret 到仓库或新环境。现有 L5 工作负载未改动。

固定 Worker 源码 `agent-worker/src/main/java/io/agentteams/worker/chatgpt/ChatGptPlanModel.java` 支持通过 `AGENTTEAMS_CHATGPT_RESPONSES_URL` 覆盖 Responses API 地址；`ChatGptPlanTokenSource.fromPath()` 每次模型请求都会重新读取 `AGENTTEAMS_CHATGPT_ACCESS_TOKEN_FILE` 指向的文件。因此 `agentteams-new` 可以把该 endpoint 指向凭证代理，并让 Worker 文件只保存代理消费凭据，无需修改旧源码。

**Credential Proxy 授权查询加固（agentteams-new，2026-11-05）：** `credential_proxy_active_attempts` 现只接受 Proxy Credential ID，不再接受调用方自报的 Agent ID 或 Tenant/Project/Team。函数从有效 Credential、未撤销 Session 和 Enrollment 当前 scope 派生身份，再验证 Worker 与 Task scope 一致；Repository 对返回行再次与已验证身份逐字段比对。旧四参数函数在升级脚本中保留签名但撤销 `PUBLIC`、`credential_proxy_query`、`credential_proxy_runtime`、`worker_enrollment` 与 `agentteams_runtime` 的执行权，避免数据库依赖对象导致 `DROP FUNCTION` 失败；新运行角色只能调用新的单参数函数。该改动只强化新项目 Credential Proxy 数据库边界，不改变 v0.1.5 Control Plane 的其它业务表 RLS 缺口，也不证明真实 Worker/HTTP 双租户链路已通过。

但当前 `ChatGptPlanTokenSource.accessToken()` 接口不接收 `TaskId`、`AttemptId`、Lease 或 `ExecutionContext`，HTTP 模型请求也没有由 Worker 运行时注入这些可信字段。Compose 已改为每个 Worker 槽位单独挂载模型 token Secret，并拒绝活动槽位使用同一底层文件；这降低了槽位间凭证复用，但 Worker 进程仍可读取自己的上游凭证。直接加一个反向代理只能做到模型端点替换，不能自然获得 Attempt 级授权；凭证代理验收前要么先扩展 Worker 的 ExecutionContext 传递契约，要么明确选用较窄的 Agent/槽位 scope 并接受其风险边界，不能把它描述为 Attempt 隔离。

实现凭证代理前应先锁定调用身份来源和轮换生命周期：代理消费凭据不可等同于 Gateway Session，不得通过可伪造的请求体字段确定 Tenant/Attempt；代理需在每次上游请求时执行范围、有效期、撤销和模型 allowlist 校验。若要维持「不改旧项目」的约束，Attempt 级能力缺口需由新项目的协议/sidecar/网关路径补足并经真实 Worker 测试证明。
