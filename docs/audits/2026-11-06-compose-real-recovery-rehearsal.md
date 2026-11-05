# Compose 真实 POC 备份恢复演练

**范围：** 本机 `agentteams-new-validation` Compose 项目；仅用于容量恢复阶段的技术演练，不代表生产灾备验收。

## 发现与修复

首次使用真实 POC bundle 恢复到全新 Compose 项目时，`pg_restore` 因 RLS policy 引用的 `agentteams_runtime` 角色尚不存在而失败。PostgreSQL custom dump 不包含集群级角色。恢复流程现增加 `roles.sql`：从 `pg_dumpall --roles-only` 导出应用角色，过滤 PostgreSQL/初始化角色，并删除所有 `PASSWORD` 属性，随后在目标实例先恢复角色、再恢复数据库。运行账号密码由部署 `.env` 重新注入，不会随备份传播。

集成测试增加角色、RLS policy 与授权的真实恢复断言：角色属性为非 superuser、无 `BYPASSRLS`、无 password hash，policy 和业务行均存在。

## 真实 POC 证据

- 源项目：`agentteams-new-validation`；备份前确认 `flyway_schema_history` 最新版本为 `121`，active attempts、active leases、active workers 均为 `0`。
- PostgreSQL 表计数（agents、sessions、attempts、assignments、leases、model credentials）均为 `0`。
- NATS 有四个持久 stream；`TASK_EVENTS` 含 `1` 条消息（116 B），其余 stream 为 0。
- 备份成功生成 `postgres.dump`、无密码的 `roles.sql` 和四个 NATS stream 快照；restore 校验 manifest 与数据文件 SHA-256 全部通过。
- 恢复目标 `agentteams-recovery-20261106-v2` 保持应用未启动；数据库迁移版本为 `121`，上述六类行数与源一致，4 个 stream 均存在且消息数与源一致。
- 演练后目标 Compose project/volumes 已清理；源项目 Control Plane、Credential Proxy、Gateway、PostgreSQL 和 NATS 均恢复为运行状态。

## 边界

该环境没有 Worker、活动任务或 Artifact 对象；本次只证明 PostgreSQL、全局角色、JetStream stream 的 POC 恢复路径。SeaweedFS 的对象恢复由现有隔离集成测试覆盖，本次真实 POC 的 `AGENTTEAMS_STORAGE_ENABLED=false`。跨主机恢复、真实 Worker Attempt、运行中的任务协调、外部生产者静默和生产容量目标仍待验收。
