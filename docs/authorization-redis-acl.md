# 授权 Redis ACL 运维

Auth 授权快照使用独立 Redis ACL 身份，与 token 撤销、限流和其他业务 Redis 连接分开。Redis 服务端 ACL 是隔离边界；应用中的独立连接和只读代码不能替代 ACL。此功能默认关闭，未配置服务端规则、独立凭据和传输保护前不得启用。

## 权限范围

`authz-writer` 只允许执行当前 publisher 与活跃用户索引脚本所需的逐项命令，读写 `authz` 命名空间中 head、snapshot、fence 和 active actor 索引，并向 `authz:versions` 发布通知。该身份不允许访问其他 key/channel，不允许 `SPUBLISH`、管理用户、改变配置或执行任意 keyspace 命令。

`agent-authz-reader` 只允许对 `authz:*:head` 执行 `GET`/`MGET`，并订阅/取消订阅 `authz:versions`；不能读 snapshot/fence/index，不能写 key、改 TTL、发布消息、订阅其他 channel 或使用 pattern subscription。连接握手所需的 `PING` 与 `CLIENT SETNAME`/`SETINFO` 不提供数据访问。其他消费者需各自申请同等收窄的只读身份，不共享 Auth writer。

策略要求 Redis 7.2 及以上，以支持只读 key pattern `%R~` / `%RW~`、`ACL DRYRUN` 以及客户端连接元数据命令。Redis ACL 对命令、key、Pub/Sub channel 分别授权；规则采用 `-@all` 后逐项放行，避免因读写命令分类变化意外扩大权限。项目 Compose 基础镜像为 Redis 7.4。托管 Redis 如不支持 `ACL SETUSER`，应在其控制面创建同等规则；脚本会失败，不会改动 `default` 或其他应用用户。

## 配置 ACL

在安全运维终端提供以下环境变量，密码由密钥管理器注入，不写入脚本参数、命令历史、日志或仓库：

- `REDIS_HOST`、`REDIS_PORT`、`REDIS_ADMIN_USER`、`REDIS_ADMIN_PASSWORD`
- `AUTHZ_WRITER_PASSWORD`、`AUTHZ_READER_PASSWORD`：分别使用不同的 32 字节随机值并以 64 位十六进制编码
- 可选 `AUTHZ_NAMESPACE`、`AUTHZ_CHANNEL`，默认 `authz` 与 `authz:versions`
- 远端连接要求 `REDIS_TLS=true`；自签 CA 可用 `REDIS_TLS_CA_CERT` 指定。明文仅允许显式 opt-in 的 loopback 验证

确认现有 Auth 与 Agent 授权开关仍关闭，然后在 Bash 环境运行：

```bash
bash scripts/security/configure-authorization-redis-acl.sh
```

脚本只重置并创建上述两个专用 ACL 用户，不会关闭或重写共享 Redis 的 `default` 账号，也不触碰 token 撤销及其他业务账号。它随后执行 ACL 正反例和一次无敏感内容的 Pub/Sub 收发检查。执行前应确认旧授权链没有使用这两个专用账号；应用仍保持关闭，直到配置完整并完成部署审查。

单独复核当前 ACL 可运行：

```bash
bash scripts/security/verify-authorization-redis-acl.sh
```

## 应用侧启用门禁

服务凭据放入密钥管理器或受保护环境变量，不进入模型配置或普通日志。Auth 使用 `authz-writer`，并要求 `auth.authorization.redis.protected-writer-configured=true`；Agent 使用独立 `agent-authz-reader`，并要求 `agent.authorization.redis.protected-reader-configured=true`。两侧的 `namespace` 和 `channel` 必须与 ACL 一致。分布式 profile 必须启用 TLS。错误身份、缺少显式 ACL 配置确认、缺密码或不安全传输会在建立连接前失败；授权功能本身仍需由各自 `*.authorization.enabled` 显式启用。

Auth/Agent 版本化授权与 Redis ACL 未在本次代码修改中部署或打开。真实 MySQL/Redis 并发、发布延迟和失效窗口验收仍由 OpenSpec 任务 11.1/11.4 执行并记录。
