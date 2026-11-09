# L5 OIDC 与 Worker 凭据资源核验

**核验日期：** 2026-11-05  
**核验方式：** 使用本机 `readonly-pod-logs` Kubernetes Context 做只读检查，并读取公开 OIDC Discovery/JWKS 元数据。未读取 Kubernetes Secret 正文、Token、私钥或 JWKS 公钥材料。

**最新复核（2026-11-06）：** 使用 `kubectl --context=readonly-pod-logs` 再次检查 `agentteams` namespace。`get pods` 可见旧版 `agentteams-java` Control Plane、Keycloak 和 Worker 等 Pod；`auth can-i` 对 Deployment、Service、Ingress、ConfigMap、Secret 的 `list` 均返回 `no`。因此只能确认旧环境组件仍有 Pod，不能确认 HTTPS 入口、新项目资源或模型凭据文件。集群侧也无法发现本机 Compose 主机上的 Token 文件路径；本地仓库只见到空的 `deploy/compose/model-token.example` 占位文件，没有可用于真实 Worker Attempt 的凭据配置。未读取任何 Secret 或凭据文件内容。

**权限复核（2026-11-06）：** 再次检查本机 context 与 RBAC。当前 context 仍是本地 `kind-agentteams`；显式指定 `readonly-pod-logs` 后，`can-i` 确认可在 `agentteams` namespace 列出/读取 Pod 及读取 Pod 日志；对 Namespace、Deployment、Service、Ingress、ConfigMap、ServiceAccount 的 list 均返回 `no`，Secret 的 list 也返回 `no`。对 Deployment、Service、Ingress、ConfigMap 的实际 list 请求均返回 `Forbidden`。允许列出的 Job/CronJob 不是 OIDC、Worker 部署或凭据文件的充分证据。因此本次不能从 L5 实时确认是否已新增 HTTPS IdP 入口、专供 `agentteams-new` 的 Worker 资源，或运行主机上的 Provider 凭据文件。未尝试读取任何 Secret 内容或本机凭据内容。

**Pod 与公开 Discovery 复核（2026-11-06）：** 只读 Pod 列表显示 L5 当前有 `keycloak` Pod，以及 `agentteams-java` 的 Control Plane、Gateway、Manager、Operator 和 12 个 Ready 的旧 Worker Pod；PostgreSQL、NATS、MinIO 也在运行。这能确认旧版集成环境仍在，不能证明有 `agentteams-new` 专属部署。公开 OIDC Discovery 当前仍返回 `http://192.168.122.55:30082/realms/agentteams`，JWKS URI 也仍为 HTTP；这与 secure Compose 所需的 HTTPS Issuer 不兼容。由于无法列出 Ingress/Service/ConfigMap，不能判断集群是否另有 HTTPS 入口或新项目配置。旧 Worker 的 OAuth token 引用不作为新 Credential Proxy 的通用 Provider 凭据；L5 是否另有专用凭据文件仍未知。

## 结论

L5 已有可响应的 Keycloak OIDC Realm，可作为新项目联调候选；但当前 Issuer 是 HTTP，不能直接用于 `agentteams-new` 安全 Compose 配置要求的可信 HTTPS OIDC。L5 也有正在运行的旧版 AgentTeams Worker；已检查的旧 Worker 元数据只确认了 `chatgpt-plan` OAuth token 引用，没有确认可直接交给新 Credential Proxy 使用的通用模型 API Key 文件。

此前可见的 Keycloak、Worker、Gateway、PostgreSQL、NATS 和 MinIO Pod 属于现有 `agentteams-java` 工作负载。由于当前 RBAC 不能列出 Deployment、Service、Ingress 或 ConfigMap，无法据此判定 L5 是否另有专供 `agentteams-new` 的部署资源；这些可见旧工作负载只提供集成环境线索，不代表新项目已部署或通过验收。

## OIDC 发现端点

公开 Discovery 与 JWKS 端点在核验时均返回 HTTP 200：

| 项目 | 当前值 | 证据与限制 |
| --- | --- | --- |
| Realm | `agentteams` | L5 Keycloak Pod 正在运行；镜像为 `quay.io/keycloak/keycloak:25.0.6` |
| Issuer | `http://192.168.122.55:30082/realms/agentteams` | 实时读取 Discovery 的 `issuer` 字段 |
| JWKS URI（外部） | `http://192.168.122.55:30082/realms/agentteams/protocol/openid-connect/certs` | 实时读取 Discovery 的 `jwks_uri` 字段 |
| JWKS URI（集群内配置） | `http://keycloak:8080/realms/agentteams/protocol/openid-connect/certs` | 现有 `agentteams-java/deploy/helm/l5-values.yaml` 中的配置；本次 Kubernetes RBAC 不允许读取实时 Deployment/ConfigMap，不能证明线上值未漂移 |
| Audience | `agentteams-api` | 现有 L5 Helm values 中声明；当前 RBAC 不允许列出 Deployment/ConfigMap，未能回读确认 |
| Tenant / Project / Team claims | `tenant` / `project` / `team` | 现有 L5 Helm values 中声明；未能确认 Realm 当前签发的 Token 实际包含这些声明 |
| Permissions claim | `permissions` | 现有 L5 Helm values 中声明；未能确认 Realm 当前 Token 映射 |

JWKS 当前返回 2 个元数据项：`RS256/sig` 与 `RSA-OAEP/enc`。只检查算法和用途，不记录 `kid` 或密钥材料。

**协议与安全限制：** Discovery 公布的 Issuer 是 HTTP。`agentteams-new` 的 secure Compose 档要求 HTTPS，因此不能通过降低校验要求来直接接入该地址。若复用 L5 Keycloak，需要先为 Keycloak 配置稳定的 HTTPS 主机名与证书，使签发 Token 的 `iss`、Discovery 和 JWKS 地址保持一致。

## Worker 与模型凭据

核验了一个运行中的旧版 Worker Pod，仅读取环境变量名/必要的非机密元数据及 Secret 引用，不读取 Secret 值：

- 模型 Provider：`chatgpt-plan`；模型标识：`chatgpt-plan:gpt-5.6-luna`。
- `AGENTTEAMS_CHATGPT_ACCESS_TOKEN_FILE` 指向容器内路径 `/var/run/agentteams/chatgpt/token`。
- 该文件来自 Kubernetes Secret `agentteams-chatgpt-oauth-session` 的 `access-token` 键，挂载在 `/var/run/agentteams/chatgpt`。
- Worker 还引用 `agentteams-gateway/worker-token` 和 `agentteams-mcp-tools/mcp-tools-token`。这两项是 Gateway/MCP 凭据，不是通用模型 API Key。
- Namespace 中当时有 12 个运行中的旧版 Worker Pod；这些不是 `agentteams-new` 的固定 Compose Worker。

已检查的旧 Worker 引用的是 `chatgpt-plan` 运行时 OAuth access token 文件，不等同于已确认可供新 Credential Proxy 使用的 Provider API Key。不要将其挂载到新 Credential Proxy，也不要复制旧 Secret。由于没有权限枚举 Secret/配置元数据，当前无法判定 L5 是否另有适用于新代理的 Provider 凭据文件；真实上游模型请求仍需独立、明确的凭据接入方案。

## 权限边界与核验限制

当前 Context 可以列出 Namespace 中的 Pod、读取 Pod 元数据和日志；RBAC 不允许列出 Deployment、Service、ConfigMap、Secret。本次没有读取任何 Secret 内容。因而无法确认实时 Control Plane 的 Audience/Claim 配置。Discovery/JWKS 是公开端点元数据，不代表已完成 OIDC 登录、Claim 映射、签名验收或多租户拒绝用例。

2026-11-06 的权限复核进一步确认：当前可用的 L5 context 名为 `readonly-pod-logs`，仅凭它不能判断集群里有没有上述资源。若要完成只读资源确认，需要运维提供可列出 `agentteams` 命名空间 Deployment、Service、Ingress 与 ConfigMap 元数据的只读权限；Secret 数据仍无需开放。若运维确认资源尚未部署，则本项应记录为缺失，而不是继续扩大权限。

## 对 `agentteams-new` 计划的影响

1. 可将 L5 `agentteams` Realm 作为 OIDC 集成测试候选，但先解决 HTTPS Issuer 与安全档配置的兼容问题。
2. 在取得受控测试主体前，只能完成 Discovery/JWKS 和配置契约核对；不能声称完成真实 OIDC 登录或多租户 Worker 验收。
3. 模型代理需要专用、最小权限的 Provider 凭据或受信任代理接入。旧 Worker 的 ChatGPT Plan OAuth Secret 不作为替代品。
4. 凭据值不应通过聊天、命令行参数、日志、仓库或 Compose `.env` 传递；仅记录文件路径与 Secret 引用名。

## 2026-11-06 Worker Pod 归属复核

使用 `readonly-pod-logs` 只读读取 `worker-*` Pod 元数据，没有读取任何 Secret 值。当前可见 Worker 均带有 `app.kubernetes.io/managed-by=agentteams-operator`、`app.kubernetes.io/name=agentteams-worker` 与 `agentteams.io/runtime=AGENTSCOPE` 标签，镜像为旧版 `agentteams-agent-worker:l5-gpt56-20261022-amd64`。Pod 环境变量引用旧 Gateway 与 MCP Secret。由此可将这些 Pod 判定为旧 `agentteams-java`/Operator 工作负载，不能计作 `agentteams-new` Worker 验收资源，也不能将其 Gateway/MCP 凭据当作新 Credential Proxy 的模型凭据。

Keycloak Pod 使用 `quay.io/keycloak/keycloak:25.0.6`。当前 RBAC 不允许读取 Deployment、Service、Ingress、ConfigMap 或 Secret，因此专用 HTTPS 入口、Issuer Claim 映射以及是否另有 `agentteams-new` 资源仍未核实。本机搜索没有发现真实模型代理 Token 文件。L5 真实验收仍需要可信 HTTPS OIDC 配置和受控测试主体，以及专供新项目使用的模型凭据与独立 Worker Enrollment Token 文件；Secret 正文无需开放给审计账号。
