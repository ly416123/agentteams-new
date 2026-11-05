# 固定容量 Compose 部署

本目录提供不依赖 Kubernetes API、CRD、Operator 或 Docker Socket 的单机/单节点部署入口。Compose 管理 PostgreSQL、NATS JetStream、Control Plane、Gateway 和最多 8 个预先定义的 Worker 槽位。Worker 数量通过启动脚本显式选择，不会随任务动态创建或扩缩。

> 当前是 POC 部署档，不代表生产就绪。Worker Enrollment 已实现 OIDC scope 授权、哈希存储会话凭据和每槽位投放；真实 IdP 下的端到端任务、多租户负向隔离、上游凭证代理和 Worker drain 恢复仍待验收。

## 前置条件

- Docker Engine 与 Docker Compose v2。
- Docker daemon 可访问 Docker Hub 与 GHCR。终端里的 `HTTP_PROXY` 不一定会传给 daemon；需要代理时，也要在 Docker Desktop/Engine 侧配置 registry 出站代理。
- 当前固定的 `agentteams-java v0.1.5` 应用镜像仅提供 `linux/amd64`；ARM64 开发机需启用 amd64 仿真，生产主机按 amd64 部署。
- 已发布的 Control Plane、Gateway、Worker GHCR 镜像 digest。`.env.example` 当前固定到 `agentteams-java v0.1.5`（source commit `0156f2c1fecdc868cb4a0f4f13fb068bb4162834`）。不要使用 `latest` 或可变 tag。
- 每个活动 Worker 槽位各自的模型 token 文件。token 只以只读 Compose secret 文件挂入对应 Worker，不写进 `.env`；不同槽位不能配置到同一个文件。

```sh
cd deploy/compose
cp .env.example .env
```

编辑 `.env`：填入强随机 `POSTGRES_PASSWORD`。镜像已固定到同一发布 tag 和 source commit，升级时从签名的 release manifest 获取新 digest 后一起更新。`POST /api/v1/agents` 只创建 Worker 记录并返回 UUID，不会签发 Gateway 会话 Token。分别设置 `WORKER_1_MODEL_TOKEN_FILE` 至 `WORKER_8_MODEL_TOKEN_FILE`，指向每个槽位独立的本机模型 token 文件；相对路径相对于本目录。使用 ChatGPT Plan Worker 时，文件应为现有 Worker 运行时支持的 access-token 格式，权限设为 `0600`。只需为活动槽位创建文件。

要启用 Artifact 对象存储，将 `AGENTTEAMS_STORAGE_ENABLED=true`，并设置 `OBJECT_STORE_ACCESS_KEY` 与至少 24 字符的 `OBJECT_STORE_SECRET_KEY`。启动脚本会先启动 `storage` profile 中的 SeaweedFS S3，并等待 S3 服务就绪后再启动 Control Plane；`agentteams` bucket 在 SeaweedFS 启动时创建，参数采用 [SeaweedFS `weed mini` S3 配置](https://github-wiki-see.page/m/seaweedfs/seaweedfs/wiki/Quick-Start-with-weed-mini)。S3 和管理端口都不发布到宿主机。当前 POC 使用单节点 SeaweedFS，并把全局 S3 凭证提供给 Control Plane；生产环境应改用具备 bucket 范围授权、备份和恢复能力的对象存储身份/服务。

Worker 池启用前，配置真实 OIDC issuer、JWKS URL、audience，并设置唯一的 `WORKER_ENROLLMENT_DB_PASSWORD`（至少 24 字符）；将 `API_AUTH_ENABLED=true`。这个开关同时开启 Control Plane API OIDC 安全和 Enrollment OIDC 验证。Enrollment 数据库角色由一次性 setup 容器创建，只能读取 `agents` 并读写 `agent_sessions`。不要手工向数据库写 Token。Control Plane 和 Enrollment HTTP 端口只绑定到 `127.0.0.1`，不可改为公网或局域网监听；如需远端访问，应放在完成 TLS 和身份校验的可信入口后。

Enrollment 数据库使用独立的 `worker_enrollment` 登录角色；setup 会启用 `agents`、`agent_sessions` 和 scope 索引的 PostgreSQL RLS，并授予 Enrollment 所需的列级权限。`worker_enrollment` 不是表所有者、超级用户或 `BYPASSRLS`。专用 `worker_enrollment_scope_owner` 角色仅拥有 scope 索引和同步触发函数，不能登录，也没有绕过 RLS 的权限；Control Plane 写入 Agent 时由该函数同步 scope，Enrollment 不能读取完整 `agents.metadata`，其他应用角色不能直接修改 scope 索引。Enrollment 在签发/撤销事务中设置 JWT 已验证的 tenant/project/team scope；无 scope 时策略不返回 Agent，跨 scope Session 写入会被拒绝。当前 RLS 只覆盖 Enrollment 所需的 Agent、Session 和 scope 索引，不代表 Task、Attempt、Artifact 等资源已完成全系统隔离。

Enrollment 端口默认为 `127.0.0.1:8090`；若 `.env` 中覆盖 `ENROLLMENT_PORT`，该值仅影响宿主机端口映射，绑定地址仍固定为 loopback。`enroll-worker.sh` 需要 `curl`、`jq` 和由 OIDC Provider 签发且 scope/audience 匹配的临时 access token 文件。

使用私有 GHCR 镜像时，先在本机执行 `docker login ghcr.io`。不要把 PAT 写入 Compose 文件或 `.env`。

## 校验与启动

不启动 Worker 的基础服务：

```sh
./validate-config.sh .env
docker compose --env-file .env config --quiet
docker compose --env-file .env up -d postgres nats nats-bootstrap control-plane gateway
docker compose --env-file .env ps
```

Control Plane 仅绑定到 `127.0.0.1:${CONTROL_PLANE_PORT}`；PostgreSQL、NATS 和 Gateway gRPC 端口不发布到宿主机。PostgreSQL/NATS 位于无外连的 `data` 网络；Worker 仅加入 `worker-egress` 网络，与同时连接两网的 Gateway 通信，不能直接连接数据库或 NATS。Worker 的 bridge 网络允许访问模型服务；主机防火墙/出站代理策略需按环境限制 Worker 外部访问。NATS 第一次启动时创建 TASK_EVENTS、AGENT_EVENTS、CONTROL_EVENTS、DEADLETTER_EVENTS 四个持久 stream；数据库由 Control Plane Flyway 迁移。

启用对象存储时，使用启动脚本保证 S3 端点就绪后再启动应用：

```sh
./start-workers.sh
```

## 固定 Worker 槽位

`WORKER_REPLICAS` 取值为 0 到 8。每个槽位有固定且唯一的 `AGENTTEAMS_AGENT_ID`；不要使用 `docker compose --scale`，它会复制同一个槽位身份。启动脚本只选择前 N 个槽位，不会一并拉起全部 Worker。

先启动基础服务并注册 Agent。首次签发凭据前，单独启动启用了 OIDC 的 Control Plane 和 Enrollment（该命令不会启动 Worker；Enrollment DB setup 会在 schema 就绪后创建受限角色并应用 RLS）：

```sh
docker compose --env-file .env -f compose.yaml --profile secure-api up -d control-plane worker-enrollment
docker compose --env-file .env -f compose.yaml ps
```

Agent scope 必须在 `metadata.scope` 中包含 enrollment 配置所使用的 `tenant`、`project`、`team` 值。申请 OIDC access token 的主体需带 `worker:session:manage` 权限。为每个要启用的槽位单独签发 token（bearer token 从安全的临时文件读取，不要放进 shell history）：

```sh
./enroll-worker.sh http://127.0.0.1:8090 <agent-uuid> /secure/path/oidc-access-token ./secrets/worker-1.runtime.env
```

脚本只把 Gateway token 写入槽位文件，不会打印；明文 token 仅在 Enrollment 响应和本机受限文件短暂出现，数据库存哈希。设置 `.env` 中对应的 `WORKER_1_CREDENTIALS_FILE` 路径（相对路径以本目录为准）、`WORKER_REPLICAS=1`，再启动：

```sh
./start-workers.sh
```
```

命令中显式列出槽位，避免 Compose profile 一次启动全部 8 个 Worker。Worker 并发固定为 1。增加容量需要修改版本化部署配置、执行受控变更并确认注册状态；任务高峰超过容量时由系统排队/限流，不创建 Worker。

启动脚本会校验每个活动槽位的 Agent UUID、独立凭据文件和文件权限，并自动开启 `secure-api` 与 `worker-pool` profile。不要复用槽位凭据文件。

轮换时先签发新 token 到当前槽位文件，重启对应 Worker 使其重新握手，再撤销旧 session：

```sh
./revoke-worker-session.sh http://127.0.0.1:8090 <agent-uuid> <old-session-uuid> /secure/path/oidc-access-token
```

撤销只阻止后续 Gateway 握手；v0.1.5 不会主动断开已建立的连接。Worker 还会通过各自的模型 token 文件调用模型服务，这与 Gateway session 是两类凭据。每个活动槽位必须使用不同文件；配置校验会拒绝指向同一个底层文件的槽位。当前模型 Token 仍可被对应 Worker 进程读取，因此这项隔离不能替代后续凭证代理，也不提供 Attempt 级授权。

## 状态与运维

- PostgreSQL 与 NATS JetStream 数据存放于命名卷 `postgres-data`、`nats-data`。
- 备份前暂停写入或使用一致性备份流程。生产部署需另外落实异机备份、恢复演练、升级/回滚和镜像签名校验。
- `docker compose down` 保留命名卷；`docker compose down -v` 会删除 PostgreSQL/NATS 数据，请谨慎操作。
- `docker compose logs -f control-plane gateway` 查看应用日志。不得将凭证或租户敏感内容写入日志。
- SeaweedFS 4.52 通过固定 digest 的可选 `storage` profile 提供单节点 S3 兼容存储；Matrix 适配器尚未加入 Compose。

## 当前限制

- Worker 镜像仍沿用旧仓库发布制品；本仓库没有复制业务状态机。
- 此部署拓扑尚未证明跨租户会话、workspace、工具状态和 Artifact 的强隔离。多租户上线前必须完成 `docs/plans/2026-11-04-agentteams-new-bootstrap.md` 的批次 2 验收。
- 本机容器网络隔离不能替代任务级 sandbox。对不可信代码/工具执行应拒绝或接入明确的 gVisor/Kata/MicroVM Provider。
- Compose 不提供高可用控制面、多主机调度、自动扩缩容或 Kubernetes 等价 Sandbox。
