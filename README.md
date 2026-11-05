# AgentTeams New

面向固定容量、多租户 Agent 协作的无 Kubernetes 部署架构。项目以 Java 21 为服务端主线，复用现有 AgentTeams 的任务、租约、Outbox、Gateway 和 Worker 能力，并提供 Docker Compose / systemd 部署路径。

> 当前阶段：固定容量 Compose POC 已接入独立 Worker Enrollment 服务、OIDC scope 检查和每槽位会话凭据投放；签名 JWT→HTTP→PostgreSQL 测试及 DB 最小权限测试通过，覆盖哈希、撤销和 tenant/project/team 拒绝。上游 Gateway 会话 fail-closed 修复已包含在 `agentteams-java v0.1.5`，固定 digest 的 5 种 gRPC 握手场景已在本机全部通过且未跳过。Credential Proxy 会在每次请求出站前及流式传输期间复核 Attempt/Lease 与代理凭据/Session，撤销后关闭上游流；活动流默认上限为 64。真实 OIDC Provider、Worker runtime 任务闭环和生产验收仍未完成。

## 目标

- 不依赖 Kubernetes API、CRD 或 Operator 即可运行任务控制面和固定 Worker 池。
- Worker 数量通过容量评估后预先配置；运行时只调度已注册且空闲的 Worker，不按任务创建或扩缩 Worker。
- 支持多租户数据、会话、文件、工具和凭据授权隔离；固定 Worker 槽位绑定单一 Tenant/Project/Team，共享基础设施不代表同一 Worker 可跨租户执行。
- 保持 PostgreSQL 为任务与租约事实源，保留 Outbox、NATS JetStream、Gateway 持久命令及 ACK 重放语义。
- Kubernetes 保留为可选的 Sandbox / 企业部署 Provider，不成为基础运行前提。

## 技术路线

- **主线：** Java 21、Spring Boot、现有 AgentScope Worker、protobuf/gRPC。
- **持久化与消息：** PostgreSQL、NATS JetStream；对象存储通过 Artifact 接口接入。
- **本机部署：** Docker Compose；单机服务管理可使用 systemd。首期不要求额外 Supervisor 或容器管理 API。
- **跨语言：** Python 仅用于需要其专属 SDK 的 Agent、评测或离线任务；通过版本化 API/gRPC 接入。无实测依据不引入 Go/Rust 服务。

## 仓库边界

`agentteams-new` 是无 Kubernetes 目标架构的主线和最终部署入口。多租户授权、Worker enrollment、固定容量调度、会话/Attempt 隔离、凭证代理、部署配置及后续新能力均在本仓库实现和验收；不能因为旧仓库已有实现，就默认把新功能继续加到 `agentteams-java`。

`agentteams-java` 当前作为有版本约束的上游兼容来源：新项目可复用稳定的领域/API 核心制品；当前 POC 暂时复用 v0.1.5 Control Plane、Gateway 和 Worker OCI 镜像，以验证任务协议、状态机和部署链路。整套旧应用镜像只是迁移过渡，不是新架构的终态依赖。只有阻断新项目的上游安全/兼容问题才回旧仓库做最小、独立、可审计的修复；后续应在新仓库建立所需的替代实现或边界清晰的应用制品。

六个共享模块与根 parent 已以固定版本发布到 GitHub Packages，并通过独立 Maven consumer 完成远端解析验证。共享核心复用策略及应用镜像迁移门槛见[共享核心复用 ADR](docs/decisions/0001-shared-core-reuse.md)。

## 架构与计划

- [架构基线](docs/architecture.md)
- [共享核心复用决策](docs/decisions/0001-shared-core-reuse.md)
- [执行计划](docs/plans/2026-11-04-agentteams-new-bootstrap.md)
- [独立 Maven 消费验证](verification/shared-core-consumer/README.md)
- [固定容量 Compose POC](deploy/compose/README.md)
- [Worker Enrollment 实施计划](docs/plans/2026-11-04-worker-enrollment.md)
- [多租户与会话隔离验收矩阵](docs/specs/2026-11-05-tenant-session-isolation-matrix.md)
- [运行时复用与隔离审计](docs/audits/2026-11-04-runtime-reuse-gap-audit.md)
- [原仓库 Kubernetes 依赖审计](../agentteams-java/docs/superpowers/specs/2026-11-04-runtime-k8s-dependency-audit.md)

## 项目结构

```text
agentteams-new/
├── docs/
│   ├── decisions/       # 架构决策记录
│   ├── plans/           # 分阶段执行计划
│   └── architecture.md  # 组件边界、运行与隔离模型
├── deploy/
│   └── compose/         # 固定容量部署配置与启动前校验
├── verification/
│   └── shared-core-consumer/ # 独立 Maven 依赖解析 fixture
├── pom.xml              # 新项目 Maven 配置与核心制品仓库
└── README.md
```

## 部署档位

| 档位 | 目标 | 运行组件 | 边界 |
|---|---|---|---|
| `local-dev` | 开发与单机验证 | PostgreSQL、NATS、控制面、Gateway、固定 Worker | 无 Kubernetes；本地凭据为受信任开发模式 |
| `self-hosted-small` | 小规模自托管 | 同一 Java 服务组合、持久卷、固定 Worker 清单 | 配置容量与备份；不自动扩缩、不承诺主机级强隔离 |
| `enterprise-k8s` | 既有企业集群 | 复用现有 Helm/Operator 与 Sandbox Provider | 属于可选兼容档，不是 `agentteams-new` 的基础依赖 |

## 多租户安全基线

任何资源读取与变更都必须先验证组织、租户、项目和主体权限，再以授权后的 scope 查询数据。Task、Assignment、Attempt、Lease、Conversation、Memory、Artifact、workspace 和凭据授权都必须能追溯到同一租户执行上下文。每个固定 Worker 槽位绑定单一 Tenant/Project/Team，首期保持单 Worker 单活动 Attempt（`maxConcurrency=1`）；任务结束或取消后清理运行时会话、工具客户端、临时文件及凭据缓存。高敏租户绑定专属固定池；不支持的隔离等级必须拒绝任务，不允许静默降级。若以后要让同一进程跨租户复用，必须另行实现并通过 Attempt 全隔离验收后再启用。

Worker 会话凭据已支持按 tenant/project/team 授权签发、哈希持久化、过期和撤销；每个 Compose Worker 槽位使用独立的 `0600` runtime env 文件。撤销阻止后续 Gateway 握手，不会主动断开已经建立的长连接。

该 Gateway token 只用于 Worker 到 Gateway 的身份认证，不是模型/MCP 上游凭据。Worker 仍可能接触上游模型/MCP 密钥；在请求级凭证代理和负向安全验收完成前，不声明上游密钥与 Worker 隔离。

## 当前不承诺

- 任务到达时创建 Worker 或自动扩缩容。
- 用 Matrix 替代任务状态、租约或持久消息系统。
- 仅凭普通本机容器提供等同 gVisor、Kata 或 MicroVM 的强隔离。
- 在凭证代理完成前宣称上游密钥不会到达 Worker。
- 真实 OIDC Provider 与真实多租户业务下的集成验收、生产就绪状态。

## Maven 制品

共享 Java 模块使用 `agentteams-java` 的稳定 Git tag 作为统一版本发布到 GitHub Packages；新项目固定消费 `0.1.5`，不读取 SNAPSHOT。已通过本机代理和 classic PAT（`read:packages`）使用独立 Maven 缓存完成远端解析；本地发布边界也可用 `verification/shared-core-consumer/verify-local-release.sh` 重放。GitHub Packages Maven Registry 要求认证，即使包关联的仓库公开也需凭证。
