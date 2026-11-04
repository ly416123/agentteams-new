# 固定容量 Compose 部署

本目录提供不依赖 Kubernetes API、CRD、Operator 或 Docker Socket 的单机/单节点部署入口。Compose 管理 PostgreSQL、NATS JetStream、Control Plane、Gateway 和最多 8 个预先定义的 Worker 槽位。Worker 数量通过启动脚本显式选择，不会随任务动态创建或扩缩。

> 当前是 POC 部署档，不代表生产就绪。多租户会话/制品隔离、凭证代理、Worker drain 恢复和完整端到端验收仍按项目计划逐批交付。

## 前置条件

- Docker Engine 与 Docker Compose v2。
- 已发布的 Control Plane、Gateway、Worker GHCR 镜像 digest。不要使用 `latest` 或可变 tag。
- Worker 模型调用所需的 token 文件。token 只以只读 Compose secret 文件挂入 Worker，不写进 `.env`。

```sh
cd deploy/compose
cp .env.example .env
```

编辑 `.env`：填入强随机 `POSTGRES_PASSWORD`，并将三个镜像变量替换为发布流水线给出的 `ghcr.io/ly416123/...@sha256:...`。确认镜像来自同一个发布 tag/source commit。将 `WORKER_n_AGENT_ID` 改为 Control Plane 注册接口为每个 Worker 返回的 UUID，保证唯一且重启稳定。设置 `MODEL_TOKEN_FILE` 为本机模型 token 文件路径；相对路径相对于本目录。使用 ChatGPT Plan Worker 时，该文件应为现有 Worker 运行时支持的 access-token 格式。

使用私有 GHCR 镜像时，先在本机执行 `docker login ghcr.io`。不要把 PAT 写入 Compose 文件或 `.env`。

## 校验与启动

不启动 Worker 的基础服务：

```sh
docker compose --env-file .env config --quiet
docker compose --env-file .env up -d postgres nats nats-bootstrap control-plane gateway
docker compose --env-file .env ps
```

Control Plane 仅绑定到 `127.0.0.1:${CONTROL_PLANE_PORT}`；PostgreSQL、NATS 和 Gateway gRPC 端口不发布到宿主机。容器 bridge 网络允许 Worker 访问模型服务；主机防火墙/出站代理策略需按环境限制 Worker 的外部访问。NATS 第一次启动时创建 TASK_EVENTS、AGENT_EVENTS、CONTROL_EVENTS、DEADLETTER_EVENTS 四个持久 stream；数据库由 Control Plane Flyway 迁移。

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
- 目前没有 Matrix、MinIO 可选 profile：Compose POC 先验证固定容量和共享持久化基础设施；对象存储/IM 接入待对应适配器与租户隔离验收后再加入。

## 当前限制

- Worker 镜像仍沿用旧仓库发布制品；本仓库没有复制业务状态机。
- 此部署拓扑尚未证明跨租户会话、workspace、工具状态和 Artifact 的强隔离。多租户上线前必须完成 `docs/plans/2026-11-04-agentteams-new-bootstrap.md` 的批次 2 验收。
- 本机容器网络隔离不能替代任务级 sandbox。对不可信代码/工具执行应拒绝或接入明确的 gVisor/Kata/MicroVM Provider。
- Compose 不提供高可用控制面、多主机调度、自动扩缩容或 Kubernetes 等价 Sandbox。
