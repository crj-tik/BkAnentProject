# 现状基线盘点（任务 1.3）

> 依据仓库 SQL、代码与配置的静态核对编制；未连接实际数据库，不证明目标环境已执行迁移或数据为空/非空；未读取任何秘密（`.env` 不读）。

## 1. 现有表结构

### bk_listing（[bootstrap SQL](../../sql/migrations/20261013_mysql_init_bootstrap.sql#L69-L103)、[RAG SQL](../../sql/migrations/20261012_listing_rag.sql)）

| 表 | 关键结构 | 现状要点 |
|---|---|---|
| `listing_info` | `id` 自增 PK；`broker_id` 可空；`total_price DECIMAL(12,2) NOT NULL`（万元）；`owner_name`/`certificate_no` 可空文本；`image_urls`/`floor_plan_urls`/`video_urls` TEXT 逗号拼接；`status`/`ocr_status`/`verification_status` 字符串；逻辑删除 `deleted` | 无 `owner_customer_id`、无区域/小区字段、无元价格、无并发版本；仅 broker_id/status/verification_status/certificate_no 普通索引，无业务唯一约束、无外键 |
| `listing_rag_revision` | `listing_id` PK，递增 `revision`、`tombstone`、`content_hash`、`latest_event_id` | RAG 索引版本，与业务快照 hash 不是同一机制 |
| `listing_rag_outbox` | `event_id` PK，`(listing_id,revision)` 唯一，投递状态/重试/租约 | dispatcher 默认关闭（`LISTING_RAG_DISPATCHER_ENABLED:false`），outbox 写入≠索引已更新 |
| `listing_rag_grant` | `(actor_user_id,resource_id,operation)` PK | 当前 GrantService 采用领域业务基线，不逐房源授权；勿当作卖方/买方关系表 |

### bk_customer（[bootstrap SQL](../../sql/migrations/20261013_mysql_init_bootstrap.sql#L105-L182)）

| 表 | 关键结构 | 现状要点 |
|---|---|---|
| `customer_profile` | `profile_type`(CUSTOMER/OWNER)、`name`/`mobile` 必填、预算 `budget_min/max DECIMAL(12,2)`、面积偏好、`broker_id` | 手机号仅普通索引无唯一；预算单位未标注，匹配代码直接与万元 `totalPrice` 比较 |
| `customer_follow_record` | `customer_id`、`broker_id`、`follow_type`(PHONE/WECHAT/SHOWING/VISIT)、`content` | 无 `listing_id`、无 `inquiry_id`；不能结构化表达"跟进针对哪套房/哪次咨询" |
| `owner_entrust_record` | `customer_id NOT NULL`、`listing_id NOT NULL`、`contract_no NOT NULL`、状态 ACTIVE/EXPIRED/COMPLETED | 代码对非空 `listingId` 直接拒绝（[实现](../../customer-service/src/main/java/com/bkanent/customer/service/impl/CustomerManagementServiceImpl.java#L200)），与 DDL `NOT NULL` 冲突；本期不修不依赖 |
| `customer_favorite_listing` | `customer_id`、`listing_id`、`favorite_source`、`remark` | 无 `(customer_id,listing_id)` 唯一约束；应用查后写在并发下可能重复 |

## 2. 身份与版本机制

- 内部房源 ID：`listing_info.id`（BIGINT 自增）；业务快照身份 `ListingRef("legacy-local", 数字字符串)`（[ListingRef](../../common/src/main/java/com/bkanent/common/listing/ListingRef.java#L6)，sourceId ≤255 字符）。
- 业务快照 revision：无外部 revision 时由规范化事实 SHA-256 生成（[ListingSnapshotFactory](../../common/src/main/java/com/bkanent/common/listing/ListingSnapshotFactory.java#L50-L82)）；hash 输入含 sourceSystem/sourceId/dataMode/datasetId/facts，改名或改模式会改变版本。
- RAG revision：按房源 ID 递增整数 + tombstone（[ListingRagOutbox](../../listing-master-service/src/main/java/com/bkanent/listing/rag/ListingRagOutbox.java#L37-L63)）；迁移须保留墓碑与未投递事件（[RAG SQL:1-4](../../sql/migrations/20261012_listing_rag.sql#L1-L4)）。
- 两套 revision 不可互换；本变更新增的乐观锁 `version` 是第三种（并发控制），亦不替代前两者。

## 3. 价格与单位

- `listing_info.total_price` 与 `ListingDTO.totalPrice` 为**万元**（[ListingDTO](../../common/src/main/java/com/bkanent/common/model/ListingDTO.java#L19-L22)、[LR-54](../../docs/logic-rationale.md#L528-L536)）。
- v1 快照 `facts.totalPrice` 为 CNY/YUAN，边界 ×10000 恰好一次（[LegacyLocalListingSourceAdapter](../../listing-master-service/src/main/java/com/bkanent/listing/source/LegacyLocalListingSourceAdapter.java#L158-L167)）。
- 客户 `budget_min/max` 单位未标注；匹配直接比较（[匹配实现](../../customer-service/src/main/java/com/bkanent/customer/service/impl/CustomerManagementServiceImpl.java#L374-L411)），实际输入须与房源同为万元；迁元前不得无证据换算。

## 4. 业主关联缺口

- `owner_name` 为可空自由文本，无 `owner_customer_id`；不能按姓名/手机号/经纪人自动回填。
- `owner_entrust_record` 因上述代码/DDL 冲突不可作为关联依据；存量委托记录仅是待核验候选。

## 5. 素材与敏感资料

- 图片/户型图/视频逗号拼接 TEXT；`property_certificate_url`/`contract_url` 单 URL；无逐项行、无可见性分级、无排序、无状态。
- 详情接口当前会返回业主、证号与证件 URL（[前端契约草稿](../../docs/contracts/listing-customer-frontend-draft.md#L23)）；授权开关默认关闭（`LISTING_AUTHORIZATION_ENABLED:false`），运行态取决于部署。

## 6. 模拟核验现状

- OCR 直接置 DONE 并填 `OCR-{id}`/`OCR识别业主{id}` 占位（[实现](../../listing-master-service/src/main/java/com/bkanent/listing/service/impl/ListingManagementServiceImpl.java#L269-L287)）。
- 核验仅检查证号与业主名非空，默认来源 `mock-third-party`（[实现](../../listing-master-service/src/main/java/com/bkanent/listing/service/impl/ListingManagementServiceImpl.java#L293-L316)）；`VERIFIED` 不代表真实权属核验，占位值也可能通过。

## 7. 比较报告 / Studio / 营销历史

- 比较报告：`*.listing.json` + PDF 存于 `/app/runtime/reports`（`compare-report-data` 卷），owner + 来源版本指纹隔离；读取前复核 owner、逐套读取权与来源版本（[ListingComparisonService](../../listing-master-service/src/main/java/com/bkanent/listing/comparison/ListingComparisonService.java#L86-L99)）。
- 旧 Long ID 仅映射 `legacy-local`（[ListingLegacyReferenceMapper](../../listing-master-service/src/main/java/com/bkanent/listing/comparison/ListingLegacyReferenceMapper.java#L13-L21)）。
- Studio 工件保存 `sources_json`/`content_json` 并按 revision/hash 复核（[StudioArtifactsService](../../agent-service/src/main/java/com/bkanent/agent/studio/StudioArtifactsService.java#L90-L119)）。
- 营销 Campaign 绑定房源快照引用/版本/hash/模式并在确认时复核（[CampaignLifecycleService](../../marketing-content-service/src/main/java/com/bkanent/marketing/campaign/CampaignLifecycleService.java#L397-L410)）。

## 8. 内部消费者与已知不兼容

| 消费者 | 现状 | 已知问题 |
|---|---|---|
| Customer 匹配/收藏 | `ListingReadClient` 签名 RPC 读取摘要；收藏仅存 `listingId`，无房源存在性/读取权校验 | 委托链路冲突（见 §1）；KI-243 经纪人映射未建 |
| 营销 | 摘要走 `MarketingListingReadClient`；Campaign 真实模式请求 `listing-master-service/<id>` 并要求 `REAL` | 与默认 `legacy-local`/`UNKNOWN` 不兼容（[CampaignListingSnapshotClient](../../marketing-content-service/src/main/java/com/bkanent/marketing/campaign/CampaignListingSnapshotClient.java#L69-L103)）；测试 mock 掩盖 |
| 平台经营 | 复盘经 v1 HTTP search/check 读取快照 | check 请求平铺 `{sourceSystem,sourceId,expectedRevision}` 与契约嵌套 `listingRef` 不符；读取 `facts.status` 但快照在 `facts.attributes.status`（[DomainParallelHttpReadAdapter](../../platform-operations-service/src/main/java/com/bkanent/businessreview/domain/DomainParallelHttpReadAdapter.java#L104-L144)） |
| Studio | 业务 RPC + 稳定引用/版本 | 无 |
| 比较 | 2–6 引用 + expectedRevision；旧 `/compare/**` 兼容 | 旧响应 columns 声明 area/unitPrice 与新行字段名不一致（[LegacyController](../../listing-master-service/src/main/java/com/bkanent/listing/controller/ListingComparisonLegacyController.java#L31)） |
| RAG | outbox + revision/tombstone 复核 | KI-56 OPEN：真实 MySQL/ES/Milvus、回填水位未验收 |

## 9. 来源 provider 配置

- 默认 `LISTING_SOURCE_PROVIDER=legacy-local`（[application.yml](../../listing-master-service/src/main/resources/application.yml#L57-L67)、[Nacos](../../nacos/listing-master-service.yaml#L101-L117)、[Compose](../../docker-compose.yml#L639-L647)、[.env.example](../../.env.example#L231-L239)）。
- HTTP provider 配置项存在（base-url/bearer-token/source-system/confirmed-data-mode/超时）；fixture broker 默认 1。
- 本变更任务 5.1 将正式配置固定 `legacy-local` 并退役 HTTP provider。

## 10. 初始化与迁移约束

- 清单：[init-manifest.tsv](../../docker/mysql/init-manifest.tsv)，runner 校验全清单顺序与 LF SHA256（[runner](../../scripts/deploy/run-init-manifest.sh#L21-L43)）。
- **runner 目标库白名单无 `bk_customer`**（[runner:30-38](../../scripts/deploy/run-init-manifest.sh#L30-L38)）：Customer 迁移需先扩白名单及测试。
- MySQL 8.4：不可用 `ADD COLUMN IF NOT EXISTS`（[KI 记录](../../docs/known-issues.md#L322)），需 information_schema 条件 DDL（先例 [20261026_marketing_content_owner.sql](../../sql/migrations/20261026_marketing_content_owner.sql#L4-L26)）。
- fresh 模式执行 fresh+upgrade；upgrade 模式跳过 fresh——存量库不能靠 upgrade 自动建 `listing_info`。
- 已应用 SQL/check 不可原地修改，只能追加新版本。

## 11. 相关已知问题

- KI-56（OPEN）：RAG 真实后端/回填/切流未验收。
- KI-115（FIXED）：旧比较读取修复，不证明真实上游验收。
- KI-236（OPEN）：网关版本化授权部分路由缺受众映射。
- KI-243（OPEN）：客户授权入口与经纪人映射未闭环；运行态请求可能被正确拒绝。
- KI-206（OPEN）：经纪人 ID≠通知用户 ID，委托提醒跳过。

## 12. 盘点结论（迁移输入）

1. 新增列/表全部可增量实施：`owner_customer_id`、区域/小区、`asking_price_yuan`、`version` 对存量可空。
2. 旧万元→元回填需精确 ×10000 并对账；客户预算单位先盘点后迁移。
3. 存量 `owner_name`/委托记录/模拟核验一律标待确认，不自动关联、不改标 REAL。
4. 素材迁移按 URL 逐条受控登记为待核对行，不主动抓取。
5. 消费者联调（营销 REAL 不兼容、经营 check 形状）在切换前必须以真实生产者契约修复。
6. `bk_customer` 迁移前先扩 runner 白名单；所有新迁移走清单追加。
