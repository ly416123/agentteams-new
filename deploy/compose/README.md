# Compose 部署目录

此目录用于无 Kubernetes 的固定容量部署配置。目前尚未加入 Compose 清单：现有服务镜像还没有确认的不可变版本与发布来源。完成 `docs/decisions/0001-shared-core-reuse.md` 中的制品边界并发布镜像后，再在这里落地可运行配置，避免使用 `latest` 或不可复现的本地构建路径。
