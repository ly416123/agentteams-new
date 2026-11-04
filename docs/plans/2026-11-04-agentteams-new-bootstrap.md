# AgentTeams New 项目启动计划

> **面向 AI 代理的工作者：** 实施时按批次完成，并在每批次后审查租户隔离与兼容边界。不得通过复制核心状态机来缩短路径。

**目标：** 建立与 `agentteams-java` 并列的无 Kubernetes 固定容量架构入口，逐步交付可部署、具备多租户隔离的 Java 21 AgentTeams 运行路径。

**架构：** 保留 PostgreSQL、Outbox、NATS JetStream、Gateway 持久命令和 gRPC Worker 通道。Worker 作为预配置固定执行槽由 Compose/systemd 管理。多租户授权与 ExecutionContext 在控制面强制执行；会话、工作目录、制品和凭证按租户隔离。

**技术栈：** Java 21、Spring Boot、AgentScope、protobuf/gRPC、PostgreSQL、NATS JetStream、Docker Compose；对象存储与 Secret Manager 通过适配器接入。

---

## 文件与组件清单

- `README.md`：项目目的、边界、支持档位和安全声明。
- `docs/architecture.md`：运行链、固定容量、多租户、凭证和部署架构。
- `docs/decisions/0001-shared-core-reuse.md`：跨仓库共享核心策略。
- `docs/plans/2026-11-04-agentteams-new-bootstrap.md`：本执行计划和门槛。
- `deploy/compose/`：在真实镜像制品可用后放置 Compose 文件、配置和启动说明。
- 后续 Java 模块：只在共享制品策略落实后增加引用，不在首批复制旧模块源码。

## 批次 0：共享核心制品边界

- [x] 从 `agentteams-java` 根 POM 与模块 POM 提取模块依赖图。6 个共享模块依赖闭包包含根 parent；Control Plane/Gateway/Worker 作为 OCI 应用交付。
- [x] 检查候选 `contracts`、`domain`、`application-contracts`、`runtime`、`observability`、`storage` 的 Maven parent、版本属性和内部依赖。
- [x] 选定 GitHub Packages、稳定 tag 版本、`GITHUB_TOKEN` 发布权限，以及 `agentteams-new` 的 package 读取权限要求。
- [x] 在旧仓库根 POM 增加 `distributionManagement`，在 release workflow 中归一化 tag 版本并发布 parent + 6 个共享核心模块；预检 13 个 POM/JAR 文件以支持成功重跑并阻止部分覆盖。
- [x] 在新仓库添加 Maven parent POM 和独立 consumer fixture。
- [x] 创建 GitHub 仓库 `ly416123/agentteams-new` 并推送启动基线 `34010d1`；旧仓库 Maven 包尚未发布，因此跨仓库读取仍待验证。
- [x] 使用临时 file repository、Maven 版本 `0.1.1` 和独立 consumer local cache 端到端验证 parent、6 个模块及传递依赖解析。
- [x] 增加 Maven 发布状态分类测试：未发布时允许发布，完整已发布时跳过，部分发布时失败并要求保留现有制品、修复后使用新 patch tag；预检先校验 GitHub 仓库身份访问，再仅跟随 HTTPS 重定向且不向跨主机转发凭证，并禁用 `.netrc`。GitHub 包必须授予旧仓库写权限。
- [ ] 在稳定 tag 发布首个 GitHub Maven Package，并验证 `agentteams-new` 仓库授权后的真实远端读取。
- [ ] 将实际发布版本写入 `agentteams.core.version`，并记录 source tag、package URLs 和验证输出。

**门槛：** 不依赖本机兄弟目录或手工 SNAPSHOT 安装；模块版本、源 commit 与依赖树可追溯。

**当前进度：** 本地 file repository 独立消费验证已通过；GitHub 远端发布与跨仓库权限验证待首个稳定 tag。Compose POC 需等待该门槛完成。

## 批次 1：无 Kubernetes 固定容量 Compose POC

1. 先从旧仓库构建并发布 Control Plane、Gateway、Worker 的固定版本 OCI 镜像；记录镜像 digest 和源 commit。
2. 新仓库新增 `deploy/compose/compose.yaml`、`.env.example`、`README.md` 和配置校验脚本。服务包括 PostgreSQL、NATS JetStream、Control Plane、Gateway 和由 `WORKER_REPLICAS` 静态配置生成的固定 Worker 槽位；不提供 Docker Socket。
3. 为数据库和 NATS 配置持久卷、健康检查、启动依赖和最小凭据配置；MinIO 与 Matrix 作为可选 profile。
4. 明确 Worker 实例身份稳定性、注册/心跳、drain、关闭信号与重启重连要求；Compose 只做部署编排，不实现任务级扩缩。
5. 编写一条端到端操作手册：启动、创建租户/Team、注册 Worker、提交任务、读取 Timeline/Artifact、停止并重启恢复。

**门槛：** 环境不提供 Kubernetes 凭据或 API 时可完成端到端任务；服务重启后任务、Outbox、命令和 ACK 状态正确恢复；容量不足只排队/限流。

## 批次 2：租户与会话隔离

1. 定义不可变 `ExecutionContext` 的 protobuf 字段、签发方、验证方、版本及过期规则；scope 不由 Worker/客户端自行选择。
2. 盘点 Task、Attempt、Conversation、Memory、Artifact、MCP binding 和 workspace 表/API，逐一确认授权后的租户 scope 过滤；为跨租户 ID 猜测和伪造上下文写拒绝场景。
3. 实现 Attempt 独立会话状态、目录、缓存键和 MCP 客户端生命周期；完成/取消/失败/租约过期均执行清理。
4. 明确 PostgreSQL RLS 的启用范围与连接池事务变量设置；如果暂不采用 RLS，仍需数据库访问层集中 scope 并给出等价负向验证。
5. 实现租户级并发、速率和模型预算；支持共享池与专属固定池路由，高敏租户不能落入共享池。

**门槛：** A/B 租户并发和连续复用同一 Worker 的负向验收证明 Conversation、Memory、Task、Artifact、工具状态、临时文件和凭据缓存互不可见；越权和伪造 scope 均拒绝。

## 批次 3：凭证源与凭证代理

1. 抽象 `CredentialSource`，先接容器 Secret 文件与至少一种企业 Secret Manager；配置和数据库只存非敏感引用。
2. 定义短时消费令牌：tenant/project/task/attempt/audience/model-or-tool scope、过期、轮换、撤销与重放防护。
3. 建设模型代理，验证 OpenAI-compatible 请求、流式转发、取消、超时、限流、成本归因和上游凭据不回传。
4. 建设独立 MCP Connector/Gateway，实施 server/tool allowlist、scope 验证、输入输出审计与密钥脱敏。
5. 所有代理授权和 Secret 解析失败均 fail-closed；上游密钥不得出现在 Worker 环境、命令、事件、日志、Trace 或产物中。

**门槛：** 越权模型/工具、过期/撤销令牌、跨租户 Attempt 和代理故障均拒绝；真实模型和 MCP 调用可按租户、项目和 Attempt 审计。

## 批次 4：容量、恢复与生产档位

1. 用真实任务到达率、执行时长、目标排队延迟、模型限流、租户配额与冗余率计算固定池建议，并通过压测校准。
2. 定义 `local-dev`、`self-hosted-small`、`enterprise-k8s` 的部署与 Sandbox 支持矩阵。
3. 补齐 PostgreSQL/NATS/对象存储备份恢复、升级回滚、凭据轮换、Worker drain 和 K8s ↔ Compose 切换操作。
4. 对要求强隔离的任务连接显式 gVisor/Kata/MicroVM Provider；不具备 Provider 时拒绝执行。
5. 建立从发布制品到 OCI digest、配置版本、数据库迁移和回滚步骤的追溯信息。

**门槛：** 容量不足不创建 Worker；租户配额公平；节点/服务重启和中间件中断后可恢复；不支持的 Sandbox 档位明确拒绝；高敏租户只进入专属池。

## 全程约束

- Java 21 保持控制面、调度、Gateway 和 AgentScope Worker 主线。Python 只按实际 SDK/算法需要接入，且不得另建任务/租户事实源。
- 新旧路径任一时刻只有一个调度写入者；禁止业务状态双写。
- Matrix 只是可选协作渠道；PostgreSQL、Outbox、NATS 持久语义不由内存队列替代。
- 不在目标路径完成前宣称生产就绪、零信任凭证隔离或普通容器强隔离。
