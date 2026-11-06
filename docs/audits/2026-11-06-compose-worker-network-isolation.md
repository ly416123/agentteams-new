# Compose Worker 网络边界验收

**核验日期：** 2026-11-06  
**隔离环境：** Colima Context `colima-agentteams-poc`，Compose project `agentteams-new-validation`。  
**探针：** `natsio/nats-box:0.14.0` 临时容器，仅接入 `worker-egress`；不挂载卷、不注入凭据、不访问默认 Docker Context。

## 核验结果

POC 的 Compose 网络成员关系如下：

- `worker-egress`：Gateway 与 Credential Proxy；Worker 槽位启动后只加入此网络。
- `data`：Control Plane、Gateway、Credential Proxy、PostgreSQL、NATS；该网络为 `internal: true`。
- `host-ingress`：仅 Control Plane。
- `provider-egress`：仅 Credential Proxy。

探针从 `worker-egress` 直接尝试连接 Control Plane 的 `data` 网络 IP 与 HTTP 8080 端口，连接被拒绝/不可达；随后连接 Gateway 的 `worker-egress` IP 与 gRPC 9090 端口成功。Control Plane readiness 返回 `UP`，Credential Proxy、PostgreSQL、NATS 健康。

因此，在当前运行中的 Compose 网络配置下，Worker 所在出站网络可访问 Gateway，但无法直接访问 Control Plane/数据库/NATS 所在数据网络；模型 Provider 出口仍由 Credential Proxy 单独持有。网络成员关系还由运行中的 Docker 网络容器清单回读确认。

## 证据边界

当时没有启动真实 Worker 容器，因为可用 L5 OIDC Issuer 为 HTTP，且新环境没有专用 Provider 凭据。此次使用与 Worker 相同网络的无凭据临时探针，证明的是 Docker 网络路径，不证明 Worker 镜像自身没有额外 socket、挂载或代理绕行能力，也不证明应用层 Tenant/Attempt 授权。真实 Worker 启动后仍须重复检查其实际网络成员，并用 Worker 进程验证 Gateway 成功、Control Plane/DB/NATS 不可达。

不能以网络隔离替代 Control Plane OIDC、Enrollment、Credential Proxy 的身份校验；内部接口仍须保留 token 校验和最小权限。
