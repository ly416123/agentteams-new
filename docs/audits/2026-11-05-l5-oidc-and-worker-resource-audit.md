# L5 OIDC 与 Worker 凭据资源核验

**核验日期：** 2026-11-05  
**核验方式：** 使用本机 `readonly-pod-logs` Kubernetes Context 做只读检查，并读取公开 OIDC Discovery/JWKS 元数据。未读取 Kubernetes Secret 正文、Token、私钥或 JWKS 公钥材料。

## 结论

L5 已有可响应的 Keycloak OIDC Realm，可作为新项目联调候选；但当前 Issuer 是 HTTP，不能直接用于 `agentteams-new` 安全 Compose 配置要求的可信 HTTPS OIDC。L5 也有正在运行的旧版 AgentTeams Worker，但没有发现可直接交给新 Credential Proxy 使用的通用模型 API Key 文件。

L5 中看到的 Keycloak、Worker、Gateway、PostgreSQL、NATS 和 MinIO 属于现有 `agentteams-java` 工作负载。没有发现专供 `agentteams-new` 的部署资源，因此这些旧工作负载只提供可复用的集成环境线索，不代表新项目已部署或通过验收。

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

发现的文件是旧版 `chatgpt-plan` 运行时使用的 OAuth access token 文件，不等同于已确认可供新 Credential Proxy 使用的 Provider API Key。不要将其挂载到新 Credential Proxy，也不要复制旧 Secret。当前 L5 检查没有识别出适用于新代理的通用 Provider 凭据文件；真实上游模型请求仍需独立、明确的凭据接入方案。

## 权限边界与核验限制

当前 Context 可以列出 Namespace 中的 Pod、读取 Pod 元数据和日志；RBAC 不允许列出 Deployment、Service、ConfigMap、Secret。本次没有读取任何 Secret 内容。因而无法确认实时 Control Plane 的 Audience/Claim 配置。Discovery/JWKS 是公开端点元数据，不代表已完成 OIDC 登录、Claim 映射、签名验收或多租户拒绝用例。

## 对 `agentteams-new` 计划的影响

1. 可将 L5 `agentteams` Realm 作为 OIDC 集成测试候选，但先解决 HTTPS Issuer 与安全档配置的兼容问题。
2. 在取得受控测试主体前，只能完成 Discovery/JWKS 和配置契约核对；不能声称完成真实 OIDC 登录或多租户 Worker 验收。
3. 模型代理需要专用、最小权限的 Provider 凭据或受信任代理接入。旧 Worker 的 ChatGPT Plan OAuth Secret 不作为替代品。
4. 凭据值不应通过聊天、命令行参数、日志、仓库或 Compose `.env` 传递；仅记录文件路径与 Secret 引用名。
