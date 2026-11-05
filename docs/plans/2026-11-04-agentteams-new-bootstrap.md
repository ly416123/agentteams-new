# AgentTeams New 项目启动计划

> **面向 AI 代理的工作者：** 实施时按批次完成，并在每批次后审查租户隔离与兼容边界。不得通过复制核心状态机来缩短路径。

**目标：** 建立与 `agentteams-java` 并列的无 Kubernetes 固定容量架构入口，逐步交付可部署、具备多租户隔离的 Java 21 AgentTeams 运行路径。

**架构：** 保留 PostgreSQL、Outbox、NATS JetStream、Gateway 持久命令和 gRPC Worker 通道。Worker 作为预配置固定执行槽由 Compose/systemd 管理。多租户授权与 ExecutionContext 在控制面强制执行；会话、工作目录、制品和凭证按租户隔离。

**技术栈：** Java 21、Spring Boot、AgentScope、protobuf/gRPC、PostgreSQL、NATS JetStream、Docker Compose；对象存储与 Secret Manager 通过适配器接入。

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

**v0.1.5 上游制品及新项目握手验收（2026-11-05）：** `agentteams-java` PR #2 合并后，release run `41968831366` 成功发布 Maven parent + 6 个共享模块和签名 OCI 镜像。Release manifest source commit `0156f2c1fecdc868cb4a0f4f13fb068bb4162834`；Control Plane digest `sha256:403c3da94d436c19ce09e2d347f9e4182fb972a41adc5995c57694922fb94173`、Gateway `sha256:40c919d31b0fe7bdf876237c36fff405a16c2208420ad42374f6dd67491e055f`、Worker `sha256:0aec65e942a9ecfa726032e560066d91266905dc878fd4a610d533db0118b86d` 已更新到 Compose 示例，三者在本机 Docker 均按 amd64 平台拉取并与 manifest digest 一致。`agentteams-new` Maven parent 固定消费 `0.1.5`；独立 consumer 用已认证 GitHub Packages、本机代理和现有第三方依赖缓存解析 6 个核心模块及传递依赖，`BUILD SUCCESS`。新 Gateway gRPC 握手测试通过 5/5、0 skipped；Enrollment 全套 27/27 测试通过，Compose config、shell syntax 与 `git diff --check` 均通过。本机是 ARM64 Colima，Gateway 通过 amd64 仿真运行，握手测试耗时约 42 秒。此阶段只完成上游安全依赖修复与握手门禁；真实 OIDC Provider/Worker Attempt、租户/会话隔离、凭证代理、容量恢复仍在计划中。

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

1. 定义不可变 `ExecutionContext` 的 protobuf 字段、签发方、验证方、版本及过期规则；scope 不由 Worker/客户端自行选择。
2. 先建立按 Task、Attempt、Conversation、Memory、Artifact、MCP binding、workspace 和后台 Job 划分的 scope 矩阵，统一 scope-aware 数据访问入口；跨租户 ID 猜测、伪造上下文、无 scope 的后台操作默认拒绝。
3. 实现 Attempt 独立会话状态、目录、缓存键和 MCP 客户端生命周期；完成/取消/失败/租约过期均执行清理。
4. 决定 PostgreSQL RLS 的表范围、独立 DB role 和连接池事务变量设置；应用层 scope 校验保持强制，RLS 用于防止漏过滤的纵深防御。若分期启用，先完成集中 scope 访问与等价负向验证，再逐表启用 RLS。
5. 实现租户级并发、速率和模型预算；支持共享池与专属固定池路由，高敏租户不能落入共享池。

**门槛：** 先完成真实 Worker Attempt、注册/心跳、drain、租约过期恢复和重启重连。随后 A/B 租户并发及连续复用同一 Worker 的负向验收证明 Conversation、Memory、Task、Artifact、工具状态、临时文件和凭据缓存互不可见；越权和伪造 scope 均拒绝。通过前部署档位必须明确限制为单租户/受信 Worker 试验，不对外宣称强隔离。

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

## 全程约束

- Java 21 保持控制面、调度、Gateway 和 AgentScope Worker 主线。Python 只按实际 SDK/算法需要接入，且不得另建任务/租户事实源。
- 新旧路径任一时刻只有一个调度写入者；禁止业务状态双写。
- Matrix 只是可选协作渠道；PostgreSQL、Outbox、NATS 持久语义不由内存队列替代。
- 不在目标路径完成前宣称生产就绪、零信任凭证隔离或普通容器强隔离。
