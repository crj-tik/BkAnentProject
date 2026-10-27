# 版本化鉴权发布与回滚

本文约束 `unify-versioned-permission-context` 的开发/生产发布。代码测试与清单 dry-run 不能代替真实 MySQL/Redis、跨服务或浏览器验收；任务 11.1、11.4、8.3–8.5 未通过前，不得启用对应的新授权开关。

## 开发 Compose 配置

Compose 从仓库根目录 `.env` 读取运行值，并只将所需值传给对应服务：`AUTH_AUTHORIZATION_ENABLED`、`AUTHZ_REDIS_WRITER_PASSWORD` 与 `AUTHZ_REDIS_TLS` 传给 Auth；`AGENT_AUTHORIZATION_ENABLED`、`AUTHZ_REDIS_READER_PASSWORD` 与 `AUTHZ_REDIS_TLS` 传给 Agent。`.env.example` 只列变量名和安全默认值，不放密码。

`nacos/auth-service.yaml` 与 `nacos/agent-service.yaml` 保存 `${...}` 占位符，由应用容器环境在运行时解析；发布到 Nacos 的内容不得替换成密码明文。Auth 总开关为 `auth.authorization.enabled`，Agent 总开关为 `agent.authorization.enabled`，两者默认关闭。密码和 TLS 变量只提供连接参数，不会自动开启 Redis 授权存储或宣告 ACL 已配置：`auth.authorization.redis.enabled`、`auth.authorization.redis.protected-writer-configured` 与 `agent.authorization.redis.protected-reader-configured` 仍须在对应 ACL 已创建、验证后显式配置。缺少 ACL、服务身份或调用方登记时保持关闭，避免服务启动或请求因不完整配置失败。

## 发布制品组

Auth、Gateway、Agent/Supervisor、`common` 鉴权契约、受保护的 A2A/MCP/Memory/RAG 消费端，以及本次启用的业务域服务必须使用同一兼容版本组发布。新协议密钥、公钥登记、Redis ACL、服务调用方/操作 ACL 与 MySQL schema 是发布组的一部分。禁止单独升级 Auth 后让旧消费者访问新接口，也禁止单独回退消费者而继续接受它不能验证的签名上下文。

当前受管迁移包括 Auth 的版本化权限与执行绑定事实，以及有持久 DataSource 的 A2A provider 数据库：Interview、Listing、Marketing、Notification、Business、Contract、Settlement。Compare 和 Media 当前没有受管关系型 DataSource；其版本化 A2A 授权不能因共用 library 而启用，必须在耐久 owner/nonce store 接入并验收后另行纳入发布组。

## 启用顺序

1. 固定候选制品 digest、配置版本、协议 key ID 和回滚目标；秘密只放在受管 secret store，不复制到发布记录。
2. 在授权的数据库环境中对完整 manifest 运行 dry-run，确认 SQL/check 的规范 SHA-256、目标库和版本都有效；再分别验 fresh、upgrade、repeat 和 schema check。应用前保留受限备份与行数/版本基线，不读出用户正文或票据原值。
3. 先应用数据库迁移。Auth 执行绑定和权限 outbox、各 provider 的 nonce/task owner/reservation 表均只增量创建；不得通过手工删表或重建库处理失败。
4. 发布完整兼容制品组及验证密钥/ACL，功能开关保持关闭。确认每个启用 consumer 对签名上下文、请求 proof、资源 owner 与权限版本都有本地验证后，再按域逐项启用并记录结果。
5. 监控 Auth freshness/outbox、拒绝原因、A2A owner 冲突及副作用完成状态。任何存储或密钥前置条件缺失都保持该域拒绝，不降级成匿名、旧宽权限或固定 marker 放行。

## 回滚顺序

1. **先止新流量**：在 Gateway/服务入口暂停受影响的新操作，排空有副作用的在途请求，并记录尚未完成的 run、A2A task、审批与 permit。不能只关闭某个版本化开关；关闭后的兼容行为必须先证明仍满足同等或更严格的 owner、签名和资源检查，否则继续拒绝该能力。
2. **成组回退应用制品**：只部署已登记且通过相同安全边界验证的 Auth、Gateway、Agent/common 与已启用消费者制品组。若没有这样的兼容制品组，保持入口关闭，不能回退到审计旁路、匿名路径、模型可见票据或旧宽权限实现。
3. **保留数据库事实**：鉴权迁移是 forward-only，没有 down migration。保留 `auth_authorization_migration`、角色/权限、授权版本、业务身份映射、Auth outbox 与执行绑定，以及每个 A2A provider 的 nonce、task owner 和 reservation 表。保留原有 permit、审批/幂等账本、session/run/checkpoint、任务和副作用回执；不清空缓存来“复位”版本，不删除或重放 outbox，不手工释放其他请求的 reservation。
4. **保留凭据验证能力**：不恢复 query-ticket，也不把 ticket/token/proof 写回模型上下文。若回退制品不能继续识别已签上下文，先让旧请求在原硬截止内完成或拒绝续接，并由原 owner 重新认证。旧/新验签公钥在所有相应上下文、permit 和短期票据过期或已撤销前保持可验证；票据原值不落库。
5. **核对恢复结果**：只核对计数、版本和状态，不输出敏感正文。确认 outbox 可继续发布、版本没有倒退；原 owner 仍能读取其任务状态，其他 actor 仍被拒绝；未确认副作用不自动重放，审批与 permit 仍绑定原 actor/资源/hash/截止；未迁移域继续关闭。

回滚目标、制品 digest、配置版本、停流时间、未完成副作用清单、数据库备份点及核对结果必须留在受限运维记录。不得将本地 fixture、源码默认关闭或 manifest dry-run 记作真实回滚演练。
