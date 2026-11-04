# AgentTeams New

面向固定容量、多租户 Agent 协作的无 Kubernetes 部署架构。项目以 Java 21 为服务端主线，复用现有 AgentTeams 的任务、租约、Outbox、Gateway 和 Worker 能力，并提供 Docker Compose / systemd 部署路径。

> 当前阶段：固定容量 Compose POC 已接入独立 Worker Enrollment 服务、OIDC scope 检查和每槽位会话凭据投放；签名 JWT HTTP 测试及 PostgreSQL 仓库/DB 最小权限测试通过。真实 OIDC Provider 下的 Worker 任务、多租户负向隔离与生产验收仍未完成。

## 目标

- 不依赖 Kubernetes API、CRD 或 Operator 即可运行任务控制面和固定 Worker 池。
- Worker 数量通过容量评估后预先配置；运行时只调度已注册且空闲的 Worker，不按任务创建或扩缩 Worker。
- 支持多租户数据、会话、文件、工具和凭据授权隔离；共享池和专属池可按租户信任等级选择。
- 保持 PostgreSQL 为任务与租约事实源，保留 Outbox、NATS JetStream、Gateway 持久命令及 ACK 重放语义。
- Kubernetes 保留为可选的 Sandbox / 企业部署 Provider，不成为基础运行前提。

## 技术路线

- **主线：** Java 21、Spring Boot、现有 AgentScope Worker、protobuf/gRPC。
- **持久化与消息：** PostgreSQL、NATS JetStream；对象存储通过 Artifact 接口接入。
- **本机部署：** Docker Compose；单机服务管理可使用 systemd。首期不要求额外 Supervisor 或容器管理 API。
- **跨语言：** Python 仅用于需要其专属 SDK 的 Agent、评测或离线任务；通过版本化 API/gRPC 接入。无实测依据不引入 Go/Rust 服务。

## 仓库边界

当前 `agentteams-java` 是完整源码仓库。为避免本仓库通过本机相对路径引用兄弟目录，或复制一份控制面和 Worker 后独立演进，6 个共享模块与根 parent 已以 `0.1.4` 发布到 GitHub Packages，并通过本仓库独立 Maven consumer 完成远端解析验证。

批次 0 已在 `agentteams-java` 发布并验证 contracts、domain、application-contracts、runtime、observability、storage 六个共享模块的版本化 Maven 制品。后续如发现模块边界不适合独立发布，则以受控迁移和兼容 API/事件契约替代；决策及影响记录在 [共享核心复用 ADR](docs/decisions/0001-shared-core-reuse.md)。

## 架构与计划

- [架构基线](docs/architecture.md)
- [共享核心复用决策](docs/decisions/0001-shared-core-reuse.md)
- [执行计划](docs/plans/2026-11-04-agentteams-new-bootstrap.md)
- [独立 Maven 消费验证](verification/shared-core-consumer/README.md)
- [固定容量 Compose POC](deploy/compose/README.md)
- [Worker Enrollment 实施计划](docs/plans/2026-11-04-worker-enrollment.md)
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

任何资源读取与变更都必须先验证组织、租户、项目和主体权限，再以授权后的 scope 查询数据。Task、Assignment、Attempt、Lease、Conversation、Memory、Artifact、workspace 和凭据授权都必须能追溯到同一租户执行上下文。共享 Worker 首期保持单 Worker 单活动 Attempt（`maxConcurrency=1`），任务结束或取消后清理运行时会话、工具客户端、临时文件及凭据缓存。高敏租户绑定专属固定池；不支持的隔离等级必须拒绝任务，不允许静默降级。

Worker 会话凭据已支持按 tenant/project/team 授权签发、哈希持久化、过期和撤销；每个 Compose Worker 槽位使用独立的 `0600` runtime env 文件。撤销阻止后续 Gateway 握手，不会主动断开已经建立的长连接。

该 Gateway token 只用于 Worker 到 Gateway 的身份认证，不是模型/MCP 上游凭据。Worker 仍可能接触上游模型/MCP 密钥；在请求级凭证代理和负向安全验收完成前，不声明上游密钥与 Worker 隔离。

## 当前不承诺

- 任务到达时创建 Worker 或自动扩缩容。
- 用 Matrix 替代任务状态、租约或持久消息系统。
- 仅凭普通本机容器提供等同 gVisor、Kata 或 MicroVM 的强隔离。
- 在凭证代理完成前宣称上游密钥不会到达 Worker。
- 真实 OIDC Provider 与真实多租户业务下的集成验收、生产就绪状态。

## Maven 制品

共享 Java 模块使用 `agentteams-java` 的稳定 Git tag 作为统一版本发布到 GitHub Packages；新项目固定消费 `0.1.4`，不读取 SNAPSHOT。已通过本机代理和 classic PAT（`read:packages`）使用独立 Maven 缓存完成远端解析；本地发布边界也可用 `verification/shared-core-consumer/verify-local-release.sh` 重放。GitHub Packages Maven Registry 要求认证，即使包关联的仓库公开也需凭证。
