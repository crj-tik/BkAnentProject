# Business Review Auth 与 Memory 注册交接

适用分支：`codex/implement-store-business-review`。本文件供协调会话合并 Auth、Memory 和房源/知识来源生产者登记。本服务不修改 `auth-service/**`、`common/**` 或 `business-service/**`。

## 服务身份

| 项 | 值 |
| --- | --- |
| 服务 / audience | `business-review-service` |
| 服务身份 key ID | `business-review-service-current` |
| Memory domain | `business-review` |
| A2A agent | `business-review-agent` |
| Capability | `a2a:business-review-agent` → `agent.workflow.invoke.business-review` |
| 数据库 | `bk_business_review` |
| 路由 / 端口 | `/business-review/**` / `9016` |
| Nacos Data ID | `business-review-service.yaml` |

## Auth 注册和失败关闭门槛

Auth 需要登记并按最小权限授予以下六个精确操作：

| 用途 | 操作码 |
| --- | --- |
| 创建事项 | `business.review.create.store` |
| 读取事项与列表 | `business.review.read.store` |
| 保存计划版本 | `business.review.plan.write.store` |
| 确认计划版本 | `business.review.plan.confirm.store` |
| 记录人工动作反馈 | `business.review.action.feedback.store` |
| 读取观察指标 | `business.review.observation.read.store` |

Auth permission-catalog 的最小迁移数据如下；由 Auth 会话按当前 migration/outbox/writer-lock 约定合并，不在本 change 中直接修改 Auth：

```sql
INSERT INTO auth_permission (permission_code, domain, operation, active, version)
SELECT permissions.permission_code, permissions.domain, permissions.operation, 1, 1 FROM (
    SELECT 'agent.workflow.invoke.business-review' permission_code, 'agent' domain, 'workflow.invoke.business-review' operation
    UNION ALL SELECT 'business.review.create.store', 'business', 'review.create.store'
    UNION ALL SELECT 'business.review.read.store', 'business', 'review.read.store'
    UNION ALL SELECT 'business.review.plan.write.store', 'business', 'review.plan.write.store'
    UNION ALL SELECT 'business.review.plan.confirm.store', 'business', 'review.plan.confirm.store'
    UNION ALL SELECT 'business.review.action.feedback.store', 'business', 'review.action.feedback.store'
    UNION ALL SELECT 'business.review.observation.read.store', 'business', 'review.observation.read.store'
) permissions
WHERE @business_review_authorization_migration
ON DUPLICATE KEY UPDATE active = 1, version = version + 1;
```

此片段只登记权限目录，不给普通角色自动授权。Auth 还必须在代码与签名契约中显式接受 `STORE` 身份类型，读取 Auth 已验证的用户—门店映射，并将单个 `businessIdentities.STORE` 写入签名上下文；不得把现有 `BROKER`、`userId` 或请求参数转换成门店 ID。领域服务只接受精确的六个 `business.review.*.store` 操作，并再次检查事项 owner 与签名 STORE 一致。Agent capability 授权本身不能替代该领域门禁。

签名 Auth context 还必须携带可信 `businessIdentities.STORE = storeId`，由 Auth 维护的用户—门店关系产生。请求正文中的 `storeId` 只作一致性断言；`employeeId`、事项 ID、模型文本、Gateway 参数和 Memory 内容都不能替代 STORE 身份。

当前 Auth/common 生产身份模型仍只有已落地的 `BROKER` 映射，因此 `VerifiedStoreScopeAdapter` 对缺少 STORE 的上下文拒绝请求。不得把 userId 当 storeId、从 `employeeId` 推断门店，或为本地演示关闭授权。合并 Auth 的 STORE 类型与可信映射、操作登记和授权刷新后，再进行跨服务验收。

## domain-parallel-v1 HTTP 委托证明

business-review consumer 对 Listing 和 Knowledge 的每次 POST 都发送 `X-Bkanent-Domain-Authorization`。Header 内容沿用当前 `DelegatedAuthorizationRequest`：Auth 签署的 context 与 freshness、本次操作的 `DelegationBinding`、以及 business-review 服务身份对 `CallerProof` 的 Ed25519 签名。它绑定真实请求体 SHA-256、目标 audience、唯一 nonce、短期限和精确业务操作；`requestId` 采用 `dp1|POST|<实际路径>|<随机 UUID>`，生产者必须核对其中方法和路径与收到的 HTTP 请求相同。

| 目标 audience | 路径 | 业务操作 |
| --- | --- | --- |
| `listing-master-service` | `/listings/business/v1/search` | 调用 API 时已验证的一个 `business.review.*.store` 操作 |
| `listing-master-service` | `/listings/business/v1/snapshots/check` | 调用 API 时已验证的一个 `business.review.*.store` 操作 |
| `knowledge-service` | `/knowledge/v1/published/search` | 调用 API 时已验证的一个 `business.review.*.store` 操作 |
| `knowledge-service` | `/knowledge/v1/published/check` | 调用 API 时已验证的一个 `business.review.*.store` 操作 |

来源服务须在返回正文前验证 Auth context/freshness、caller proof、key ID `business-review-service-current`、目标 audience、操作、body hash、`requestId` 中的方法/路径、签名 STORE 和短期限，并消费 nonce 防重放。来源请求不转发用户 Bearer，也不在每次工具调用上远程访问 Auth。来源端登记 `BUSINESS_REVIEW_SERVICE_IDENTITY_PUBLIC_KEY` 后才可开放；缺失或无效证明必须返回认证拒绝，不能返回空匹配。

本分支已实现并以本地 HTTP fixture 验证 consumer 签名与 header 绑定。房源/知识生产者应按上述请求证明合同接入自己的入口；并行实现合并前不要把 fixture 结果视作生产互通证据。

## Memory 注册

`nacos/memory-service.yaml` 新增的 trusted caller 条目为：

```yaml
business-review-service:
  public-keys:
    business-review-service-current: ${BUSINESS_REVIEW_SERVICE_IDENTITY_PUBLIC_KEY:}
  service-delivery: true
  experience-outcome-publisher: false
  allowed-agents-by-domain:
    business-review: [business-review-agent]
```

密钥生成器登记 `business-review-service`，其公钥变量由本地 keygen 写入忽略文件 `.secrets/service-identities/public-keys.env`；私钥只挂载到该服务的只读 secret 路径。生产 Memory 配置必须注入对应公钥。`A2aMemoryHookFactory.create("business-review-service", "business-review", "business-review-agent", chatModel)` 是必需启动依赖；Memory 调用拒绝或密钥/Agent/domain 未登记时，不允许绕过 Hook 调用模型或业务工具。

只允许 `business-review-agent` 访问 `business-review` 私域。不得把 trade-agent 历史迁移或复用到新 domain，不增加 Memory RAG 投影、experience outcome publisher 或来自模拟/人工回报的生产经营有效性结论。Memory 只保存允许的 Agent 上下文，不是真实经营事实来源。

## 配置同步

服务模板、Nacos 和 Compose 使用相同的服务配置项。`.env.example` 提供可调非敏感值；本地 `.env` 若已存在，仅补缺失项并保留用户值和秘密。

| 配置项 | 默认值 / 来源 |
| --- | --- |
| `MYSQL_DB_BUSINESS_REVIEW` | `bk_business_review` |
| `BUSINESS_REVIEW_MEMORY_V2_COMPRESSION_ENABLED` | `false` |
| `BUSINESS_REVIEW_AGENT_MODEL` | `gpt-5.6-terra` |
| `BUSINESS_REVIEW_SOURCE_MODE` | `http`；测试或显式演示才选 `fixture` |
| `BUSINESS_REVIEW_LISTING_BASE_URL` | `http://listing-master-service:9004` |
| `BUSINESS_REVIEW_KNOWLEDGE_BASE_URL` | `http://knowledge-service:9015` |
| `BUSINESS_REVIEW_AUTHORIZATION_ENABLED` | `true` |
| `BUSINESS_REVIEW_A2A_AUTHORIZATION_ENABLED` | `true` |
| `BUSINESS_REVIEW_SERVICE_IDENTITY_PUBLIC_KEY` | keygen 生成的忽略文件，经 Nacos 注入 |

旧 `BUSINESS_MEMORY_V2_COMPRESSION_ENABLED`、`BUSINESS_MEMORY_EXPERIENCE_OUTCOME_PUBLISHER`、`business-service` 配置、旧数据库及 `trade` Memory 身份保持原样。
