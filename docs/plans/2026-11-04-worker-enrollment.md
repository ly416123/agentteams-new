# Worker enrollment 实现计划

> **面向 AI 代理的工作者：** 按顺序执行任务，先写失败测试，验证失败后再实现。完成每一阶段后检查租户 scope 和秘密投放边界。

**目标：** 在不修改 `agentteams-java` 的前提下，为固定 Worker 槽位提供可撤销、租户范围受限的 Gateway 会话凭证签发与 Compose 投放路径。

**架构：** 新增独立 Java 21 Spring Boot enrollment 服务，使用 v0.1.4 已有 `agents`、`agent_sessions` 表。OIDC 权限和 Agent 元数据 scope 都通过后才可签发/撤销；明文 Token 仅在签发响应返回一次，持久层只写 SHA-256。Compose 为每个 Worker 加载独立、权限受限的 runtime env 文件。

**技术栈：** Java 21、Spring Boot 3.5.16、Spring Security OAuth2 Resource Server、Spring JDBC、PostgreSQL、JUnit 5、Docker Compose v2。

---

## 文件清单

- `pom.xml`：添加 `worker-enrollment` 模块及 Spring Boot BOM、插件版本。
- `worker-enrollment/pom.xml`：Web、OAuth2 Resource Server、JDBC、PostgreSQL、测试依赖。
- `worker-enrollment/src/main/java/io/agentteams/enrollment/`：应用、OIDC 安全、配置、Session 服务/仓库/API。
- `worker-enrollment/src/test/java/io/agentteams/enrollment/`：Token、scope、授权、撤销与 HTTP 安全行为测试。
- `worker-enrollment/src/main/resources/application.yml`：OIDC/JDBC/Token TTL 属性；缺配置时拒绝启动。
- `deploy/compose/compose.yaml`：enrollment 服务、CP OIDC 配置和每槽位 runtime env 文件。
- `deploy/compose/.env.example`：OIDC issuer/JWKS/audience 与每槽位凭证文件路径。
- `deploy/compose/validate-config.sh`、`start-workers.sh`：生产配置校验及 Worker 槽位凭证文件检查。
- `deploy/compose/enroll-worker.sh`：请求签发并以 `0600` 权限写入指定槽位文件，Token 不输出到终端。
- `deploy/compose/README.md`：签发、轮换、撤销和启动操作说明。

## Task 1：建立 Java 模块与安全配置

- [x] 新建 root Maven module 与 Spring Boot 依赖管理。
- [x] 先写 `EnrollmentOidcPropertiesTest`：缺 issuer、audience、JWKS URI 时配置校验失败；合法配置通过。
- [x] 运行定向测试验证红灯，再实现 properties 校验与 Spring Security JWT issuer/audience 验证。

## Task 2：实现 Session 签发和撤销

- [x] 先写 `WorkerSessionServiceTest`：只对存在且 scope 与调用者完全相同的 Agent 签发；缺权限、缺 scope、跨租户请求、终止 Agent 均拒绝。
- [x] 测试 Token 使用安全随机数；数据库仅收到 SHA-256；TTL 限制在 1 小时至 30 天。
- [x] 测试撤销必须同时匹配 `agent_id`、Session ID 与调用者 scope；重复撤销幂等，不暴露其他租户的 Session 是否存在。
- [x] 实现 JDBC 仓库：读取 `metadata.scope.{tenant,project,team}`；签发事务插入 `agent_sessions`；撤销只更新 `revoked_at`。仅锁定 Session 行，不锁 Agent 行以遵守专用 DB role 的最小权限。
- [x] 实现 HTTP API：`POST /api/v1/agents/{agentId}/sessions`、`DELETE /api/v1/agents/{agentId}/sessions/{sessionId}`；强制 OIDC Bearer JWT，并要求 `permissions` 含 `worker:session:manage`。

## Task 3：Compose 安全接线和每槽位投放

- [x] 添加 enrollment 服务与 OIDC issuer/JWKS/audience 配置；同一 OIDC 配置显式启用 Control Plane API 安全。
- [x] 为每个 Worker 配置不同的 `env_file` 路径；启动时要求活动槽位凭据文件存在、权限不可被组/其他用户读取且含非空 Gateway Token。
- [x] 实现 `enroll-worker.sh`：读取本地 OIDC access token 文件、Agent UUID 和目标 runtime env 文件；调用签发 API 后原子写临时文件、`chmod 600`、`mv`，不回显 Token。
- [x] 实现撤销命令调用 API；文档要求轮换先签发新 Session、更新 Worker runtime env、重连后再撤销旧 Session。
- [x] 更新 Compose README 和 `.env.example`，说明 OIDC、scope、投放与撤销流程。

## Task 4：验证与交付

- [x] 运行 22 项 Enrollment 测试、Maven verify、Compose config 与 shell 静态校验；CI 已覆盖同样的 Java 21/Compose 检查。
- [x] 使用 Testcontainers/PostgreSQL 验证 scope 查询、Token Hash 落库、撤销幂等和专用 DB role 仅能访问授权表；CI 明确要求 10 项 PostgreSQL 集成断言全部执行。
- [x] 使用进程内临时 RSA/JWKS 发行者通过真实 HTTP JWT 解码器与 PostgreSQL 验证签名、issuer、audience、Token Hash、撤销和 tenant/project/team scope 拒绝。
- [x] 在 CI 中启动固定 digest 的 v0.1.4 Gateway 和 PostgreSQL，以最小 protobuf/gRPC 客户端验证有效握手、Gateway/Agent 状态投影，以及未知、过期、撤销和 Agent 绑定不匹配的拒绝；共 5 项，CI 强制确认未跳过。
- [ ] 在部署环境使用真实 OIDC Provider 验证 claims 映射，并使用真实 Worker runtime 完成任务 attempt、续租/重连及撤销后的新连接拒绝；测试发行者不替代真实 IdP，当前没有有效 Worker 模型凭据。
- [x] 检查没有测试密钥或 Token 文件入库；更新审计、PR 描述并提交。

## 不变量

- 不更改 `agentteams-java`，不复制旧 Task/Attempt/Gateway 状态机。
- 不向数据库、日志、Trace、Shell 历史、共享 `.env` 写入或输出明文 Worker Token。
- Worker Token 仅认证 Worker 对 Gateway 的连接，不是模型/MCP 上游凭证；后者仍由批次 3 凭证代理解决。
- 固定 digest Gateway 测试使用合成数据库 session 与协议客户端；它验证 Gateway 鉴权和状态写入，但不代表完整 Worker AgentScope runtime 或任务闭环验收。
- OIDC/JWKS 配置错误必须 fail-closed。生产 Compose 不允许关闭 API 身份认证或将 Control Plane/enrollment 端口绑定到非回环地址，除非前置可信 TLS 入口已明确配置。
- v0.1.4 Worker 只在连接时校验 Session；Token 到期不会主动断开已建立连接。轮换流程必须创建新 Session 并触发 Worker 重连，不能把到期等同于即时撤销。
