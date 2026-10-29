# 房源比较收敛与旧模块退役

状态：已收敛到 `listing-master-service`。本文件记录最终运行边界，不再描述 Compare 服务的排空或回退步骤。

## 当前功能归属

- 房源搜索、比较、报告历史与 PDF 都由 `listing-master-service` 处理。Supervisor 通过 `a2a:listing-agent` 使用房源及 `listing-comparison` 技能。
- 房源比较以稳定来源引用、revision、content hash 和数据模式为输入；创建、历史读取及 PDF 下载均按当前来源版本和 owner 授权复核。
- 旧 `/compare/**` 用户路径由 Listing 兼容控制器承接，旧请求/响应字段由 Listing 自有 DTO 维持兼容；不再转发到独立 Compare 服务。
- 报告文件保存在 `listing.comparison.report.storage-dir`，开发 Compose 卷名为 `listing-comparison-data`。
- `compare.report.create` 与 `compare.report.read.own` 仍是 Auth 签名上下文中的基线动作标识，不是角色授予；匿名分享不提供。

## 已退役内容

- 独立 `compare-engine-service` Maven 模块、源码、维护测试、`CompareEngineRpcService` 公共 RPC 契约和 Compare 专用报告缓存/目录实现已删除。
- Compare Compose 服务、独立 Nacos data ID、旧 MCP/RPC/A2A caller 信任和 Memory `compare/compare-agent` 空间已移除。
- Auth 目录中的 `compare.report.share.public` 由受管退役迁移停用并清除角色关联。
- 未上线且无历史资产。当前 Compare 报告数据卷和旧 Promotion 数据库按迁移守卫核验为空后退役；不导入旧报告、不恢复旧身份。

## 数据库迁移

`sql/migrations/20261029_legacy_compare_promotion_retirement.sql` 以 manifest 顺序 540 执行。迁移逐表统计 `bk_promotion` 行数，只有确认所有表为空才删除该库；发现任何数据会中止并保留数据库。随后在 Auth writer lock 下停用公开分享权限、移除角色关联、递增授权版本并写入 outbox 与幂等账本。

`docker/mysql/checks/legacy-compare-promotion-retirement.sql` 验证旧 schema 不存在、分享权限已停用且没有角色关联、迁移账本恰好登记一次。初始 bootstrap 不再创建 `bk_promotion`；既有历史 SQL 文件保持摘要不变。

## 配置边界

- `nacos/` 只保留当前服务的 data ID。Compare/Promotion 独立模板已退役；Listing 继续保留 `listing.rag.trusted-callers.media-listing-current`，Media 使用同一 `MEDIA_LISTING_AUTH_SECRET` 签名。
- 本机 Compose 只从忽略的 `.env` 向 Listing 与 Media 注入签名密钥；`.env.example` 只列出空占位符。不要将密钥写入受版本管理的 YAML。
- Memory 的 caller/domain/agent 映射只保留当前受信服务；RAG 模板不再声明 Compare 的五类 Memory 空间。

Listing 来源与存储配置在 `application.yml`、Nacos、Compose 和 `.env.example` 中使用同一组键：`LISTING_SOURCE_PROVIDER`、`LISTING_SOURCE_DATASET_ID`、`LISTING_SOURCE_FIXTURE_BROKER_ID`、`LISTING_SOURCE_HTTP_BASE_URL`、`LISTING_SOURCE_HTTP_BEARER_TOKEN`、`LISTING_SOURCE_HTTP_SYSTEM`、`LISTING_SOURCE_HTTP_CONFIRMED_DATA_MODE`、`LISTING_SOURCE_HTTP_CONNECT_TIMEOUT_MS`、`LISTING_SOURCE_HTTP_READ_TIMEOUT_MS`、`LISTING_AUTHORIZATION_ENABLED`、`LISTING_COMPARISON_STORAGE_DIR`。可选 Memory v2 授权上下文开关为 `MEMORY_V2_AUTHORIZATION_CONTEXT_ENABLED`，默认关闭。

## 回归重点

- Listing 来源快照的版本复核、比较创建、owner-only 报告历史、PDF 读取和服务重启后的文件恢复。
- `/compare/**` 兼容入口的旧 JSON 字段、签名操作校验和 owner 隔离。
- Compose 与 Nacos 模板的同密钥签发/验签配置；空密钥或错误密钥必须失败关闭。
- 迁移 manifest 的校验和、空库守卫、权限停用、幂等重跑，以及非空旧库时不执行删除。
