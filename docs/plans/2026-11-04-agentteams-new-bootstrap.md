# AgentTeams New 项目启动计划

> **面向 AI 代理的工作者：** 实施时按批次完成，并在每批次后审查租户隔离与兼容边界。不得通过复制核心状态机来缩短路径。

**目标：** 建立与 `agentteams-java` 并列的无 Kubernetes 固定容量架构入口，逐步交付可部署、具备多租户隔离的 Java 21 AgentTeams 运行路径。

**架构：** 保留 PostgreSQL、Outbox、NATS JetStream、Gateway 持久命令和 gRPC Worker 通道。Worker 作为预配置固定执行槽由 Compose/systemd 管理。多租户授权与 ExecutionContext 在控制面强制执行；会话、工作目录、制品和凭证按租户隔离。

**技术栈：** Java 21、Spring Boot、AgentScope、protobuf/gRPC、PostgreSQL、NATS JetStream、Docker Compose；对象存储与 Secret Manager 通过适配器接入。

**仓库边界：** `agentteams-new` 是目标架构主线。新功能默认在此开发；当前复用 `agentteams-java` v0.1.5 应用镜像仅用于验证兼容 POC。上游只接受阻断性安全/兼容问题的最小修复。真实 Worker、隔离和恢复验收通过后，按迁移阶段将所需应用装配与运行时能力移入新仓库，逐步解除旧应用镜像依赖；共享领域/API 继续以固定版本制品复用。

---

## 文件与组件清单

- `README.md`：项目目的、边界、支持档位和安全声明。
- `docs/architecture.md`：运行链、固定容量、多租户、凭证和部署架构。
- `docs/decisions/0001-shared-core-reuse.md`：跨仓库共享核心策略。
- `docs/plans/2026-11-04-agentteams-new-bootstrap.md`：本执行计划和门槛。
- `deploy/compose/`：在真实镜像制品可用后放置 Compose 文件、配置和启动说明。
- 后续 Java 模块：只在共享制品策略落实后增加引用，不在首批复制旧模块源码。

## 批次 0：共享核心制品边界

- [x] 从 `agentteams-java` 根 POM 与模块 POM 提取模块依赖图。6 个共享模块依赖闭包包含根 parent；Control Plane/Gateway/Worker 作为 OCI 应用交付。
- [x] 检查候选 `contracts`、`domain`、`application-contracts`、`runtime`、`observability`、`storage` 的 Maven parent、版本属性和内部依赖。
- [x] 选定 GitHub Packages、稳定 tag 版本、`GITHUB_TOKEN` 发布权限，以及独立消费者使用 classic PAT `read:packages` 的本机读取方式。
- [x] 在旧仓库根 POM 增加 `distributionManagement`，在 release workflow 中归一化 tag 版本并发布 parent + 6 个共享核心模块；预检 13 个 POM/JAR 文件以支持成功重跑并阻止部分覆盖。
- [x] 在新仓库添加 Maven parent POM 和独立 consumer fixture。
- [x] 创建 GitHub 仓库 `ly416123/agentteams-new` 并推送启动基线 `34010d1`。
- [x] 在新仓库实现 `verification/shared-core-consumer/verify-local-release.sh`，从旧仓库已提交 ref 生成干净源码快照，在临时 file repository 发布并校验 13 个 POM/JAR，再用空 Maven settings 和独立 local cache 端到端解析 parent、6 个模块及第三方传递依赖。
- [x] 增加 Maven 发布状态分类测试：未发布时允许发布，完整已发布时跳过，部分发布时失败并要求保留现有制品、修复后使用新 patch tag；预检先校验 GitHub 仓库身份访问，再仅跟随 HTTPS 重定向且不向跨主机转发凭证，并禁用 `.netrc`。发布权限由来源仓库 workflow 的 `packages: write` 提供。
- [x] 在稳定 tag 发布首个 GitHub Maven Package，并使用远端消费者验证真实下载。`v0.1.0` 发布 workflow 在测试门禁失败，未发布 Maven 制品；该 tag 未复用。
- [x] 将实际发布版本 `0.1.1` 写入 `agentteams.core.version`，记录 source tag、包列表和远端验证结果。

**门槛：** 不依赖本机兄弟目录或手工 SNAPSHOT 安装；模块版本、源 commit 与依赖树可追溯。

**当前进度：** 批次 0 共享核心制品边界已完成本地与远端验证。`agentteams-java` tag `v0.1.1`（commit `0b0ac9e78a3e4be719fba6e5a66042e6ed16b507`）的 release workflow `41821553040` 已成功发布根 parent 与 6 个共享模块；该工作流随后在 OCI 镜像签名/attestation 阶段失败，与 Maven 发布步骤无关。2026-11-04 从 `agentteams-new` 使用本机代理 `127.0.0.1:7892`、classic PAT `read:packages`、临时 Maven settings 和独立本地缓存执行远端 `dependency:tree`，成功解析 6 个 `0.1.1` 模块与第三方依赖并输出 `BUILD SUCCESS`。Maven Packages 列表为 [agentteams-java Packages](https://github.com/ly416123/agentteams-java/packages)。该 registry 的 Maven 包继承仓库权限，无独立包授权入口。跨仓库 GitHub Actions `GITHUB_TOKEN` 路径尚未验证；旧仓库 Kind CI 问题留待后续部署/镜像批次处理。

**批次 1 执行记录：** 在 `agentteams-java` 隔离 worktree 中修复 release workflow provenance 的 jq 括号、Cosign 类型及 SLSA v1 predicate 结构（`cf1f4f1`、`02d5ad3`、`02d5ad30`）。`v0.1.2` workflow `41830278642` 的 Maven 包和 Control Plane 镜像已发布，但完整 release 在 provenance 阶段失败；`v0.1.3` workflow `41833566751` 因修复前结构已触发并提交取消。修正后的 `v0.1.4` workflow `41833753572` 成功，发布了 parent + 6 个 Maven 核心包、4 个签名/attestation 镜像和签名 release manifest；本机 Cosign 2.4.1 `attest --no-upload` 红/绿验证、远端 Maven consumer `0.1.4` 和 release manifest 签名校验均通过。三项 Compose 镜像 digest 固定在 `deploy/compose/.env.example`，对应 source commit `02d5ad3017f56b2a3cbb6c60112077d32f3299f4`。Compose 提供可选单节点 S3 `storage` profile；Worker egress 网络与数据库/NATS/对象存储数据网络隔离，UUID 校验不区分大小写。基础、storage、worker profiles 的配置解析、配置校验正/负场景、shell 语法及 `git diff --check` 已通过。2026-11-04 宿主机本机代理可访问 Docker Hub/GHCR，但默认 Colima profile 的 dockerd 没有使用代理；为避免重启并中断现有容器，另建隔离的 `agentteams-poc` Colima profile 并配置 daemon proxy，成功拉取 `natsio/nats-box:0.14.0`、PostgreSQL、NATS 和 SeaweedFS。验证发现 `minio/minio`、`minio/mc` 无法从 Docker Hub 获取，Quay 返回未授权；因此改为固定 digest 的 SeaweedFS 4.52 单节点 S3，实际容器启动成功、创建 `agentteams` bucket，S3 endpoint 可达且匿名请求被拒绝（HTTP 403）。随后修复 NATS stream bootstrap 交互确认问题，在隔离 Compose 环境完成 NATS 四个 stream 创建、PostgreSQL 迁移到 v121、Control Plane/Gateway 启动；通过 Control Plane TaskFile API 上传并下载对象成功，SeaweedFS 容器重启后同一对象仍可读取。应用镜像为 amd64-only，Compose 已明确平台；ARM64 开发 smoke 使用 QEMU 仿真，启动时间显著更长。真实 Worker 任务、Worker drain、专用 Artifact presigned-upload 流程、多租户/会话隔离和凭证代理仍待后续批次验收；本 POC 不代表生产就绪。

**运行时复用边界审计：** 对 `agentteams-java` v0.1.4 源码 commit `02d5ad3017f56b2a3cbb6c60112077d32f3299f4` 完成只读检查，记录见 [旧运行时复用边界审计](../audits/2026-11-04-runtime-reuse-gap-audit.md)。旧版可复用任务状态机、DB Lease/恢复、Gateway 持久状态、应用层租户授权、Matrix 绑定和 Artifact API；但未发现 PostgreSQL RLS policy，MCP 凭证可从 Worker 环境读取、模型 Token 可由 Worker 读取文件，Compose 也尚未跑真实 Worker Attempt。上述机制不构成新项目的多租户强隔离或凭证不落 Worker 证明。后续先完成真实固定 Worker 生命周期，再依次落地 scope 闭环、Attempt 隔离、RLS 纵深防御与凭证代理。

**Worker 启动阻断项（历史记录，已部分解除）：** 原先固定槽位没有受支持的注册凭证生命周期，且 v0.1.4 Gateway 会话校验失效。`agentteams-new` 已增加受控 Worker enrollment（Token 只展示一次、DB 仅存 Hash、支持撤销/轮换、每槽位独立投放），上游 Gateway 缺陷已由 v0.1.5 修复并通过新 digest 握手验收。Control Plane 生产 API OIDC 配置和真实 IdP claims 映射仍需验收；不得通过手工插入长期 Token 或公开 API 临时绕过认证来宣布通过。

**Gateway 安全阻断已解除（2026-11-05）：** v0.1.4 固定 digest 的真实 gRPC 测试证明未知、过期、已撤销和 Agent 不匹配会话均错误收到 `READY`（[CI 41953617348](https://github.com/ly416123/agentteams-new/actions/runs/41953617348)）。在用户授权自动推进后，对上游依赖进行了最小 fail-closed 修复并合并至 `agentteams-java` PR #2；稳定版本 `v0.1.5` 的 release workflow [41968831366](https://github.com/ly416123/agentteams-java/actions/runs/41968831366) 成功，source commit 为 `0156f2c1fecdc868cb4a0f4f13fb068bb4162834`。新项目已锁定 release manifest 中的 Gateway/Worker/Control Plane digest；新 Gateway 固定镜像握手测试本机执行 5/5、0 skipped，其中有效 token 接受、四类无效 token 全部拒绝。此上游变更只修复新架构复用的安全依赖，Enrollment、多租户隔离、凭证代理及 Compose 产品能力仍只在 `agentteams-new` 开发。v0.1.4 的历史审计结论保留，不应用于当前镜像。

**v0.1.5 上游制品及新项目握手验收（2026-11-05）：** `agentteams-java` PR #2 合并后，release run `41968831366` 成功发布 Maven parent + 6 个共享模块和签名 OCI 镜像。Release manifest source commit `0156f2c1fecdc868cb4a0f4f13fb068bb4162834`；Control Plane digest `sha256:403c3da94d436c19ce09e2d347f9e4182fb972a41adc5995c57694922fb94173`、Gateway `sha256:40c919d31b0fe7bdf876237c36fff405a16c2208420ad42374f6dd67491e055f`、Worker `sha256:0aec65e942a9ecfa726032e560066d91266905dc878fd4a610d533db0118b86d` 已更新到 Compose 示例，三者在本机 Docker 均按 amd64 平台拉取并与 manifest digest 一致。`agentteams-new` Maven parent 固定消费 `0.1.5`；独立 consumer 用已认证 GitHub Packages、本机代理和现有第三方依赖缓存解析 6 个核心模块及传递依赖，`BUILD SUCCESS`。新 Gateway gRPC 握手测试通过 5/5、0 skipped；Enrollment 全套 27/27 测试通过，Compose config、shell syntax 与 `git diff --check` 均通过。本机 ARM64 Colima 通过 amd64 仿真运行：固定容量基础 Compose 中 CP/Gateway/PostgreSQL/NATS 启动成功，数据库 schema 到 v121、4 个 NATS streams 已创建；重启 CP/Gateway 后 schema 与 streams 均保留，CP readiness 返回 `UP`。CP 冷启动约 83 秒，反映 ARM64 仿真耗时，正式运行建议使用 amd64。此阶段只完成上游安全依赖修复、握手门禁和基础服务重启恢复；真实 OIDC Provider/Worker Attempt、租户/会话隔离、凭证代理、容量恢复仍在计划中。

**固定容量启动契约与 L5 资源盘点（2026-11-05）：** 新增无容器副作用的 `start-workers.sh` 契约测试，检查 `WORKER_REPLICAS=0` 只启动共享服务、配置为 2 时只点名 `worker-1` 和 `worker-2`，并禁止使用 `--scale`；OIDC URL 契约测试同时要求 API Auth 启用时 issuer/JWKS 使用 HTTPS。L5 只读核验确认 Keycloak 25.0.6 仅启用 HTTP 8080，Control Plane realm 为 `agentteams`，issuer `http://192.168.122.55:30082/realms/agentteams`、JWKS `http://keycloak:8080/realms/agentteams/protocol/openid-connect/certs`、audience `agentteams-api`，Tenant/Project/Team/Permissions claim 为 `tenant`/`project`/`team`/`permissions`。Worker Pod 将 Secret `agentteams-chatgpt-oauth-session` 只读挂载至 `/var/run/agentteams/chatgpt/token`，未读取 Secret 内容。`readonly-pod-logs` context 不能读取 Secret/ConfigMap，也不能列出 Service/Ingress；因而无法核实现成 HTTPS 入口。本机新仓库 checkout 没有 `deploy/compose/.env` 或任何 `WORKER_n_MODEL_TOKEN_FILE` 配置。真实 IdP/Worker Attempt 验收需要可信 HTTPS issuer/JWKS 地址（或 L5 HTTPS 入口）和新运行主机上每个活动固定 Worker 独立的模型凭据文件；不需要、也不应复制 L5 Secret。现有 L5 工作负载未改动。

**批次 2A scope 基线（2026-11-05）：** 新增 Tenant/Project/Team/Agent/Task/Attempt/Lease/Conversation/Artifact/Tool/Workspace/Credential/事件/审计资源矩阵，逐项定义可信 scope 来源、校验点、生命周期和负向验收；同步建立项目领域词汇。当前 Enrollment 已有 Tenant/Project/Team 逐项比较及跨 scope PostgreSQL/JWT 测试；旧 Worker 上游源码有 Attempt session/workspace 清理机制，但 Compose 双 Tenant 真实任务与 Task/Artifact 全链路尚未验证，批次 2 仍未通过。

**批次 2C Enrollment RLS 子集（2026-11-05）：** 为独立 `worker_enrollment` 数据库角色在 `agents` 与 `agent_sessions` 表启用 RLS；scope 由已验证 JWT 进入事务局部 GUC，窄列权限禁止读取 Session Token Hash，角色设置为非 owner、非 superuser、无 `BYPASSRLS`。Testcontainers 验证缺 scope 默认拒绝、Tenant A/B 同连接隔离、跨 scope 写拒绝和事务结束清理，定向 PostgreSQL 测试 5/5 通过。其他任务/会话/Artifact 等表仍未采用 RLS。完整本机 `verify` 中复用 Gateway 镜像在 ARM64 Colima 上未能于 2 分钟开放 gRPC 端口；不含该镜像用例的 23 项通过。推送后 Ubuntu CI run [41981566128](https://github.com/ly416123/agentteams-new/actions/runs/41981566128) 完整通过，要求的 PostgreSQL、签名 OIDC→数据库和固定 Gateway 握手 16 项均执行、无跳过；Compose 配置和 shell 检查通过。

**批次 2C Enrollment scope 最小读取面（2026-11-05）：** Enrollment 此前需要 `SELECT agents.metadata` 才能取 scope，导致受限服务角色仍可读整份 metadata JSON。现在由数据库触发器维护 `worker_enrollment_agent_scopes` 窄列索引，Enrollment SQL 从索引取 Tenant/Project/Team，角色仅获准读 `agents(id, phase)` 和 scope 索引列。同步函数由仅拥有 scope 索引的 `NOLOGIN`、非 superuser、无 `BYPASSRLS` 角色执行；Agent 写入角色无权直接写 scope 表。PostgreSQL 集成测试覆盖 insert/update/无效 scope 清理、metadata 列拒绝、RLS 与 Enrollment 操作；新的非超级用户 Agent 写入测试已证明触发器可同步且写入角色不能直接更改索引。完整模块测试及新 CI 门禁待本次补丁完成后更新。此改动只收窄 Enrollment 数据库角色，未扩展到 Control Plane 业务表。

**批次 2C scope owner 权限回归（2026-11-05）：** 触发器改为由专用、无登录、非 superuser、无 `BYPASSRLS` 的 scope owner 执行；迁移会将预先存在的同名角色也强制收紧，避免沿用 LOGIN/BYPASSRLS 权限。PostgreSQL 测试先以故意不安全的预置角色验证失败，再验证 Agent insert/update 和无效 scope 清理均正确同步，且 Agent 写入角色无 scope 表直接写权限。24 项本机模块测试（不含 amd64 Gateway 镜像握手）通过。CI 首次因门禁仍要求 Repository 测试数为 5 而失败；将计数更新为 17 后，CI run [41987564292](https://github.com/ly416123/agentteams-new/actions/runs/41987564292) 全绿，Repository 6、OIDC 6、Gateway 握手 5 项均执行，Compose/shell 检查通过。

**真实 Control Plane schema 验收（2026-11-05）：** 在专用 Colima profile 与隔离 Compose project 中启动固定 v0.1.5 Control Plane/PostgreSQL，实际执行 Enrollment RLS 安装 SQL。确认 v121 schema 含 scope 索引和 Agents/Agent Sessions RLS；Enrollment 角色可读 `agents.id` 和 scope 索引列，但不能读 `agents.metadata` 或直接写 scope 索引；scope owner 为 `NOLOGIN`、非 superuser、无 `BYPASSRLS`；Control Plane readiness 为 UP。验收后删除隔离容器/卷/网络和临时凭据，并停止专用 profile。PR #1 提交 `6b31984` 的 CI run [41989421452](https://github.com/ly416123/agentteams-new/actions/runs/41989421452) 全绿。

**Worker 模型凭证槽位隔离（2026-11-05）：** Compose 从所有 Worker 共用一个模型 Secret 改为最多 8 个独立槽位 Secret；每个 Worker 只挂载其槽位文件，启动校验拒绝多个活动槽位指向同一个底层文件并要求文件仅所有者可读（`0600`）。合约测试覆盖八个 Secret 映射、共享文件和宽权限文件拒绝；8 项本机 Compose/OIDC 合约测试、Compose config 与 shell 语法通过。PR #1 提交 `5a53931` 的 CI run [41990267166](https://github.com/ly416123/agentteams-new/actions/runs/41990267166) 全绿。该改动降低槽位间凭证复用，但上游 Token 仍可被对应 Worker 读取，不能替代 Attempt 级凭证代理。

**复用 Control Plane 的 RLS 边界审计（2026-11-05）：** 对固定 v0.1.5 的迁移 SQL、scope Repository、Task 查询与 Compose 数据库身份做只读检查，记录见[共享 Control Plane scope 与 RLS 就绪度审计](../audits/2026-11-05-shared-control-plane-scope-and-rls-readiness.md)。Task/Attempt/Assignment/Lease 等需沿 `resource_scopes` 与父子关系判定 scope；Conversation 有自身 scope。当前 Compose 使用 `POSTGRES_USER=agentteams` 初始化超级用户作为 Control Plane 连接，源码查询未设置事务级 GUC，因此单独从新仓库启用业务表 RLS无法保护 Control Plane。全系统 RLS 前置是拆分非 superuser/非 owner 运行角色、实现所有 API 和后台作业的事务 scope，并验证跨 Tenant 的业务 API；当前不宣称强隔离。

**凭证代理接入边界（2026-11-05）：** Worker 可配置 ChatGPT Responses API endpoint 并逐请求读取模型 Token 文件，便于新项目把上游调用改道到代理；但当前模型 Token Source 不接收 Task/Attempt/Lease 上下文。Compose 已切换到每个固定 Worker 槽位独立的模型 Secret，仍不能证明 Attempt 级授权。不能信任客户端自报的 HTTP header/request body；固定并发为 1 时，代理可按独立 Worker 凭据识别 Agent，再从数据库解析唯一有效 Assignment/Attempt/Lease 与 Task scope。零条、多条、过期或 scope 不匹配均拒绝。该边界记录在 [ADR 0002](../decisions/0002-attempt-bound-model-proxy.md)。

**Attempt 授权解析实现起步（2026-11-05）：** 新增 `credential-proxy` Java 模块。`AttemptExecutionContextResolver` 输入是从代理凭据哈希解析出的 Worker 身份（代理凭据 ID、Agent ID、Tenant/Project/Team、模型凭证引用）；它只在数据库候选恰好一条、Agent scope 匹配、Assignment/Attempt/Lease 活动且 Lease 未过期时产生内部上下文。上下文最长 5 分钟且不超过 Lease 到期。9 项单元测试覆盖无/多 Assignment、过期、较短 Lease 上下文过期、非活动、Agent/scope 不匹配及缺失字段。本机 `mvn verify` 成功；本机无 Docker，Testcontainers 的 17 项 PostgreSQL/OIDC/Gateway 测试被禁用跳过。PR #1 提交 `cc093ce` 的 CI run [41992380412](https://github.com/ly416123/agentteams-new/actions/runs/41992380412) 实际执行并通过全部 17 项集成测试和 9 项 Resolver 门禁，Compose/shell 检查通过。当前只实现领域解析边界；数据库查询、代理凭据签发、Provider Secret 解析和 HTTP 流式代理仍未完成。

**L5 资源复核（2026-11-05）：** 使用 L5 的 `readonly-pod-logs` context 只读列出 `agentteams` 命名空间 Pod：旧版 Control Plane、Gateway、Manager、Operator、Keycloak 25.0.6、PostgreSQL 16、NATS、MinIO 均在运行，另有 12 个旧版 Worker Pod 为 Running。一个 Worker Pod 的规格引用 Secret `agentteams-chatgpt-oauth-session`，挂载目录 `/var/run/agentteams/chatgpt`；本轮只检查了 Pod 对 Secret 的引用和挂载元数据，没有读取 Secret 内容。该 context 的 RBAC 对 Secret、Service、Ingress 均返回 `no`，所以不能确认 HTTPS 入口；此前 Control Plane 配置盘点显示 issuer/JWKS 为 HTTP，audience 为 `agentteams-api`，scope claims 为 `tenant`/`project`/`team`/`permissions`。这证明 L5 有旧版 IdP、Worker 与模型凭据引用，可作兼容参考环境；但它没有 `agentteams-new` 的 Enrollment/credential-proxy，且 HTTPS issuer 门槛不满足。新项目 checkout 也没有 Worker 模型凭据文件。真实验收仍需受信 HTTPS OIDC 入口和新运行主机独立凭据文件；无需读取或复制 L5 Secret。

**Attempt 数据库查询实现（2026-11-05）：** `credential-proxy` 增加 JDBC repository，通过 PostgreSQL `SECURITY DEFINER` 函数查询唯一活动 Assignment/Attempt/Lease，并同时匹配 Agent 的 `WORKER` scope 与任务的 `TASK` scope。非登录函数 owner 只获必要列的 SELECT，代理查询组无底层表权限，只获函数 EXECUTE；脚本要求在共享 schema v121 完成后由数据库管理员应用。新增 4 项 PostgreSQL Testcontainers 验收（精确租户匹配、过期/其他租户过滤、多活动执行保留给 Resolver 拒绝、运行角色无法直接 SELECT）。本机没有 Docker，4 项集成测试本机跳过；CI 门禁要求 4 项全部真实执行。首次 CI 执行发现有效 Lease 测试夹具误设为立即到期，已修正；PR #1 GitHub Actions [run 42000585636](https://github.com/ly416123/agentteams-new/actions/runs/42000585636) 已通过 Maven、原 17 项 PostgreSQL/OIDC/Gateway 测试、新增 4 项 PostgreSQL 凭证代理测试、9 项 Resolver 测试及 Compose/shell 检查，均无跳过。模型代理凭据哈希查验、运行登录角色签发、Provider Secret 解析和 HTTP 流式转发仍未完成。

**模型代理凭据签发与撤销绑定（2026-11-05）：** Enrollment API 现在可在 Worker session 签发请求中接收非敏感 `modelCredentialRef`，返回独立 `atp_` 模型代理 Token；Gateway Token 与代理 Token 分别只存 SHA-256，代理记录绑定 session、Agent、Tenant/Project/Team、凭据引用和相同到期时间。Enrollment DB role 只获同 scope 的代理凭据插入、撤销及最少元数据读取权，不能读取 Token 哈希或模型凭据引用；撤销 Gateway session 会在同一事务撤销关联代理凭据。`enroll-worker.sh` 可把两种 Token 写入各自 `0600` 文件。CI [run 42006053503](https://github.com/ly416123/agentteams-new/actions/runs/42006053503) 通过 18 项 PostgreSQL/OIDC/Gateway 集成、4 项 Credential Proxy PostgreSQL 测试、9 项 Resolver 单测及 Compose/shell 门禁，零跳过；新增测试验证代理 Token 哈希持久化、scope 限制和 Session 联动撤销。本机 8 项 Enrollment 服务单测通过，但本机没有 Docker，PostgreSQL 集成由 CI 执行。代理 HTTP 认证、Provider Secret 解析和流式转发尚未实现，`atp_` Token 当前不可直接用于模型调用。

**模型代理 Token 鉴权查询（2026-11-05）：** `credential-proxy` 新增 `JdbcModelProxyCredentialAuthenticator`，仅接受固定格式 `atp_` token，在应用侧计算 SHA-256 后调用独立 PostgreSQL `SECURITY DEFINER` 函数；函数同时验证 Token 与 Gateway session 的撤销/过期状态，以及 Agent 当前 Tenant/Project/Team scope，且只返回代理所需身份和非敏感凭据引用。运行角色只能执行函数，不能读取 Token 哈希表。GitHub Actions [run 42006970309](https://github.com/ly416123/agentteams-new/actions/runs/42006970309) 全绿，5 项 PostgreSQL Credential Proxy 边界测试、9 项 Resolver 测试及全部 Enrollment/OIDC/Compose 门禁均执行通过、零跳过。本机 Maven 编译和 9 项 Resolver 测试通过；本机无 Docker，5 项数据库测试本地跳过，由 CI 实际执行。此阶段只完成认证与身份解析，尚未接入 HTTP 模型代理、Provider Secret Source、上游请求转发和流式处理。

**凭据文件源（2026-11-05）：** `credential-proxy` 增加 `CredentialSource` 接口和只接受 `file:<相对路径>` 的容器文件实现。路径限制在挂载根目录内，拒绝绝对路径、穿越、逃逸 symlink、缺失/空文件及超过 64 KiB 的文件；读取时清零中间字节与字符缓冲，异常不回显引用或路径。5 项安全测试覆盖正常读取和拒绝边界。GitHub Actions [run 42007958762](https://github.com/ly416123/agentteams-new/actions/runs/42007958762) 全绿，Credential Source 5 项测试零跳过，PostgreSQL、Resolver 和 Compose 门禁也均通过。挂载根目录必须由运维只读挂载，HTTP 代理尚未接入该 source，因此当前还不能用于模型调用。

## 批次 1：无 Kubernetes 固定容量 Compose POC

1. 先从旧仓库构建并发布 Control Plane、Gateway、Worker 的固定版本 OCI 镜像；记录镜像 digest 和源 commit。
2. 新仓库新增 `deploy/compose/compose.yaml`、`.env.example`、`README.md` 和配置校验脚本。服务包括 PostgreSQL、NATS JetStream、Control Plane、Gateway 和由 `WORKER_REPLICAS` 静态配置生成的固定 Worker 槽位；不提供 Docker Socket。
3. 为数据库和 NATS 配置持久卷、健康检查、启动依赖和最小凭据配置；单节点 S3 兼容存储与 Matrix 作为可选 profile。
4. 实现固定 Worker enrollment：服务端验证槽位归属并签发单次呈现的短时 Gateway Token，持久层只保存 Hash，支持撤销、轮换和过期；每槽位单独投放凭证，不放入共享 `.env`。
5. 配置 Control Plane OIDC issuer、audience 与租户/项目/团队 claim；生产档缺少身份配置时拒绝启动，local-dev 档仅允许回环绑定并显式标注。
6. 明确 Worker 实例身份稳定性、注册/心跳、drain、关闭信号与重启重连要求；Compose 只做部署编排，不实现任务级扩缩。
7. 编写一条端到端操作手册：启动、创建租户/Team、签发并注册 Worker、提交任务、读取 Timeline/Artifact、停止并重启恢复。

**门槛：** 环境不提供 Kubernetes 凭据或 API 时可完成端到端任务；通过受支持的 enrollment 完成 Worker 认证；API 身份认证启用；服务重启后任务、Outbox、命令和 ACK 状态正确恢复；容量不足只排队/限流。

## 批次 2：租户与会话隔离

1. 以[多租户与会话隔离验收矩阵](../specs/2026-11-05-tenant-session-isolation-matrix.md)为资源基线，定义不可变 `ExecutionContext` 的 protobuf 字段、签发方、验证方、版本及过期规则；scope 不由 Worker/客户端自行选择。
2. 先建立按 Task、Attempt、Conversation、Memory、Artifact、MCP binding、workspace 和后台 Job 划分的 scope 矩阵，统一 scope-aware 数据访问入口；跨租户 ID 猜测、伪造上下文、无 scope 的后台操作默认拒绝。
3. 实现 Attempt 独立会话状态、目录、缓存键和 MCP 客户端生命周期；完成/取消/失败/租约过期均执行清理。
4. [部分完成] `worker_enrollment` 的 `agents`、`agent_sessions` 已采用独立非绕过 RLS 角色和事务局部 scope；应用层 scope 校验保持强制。其余 Task、Attempt、Conversation/Memory、Artifact/File、Tool/MCP、Outbox/Audit 表需先完成真实 schema/调用链盘点，再逐表加入策略和负向验收。
5. 实现租户级并发、速率和模型预算；支持共享池与专属固定池路由，高敏租户不能落入共享池。

**门槛：** 先完成真实 Worker Attempt、注册/心跳、drain、租约过期恢复和重启重连。随后验证同一 Tenant/Project/Team 内连续复用 Worker 不残留会话/文件/工具状态，并由不同固定 Worker 并发处理 A/B Tenant；跨 Tenant Assignment 必须拒绝。越权和伪造 scope 均拒绝。通过前部署档位必须明确限制为单租户/受信 Worker 试验，不对外宣称强隔离。

## 批次 3：凭证源与凭证代理

1. 抽象 `CredentialSource`，先接容器 Secret 文件与至少一种企业 Secret Manager；配置和数据库只存非敏感引用。
2. 定义短时消费令牌：tenant/project/task/attempt/audience/model-or-tool scope、过期、轮换、撤销与重放防护。
3. 建设模型代理，验证 OpenAI-compatible 请求、流式转发、取消、超时、限流、成本归因和上游凭据不回传。
4. 建设独立 MCP Connector/Gateway，实施 server/tool allowlist、scope 验证、输入输出审计与密钥脱敏。
5. 所有代理授权和 Secret 解析失败均 fail-closed；上游密钥不得出现在 Worker 环境、命令、事件、日志、Trace 或产物中。

**门槛：** 越权模型/工具、过期/撤销令牌、跨租户 Attempt 和代理故障均拒绝；真实模型和 MCP 调用可按租户、项目和 Attempt 审计。

## 批次 4：容量、恢复与生产档位

1. 用真实任务到达率、执行时长、目标排队延迟、模型限流、租户配额与冗余率计算固定池建议，并通过压测校准。
2. 定义 `local-dev`、`self-hosted-small`、`enterprise-k8s` 的部署与 Sandbox 支持矩阵。
3. 补齐 PostgreSQL/NATS/对象存储备份恢复、升级回滚、凭据轮换、Worker drain 和 K8s ↔ Compose 切换操作。
4. 对要求强隔离的任务连接显式 gVisor/Kata/MicroVM Provider；不具备 Provider 时拒绝执行。
5. 建立从发布制品到 OCI digest、配置版本、数据库迁移和回滚步骤的追溯信息。

**门槛：** 容量不足不创建 Worker；租户配额公平；节点/服务重启和中间件中断后可恢复；不支持的 Sandbox 档位明确拒绝；高敏租户只进入专属池。

## 批次 5：解除旧应用镜像的长期依赖

1. 依据批次 1–4 的真实任务和安全验收结果，列出新路径实际需要的 Control Plane、Gateway、Worker 能力及稳定 API/事件/数据库边界。
2. 在 `agentteams-new` 实现或装配新路径所需的应用组件；共享核心只通过固定版本 API 制品复用，不复制整套旧业务状态机。
3. 对新旧实现执行同一套 Worker Attempt、租户/会话隔离、凭证代理、重启恢复和容量上限验收，并确认 PostgreSQL 仍只有一个事实源、无双写。
4. Compose 默认切换到新仓库维护和发布的目标应用制品；旧 OCI 镜像仅保留为有期限的兼容/回滚路径，并记录退役条件。

**门槛：** 新仓库可以独立构建、发布和部署目标路径；运行不要求 `agentteams-java` 应用镜像或 Kubernetes，且共享协议与数据兼容性验收通过。

## 全程约束

- Java 21 保持控制面、调度、Gateway 和 AgentScope Worker 主线。Python 只按实际 SDK/算法需要接入，且不得另建任务/租户事实源。
- 新旧路径任一时刻只有一个调度写入者；禁止业务状态双写。
- Matrix 只是可选协作渠道；PostgreSQL、Outbox、NATS 持久语义不由内存队列替代。
- 不在目标路径完成前宣称生产就绪、零信任凭证隔离或普通容器强隔离。
