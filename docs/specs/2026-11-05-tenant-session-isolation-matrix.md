# 多租户与会话隔离验收矩阵

**状态：** 批次 2A 基线已落文档；Enrollment DB 的 agents/agent_sessions RLS 已实施，其他资源仍待盘点和验收
**日期：** 2026-11-05
**范围：** HTTP 身份、业务资源、Worker 执行上下文、数据库与后台消息

## 目标与术语

本矩阵定义从 Principal 到 Worker Attempt 的授权链，以及每种资源的规范 scope、强制检查点和负向验收。术语遵循仓库根目录的 [领域词汇](../../CONTEXT.md)。

核心关系：

```text
Principal
  └─ membership → Tenant → Project → Team
                         └─ Agent
                              └─ Assignment → Task → Attempt + Lease
                                                  ├─ Conversation / Memory
                                                  ├─ Workspace / Tool session
                                                  ├─ Artifact
                                                  └─ Credential authorization
```

Task 与 Attempt 的事实源是 PostgreSQL。NATS、Gateway、Worker、对象存储和观测数据只承载或索引由该事实源授权的范围，不得自行决定租户归属。

## 资源矩阵

| 资源 | 规范 scope / 所有者 | 强制校验点 | 隔离与生命周期要求 | 负向验收 |
|---|---|---|---|---|
| Principal 与成员关系 | `tenantId/projectId/teamId/subject` | OIDC 签名、issuer、audience、有效期；服务端读取当前成员关系与角色 | 撤销成员后后续请求立即拒绝；不能信任请求体中的 scope | 缺 claim、伪造 claim、无成员关系、已撤销成员均拒绝 |
| Agent 注册 | `tenantId/projectId/teamId/agentId` | 创建/修改 Agent 时验证操作者对目标 Team 的管理权 | Agent 的业务 scope 创建后不可由普通 Worker 请求更改；Enrollment 只能签发到完全匹配的 Agent | 跨租户 Agent ID 猜测、缺任一 scope、跨 Team 签发均拒绝 |
| Task | `tenantId/projectId/teamId/taskId` | 查询、创建、修改、取消、排队、Timeline 和结果读取均授权 | 所有 Repository 查询带服务端 scope；ID 是定位符，不是权限 | Tenant B 用 Tenant A 的 ID 读取或变更 Task 均拒绝且不泄露存在性 |
| Assignment / Attempt / Lease | Task scope 加 `agentId/attemptId/leaseId` | 调度写入；Gateway 与 Worker 接收消息时校验 Agent、Attempt、Lease 和有效期 | 每次重试使用新 Attempt；续租绑定同一 Assignment；过期/取消后停止副作用 | 伪造 Agent/Attempt/Lease、重放旧 Lease、跨 Task 续租均拒绝 |
| Conversation / Memory | Conversation 隶属 `tenantId/projectId`；运行态附加 `taskId/attemptId` | 每次读取、检索、组装上下文前重新授权 | 默认运行态会话以 Attempt 为 key；跨 Task 延续必须有显式授权；缓存键包含完整 scope | Tenant B 猜测 Conversation ID；同 Worker 连续执行 A/B 后检索到彼此内容，均应失败 |
| Artifact / Task File | `tenantId/projectId/taskId/attemptId/artifactId` | 上传前验证有效 Task/Attempt；下载/签发 URL 时重新授权 | 对象 key 仅用于命名，不作为授权；短期 URL 受众和过期明确；删除/过期策略与数据库清单一致 | 跨租户猜测对象 ID、复用其他 Attempt 上传 URL、过期 URL 均拒绝 |
| MCP binding / Tool call | Binding 归属 `tenantId/projectId/teamId`；调用再绑定 `taskId/attemptId/leaseId` | Worker 仅能调用当前 Assignment 所允许的已审批 server/tool | Connector session 按 Attempt 创建/释放；参数、返回值按策略脱敏 | 跨 Team server、未允许 tool、过期 Attempt、伪造工具 scope 均拒绝 |
| Workspace / 临时文件 | `tenantId/projectId/teamId/agentId/taskId/attemptId` | 创建 workspace 时验证服务端 Assignment；访问时校验 owner 和当前 Lease | 首期 Worker Agent 绑定单一 Tenant/Project/Team；同 scope 多 Attempt 不共享目录，终态清理；重新分配不得继承旧内容 | 路径穿越、旧 Attempt 重用目录、同一 Worker 连续执行不同 Attempt 后读到前次残留均拒绝；跨 Tenant Assignment 必须拒绝 |
| 上游模型/MCP 凭据 | 凭据引用归属 `tenantId/projectId`；消费授权再绑定 `taskId/attemptId/audience/tool-or-model` | Credential Proxy 每次请求验证短时消费 Token、allowlist、时效和撤销 | Worker 不得持有上游明文；代理解析 Secret 后只向上游转发；缓存按 scope 隔离并及时失效 | 跨租户引用、错误 audience、过期/撤销/重放 Token、代理不可用均拒绝 |
| Outbox / NATS 事件 / 后台 Job | 事件携带不可变 `tenantId/projectId/teamId/taskId/attemptId` 与 schema version | 生产者从已授权事务写入；消费者验证 schema 与业务资源归属，不信任 header 单独授权 | 重投递幂等；后台任务必须有显式 Tenant scope 或受限系统身份；死信保留审计但不扩权 | 缺 scope、篡改关联 ID、重复或跨租户重放均拒绝/隔离处理 |
| 审计 / 日志 / Trace / 指标 | 关联 scope 只作审计维度，不自动赋予访问权 | 查询审计数据也需授权；导出需管理员权限 | 可记录 tenant/project/attempt ID 和决策结果；不记录 Token、Secret、原始敏感提示词或跨租户内容 | 未授权主体读取其他 Tenant 审计；凭据在日志/Trace 中出现，均判失败 |

## 数据库纵深防御

应用层 scope 校验必须保持强制，PostgreSQL Row-Level Security（RLS）用于防止遗漏过滤条件。

- 先盘点 Task、Assignment、Attempt、Lease、Conversation/Memory、Artifact/File、MCP binding、Credential reference、Audit 与 Outbox 的真实表和外键，再选择 RLS 范围。
- 每个请求事务从已验证的 `ExecutionContext` 设置事务局部 `tenant_id/project_id`；连接池归还连接后不得保留上个请求的 scope。
- 缺少 scope 时策略默认拒绝。后台任务使用最小权限的显式身份，不能用空 scope 代表全租户。
- 应用连接角色不得是表 owner、superuser 或 `BYPASSRLS`；迁移角色与运行角色分离。
- RLS policy 必须同时覆盖 `USING` 和写入 `WITH CHECK`；级联关系以父资源 scope 为准，不能由客户端可写字段决定。

## 当前证据与边界

- 新项目 `worker-enrollment` 已从经验证 JWT 读取配置指定的 tenant/project/team claims，并要求管理权限；Repository 将 Agent 元数据中的三段 scope 与调用者逐项比较，Session Hash 持久化。已有 PostgreSQL/JWT 集成测试证明缺 scope 与跨 Tenant/Project/Team 签发、撤销请求被拒绝。
- 复用的 v0.1.5 Worker 运行时以 Attempt ID 创建 AgentScope session；旧源码显示 workspace scope 含 Tenant/Project/Team/Agent/Task/Attempt，并在运行终止时释放 Harness 绑定。这是上游实现证据，尚未证明固定 Compose 组合中同一 Agent 连续 Attempt 无残留，或不同 Tenant 的独立 Worker 互不可见。
- 新项目没有复制 Task/Attempt/Artifact/Outbox 状态机，也没有为这些资源实现新的全系统 `ExecutionContext` 或 RLS。当前 Compose 复用旧应用镜像，因此不能宣称批次 2 已完成。
- `worker_enrollment` 专用数据库角色现对 `agents` 与 `agent_sessions` 启用 RLS；授权/撤销事务将已验证的 tenant/project/team 写入事务局部 PostgreSQL 设置，策略缺 scope 默认拒绝，Agent 关联约束 Session 行，列级授权不允许读取 Token Hash。PostgreSQL 集成测试覆盖同一池连接跨 Tenant 复用、跨 scope 插入拒绝、事务结束后 scope 清除和角色无 `BYPASSRLS`。这项纵深防御仅覆盖 Enrollment。
- L5 有真实 Keycloak 和 Worker 凭据 Secret 引用，但 issuer/JWKS 使用 HTTP，当前新 Compose 强制 HTTPS；现有本机 Kubernetes context 只允许读取 Pod，不能读取 Secret/ConfigMap。L5 不作为本轮隔离验收环境，且其运行服务未被改动。

## 验收批次

1. **2A scope 闭环：** 为每个资源标明真实表、API、事件和消费者；覆盖跨 Tenant/Project/Team ID 猜测、缺失/伪造 scope、撤销成员与后台 Job。
2. **2B Attempt 隔离：** 真实 Worker 在固定 Tenant/Project/Team 内连续执行多个 Task/Attempt，并以独立 Worker 并发覆盖 A/B Tenant；检查 Conversation、Memory、Workspace、Tool session、缓存和 Artifact，且验证跨 Tenant Assignment 被拒绝；终态、取消、失败与租约过期均检查清理。
3. **2C RLS：** 连接池复用同一数据库连接跨 Tenant 操作；证明事务 scope 重置、缺 scope 拒绝、写入 `WITH CHECK` 生效，以及应用角色不能绕过 RLS。

批次 2 通过前，部署档位只能标为单 Tenant/受信 Worker 试验，不得宣称多租户强隔离或生产就绪。
