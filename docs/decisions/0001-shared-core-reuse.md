# ADR 0001：新架构的共享核心复用方式

- **状态：** 已采纳为阶段性决策
- **日期：** 2026-11-04

## 背景

`agentteams-java` 是 Maven 多模块仓库。所有模块继承根 `agentteams-java:0.1.0-SNAPSHOT`，模块间使用 `${project.version}`，子模块 parent 的 `relativePath` 指向兄弟目录中的根 POM。它们不能由一个独立 Maven 项目稳定消费。当前仓库也未建立共享模块的版本发布契约。

新架构需要复用成熟任务语义和运行协议，同时避免复制核心后出现两套 Task/Attempt、租户授权、Outbox 和租约事实源。

## 决策

1. `agentteams-new` 是目标架构主线和最终部署入口。新功能默认在新仓库实现；不引用本机兄弟目录路径，也不把完整应用源码复制一份后分别演进。
2. 版本制品发布到 GitHub Packages 的 `agentteams-java` Maven Registry。旧仓库 release workflow 使用 `GITHUB_TOKEN` 和 `packages: write` 发布。GitHub 对 Apache Maven Registry 使用仓库级权限，包始终继承来源仓库权限，不能为 Maven 包单独设置授权。独立仓库本机消费已使用有 `read:packages` scope 的 classic PAT 验证；跨仓库 Actions 的 `GITHUB_TOKEN` 读取路径未验证，后续 CI 可用仓库 Secret 中的 classic PAT。
3. 以稳定 tag `vX.Y.Z` 为制品版本。发布 workflow 从 tag 导出临时 Maven 源码副本，在副本中使用固定版本的 `versions-maven-plugin` 将根 POM 和各子模块 parent 改为 tag 版本，再以同一个 Maven reactor 发布根 parent POM 与 6 个共享库：`contracts`、`domain`、`application-contracts`、`observability`、`storage`、`runtime`。主 checkout 保持不变，确保后续 OCI 镜像仍对应签名的原始源码指纹。根 parent 提供依赖管理；新仓库不引用 SNAPSHOT。
4. 发布前先用 `GITHUB_TOKEN` 校验 GitHub 仓库访问，再检查 GitHub Maven URL 上的根 POM 与 6 个 JAR/POM 文件。发布 workflow 声明 `packages: write`，从 `agentteams-java` 仓库发布其关联的 Maven 包；Maven registry 只支持仓库级权限，不提供独立包授权入口。全部 13 个文件都存在时，视为同版本重跑并跳过 Maven 发布；全部不存在时执行发布；部分存在时失败并报告缺项，避免覆盖已有文件。预检限制最多 3 次 HTTPS 重定向，不使用 `--location-trusted`，显式禁用 `.netrc`；最终 200 视为文件已存在，404 视为缺失，其他响应一律失败关闭。首次 Maven deploy 的服务端授权校验是最终门槛。部分发布时保留已发布制品，修复原因后使用新的 patch tag，避免删除或复用不可变版本。
5. Control Plane、Gateway 与 Worker 的 v0.1.5 OCI 镜像仅用于新仓库 POC 的过渡复用，目的是验证已有协议、状态机和部署链路，不代表新项目以旧应用仓库为长期运行时依赖。新仓库只有在固定版本镜像可追溯到源 commit 和 digest 后，才可把 Compose 标记为可运行。
6. 若某个候选库以后出现部署实现、启动逻辑或不稳定内部依赖，则先从公开核心 API 拆开再发布；不把 Operator/CRD 作为共享核心依赖。若模块边界需要跨仓库迁移，必须以 protobuf/gRPC、公共 API、数据库迁移和事件 schema 为兼容门槛。
7. 不使用 Git 子模块指向开发分支作为长期依赖方案；生产交付使用不可变制品版本和 OCI digest。
8. POC 验收后分阶段把新架构所需的应用装配和运行时能力迁入或实现于 `agentteams-new`，优先解除对旧版 Control Plane/Gateway/Worker 整套应用镜像的依赖。每个迁移阶段须保持共享 API、数据库迁移和事件 schema 兼容，并用真实 Worker、租户隔离和故障恢复验收；不做无业务收益的整仓重写。

## 备选方案

| 方案 | 优点 | 代价 | 结论 |
|---|---|---|---|
| 本地兄弟目录 / Maven `systemPath` | 初期最快 | CI 不可复现，路径绑定本机，无法版本治理 | 拒绝 |
| Git 子模块 | 保留原始源码与提交历史 | 子模块更新和 CI 凭证复杂；依赖源码仓库布局 | 仅作短期评估，不作长期制品契约 |
| 复制模块源码 | 独立性直接 | 修复和安全策略容易分叉，形成双写业务语义 | 拒绝 |
| 发布版本化 Maven 制品 | 边界和版本可审计，构建可复现；现有 GitHub Actions 已有包发布权限 | 需配置 GitHub Package 仓库读取授权，制品版本不可随意复用 | 采纳 |

## 影响与验证门槛

- 发布清单：根 parent POM 加 6 个核心 JAR；模块图由 Gateway/Control Plane/Worker 的 OCI 镜像交付，不另发为共享库。
- 发布版本来自稳定 tag；不能依赖本机 `~/.m2` 手工安装的 SNAPSHOT。
- 本地独立消费者 fixture `verification/shared-core-consumer/verify-local-release.sh` 已从旧仓库 commit `0b0ac9e78a3e4be719fba6e5a66042e6ed16b507` 导出干净源码，将 `0.1.1` 发布到临时 file repository，校验 13 个 POM/JAR，并解析 6 个模块与第三方依赖，退出码为 0。
- GitHub Packages `v0.1.1` 发布工作流 `41821553040` 的 Maven 发布步骤成功，发布根 parent 与 6 个共享模块；之后工作流在 OCI 镜像签名/attestation 步骤失败，不影响 Maven 制品发布。远端包列表见 [agentteams-java Packages](https://github.com/ly416123/agentteams-java/packages)。
- 远端独立消费者已通过本机代理 `127.0.0.1:7892` 和带 `read:packages` 的 classic PAT，在临时 Maven settings 与独立 local cache 下解析根 parent、6 个 `0.1.1` 模块和第三方传递依赖，Maven 输出 `BUILD SUCCESS`。跨仓库 GitHub Actions 的 `GITHUB_TOKEN` 尚未验证。
- 旧仓库最新 CI `41816738317` 的 Kind 场景失败于 Tuwunel AppService 回调 403，以及 Worker 任务保持 QUEUED、未创建 Attempt；这是后续镜像/部署批次的事项，不阻止共享 Maven 制品的发布与消费验收。
- 发布重试先检查 13 个关键 POM/JAR 文件：全有则跳过，全无才发布，部分存在时中止并列出缺项；保留部分制品，修复后使用新 patch tag。
- 镜像需要可追溯到源 commit、制品版本和配置版本。
- 当前 Compose 使用旧仓库发布的 v0.1.5 应用镜像仅证明兼容 POC 可运行；只有新仓库拥有并验收目标路径所需的应用装配/制品后，才可宣称完成旧应用镜像依赖迁移。
- 新旧路径共享同一 PostgreSQL 事实源期间，禁止双写同一业务状态；若要切换实例，先 drain Lease 并明确单一调度写入者。

## 发布与消费配置

旧仓库根 POM 的 `distributionManagement` 指向 `https://maven.pkg.github.com/ly416123/agentteams-java`；release workflow 为 Maven server `github` 配置 `GITHUB_ACTOR` 与 `GITHUB_TOKEN`。新仓库 POM 使用同一 repository URL，当前固定 `agentteams.core.version=0.1.1`。本机消费使用 classic PAT 的 `read:packages` scope；Maven 包权限继承 `agentteams-java` 仓库，设置页不会显示容器包那类独立访问控制。新仓库 Actions 的跨仓库认证仍需单独验收。

参考：[GitHub 官方 Maven Registry 配置与发布文档](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry)、[GitHub Packages 权限模型](https://docs.github.com/en/packages/learn-github-packages/about-permissions)。
