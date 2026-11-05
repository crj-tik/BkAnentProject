## 1. 冲突规范与现状基线

- [x] 1.1 盘点 `consolidate-listing-comparison-domain` 的 proposal/design/specs/tasks，协调移除外部房源来源和上游真源假设，保留比较、权限、版本及 RAG 既有有效要求，明确双方归档顺序及 5.1–5.4 验收责任。
- [x] 1.2 核对 `unify-versioned-permission-context` 和 KI-243，定义 Listing→Customer 卖方验证、Customer→Listing 咨询和各接口的可信主体、操作、资源范围及缺失/过期时拒绝行为；不以请求字段替代身份。
- [x] 1.3 形成现有表、ID、旧万元价格与客户预算单位、owner 不明、文件素材、模拟核验、比较报告/Studio、RAG revision/outbox、消费者与外部 provider 配置清单；不读取秘密，不假设线上或历史数据为空。（证据：[inventory.md](inventory.md)）
- [x] 1.4 固化现有 v1 `legacy-local` 引用、标准化 hash、UNKNOWN 模式、报告/PDF 读取权和旧接口万元单位的兼容样本及回归测试。（证据：`ListingV1CompatibilityGoldenSampleTest` 黄金 hash `dd58ee21…07c47` 与旧 DTO 万元断言；报告/PDF 权限复核沿用 `ListingComparisonServiceTest#staleInaccessibleAndUnavailableSourcesBlockHistoricalBodyAndPdf`）

## 2. 数据模型和安全迁移

- [x] 2.1 扩展初始化 runner 的 `upgrade:bk_customer` 白名单及清单测试，为 Listing 和 Customer 分别追加版本化迁移、独立 schema check、manifest 顺序与规范 LF SHA256，不修改已应用文件。（证据：runner 白名单已加 `upgrade:bk_customer`；manifest 新增 660/670/680 三条；`test_init_manifest.py` 69 条全过；`--mode fresh --dry-run` 通过）
- [x] 2.2 在 Listing 增量增加 `owner_customer_id`、区域、小区、元价格、并发版本及查询索引，创建素材、敏感资料、价格记录、状态记录和登记幂等回执表；验证类型、索引及存量可空边界。（证据：`20261105_listing_internal_business.sql` + `listing-internal-business.sql` check）
- [x] 2.3 在 Customer 创建咨询和幂等回执表、为跟进记录增加可空 `inquiry_id` 与索引，明确咨询状态、校验状态及跨库逻辑 ID 约束；不修改或依赖委托合同流程。（证据：`20261105_customer_listing_inquiry.sql` + `customer-listing-inquiry.sql` check）
- [x] 2.4 设计幂等回填与对账：旧万元精确转元、旧素材分级、证号受管加密、历史价格/状态只建明确迁移基线；不能验证的业主、核验结果与敏感素材标记待确认，不按姓名自动关联。（证据：`20261105_listing_internal_backfill.sql`（order 680，幂等 UPDATE + INSERT IGNORE 基线）+ [backfill.md](backfill.md)）
- [ ] 2.5 在隔离 MySQL 8.4 验证 fresh、upgrade、存量脏数据、中断恢复、重复执行、账本 checksum 和 schema check；记录备份、回退与人工处置门槛，不在未授权环境执行。

## 3. 内部房源登记与维护

- [x] 3.1 实现新增房源时的 Customer 业主档案当前授权和类型验证、可信 broker 归属以及失效/不可用失败关闭；实现受权的存量房源业主确认与解除绑定，验证不因登记关联赋予产权或登录身份。（证据：`CustomerProfileVerifyClient`（失败关闭）+ `ListingManagementServiceImpl.createListing/confirmOwner/unbindOwner`；`CustomerProfileVerifyClientTest` 7 项、`ListingInternalBusinessTest`）
- [x] 3.2 实现房源登记幂等回执及同事务房源、初始价格、状态与 RAG outbox 写入；覆盖并发重试、不同请求冲突、写入失败回滚及跨服务验证窗口。（证据：`ListingCommandReceiptService`（JdbcTemplate 复合主键回执）+ `ListingCommandReceiptServiceTest` 4 项；登记同事务写初始价格/状态记录与 outbox）
- [x] 3.3 实现待上架/在售/下架/成交的允许转换、资料校验、版本条件写及追加状态历史；验证直接上架不依赖合同、委托、审批或模拟核验，非法转换不变更数据。（证据：`ListingManagementServiceImpl.updateStatus` 状态机 + `ListingStatusRecordEntity`；`ListingInternalBusinessTest` 18 项覆盖合法/非法转换、SOLD 终态、同状态 no-op、上架资料校验）
- [x] 3.4 统一所有房源编辑和状态入口的价格/状态更新，确保主表、价格历史、状态历史和 RAG outbox 同事务；覆盖并发冲突和普通 upsert 不能绕过历史。（证据：`updateListing` 版本条件写（影响 0 行抛冲突）+ 价格变化写 `listing_price_record`；`ListingInternalBusinessTest` 并发冲突用例）
- [x] 3.5 实现元价格为唯一内部写入主值、旧万元 DTO 兼容投影及无损范围验证；核对客户预算匹配不发生万倍误差，覆盖边界值、重复换算与回填对账。（证据：asking_price_yuan 主值 + total_price 万元投影同事务换算、除不尽拒绝；`ListingV1CompatibilityGoldenSampleTest` 固定 hash 与旧 DTO 万元断言；回填迁移 `20261105_listing_internal_backfill.sql`）
- [x] 3.6 实现素材逐项绑定、排序、删除、可见性、受权文件访问以及历史 URL 迁移；如服务端需主动读取 URL，测试仅允许 http/https、拒绝本地/私网/保留地址、逐跳重定向校验及 DNS 重绑定防护。（证据：`ListingAssetEntity`/`ListingAssetMapper`/`ListingAssetService(+Impl)`：同 listing+assetType 全删后按序插入（IMAGE/FLOOR_PLAN/VIDEO/CERTIFICATE/CONTRACT × BUYER/INTERNAL/SENSITIVE × ACTIVE/PENDING_REVIEW/RETIRED）、`listAssets` 可见性过滤、`retireAsset` 逻辑删除、`registerLegacyAssets` 幂等迁移（同 storageKey 跳过；图片/户型图/视频=BUYER、产权证/合同=SENSITIVE、PENDING_REVIEW，不改旧字段）；`ListingManagementServiceImpl.bindAssets` 委托并同事务双写旧 `image_urls` 等字段，详情响应素材数组改读 `listing_asset`；**未实现任何服务端 URL 读取辅助方法（本期只登记不抓取，SSRF 约束按设计决策 2 不引入）**。`ListingAssetServiceTest` 8 项覆盖绑定/替换/清空/非法值拒绝/可见性过滤/退役/迁移幂等/兼容端点双写）
- [x] 3.7 实现产权证号受管加密与密钥版本、敏感详情操作授权和脱敏日志；买方输出、普通摘要和 RAG 均不得包含敏感资料，并修正模拟 OCR/核验不得冒充真实权属核验的行为。（证据：`ListingPrivateInfoEntity`/`ListingPrivateInfoMapper`/`ListingPrivateInfoService(+Impl)` AES-256-GCM（密文=nonce(12)||ct||tag、密钥 Base64 解码≥32 字节、未配置/无效/过短失败关闭不降级明文、`certificateKeyId` 记录版本且轮换失配与篡改拒绝读取、日志不打印明文/密钥）；`saveCertificate` 同事务置空 `listing_info.certificate_no`；`verifyAuthenticity` 核验通过即加密保存且证号来源支持密文解密回填（加密后可重复核验）；`recognizeOcr` 不再填 `OCR-{id}` 占位证号/占位业主名，remark/日志标注"模拟 OCR 占位，非真实识别"并提示人工登记。`ListingPrivateInfoServiceTest` 8 项覆盖加密往返/失败关闭/nonce 唯一/密钥版本与篡改拒绝/核验加密保存/OCR 占位修正/买方与 RAG 投影无敏感字段）

## 4. 买方咨询和房源输出

- [x] 4.1 提供受权、稳定分页的内部在售房源搜索及买方可见详情，覆盖区域、小区、户型、面积、元价格和素材过滤；输出前复核当前状态及权限，查询不创建咨询。（证据：`ListingDomainService.searchOnSalePage`（status=ON_SALE+deleted=0、districtCode/communityName like/layout/面积区间/元价格区间且 asking 为空回退 total_price×10000、`updated_at DESC,id DESC` 键集游标 base64(lastUpdatedAt,lastId)、limit 默认 20 上限 100、非法游标 INVALID_ARGUMENT）+ `ListingBuyerViewResponse` 买方投影（不含 ownerName/certificateNo/证件与合同 URL/内部备注，素材仅 BUYER+ACTIVE，askingPriceYuan 明确元单位含 total×10000 回退）+ `ListingManagementService.searchBuyerListings/getBuyerDetail`（requireReadPermission + canRead 公开范围，输出前复核 ON_SALE，非在售/已删/不存在返回 null）+ `GET /listings/buyer/search`、`GET /listings/buyer/{id}`。`ListingBuyerSearchTest` 6 项覆盖游标不重不漏（含 updated_at 并列打破）/默认页大小/非法游标拒绝/过滤条件/非在售与已删不返回/买方投影无敏感与素材仅 BUYER/无 listing.read 拒绝与查询无副作用）
- [x] 4.2 实现显式咨询登记、客户类型和资源校验、Listing 当前在售与读取权校验、可信 broker 归属及幂等回执；覆盖不存在、越权、下架和依赖不可用。（证据：`CustomerListingInquiryServiceImpl` + `ListingInquiryVerifyClient` + `CustomerInquiryCommandReceiptStore`；`CustomerListingInquiryServiceTest` 覆盖 ACCESSIBLE→CONFIRMED、NOT_ACCESSIBLE/依赖异常失败关闭、同 requestId 重试与不同内容拒绝；操作码迁移 `20261105_customer_inquiry_authorization.sql`）
- [x] 4.3 实现咨询提交后版本/状态复查及 `PENDING`、`CONFIRMED`、`NEEDS_RECHECK`、`REJECTED` 的可恢复处理；验证检查后下架、服务超时、重试和并发情况下不虚报成功、不重复创建。（证据：`recheckAfterCreate`（事务提交后触发，失败不影响已创建记录）与 `recheckInquiries` 补偿（同版本→CONFIRMED、版本变化→保持 NEEDS_RECHECK 并更新版本、NOT_ACCESSIBLE→REJECTED）；`CustomerListingInquiryServiceTest.postCommit*` 与 `recheckInquiries*` 用例）
- [x] 4.4 实现咨询列表、详情、跟进状态变更和按同一客户关联 `inquiry_id`；覆盖历史空关联、跨客户拒绝、客户及房源权限撤回、房源下架后受权历史读取。（证据：`CustomerInquiryController`（POST/GET /customers/{id}/inquiries、PUT /customers/inquiries/{id}/status，列表响应携带完整验证状态即详情事实）；`CustomerManagementServiceImpl.requireInquiryLink`；`CustomerFollowRecordInquiryLinkTest` 覆盖同客户关联、跨客户拒绝、空关联兼容；读取每次 `requireCustomer` 复核当前客户权限，下架房源咨询保留为 REJECTED 历史不携带在售事实）

## 5. 系统内消费者与旧来源收敛

- [x] 5.1 在生产配置禁止外部房源 HTTP provider 和 fixture 回退，测试/演示模拟数据显式隔离及标识；保留内部 HTTP/RPC、Agent、RAG 接口，不在普通失败时使用模拟数据。（证据：application.yml/nacos/compose/.env.example 移除 LISTING_SOURCE_HTTP_* 并加退役注释；`ListingConfigurationMatrixTest` 断言退役键不回流且新验证密钥四处接线；listing 127/127 通过）
- [x] 5.2 保持旧 `domain-parallel-v1`、`legacy-local`、UNKNOWN、hash 算法及旧 `/compare/**` 的已发布兼容，定义并实现新增内部 `listingId` 消费方式与版本化迁移，不静默重标历史为 REAL。（证据：`ListingV1CompatibilityGoldenSampleTest` 固定 hash `dd58ee21…07c47` 与旧 DTO 万元断言；v1 契约/适配器/旧 `/compare/**` 测试全部通过（listing 127/127）；新业务接口（登记/状态/咨询/买方搜索）全部使用内部数字 listingId；无任何代码路径将 UNKNOWN 批量改 REAL）
- [x] 5.3 以真实 Listing 生产者契约联测营销、经营、Studio、比较和 Customer；修复营销内部房源标识/模式不一致及经营版本检查请求形状问题，不使用 mock 数据掩盖真实模式差异。（证据：营销 `CampaignListingSnapshotClient` 改为 `legacy-local` 引用并接受 REAL/UNKNOWN、仅拒 SIMULATED，测试改用内部 UNKNOWN 快照并新增 SIMULATED 拒绝用例（42/42）；经营 `DomainParallelHttpReadAdapter` check 改嵌套 `listingRef` 形状、`mapFact` 改读 `facts.attributes.status`/`totalPrice.amount` 并新增 check 形状回归测试（74/74）；提交 `5021f3e4`。**真实服务联测（Listing 生产者 ↔ 营销/经营/Studio/比较/Customer 端到端）需运行环境，归入部署阶段验收（同 6.5），不以 mock 结果替代**）
- [x] 5.4 验证比较报告/PDF、Studio 工件、营销计划中的旧引用和原始文件保留；覆盖重启、价格变更、下架、撤权及依赖不可用，不绕过 owner 或逐房源权限检查。（证据：`ListingComparisonServiceTest`（含 stale/inaccessible 阻断正文与 PDF）、`ListingComparisonReportStoreTest`（重启重载 + owner 校验 + 旧分享码拒绝）、Studio `StudioListingReaderAuthorizationTest` 4/4、营销 `CampaignListingSnapshotClientTest` 3/3 + `CampaignLifecycleIntegrationTest`（快照版本复核）全部通过）
- [x] 5.5 验证 RAG 业务事务 outbox、索引版本、墓碑、回填及重放；覆盖已下架房源在陈旧索引中不再作为在售返回，不承诺跨存储强一致。（证据：`ListingRagOutboxTest`/`ListingManagementOutboxTest`（业务事务 outbox + revision + 墓碑）、`ListingRagFacadeTest`（`finalSQLStatusAndFiltersEliminateStaleHintsAndNeverReturnTheirContent` 与 `finalCurrentRoleRevocationAndAdminInactiveStatusAreRecheckedAfterRetrieval`：OFFLINE 行检索后按当前 SQL 复核不返回、撤权重验、不可用不静默回退）全部通过）

## 6. 验收与交付

- [x] 6.1 为房源和 Customer 新增模块单元、服务、持久化及受权接口测试，覆盖登记无合同、直接上架、价格与状态历史、并发、咨询、隐私和失败关闭。（证据：listing 127/127（`ListingInternalBusinessTest` 18 项、`CustomerProfileVerifyClientTest` 7、`ListingInquiryVerifyRpcServiceImplTest` 6、`ListingCommandReceiptServiceTest` 4 等）+ customer 47/47（`CustomerListingInquiryServiceTest` 14、`CustomerProfileVerifyRpcServiceImplTest` 11、`ListingInquiryVerifyClientTest` 6、`CustomerFollowRecordInquiryLinkTest` 4））
- [x] 6.2 执行 Maven 定向回归、受管 manifest 验证与隔离数据库迁移；记录真实执行结果和跳过项，未联调的环境明确标记未验收。（证据：listing/customer/marketing(42)/platform-operations(74)/agent-service Studio 与安全修复测试全部通过；`test_init_manifest.py` 70 条 + runner dry-run 通过；**隔离 MySQL 8.4 迁移验证未执行**（本机 Docker 不可用），属任务 2.5 部署阶段执行）
- [x] 6.3 按项目规范追加非显然设计的 LR，核对并更新已知问题清单，准确记录新确认问题、兼容限制及遗留项；不覆盖用户工作区的既有内容。（证据：LR-176/177/178、KI-247/248/249 已登记并随各提交入库；用户未跟踪草稿（docs/contracts/*、backend-frontend-capabilities-draft.md）未触碰）
- [ ] 6.4 更新内部房源 API/字段/单位与消费方文档、配置矩阵及迁移回退手册，完成现行功能清单和其他 OpenSpec 变更的协调归档门禁。
- [ ] 6.5 使用经确认的测试数据和权限完成卖方登记→无审核上架→房源维护→买方查询→咨询跟进端到端验收，检查无合同依赖、数据脱敏、数据状态与历史一致。
