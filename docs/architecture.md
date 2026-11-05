# AgentTeams New 架构基线

**状态：** 阶段性目标架构；Compose 基础服务已验证，真实 Worker 与多租户隔离尚未验收
**日期：** 2026-11-04

## 1. 设计目标

提供无 Kubernetes 运行路径，适配可预估容量的 Agent 协作业务。Worker 作为预置执行槽运行，任务调度只分配已注册、健康、空闲的槽位。系统必须支持多租户，并将租户隔离视为上线条件，而非后续增强项。

## 2. 组件与事实源

```text
Client / Console / Matrix Adapter
              │ authenticated API / channel events
              ▼
Control Plane ───── PostgreSQL (Task / Attempt / Lease / tenant scope)
      │ Outbox                         │
      ▼                                │
NATS JetStream ──► Agent Gateway ◄─────┘ command persistence / ACK cursor
                         │ gRPC stream
                         ▼
             Fixed Worker Pool (preconfigured slots)
                │                 │
        Model Credential     MCP Connector
             Proxy            (target state)
```

- PostgreSQL 保存任务、Attempt、Assignment、Lease、授权关系、Outbox 和可恢复状态，是业务事实源。
- NATS JetStream 承载持久事件。Gateway 持久化命令并按序号、ACK 游标处理断线恢复。
- Worker 静态部署并主动注册。控制面不接触 Docker Socket，不持有创建容器的权限。
- 固定 Worker 的 Gateway 会话由独立 `worker-enrollment` 服务签发：调用者须通过 OIDC issuer、签名、audience 验证，并持有 `worker:session:manage` 权限；调用 scope 必须与 Agent 注册元数据中的 tenant/project/team 完全一致。明文随机 token 只返回一次，数据库仅保存 SHA-256，TTL 限制在 1 小时至 30 天。
- Enrollment 数据库帐号只拥有读取 `agents`、读取/插入/更新 `agent_sessions` 的权限；Compose 为每个 Worker 槽位单独加载 `0600` 凭据文件。v0.1.5 Gateway 在握手时校验 session，撤销不主动断开现有连接。
- Matrix 是可选的人机协作适配器；不能作为调度总线或任务状态库。
- Artifact 正文存储在隔离的对象存储命名空间，通过租户授权后的短期 URL 访问。

## 3. 固定容量与调度

容量规划给出 Worker 实例数和执行槽位数，部署配置据此启动固定池。每个 Worker Agent 绑定单一 Tenant/Project/Team；共享基础设施不允许同一 Worker 进程跨租户执行。扩容由运维修改版本化配置并执行受控滚动；任务高峰超出容量时按租户配额公平排队或限流。

首期 `maxConcurrency=1`。Worker 的在线状态不代表拥有任务；只有有效 Assignment/Attempt Lease 才能执行。注册、心跳、drain、取消、租约过期与 ACK 恢复沿用现有协议，具体接口差异需在 Compose POC 前确认。

容量输入至少包括任务到达率、执行时长分布、目标排队延迟、模型限流、租户并发配额、冗余系数和故障时可接受降级。Worker 数由压测校准，不以未经测量的固定公式承诺吞吐。

## 4. 多租户数据与会话隔离

所有执行携带服务端签发且不可变的 `ExecutionContext`，至少包含 `organizationId`、`tenantId`、`projectId`、`teamId`、`taskId`、`attemptId`、主体/授权信息和信任等级。Worker 不接受客户端提供的 scope 作为授权依据。

安全栅栏覆盖：

1. **数据库：** 授权后 scope 查询；跨租户资源 ID、伪造 scope 和越权更新均拒绝。高敏部署可启用 PostgreSQL RLS 作为纵深防御。
2. **会话与记忆：** 会话主键和记忆分区包含租户/项目 scope；Worker 不保存可跨任务复用的对话内存。需要延续的对话由服务端校验后组装。
3. **执行与文件：** Attempt 独立 workspace、临时目录、缓存键和工具客户端；清理与状态终结绑定。共享池首期单并发；高敏租户使用专属池/主机。
4. **制品：** 对象 key 带不可变租户 scope；下载时重新授权，禁止仅凭猜测对象 ID 读取。
5. **配额与可见性：** 租户级并发、速率和模型预算；日志、Trace、审计事件携带租户标识并对凭据和跨租户内容脱敏。

数据库 RLS 是否启用及事务连接池的 scope 注入方式必须在第一期实现前定案；应用层 scope 过滤是强制要求，RLS 作为建议的第二道防线。

## 5. 凭证流

Enrollment 只解决 Worker 到 Gateway 的身份凭据，不是模型/MCP 上游凭证代理。短期兼容模式允许受信任 Worker 从容器 Secret 文件读取上游密钥，但不能称为零信任凭证隔离。目标模式由凭证代理验证短期消费令牌，检查租户、项目、Attempt、模型/工具 allowlist、过期和撤销状态，再由代理解析上游密钥并转发。模型与 MCP 分别定义适配协议；代理不可用时 fail-closed，日志/事件/Trace 不记录密钥。

固定 Worker 并发为 1 时，模型代理凭据只标识 Agent 和其租户 scope；每次上游调用前，代理从 PostgreSQL 解析该 Agent 唯一的活动 Assignment/Attempt/Lease 和 Task `resource_scopes`。客户端提供的 Tenant/Task/Attempt 字段不参与授权。无活动执行、多条活动执行、租约过期或 scope 不一致均拒绝。当前执行上下文实现见 [ADR 0002](decisions/0002-attempt-bound-model-proxy.md)；它尚未接入数据库查询和上游转发。

Secret Manager 负责密钥存储和轮换；数据库及消息只存引用、版本、策略与审计元数据。Higress 可作为模型代理候选，MCP 需单独评估 MCP Connector/Gateway，不能假设普通 AI Gateway 自动具备完整工具授权能力。

## 6. 安全边界与部署

Compose/systemd 管理固定进程，不把宿主机容器运行时 API 暴露给控制面。默认任务档仅允许受信任工具和受限执行。需要 `ISOLATED`/`HARDENED` 的任务必须由显式的 gVisor、Kata、MicroVM 等 Provider 承载；本机无该 Provider 时明确拒绝。

PostgreSQL、NATS、对象存储与 Matrix（启用时）都要有持久化、健康检查、备份和恢复策略。基础运行不依赖 Kubernetes API、ServiceAccount、CRD 或 Operator；Kubernetes 仅作为可选 Sandbox/企业交付实现。

## 7. 兼容与共享代码边界

新架构不另建 Task/Attempt/RBAC/Outbox 状态机，不双写事实源。外部边界使用版本化 protobuf/gRPC 与稳定公共 API。共享核心 Maven 制品 `0.1.5` 已发布并通过独立远端消费者验证。Control Plane、Gateway、Worker 的 OCI 镜像来自 `agentteams-java v0.1.5`，均按 digest 固定在 `deploy/compose/.env.example`，源 commit 为 `0156f2c1fecdc868cb4a0f4f13fb068bb4162834`；发布清单见 [v0.1.5 release](https://github.com/ly416123/agentteams-java/releases/tag/v0.1.5)。

Java 21 继续承载 Control Plane、Gateway、Scheduler、协议和 AgentScope Worker。Python 仅用于确实需要 Python SDK 的独立 Agent/评测/批处理；其他语言需先有可测量的性能或隔离需求。

## 8. 首期验收

- 在没有 kubeconfig、ServiceAccount 和 Kubernetes API 连通性的环境完成从注册固定 Worker 到提交任务、读取结果的流程。
- Worker 数量保持预配置值；容量不足只排队或限流，不创建新 Worker。
- 同一 Worker 连续执行不同租户任务、并发处理独立租户任务时，会话、文件、工具状态、凭据缓存与制品访问无串扰。
- Task、Outbox、Gateway 命令/ACK 游标在进程重启后可恢复；关键状态不依赖内存或本地 JSON。
- 过期/越权的租户 scope 与凭证消费令牌拒绝访问；明文上游密钥不进入 Worker 命令、持久事件、日志或 Trace（凭证代理目标档）。
