# 旧运行时复用边界审计

**审计对象：** `agentteams-java` v0.1.4 源码提交 `02d5ad3017f56b2a3cbb6c60112077d32f3299f4`，以及 `agentteams-new` 批次 1 Compose POC。  
**目的：** 明确新项目可以复用的任务运行能力、隔离依赖和后续实现顺序。  
**范围：** 任务派发、Worker 生命周期、持久化、凭证、Matrix、租户/会话/制品隔离。旧仓库仅作只读检查。

## 结论

`agentteams-java` 已有可复用的 Java 任务状态机、数据库租约与恢复、Gateway 持久状态、应用层授权、Matrix 身份/房间绑定和 S3 兼容制品流程。它能作为新项目的兼容运行时与业务行为基线。

但 v0.1.4 还不能证明满足 `agentteams-new` 的多租户隔离目标：授权主要依靠 API 与 Service 层，迁移中未发现 PostgreSQL Row-Level Security（RLS）策略；Worker 的 MCP 凭证可从环境变量解析，模型 Token 可由 Worker 进程读取文件；对象存储 key 与短时 URL 本身不是授权机制；Matrix 与租户绑定仍依赖应用层身份和房间映射。Gateway 在存在 PostgreSQL 时会使用 `agent_sessions` 短时会话 Token 校验，但源码没有该记录的签发/撤销服务或 API；当前 Compose 也没有为 Worker 提供 Gateway 会话 Token。另一个重要边界是 v0.1.4 的 API OIDC 安全默认关闭，Compose 只将 HTTP 端口绑定到本机回环地址。现有 Compose 冒烟只验证了基础服务、TaskFile API 和对象持久性，没有运行真实 Worker Attempt，也没有 A/B 租户负向验收。

因此批次 1 继续作为无 Kubernetes 的部署底座。新仓库现已增加独立 Worker Enrollment 服务和 Compose 每槽位凭证投放：OIDC issuer/JWKS/audience 验证、`worker:session:manage` 权限、Agent scope 精确比对、明文只回传一次、DB 仅存 SHA-256、过期与撤销 API。固定容量不需要动态创建或扩缩 Worker，但仍须支持注册、健康状态、排空、租约失效和重启恢复。此实现尚未在真实 IdP 和 Gateway 上做集成验收，也未完成完整多租户隔离或上游凭证代理。

## 依赖审计

| 领域 | v0.1.4 已有机制 | 关键依赖/缺口 | 新项目决策 |
|---|---|---|---|
| 任务派发 | `TaskAssignmentService` 在事务中锁定任务，生成 Assignment、Attempt、Lease 并写入 Outbox；租约到期可回收。调度器有数据库调度租约。 | 调度面对的是数据库中的 Agent/Worker 注册状态；Compose 只固定进程数量，不会自动保证实例身份唯一、排空或容量正确。 | 复用派发与状态机。Compose 槽位用稳定 Worker ID；容量不足排队/限流。补齐 drain、重启和过期 Lease 验收，不引入 K8s Controller。 |
| Worker 生命周期 | Gateway 处理 Hello、会话 Token 校验、协议协商、注册、心跳/在线投影、重连及命令重放；`JdbcAgentStateStore` 持久化连接状态。 | v0.1.4 `agent_sessions` 没有签发/撤销入口；撤销只会影响未来握手，不会主动断开现有连接。Enrollment 接通了数据库结构与协议，但真实 Worker 尚未连通验证。 | 新项目增加受控 enrollment 服务：OIDC 权限和 tenant/project/team scope 校验、短时 Worker Token、数据库 Hash、轮换/撤销及每槽位文件投放。不要用 SQL 手工灌入长期 Token 冒充完成注册。 |
| 状态持久化 | PostgreSQL 保存任务、Attempt、Lease、Assignment、Outbox 等；Gateway 命令/ACK 状态可走 JDBC 持久化；NATS JetStream 承载持久事件。 | 必须确认新部署使用持久卷、备份恢复和迁移流程；不能把 Gateway 内存连接状态当成事实源。 | 保留 PostgreSQL + Outbox + JetStream 的职责划分。Compose POC 已验证迁移到 v121 与 NATS stream 创建；还需故障恢复和真实任务端到端验收。 |
| API 与租户授权 | `ExecutionContext` 包含 organization/tenant/project/team/subject；目录解析校验成员关系；任务 Service 校验任务 spec scope；项目资源授权与资源 scope 映射已存在。API 默认拒绝没有显式授权边界的路由。 | 隔离依赖应用层查询和调用路径正确。审计的 Flyway SQL 未发现 RLS policy；运维 SQL、后台 Job、内部服务若绕过授权 Service，数据库不会自动按租户拒绝。 | 先集中 scope-aware Repository/Service 并让缺 scope 失败关闭。对高风险共享表增加 PostgreSQL RLS 和连接事务 scope 设置，测试连接池复用时上下文不会串租户。 |
| 会话与 Attempt | Assignment payload 有 Attempt/Lease ID；AgentScope MCP 请求可附 Task/Attempt/Lease/Call ID；Task Memory 可由控制面组装。 | 这些标识不等同于 Worker 执行隔离。需要确认对话记忆、工具客户端、临时目录、缓存和 MCP 会话是否按 Attempt 创建/清理。此次 Compose POC 未运行真实 Attempt，无法证明。 | 每个 Attempt 使用隔离会话键、workspace、缓存命名空间和工具客户端生命周期；终态/取消/租约超时统一清理。共享池先设 `maxConcurrency=1`，验收后再开放并发。 |
| 制品与对象存储 | TaskFile API 可由控制面授权后上传/下载；对象 key 有 task/file 或 task/attempt 维度；Artifact Complete 校验 key、大小和 SHA-256；可签发短时 S3 URL。 | S3 URL 是持有者凭证。旧 `ArtifactService.prepareDownload(storageKey, ...)` 本身不做授权；安全依赖 Controller 先验证 Task/Attempt 与资源 scope。Key 未天然包含 organization/tenant，存储端也不执行租户策略。 | 所有下载先授权 Task/Attempt，再由服务端签发短时 URL；不能接受客户端任意 key。新 key 带不可变 tenant/project/task/attempt；设对象生命周期与签名 TTL。补 A→B 猜 ID、跨租户 URL 负向验收。 |
| 凭证 | Agent Worker MCP Provider 可从环境变量名解析密钥；ChatGPT 计划模型支持从 Token 文件读取；MCP 请求构造时注入 Authorization。 | 上游秘密进入 Worker 进程环境或文件，Worker 被攻陷时可能读取。现有模式是受信 Worker 凭证投放，不是零信任代理。Compose Secret 文件只能改善投放方式，不能改变 Worker 可读事实。 | 批次 3 实现 Credential Source + 短时消费 Token + 模型/MCP Proxy。Worker 只持有绑定 tenant/project/task/attempt/audience 的短时 Token；代理解析上游密钥并 fail-closed。过渡期明确标注受信执行档，不宣称凭证隔离。 |
| Matrix | Matrix 身份映射到 Principal；房间绑定带 Project；处理命令时解析 ExecutionContext 并校验房间 Project 与调用者 Project 一致，再经统一 Dispatcher。 | Matrix 是输入/通知适配器，不是授权事实源。绑定、成员撤销、租户迁移和事件重放仍需应用层校验；消息可见性取决于 Matrix 房间成员管理。 | 保持可选集成。每条命令以服务端 Principal 和当前绑定重新授权；拒绝仅凭 room ID 或消息正文里的 tenant/task 标识授权。Matrix 可见性不替代 API 数据隔离。 |
| 部署与容量 | v0.1.4 Worker/Gateway/Control Plane 以 OCI 镜像交付；Compose POC 已固定 Worker 槽位、镜像 digest 和 amd64 平台。 | 旧 Kubernetes Operator/CR 不应成为无 K8s 路径的运行时依赖。Compose POC 当前未验收 Worker 自动注册、drain 与真实任务。 | 新路径只依赖 Compose/systemd、PostgreSQL、NATS 与可选 S3/Matrix。容量由压测和业务参数确定；变化时版本化配置后滚动部署，不做按任务动态扩缩。 |
| 控制面 API 身份 | v0.1.4 有 OIDC/JWT 过滤器、显式路由授权和项目成员校验。 | `application.yml` 默认 `agentteams.security.api.enabled=false`、OIDC `enabled=false`；Compose 未覆盖。当前 API 仅因端口绑定 `127.0.0.1` 限制网络入口，不能承载多人/多租户远程访问。 | 新部署必须显式配置 OIDC issuer、audience 和 tenant/project/team claim；启动时生产档缺少配置则失败。开发档仅绑定回环并显示醒目警告。 |

## 依赖分类

### 可直接复用（需锁定 0.1.4 制品）

- Java 任务领域模型、Assignment/Attempt/Lease 状态和事务接口。
- PostgreSQL Outbox、Gateway 持久命令/ACK 投影与 NATS JetStream 消息边界。
- Worker gRPC 协议、注册与心跳基础、任务处理器及 Matrix 适配器。
- TaskFile/Artifact API、校验和和 S3 兼容存储接口。

复用表示保持既有协议和业务状态，不代表已经完成新项目的隔离验收。新项目不得再实现第二套 Task/Attempt 状态机。

### 必须在新项目补强或验证

1. 租户 scope 从认证入口贯穿 Repository、后台 Job、Outbox 消费和 Artifact 下载；未绑定 scope 的操作默认拒绝。
2. PostgreSQL RLS 是否覆盖 Task、Attempt、会话/记忆、文件/Artifact、凭证引用与审计表；连接池复用下事务上下文要可证明。
3. Attempt 会话、文件系统、工具连接和缓存的创建、唯一命名与终结清理。
4. 固定 Worker 槽位的注册、健康、唯一身份、drain、进程退出、恢复和容量不足排队行为。
5. Worker enrollment 的真实 Gateway 握手、轮换、撤销与到期恢复；避免 `.env` 存放明文长期会话 Token。（服务实现已完成，需集成验收。）
6. Worker 不接收上游模型/MCP 明文凭证的代理模式及负向安全验收。
7. Matrix 命令在处理当下重新做租户授权，房间绑定变更即时生效。

## 可落地执行计划

| 顺序 | 批次 | 交付物 | 通过条件 |
|---:|---|---|---|
| 1 | 批次 1 收尾 | 新项目 Worker enrollment/API 身份配置、Compose 单槽位 Token 投放与生命周期操作手册 | OIDC 身份启用；Worker Token 可签发/轮换/撤销且 DB 仅存 Hash；一个 Worker 完成真实 Task Attempt；CP/Gateway/Worker 重启可恢复；容量到顶只排队 |
| 2 | 批次 2A：scope 闭环 | `ExecutionContext` 版本化字段与签发/验证约定；按表/接口列出的 scope 矩阵；统一 scope-aware 数据访问入口 | A/B 租户访问 Task、Attempt、Memory、Artifact、MCP binding 和管理 API 的 ID 猜测/伪造 scope 全部拒绝 |
| 3 | 批次 2B：会话隔离 | Attempt workspace/session/cache/tool-client 生命周期和清理策略 | 同一 Worker 顺序复用 A、B 租户后无残留；并发场景无会话、文件、工具状态串扰 |
| 4 | 批次 2C：数据库纵深防御 | 选择 RLS 表范围、DB role、事务变量注入和迁移方案；缺 scope 的后台任务显式用受限系统身份 | 开启连接池复用并跨租户切换连接，越权 SQL 被 RLS 拒绝；应用层检查仍启用 |
| 5 | 批次 3：凭证代理 | 凭证引用、消费 Token、模型 Proxy、MCP Connector、审计脱敏 | Worker 环境/文件/消息/日志没有上游明文凭证；过期、撤销、错误租户或 audience 的 Token 均拒绝 |
| 6 | 批次 4：容量与恢复 | 容量测算、配额、备份恢复、升级回滚、部署档位矩阵 | 固定池在目标负载下达到排队目标；中间件和进程恢复不丢任务事实；安全等级不支持时拒绝执行 |

**建议实施顺序：** 先完成 1 的真实 Worker 流程作为基线，再按 2A → 2B → 2C 建立隔离，之后实施凭证代理。RLS 是纵深防御，不代替应用层授权；若 RLS 暂缓，批次 2 仍必须先完成集中 scope 数据访问和跨租户负向验收。

## 验收边界与风险

- 当前 Compose 证据限于 PostgreSQL 迁移、NATS stream、Control Plane/Gateway 启动、TaskFile 上传下载和 SeaweedFS 重启后对象仍在；新增 Enrollment 模块的 15 项单元/HTTP 安全测试、Maven verify、Compose config 与 shell 语法校验已通过。新增本地 RSA/JWKS 测试发行者覆盖真实 JWT 签名、issuer、audience 解码，但没有证明真实 Worker 执行或多租户隔离。真实 OIDC Provider、数据库/Gateway 握手集成尚未运行；启用 Worker 前必须设置 `API_AUTH_ENABLED=true` 并配置真实 IdP。
- `agentteams-java` 的测试目录已有 ExecutionContext、ScopeIsolation、TaskService、Artifact、Matrix 和调度单元测试；这些测试支持应用机制存在，但不是新部署组合的端到端安全证据。
- 本文档只审计指定 commit，不保证上游后续版本行为相同。升级共享制品前需按同一矩阵重新审计。
- 在批次 2/3 通过前，部署说明必须称为「单租户/受信 Worker 试验档」或明确限制租户间共池；不得宣称多租户强隔离、零信任凭证或生产就绪。

## 源码证据索引

以下路径均相对于 `agentteams-java` commit `02d5ad3017f56b2a3cbb6c60112077d32f3299f4`：

- `control-plane/src/main/java/io/agentteams/controlplane/security/ExecutionContext.java`
- `control-plane/src/main/java/io/agentteams/controlplane/security/ExecutionContextResolver.java`
- `control-plane/src/main/java/io/agentteams/controlplane/security/JdbcExecutionContextDirectory.java`
- `control-plane/src/main/java/io/agentteams/controlplane/security/ApiAuthenticationFilter.java`
- `control-plane/src/main/java/io/agentteams/controlplane/service/TaskAssignmentService.java`
- `control-plane/src/main/java/io/agentteams/controlplane/service/TaskAssignmentScheduler.java`
- `control-plane/src/main/java/io/agentteams/controlplane/service/TaskService.java`
- `control-plane/src/main/java/io/agentteams/controlplane/api/TaskFileController.java`
- `control-plane/src/main/java/io/agentteams/controlplane/api/ArtifactController.java`
- `control-plane/src/main/java/io/agentteams/controlplane/artifact/ArtifactService.java`
- `control-plane/src/main/java/io/agentteams/controlplane/matrix/MatrixTaskCommandHandler.java`
- `agent-worker/src/main/java/io/agentteams/worker/agentscope/EnvironmentMcpCredentialProvider.java`
- `agent-worker/src/main/java/io/agentteams/worker/agentscope/AgentScopeMcpRuntimePort.java`
- `agent-worker/src/main/java/io/agentteams/worker/chatgpt/ChatGptPlanModel.java`
- `agent-gateway/src/main/java/io/agentteams/gateway/JdbcAgentStateStore.java`
- `agent-gateway/src/main/java/io/agentteams/gateway/AgentChannelService.java`
- `agent-gateway/src/main/java/io/agentteams/gateway/JdbcAgentSessionStore.java`
- `agent-gateway/src/main/java/io/agentteams/gateway/SessionTokenAuthenticator.java`
- `agent-gateway/src/main/java/io/agentteams/gateway/AgentGatewayGrpcConfiguration.java`
- `control-plane/src/main/resources/application.yml`（OIDC/API 安全默认关闭）
- `control-plane/src/main/resources/db/migration/`（全量 SQL 检查未发现 RLS policy）
