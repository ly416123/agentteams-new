# AgentTeams New 领域词汇

本文件约定多租户任务执行路径中的核心概念，避免 API、数据库和运行时使用不同含义描述同一授权边界。

## 组织与身份

**Tenant（租户）：** 数据与授权隔离的顶层边界。租户标识必须由已验证的主体或服务端关系得出。
_Avoid：_ Account、Organization（除非指独立的组织实体）

**Project（项目）：** 租户内承载资源、成员与任务的授权范围。
_Avoid：_ Workspace（此词专用于执行文件目录）

**Team（团队）：** 项目内组织 Agent 成员与协作权限的范围。
_Avoid：_ Worker Group（部署槽位集合不是业务团队）

**Principal（主体）：** 经过身份验证、并通过成员关系获得 Tenant/Project/Team 权限的人或服务身份。
_Avoid：_ User（当主体也可能是服务身份时）

**Agent：** 在控制面注册、具有稳定 ID 和业务 scope 的可执行身份。一个 Worker 进程通过凭据认证后代表某个 Agent 连接 Gateway。
_Avoid：_ Worker（Agent 是业务身份，Worker 是运行该身份的进程/槽位）

## 任务执行

**Task（任务）：** 在项目/团队授权范围内提交的一项持久化业务工作。
_Avoid：_ Job（保留给后台调度工作）

**Attempt（尝试）：** Task 的一次具体执行；每次重试都创建新的 Attempt 身份。
_Avoid：_ Run（除非引用某个 API 中已有的 Run 实体）

**Lease（租约）：** 为一个 Agent/Attempt 分配的、有期限且可续约的执行权。
_Avoid：_ Session（租约是调度权，不是会话或认证凭据）

**Worker Session（Worker 会话凭据）：** Agent 连接 Gateway 时使用的可过期、可撤销认证凭据；持久层只存其哈希。
_Avoid：_ Attempt、Conversation

**ExecutionContext（执行上下文）：** 服务端根据已验证主体、授权关系和当前 Assignment 构造的不可变 Tenant/Project/Team/Agent/Task/Attempt 范围。客户端或 Worker 自报的 scope 不构成授权依据。
_Avoid：_ Request Context（容易与普通 HTTP 请求上下文混淆）

**Conversation（对话）：** 经授权后可关联到 Task 的交互历史；其可见范围不得超出所属 Tenant/Project，执行时的临时对话状态按 Attempt 隔离。
_Avoid：_ Agent Memory（持久记忆是单独资源）

**Workspace（工作区）：** 单次 Attempt 可读写的文件系统目录及其子进程临时文件范围。
_Avoid：_ Project Workspace（除非明确指持久项目文件）
