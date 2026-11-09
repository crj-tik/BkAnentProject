## Context

现有 `bk_listing.listing_info` 保存房源当前值和素材 URL，`bk_customer.customer_profile` 区分 `OWNER` / `CUSTOMER`，但房源没有可靠的业主档案 ID；现有委托链路不能作为关联依据。房源 CRUD 发布 RAG outbox；当前快照引用及报告还使用 `legacy-local`、内容 hash 和数据模式。仓库存在未完成的 `consolidate-listing-comparison-domain`，其外部上游假设被本变更取代；动机和范围见 proposal，行为见本变更的两个 specs。

## Goals / Non-Goals

**Goals:** 在保留现有房源 ID 和消费者兼容的前提下支持内部售卖登记、直接上架、可追溯维护、房源输出和咨询记录；定义能增量迁移的数据模型、跨域边界及验收门禁。

**Non-Goals:** 不建设委托、合同、结算、交易闭环、独立买卖双方登录或外部房源接入；不把人工登记当成真实权属核验；不修改通用 Auth/Memory 协议或将 RAG 索引当作房源真源。

## Decisions

### 1. 身份、真源与跨域登记

`listing_info.id` 是唯一内部房源业务 ID；Listing 拥有房源当前值，Customer 拥有业主/买方档案与咨询。单次新增房源关联一个已有 `OWNER` 档案 `owner_customer_id`，该字段表达经纪人登记的卖方关联，不证明产权、委托或客户登录身份。新登记必须提供；存量允许空并标为待确认，不按姓名、手机号、经纪人 ID 或未经确认的委托记录自动回填。多业主/产权份额以后单独设计。

在登记入口调用 Customer 受权档案验证，确认类型、存在性和当前操作人资源范围；不可用或拒绝时不创建可用房源。业主档案由 Customer 独立维护：它可先存在，随后 Listing 在自身事务内原子写入房源、登记幂等结果、初始状态记录和 RAG outbox。该方案不声称两个数据库构成分布式事务；Customer 校验和 Listing 提交间可能发生撤权或档案变化，校验后重验当前授权证明与有效期，记录验证时间；后续敏感访问仍重新校验。档案删除/撤权后保留历史关联但不因 ID 或旧回执授予访问权；客服端提供显式失联/不可访问结果，不自动删除或重新绑定。

登记命令带 `request_id`，按 `(actor_user_id, operation, request_id)` 去重并绑定规范请求摘要；同内容重试返回同一结果，不同内容冲突。关联以逻辑 ID 跨库保存，不建立跨服务物理外键。相比使用 `owner_entrust_record`，该方案不引入本期被明确排除的合同和委托语义。

### 2. 表设计及关键约束

以下为目标结构，类型基于 MySQL 8.4；沿用 `BIGINT` 自增 ID、`DATETIME` 和现有逻辑删除约定。新增表主键均 `BIGINT`，省略的 `created_at` / `updated_at` 为 `DATETIME NOT NULL`；审计历史按追加记录处理，不以普通编辑接口覆盖或逻辑删除。无可靠材料时保持 NULL/未知。敏感字段和权限不依赖表是否跨库。

**保留并增量修改 `bk_listing.listing_info`**

| 字段 | 类型 / 约束 | 含义 |
|---|---|---|
| `id`、`broker_id` | 沿用现有；新登记 broker 非空 | 内部房源 ID、可信负责经纪人 |
| `owner_customer_id` | `BIGINT NULL`，索引 | Customer 的业主档案 ID；仅存量可空，新登记非空，非产权证明 |
| `title`、`address`、`layout`、`area` | 沿用现有，面积 `DECIMAL(10,2)` | 展示资料；面积单位平方米，保留既有口径，不推测一定是建筑面积 |
| `community_name`、`district_code` | `VARCHAR(128) NULL`、`VARCHAR(32) NULL` | 小区和行政区域筛选 |
| `asking_price_yuan` | `DECIMAL(16,2) NULL` | 新内部挂牌价，CNY 元；回填前存量可空，新房源和切换完成后上架必须非空且大于零 |
| `total_price` | 原 `DECIMAL(12,2) NOT NULL` | 兼容期万元字段；所有写入口由统一领域命令维护，禁止外部直接双写 |
| `status` | 沿用 `VARCHAR(32)` | 既有 `PENDING_REVIEW` 兼容编码改作“待上架”，不代表需要审核；另有 `ON_SALE`、`OFFLINE`、`SOLD` |
| `version` | `BIGINT NOT NULL DEFAULT 0` | 乐观并发版本，任何会影响可见事实或状态的修改递增；与业务快照 hash 和 RAG revision 不等同 |
| `created_at`、`updated_at`、`deleted` | 沿用 | 生命周期与逻辑删除 |

基本信息沿用的 `floor_level`、`decoration`、`school_zone`、`traffic` 继续维护；不可靠字段允许未知。建议索引 `(broker_id, status, deleted, updated_at, id)`、`(status, deleted, updated_at, id)`、`(owner_customer_id, deleted, id)`；价格/面积复合索引以实际执行计划验证。未指定排序的旧接口保留语义，新分页使用 `updated_at DESC, id DESC` 稳定游标。

**`bk_listing.listing_asset`**：`id BIGINT PK`、`listing_id BIGINT NOT NULL`、`asset_type VARCHAR(32) NOT NULL`（图片/户型图/视频/产权证/其他）、`storage_key VARCHAR(512) NOT NULL`、`visibility VARCHAR(16) NOT NULL`（`BUYER`/`INTERNAL`/`SENSITIVE`）、`sort_order INT NOT NULL DEFAULT 0`、`status VARCHAR(16) NOT NULL`、`created_by BIGINT NOT NULL`、时间及 `deleted TINYINT NOT NULL DEFAULT 0`。索引 `(listing_id, asset_type, deleted, sort_order, id)`。仅引用受管文件，不在此表保存公开长期 URL；访问由文件服务签发短期受权凭据或受权代理。旧 `contract_url` 仅作为历史敏感文件兼容，不建设合同业务。URL 转存如果需要服务端发起请求，仅允许 http/https，逐次校验 host 并拒绝 localhost、环回、私有及保留地址；重定向每跳复验，并在连接时防止 DNS 重绑定，否则不发请求。旧资源无需主动抓取时仅受控登记、标待核对。

**`bk_listing.listing_private_info`**：`listing_id BIGINT PK`、`certificate_no_ciphertext VARBINARY(1024) NULL`、`certificate_key_id VARCHAR(128) NULL`、时间；使用受管密钥的认证加密、唯一随机 nonce 和密钥版本，不将明文或密钥写日志。历史 `owner_name` 是待核对文本，不作为身份；敏感资料只由明确的私有详情/文件权限读取。初期不按证号自动去重。

**`bk_listing.listing_price_record`**：`id`、`listing_id BIGINT NOT NULL`、`old_price_yuan DECIMAL(16,2) NULL`、`new_price_yuan DECIMAL(16,2) NOT NULL`、`reason VARCHAR(255) NULL`、`operator_user_id BIGINT NOT NULL`、`created_at`、`request_id VARCHAR(128) NOT NULL`；索引 `(listing_id, created_at, id)`，每房源及操作幂等键去重。首次登记 old 为 NULL。历史回填只能写可证实的迁移基线，不伪称旧价格发生时间或操作者。

**`bk_listing.listing_status_record`**：`id`、`listing_id BIGINT NOT NULL`、`from_status VARCHAR(32) NULL`、`to_status VARCHAR(32) NOT NULL`、`action VARCHAR(32) NOT NULL`、`reason VARCHAR(255) NULL`、`operator_user_id BIGINT NOT NULL`、`created_at`、`request_id VARCHAR(128) NOT NULL`；索引 `(listing_id, created_at, id)` 并对幂等键去重。基线迁移使用显式 `MIGRATION_BASELINE` 及系统操作身份，不伪造审核或历史流转。

**`bk_customer.customer_listing_inquiry`**：`id`、`customer_id BIGINT NOT NULL`、`listing_id BIGINT NOT NULL`、`broker_id BIGINT NOT NULL`、`inquiry_channel VARCHAR(32) NULL`、`inquiry_content TEXT NULL`、`status VARCHAR(32) NOT NULL`、`validation_state VARCHAR(24) NOT NULL`、`checked_listing_version VARCHAR(255) NULL`、`checked_at DATETIME NULL`、时间；索引 `(customer_id, created_at, id)`、`(listing_id, created_at, id)`、`(broker_id, status, updated_at, id)`、`(validation_state, updated_at, id)`。业务状态为 `OPEN`、`FOLLOWING`、`CLOSED`，允许 OPEN→FOLLOWING/CLOSED、FOLLOWING→CLOSED，CLOSED 终态；验证状态 `PENDING`、`CONFIRMED`、`NEEDS_RECHECK`、`REJECTED` 与业务状态独立。只有 `CONFIRMED` 对外表现为创建成功。同一买方/房源允许多条独立咨询，不加该组合唯一约束。

**`bk_customer.customer_follow_record`**：新增 `inquiry_id BIGINT NULL` 和索引 `(inquiry_id, created_at, id)`；创建跟进时校验关联咨询属于同一客户、操作人在客户与咨询范围内。历史记录保留 NULL，不推断。沿用 `customer_profile`；本期不改 `owner_entrust_record`、合同表或其流程，不以其阻断房源登记。

**`listing_command_receipt` / `customer_inquiry_command_receipt`**：分别归 Listing 与 Customer 数据库；`actor_user_id BIGINT`、`operation VARCHAR(64)`、`request_id VARCHAR(128)` 唯一，另含请求摘要 `BINARY(32)`、业务对象 ID、完成状态、创建时间。业务插入和回执同一事务，唯一键串行化并发重试；异常回滚不留下成功回执。保留期限及清理按运维政策确定，期限内可重试；无幂等键的旧接口保留已发布语义，新登记/咨询契约要求键。

### 3. 直接上架、价格与数据一致性

新登记初始 `PENDING_REVIEW`（API 展示名称为待上架，兼容老枚举），允许 `PENDING_REVIEW→ON_SALE/OFFLINE`、`ON_SALE→OFFLINE/SOLD`、`OFFLINE→ON_SALE`；`SOLD` 为终态。相同状态不创建重复历史；其他跳转拒绝。上架检查标题、地址、户型非空，面积和价格大于零；不检查审批、合同、委托或模拟核验。`SOLD` 仅记录经授权确认的房源状态，不建设合同成交域。更新按 `version` 做条件写，冲突回滚；更改价格/状态、历史、版本与 RAG outbox 同事务。私有信息、素材投影和普通 upsert 不能绕过统一更新入口。

新契约及所有新记录内部金额为 CNY 元；旧 `total_price` 和 `ListingDTO` 仍为万元，v1 继续按既有规则恰好换算一次。切换时将旧值精确乘 10000 回填新字段，并以新元字段为唯一业务写入主值，旧字段作为兼容投影在同一事务由系统换算；旧万元字段精度和范围若无法无损表达新价格，兼容期拒绝该价格并返回明确错误，不静默四舍五入。将来移除万元投影需另行版本化和数据对账。Customer 预算先盘点历史输入单位，兼容期维持旧约定；迁元之前不得无证据批量换算或改变匹配语义。

### 4. 咨询、输出和权限

Listing 提供带权限、状态和版本的当前买方可见投影及稳定分页；买方输出不包含业主联系方式、证号、合同或证件图片。现阶段由获准经纪人代买方操作，不推断已存在买方登录身份。受权 Customer 创建咨询前读取当前房源 `ON_SALE` 和目标客户类型/资源权，执行幂等写入；如果 Listing 不可用则失败关闭。跨服务校验和本地提交间存在状态变化窗口，不承诺跨库原子：保存房源检查版本/时间，提交后复查；若复查发现变化或不可用，咨询以 `NEEDS_RECHECK` 补偿状态保留而非谎报已确认，重试幂等并由处理任务重新核验；仅核验成功后对调用者返回成功。`NEEDS_RECHECK` 为系统内部过渡状态，不是业务跟进状态。历史咨询读取时重验当前客户与房源权限，房源下架后受权人员可读咨询历史，但不返回已失效的在售事实；撤权或依赖不可用时失败关闭。收藏与咨询分离。

### 5. 内部来源收敛与版本兼容

正式业务来源仅为内部 Listing；HTTP 外部 provider 退役，明确关闭生产可选 fixture，测试/演示环境的模拟数据必须显式隔离且带 `SIMULATED`，不可作为正式数据回退。调用 Listing 的 HTTP、RPC、Agent、RAG 均属内部服务，不因本决策移除。新业务接口以内部数字 `listingId` 表示；既有 `domain-parallel-v1` 兼容层继续映射 `legacy-local/<id>` 并保持既有 hash 算法、授权检查及 UNKNOWN 语义。不能因内源而把历史 UNKNOWN、模拟记录或模拟核验批量改 REAL。新增独立版本契约和明确的数据性质证据需版本化，不原地更改 v1 `sourceSystem`、`datasetId`、`dataMode`、facts 或 hash 规则。

盘点营销、经营、Studio、比较/报告、Customer、RAG 等消费者；修复营销要求 `listing-master-service/REAL` 与内部 `legacy-local/UNKNOWN` 不兼容，以及经营来源 check 请求结构不一致，采用实际生产者契约联测，不以 mock 或改变数据真实性绕过。兼容期旧报告/PDF/Studio 记录保留原 bytes、引用及指纹，读取时维持当前 owner、授权和版本复核；调价或撤权时可拒绝或标记过时，不伪造当前事实。RAG revision/outbox/tombstone 必须保留；业务乐观锁 revision 不替代索引 revision。

### 6. 并行 OpenSpec 边界

本变更新增 `listing-internal-management` 与 `buyer-listing-consultation`，不把另一个未归档 change 的规格当作主规范。实施前协调并更新 `consolidate-listing-comparison-domain` 的 proposal/design/specs/tasks：删除外部房源来源及上游事实与本地写入分离的过时要求，保留权限、版本、比较/报告和 RAG 的有效功能要求，明确与本变更的验收责任和归档顺序。完成统一基线后才能归档，防止旧 change 后归档把外部来源要求重新带回；不自行宣称其未完成 5.1–5.4 验收已完成。与 `unify-versioned-permission-context` 协调身份与资源权限，保持失败关闭。

### 7. 跨服务可信身份与操作边界

依据 `unify-versioned-permission-context` 的 domain-authorization-adoption 要求与 KI-243 现状，定义两条新增跨服务校验链路；均复用既有 RagAuthEnvelope 服务签名加用户 Auth 上下文转发模式（同 Customer→Listing 的 `ListingReadClient`），不新增认证协议：

**Listing→Customer 卖方档案验证（登记房源时）**
- 主体：当前已验签用户上下文（actor 与 BROKER 映射）；服务身份 `listing-master-service`→`customer-service`。
- 操作：新增受管 RPC `CustomerProfileVerifyRpcService.verifyOwnerProfile`，输入 `customerId` 与期望类型 `OWNER`。Customer 侧按签名上下文核验：档案存在、未删除、类型为 `OWNER`、当前操作人按 `customer.read` / `customer.read.all` 范围可访问（本人 broker 归属或业务管理员）。
- 返回：档案摘要（id、profileType、brokerId、updatedAt）或明确的 NOT_FOUND / NOT_ACCESSIBLE / INVALID_PROFILE_TYPE。
- 拒绝语义：无签名上下文、BROKER 映射缺失或过期时拒绝登记（AUTHENTICATION_REQUIRED / PERMISSION_DENIED）；Customer 服务不可用时 DEPENDENCY_UNAVAILABLE 失败关闭，不创建房源；不以请求体 brokerId / customerId 推断归属。

**Customer→Listing 咨询房源验证（创建咨询时）**
- 主体：当前已验签用户上下文；服务身份 `customer-service`→`listing-master-service`。
- 操作：新增受管 RPC `verifyInquiryListing`，输入 `listingId`。Listing 侧按签名上下文核验：房源存在、未删除、状态为 `ON_SALE`、当前操作人具备 `listing.read`（公开摘要范围允许跨 broker 在售浏览，与 RAG 摘要一致；不使用 v1 来源快照对非管理员的本人 broker 限制）。
- 返回：`{listingId, status, version, checkedAt}` 或 NOT_ACCESSIBLE（不区分不存在与无权，避免泄露）。
- 拒绝语义：同上失败关闭；房源非在售或不可读时拒绝创建咨询。

**统一约束**
- 两条链路的 actor 与 broker 均来自 Auth 签名上下文，请求字段仅作为业务目标而非身份；服务间调用带请求级签名证明并由接收端本地验签；权限不足或映射缺失时拒绝而非降级。
- 跨服务校验结果记录验证时间与房源版本，用于提交后复查（见决策 4 的 `NEEDS_RECHECK`）。
- KI-243 未闭环（Auth 账号暂无经纪人映射）：运行态请求在映射补齐前会被正确拒绝，属验收环境准备事项（任务 6.5），不改变本设计的失败关闭语义。

## Risks / Trade-offs

- [跨服务档案或房源校验与提交竞态] → 明确短期授权与验证版本、回执幂等、提交后复查和 `NEEDS_RECHECK`，持续校验读权限；不宣称跨库原子。
- [旧数据无法识别业主或是否真实] → 保留 NULL/UNKNOWN、形成待确认清单并逐条人工核实，不按姓名或模拟结果补全。
- [兼容期价格、版本或报告指纹漂移] → 元/万元双向投影有唯一写入口、无损校验和对账；v1 hash 原样保留，实物报告及权限复核测试通过后切换。
- [敏感文件或证号暴露] → 分级访问、受管加密、短期受权文件访问、脱敏日志；不在 RAG、普通摘要及买方输出中携带敏感信息。
- [索引丢事件或出现陈旧结果] → 保留同事务 outbox、墓碑、回填与最终真源复核，不承诺即时或 exactly-once。
- [现有旧变更重新引入外部来源] → 变更依赖协调和归档门禁，明确原规格冲突的修改责任与顺序。

## Migration Plan

1. 对目标环境备份并只读盘点 schema、房源 ID、业主候选关系、万元/预算、旧素材、模拟数据、引用消费者、报告文件和 RAG/outbox；不因为旧提案说未上线就假定当前数据为空。
2. 扩展初始化 runner 对 `bk_customer` 的受控升级白名单和测试；为 Listing 与 Customer 分别追加有序版本 SQL、schema check 和规范 LF SHA256，不修改已应用的历史文件。真实 MySQL 8.4 隔离库覆盖 fresh、upgrade、已有数据、重复运行和中断恢复。
3. 先增量建表/加可空列并验证权限和旧接口；旧价格精确回填并核对边界，分级迁移素材和加密证件，异常项保留原始数据并出具待处理清单。不能可靠识别的 owner 和核验状态保持未知，运营人员使用受权方式确认关联；历史状态/价格只写可识别迁移基线，不伪造历史。
4. 加入统一命令入口、幂等、版本与历史，兼容旧字段的唯一写入规则，并按消费者依赖顺序升级 Customer、营销、经营、Studio、比较与索引；保留 v1 指纹和实际文件，对未迁移或不匹配的消费者不进行切流。
5. 通过权限/撤权、隐私、上架无合同、万元换算、外部 provider 不启用、咨询重试及跨服务故障、历史报告、RAG 重放等验收后切到内部真源并关闭外部房源 provider；同步更新模板和运行配置，部署动作按实际环境授权执行。
6. 回滚优先恢复兼容版本、读取配置和受管备份；若新结构或新数据已写入，停止写入、备份、对账并使用预先验证的向后兼容方案，不删除新表、日志、报告或墓碑；任何不可逆迁移都需单独审批和回滚演练。
