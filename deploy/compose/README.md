# 固定容量 Compose 部署

本目录提供不依赖 Kubernetes API、CRD、Operator 或 Docker Socket 的单机/单节点部署入口。Compose 管理 PostgreSQL、NATS JetStream、Control Plane、Gateway 和最多 8 个预先定义的 Worker 槽位。Worker 数量通过启动脚本显式选择，不会随任务动态创建或扩缩。

> 当前是 POC 部署档，不代表生产就绪。Worker Enrollment 已实现 OIDC scope 授权和哈希凭据；Credential Proxy 已加入固定 Worker 启动链，真实 IdP 下的端到端任务、多租户负向隔离、真实 Provider 联调和 Worker drain 恢复仍待验收。

## 前置条件

- Docker Engine 与 Docker Compose v2。
- Docker daemon 可访问 Docker Hub 与 GHCR。终端里的 `HTTP_PROXY` 不一定会传给 daemon；需要代理时，也要在 Docker Desktop/Engine 侧配置 registry 出站代理。
- 当前固定的 `agentteams-java v0.1.5` 应用镜像仅提供 `linux/amd64`；ARM64 开发机需启用 amd64 仿真，生产主机按 amd64 部署。
- 已发布的 Control Plane、Gateway、Worker GHCR 镜像 digest。`.env.example` 当前固定到 `agentteams-java v0.1.5`（source commit `0156f2c1fecdc868cb4a0f4f13fb068bb4162834`）。不要使用 `latest` 或可变 tag。
- 可信 HTTPS OIDC Provider、每个活动 Worker 的代理 Token 文件，以及按 Tenant/Project 组织的上游 Provider 凭据文件。Worker 仅接收独立的 `atp_` 代理 Token；上游凭据目录只读挂入 Credential Proxy。

```sh
cd deploy/compose
cp .env.example .env
```

编辑 `.env`：填入随机且互不相同的 `POSTGRES_PASSWORD`、`CONTROL_PLANE_DB_PASSWORD`、`WORKER_ENROLLMENT_DB_PASSWORD` 和 `CREDENTIAL_PROXY_DB_PASSWORD`（各至少 24 字符）。PostgreSQL 初始化用户 `agentteams` 只供 Flyway 迁移和数据库角色初始化使用；Control Plane SQL 使用非超级用户 `agentteams_runtime`。将 `CREDENTIAL_PROXY_UID`、`CREDENTIAL_PROXY_GID` 设为运行部署用户的数字 UID/GID；Compose 中 Worker 和 Credential Proxy 都以此身份运行，以便它们只读各自挂载的 `0600` 文件。镜像已固定到发布 digest，升级时从签名 release manifest 获取新 digest 后一起更新。`POST /api/v1/agents` 只创建 Worker 记录，不会签发会话 Token。

创建 `PROVIDER_CREDENTIALS_DIR` 指定的目录，并按 `file:<tenant>/<project>/<team>/<credential-name>` 的引用组织凭据。例如 `.env` 中 `WORKER_1_MODEL_CREDENTIAL_REF=file:tenant-a/project-a/team-a/chatgpt` 对应文件 `secrets/provider/tenant-a/project-a/team-a/chatgpt`。引用中的 Tenant、Project、Team 必须与数据库活动 Attempt 的 scope 完全一致。目录和所有子目录权限设为 `0700`，凭据文件设为 `0600`，所有者 UID 与 `CREDENTIAL_PROXY_UID` 一致。Credential Proxy 只读挂载此目录；Worker 网络为 internal 网络，只能访问 Gateway 和 Proxy，Provider 出站只由 Proxy 所在网络提供。每个活动 Worker 还需要各自的 `WORKER_n_MODEL_PROXY_TOKEN_FILE`，由 Enrollment 命令生成；这些 `atp_` 文件也须为 `0600` 且由同一 UID 拥有。只需为活动槽位准备模型代理 Token 和其引用的 Provider 凭据文件。

要启用 Artifact 对象存储，将 `AGENTTEAMS_STORAGE_ENABLED=true`，并设置 `OBJECT_STORE_ACCESS_KEY` 与至少 24 字符的 `OBJECT_STORE_SECRET_KEY`。启动脚本会先启动 `storage` profile 中的 SeaweedFS S3，并等待 S3 服务就绪后再启动 Control Plane；`agentteams` bucket 在 SeaweedFS 启动时创建，参数采用 [SeaweedFS `weed mini` S3 配置](https://github-wiki-see.page/m/seaweedfs/seaweedfs/wiki/Quick-Start-with-weed-mini)。S3 和管理端口都不发布到宿主机。当前 POC 使用单节点 SeaweedFS，并把全局 S3 凭证提供给 Control Plane；生产环境应改用具备 bucket 范围授权、备份和恢复能力的对象存储身份/服务。

Worker 池启用前，配置真实 HTTPS OIDC issuer、JWKS URL、audience，并设置唯一的 `WORKER_ENROLLMENT_DB_PASSWORD` 和 `CREDENTIAL_PROXY_DB_PASSWORD`（各至少 24 字符）；将 `API_AUTH_ENABLED=true`。这个开关同时开启 Control Plane API OIDC 安全和 Enrollment OIDC 验证；Enrollment 服务自身也拒绝远程 HTTP issuer/JWKS，只有 loopback 地址允许 HTTP 用于本地测试/开发，Compose 校验仍要求所有启用的 OIDC 地址使用 HTTPS。不要手工向数据库写 Token。Control Plane 和 Enrollment HTTP 端口只绑定到 `127.0.0.1`，不可改为公网或局域网监听；如需远端访问，应放在完成 TLS 和身份校验的可信入口后。

Control Plane、Enrollment 和 Credential Proxy 使用分离的数据库登录角色。`database-role-setup` 在启动 Control Plane 前创建非超级用户 `agentteams_runtime`，授予现有及 Flyway 后续创建对象所需权限；Control Plane 运行 SQL 使用该角色，Flyway 单独使用初始化账户。Enrollment 与 Credential Proxy setup 再为各自角色启用窄列授权、RLS 和 `SECURITY DEFINER` 查询函数；Credential Proxy 运行角色只能执行活动 Attempt/模型 Token 查询函数，不能直接读取 Token 哈希或业务表。为兼容 Control Plane 现有应用层授权，RLS 对 `agentteams_runtime` 在 `agents`、`agent_sessions` 上仍允许全表操作；此策略不提供租户级数据库隔离。该角色目前仍可访问其 schema 中的业务表，且 Flyway 凭据与应用进程同处一个容器环境，因此本次改动仅消除日常 SQL 的 PostgreSQL 超级用户权限，不代表业务表已完成 RLS 或迁移凭据已隔离。

Enrollment 端口默认为 `127.0.0.1:8090`；若 `.env` 中覆盖 `ENROLLMENT_PORT`，该值仅影响宿主机端口映射，绑定地址仍固定为 loopback。`enroll-worker.sh` 需要 `curl`、`jq` 和由 OIDC Provider 签发且 scope/audience 匹配的临时 access token 文件。Credential Proxy 默认只转发到 `https://api.openai.com/v1/responses`，只允许 `.env` 的模型 allowlist；自定义上游地址应使用 HTTPS，HTTP 仅允许 loopback 测试地址。

使用私有 GHCR 镜像时，先在本机执行 `docker login ghcr.io`。不要把 PAT 写入 Compose 文件或 `.env`。

## 校验与启动

不启动 Worker 的基础服务（依赖会自动运行 `database-role-setup`）：

```sh
./validate-config.sh .env
docker compose --env-file .env config --quiet
docker compose --env-file .env up -d postgres nats nats-bootstrap control-plane gateway
docker compose --env-file .env ps
```

Control Plane 仅绑定到 `127.0.0.1:${CONTROL_PLANE_PORT}`；PostgreSQL、NATS 和 Gateway gRPC 端口不发布到宿主机。PostgreSQL/NATS 位于无外连的 `data` 网络；Worker 仅加入 internal 的 `worker-egress` 网络，与同时连接两网的 Gateway 和 Credential Proxy 通信，不能直接连接数据库、NATS 或公网模型服务。Credential Proxy 单独连接 `provider-egress` bridge 网络，Host 防火墙/出站代理应按上游地址限制该出口。NATS 第一次启动时创建四个持久 stream；数据库由 Control Plane Flyway 迁移，随后 setup 安装 Enrollment 与 Credential Proxy 的受限角色和查询函数。

启用对象存储时，使用启动脚本保证 S3 端点就绪后再启动应用：

```sh
./start-workers.sh
```

## 固定 Worker 槽位

`WORKER_REPLICAS` 取值为 0 到 8。每个槽位有固定且唯一的 `AGENTTEAMS_AGENT_ID`；不要使用 `docker compose --scale`，它会复制同一个槽位身份。启动脚本只选择前 N 个槽位，不会一并拉起全部 Worker。

### 估算固定容量

先从生产或 POC 遥测中统计任务到达率、平均执行时长，并为排队等待 p95 和 Worker 利用率设定目标。运行仓库自带的 Erlang C（M/M/c）估算器：

```sh
python3 ../../verification/fixed_capacity_estimator.py \
  --arrival-per-minute 6 \
  --mean-duration-seconds 30 \
  --queue-p95-seconds 5 \
  --max-utilization 0.70 \
  --max-workers 8
```

示例输入会建议 7 个固定 Worker 槽位。该结果是假设到达服从泊松分布、执行时长服从指数分布、单个槽位并发为 1 的初始估算；它不替代压测校准。建议分别用峰值时段数据计算共享池总量，并对必须专属运行的租户单独计算，再按租户配额检查公平性。达到 8 槽仍无法满足排队或利用率目标时，工具明确返回容量不足；应调整容量目标/配置或限流排队，不会动态创建 Worker。

先启动基础服务并注册 Agent。首次签发凭据前，单独启动启用了 OIDC 的 Control Plane 和 Enrollment（该命令不会启动 Worker；Enrollment DB setup 会在 schema 就绪后创建受限角色并应用 RLS）：

```sh
docker compose --env-file .env -f compose.yaml --profile secure-api up -d control-plane worker-enrollment
docker compose --env-file .env -f compose.yaml ps
```

Agent scope 必须在 `metadata.scope` 中包含 enrollment 配置所使用的 `tenant`、`project`、`team` 值。申请 OIDC access token 的主体需带 `worker:session:manage` 权限。为每个要启用的槽位单独签发 token（bearer token 从安全的临时文件读取，不要放进 shell history）：

```sh
./enroll-worker.sh http://127.0.0.1:8090 <agent-uuid> /secure/path/oidc-access-token ./secrets/worker-1.runtime.env
```

基础用法只把 Gateway token 写入槽位文件；明文仅在 Enrollment 响应和本机受限文件短暂出现，数据库存哈希。设置 `.env` 中对应的 `WORKER_1_CREDENTIALS_FILE` 路径（相对路径以本目录为准）、`WORKER_REPLICAS=1`，再启动：

Enrollment 也会为模型代理创建独立短期凭据。附加 `ttl-seconds`、Tenant/Project 下的非敏感 `model-credential-ref` 和代理 Token 文件路径：

```sh
./enroll-worker.sh http://127.0.0.1:8090 <agent-uuid> /secure/path/oidc-access-token \
  ./secrets/worker-1.runtime.env 604800 file:tenant-a/project-a/team-a/chatgpt ./secrets/worker-1.model-proxy-token
```

Gateway Token 和模型代理 Token 互不通用；数据库只存两者的 SHA-256，模型代理凭据绑定 Gateway session、Agent scope 和模型凭据引用，撤销 session 会一并撤销模型代理凭据。输出文件均设置为 `0600`。Worker 将 Responses API 请求发往内部 Credential Proxy；Proxy 逐请求校验唯一活动 Assignment/Attempt/Lease，并要求凭据引用中的 Tenant/Project 与数据库执行上下文一致，再读取 Provider Secret、替换 Authorization 并流式转发。客户端不能通过请求体或 header 选择租户/Attempt；非成功上游错误正文不会回传给 Worker。

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

撤销会阻止后续 Gateway 握手，并同步撤销关联的模型代理 Token；v0.1.5 不会主动断开已建立的 Gateway 连接。每个活动槽位必须使用不同的代理 Token 文件；多个同 Tenant/Project 的 Worker 可引用同一个受 scope 保护的 Provider 凭据。

## 状态与运维

- PostgreSQL 与 NATS JetStream 数据存放于命名卷 `postgres-data`、`nats-data`。
- 备份前先暂停新任务提交，并确认没有运行中 Attempt；随后停止全部任务生产者/消费者，保持整个备份窗口没有 Task 或 Artifact 写入。当前 POC 没有专用 Worker drain 命令，需由操作者确认 Attempt 已排空。`backup-state.sh` 生成 PostgreSQL custom dump、4 个 JetStream 持久 Stream 快照；启用对象存储时，还会停止 SeaweedFS 并归档 `seaweedfs-data` 卷。备份目录及文件权限收紧为 `0700/0600`，包含敏感租户数据；转移到异机后仍需加密和限制访问。

```sh
docker compose --env-file .env --profile secure-api --profile worker-pool stop \
  worker-1 worker-2 worker-3 worker-4 worker-5 worker-6 worker-7 worker-8 \
  gateway credential-proxy worker-enrollment control-plane
./backup-state.sh .env /secure/backup/agentteams-2026-11-05
./start-workers.sh .env
```

恢复只允许写入全新的 Compose 项目及空命名卷。脚本会校验 bundle SHA-256，恢复 PostgreSQL、NATS Stream 和可选对象存储；失败时删除本次创建的隔离项目卷。先验证恢复出的数据库/Stream/对象，再用同一个项目名启动应用：

```sh
./restore-state.sh .env /secure/backup/agentteams-2026-11-05 agentteams-restore-2026-11-05
COMPOSE_PROJECT_NAME=agentteams-restore-2026-11-05 ./start-workers.sh .env
```

还需从独立 Secret Manager/安全介质恢复 OIDC、数据库和 Provider 凭据文件；这些机密不会打入备份 bundle。恢复目标未完成验证前，不要启动 Worker，也不要将流量切到目标项目。生产部署仍需异机备份、定期恢复演练、升级/回滚和镜像签名校验。
- `docker compose down` 保留命名卷；`docker compose down -v` 会删除 PostgreSQL/NATS 数据，请谨慎操作。
- `docker compose logs -f control-plane gateway` 查看应用日志。不得将凭证或租户敏感内容写入日志。
- SeaweedFS 4.52 通过固定 digest 的可选 `storage` profile 提供单节点 S3 兼容存储；Matrix 适配器尚未加入 Compose。

## 当前限制

- Worker 镜像仍沿用旧仓库发布制品；本仓库没有复制业务状态机。
- 此部署拓扑尚未证明跨租户会话、workspace、工具状态和 Artifact 的强隔离。多租户上线前必须完成 `docs/plans/2026-11-04-agentteams-new-bootstrap.md` 的批次 2 验收。
- 本机容器网络隔离不能替代任务级 sandbox。对不可信代码/工具执行应拒绝或接入明确的 gVisor/Kata/MicroVM Provider。
- Compose 不提供高可用控制面、多主机调度、自动扩缩容或 Kubernetes 等价 Sandbox。
