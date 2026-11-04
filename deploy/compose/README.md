# 固定容量 Compose 部署

本目录提供不依赖 Kubernetes API、CRD、Operator 或 Docker Socket 的单机/单节点部署入口。Compose 管理 PostgreSQL、NATS JetStream、Control Plane、Gateway 和最多 8 个预先定义的 Worker 槽位。Worker 数量通过启动脚本显式选择，不会随任务动态创建或扩缩。

> 当前是 POC 部署档，不代表生产就绪。多租户会话/制品隔离、凭证代理、Worker drain 恢复和完整端到端验收仍按项目计划逐批交付。

## 前置条件

- Docker Engine 与 Docker Compose v2。
- Docker daemon 可访问 Docker Hub 与 GHCR。终端里的 `HTTP_PROXY` 不一定会传给 daemon；需要代理时，也要在 Docker Desktop/Engine 侧配置 registry 出站代理。
- 当前固定的 `agentteams-java v0.1.4` 应用镜像仅提供 `linux/amd64`；ARM64 开发机需启用 amd64 仿真，生产主机按 amd64 部署。
- 已发布的 Control Plane、Gateway、Worker GHCR 镜像 digest。`.env.example` 当前固定到 `agentteams-java v0.1.4`（source commit `02d5ad3017f56b2a3cbb6c60112077d32f3299f4`）。不要使用 `latest` 或可变 tag。
- Worker 模型调用所需的 token 文件。token 只以只读 Compose secret 文件挂入 Worker，不写进 `.env`。

```sh
cd deploy/compose
cp .env.example .env
```

编辑 `.env`：填入强随机 `POSTGRES_PASSWORD`。镜像已固定到同一发布 tag 和 source commit，升级时从签名的 release manifest 获取新 digest 后一起更新。将 `WORKER_n_AGENT_ID` 改为 Control Plane 注册接口为每个 Worker 返回的 UUID，保证唯一且重启稳定。设置 `MODEL_TOKEN_FILE` 为本机模型 token 文件路径；相对路径相对于本目录。使用 ChatGPT Plan Worker 时，该文件应为现有 Worker 运行时支持的 access-token 格式。

要启用 Artifact 对象存储，将 `AGENTTEAMS_STORAGE_ENABLED=true`，并设置 `OBJECT_STORE_ACCESS_KEY` 与至少 24 字符的 `OBJECT_STORE_SECRET_KEY`。启动脚本会先启动 `storage` profile 中的 SeaweedFS S3，并等待 S3 服务就绪后再启动 Control Plane；`agentteams` bucket 在 SeaweedFS 启动时创建，参数采用 [SeaweedFS `weed mini` S3 配置](https://github-wiki-see.page/m/seaweedfs/seaweedfs/wiki/Quick-Start-with-weed-mini)。S3 和管理端口都不发布到宿主机。当前 POC 使用单节点 SeaweedFS，并把全局 S3 凭证提供给 Control Plane；生产环境应改用具备 bucket 范围授权、备份和恢复能力的对象存储身份/服务。

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

```sh
在 `.env` 中设置 `WORKER_REPLICAS=2`，并确认前两个 Agent UUID 已在 Control Plane 注册：

```sh
./start-workers.sh
```
```

命令中显式列出槽位，避免 Compose profile 一次启动全部 8 个 Worker。Worker 并发固定为 1。增加容量需要修改版本化部署配置、执行受控变更并确认注册状态；任务高峰超过容量时由系统排队/限流，不创建 Worker。

Worker 使用模型 token 文件。未配置有效凭证时不要启动 Worker；空占位文件只用于让无 Worker 的基础服务配置校验通过。

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
