# Tasks

## 1. 现状核对与契约固化

- [x] 1.1 核对对比报告存储与读取路径（报告 store 的 sources 结构、响应模型、过时/权限复验入口）、listing-agent 技能装配点、Supervisor 技能注册方式与前端契约文档位置；确认卡片投影的挂接点与存量报告兼容性前提。（证据：挂接点=服务边界 create/getOwned 返回，store 与存储格式零改动；响应模型经 11 参兼容构造保持既有调用方编译不变）
- [x] 1.2 固化卡片数据结构契约（cards/metrics/highlights/单位/unknownFields 字段定义）与两种传输方式（内嵌结构块 / reportId 句柄）的边界，作为实现与前端契约文档的共同基准。（证据：卡片/指标归属模型 `ListingComparisonCard`/`ListingComparisonMetricWinner`，字段与 specs 契约一致）

## 2. 卡片投影（listing 域，零新对比逻辑）

- [x] 2.1 实现报告读取响应的卡片投影：读取时从已存储 sources 与规则结果计算 cards（每套一张：listingRef/revision/contentHash/标题/显式单位事实/unknownFields）、跨卡 metrics、每卡 highlights（由确定性指标反查）；不引入独立计算、不修改 rows/metrics 语义。（证据：`ListingComparisonCardProjection.fill`——从 sources 确定性投影，单价复用 `ListingComparisonFacts.unitPriceYuan`，徽标/指标归属由 min/max 反查；服务边界 create/getOwned 接入）
- [x] 2.2 测试：多套报告卡片完整性、卡片与 rows 同源逐字段一致、未知字段不补值、单价缺省（缺面积/价格为空且不参与单价指标）、徽标仅来自确定性指标、存量旧报告读取自动获得卡片、过时判定与权限复验行为不变。（证据：`ListingComparisonCardProjectionTest` 4 项——投影与指标归属、卡片与 rows 同源一致（含未知↔"未知"、单价缺省 null）、create/getOwned 返回卡片、存量旧文件（JSON 移除卡片字段）读取自动投影；listing 153/153 通过）

## 3. 咨询-报告关联（customer 域）

- [x] 3.1 新增 `customer_inquiry_comparison` 受管迁移（bk_customer：inquiry_id/report_id/created_by/created_at，UNIQUE(inquiry_id, report_id)、INDEX(inquiry_id, created_at)）+ manifest 追加与 schema check 断言（表/列/唯一键）。（证据：`sql/migrations/20261106_customer_inquiry_comparison.sql`、`docker/mysql/checks/customer-inquiry-comparison.sql`（列/主键/唯一键/两索引逐列断言，statistics 按 seq_in_index 计数）、manifest order 710 `customer-inquiry-comparison-20261106-v1`；`python scripts/deploy/test_init_manifest.py` 72 条通过、`run-init-manifest.sh --dry-run` 通过；隔离 MySQL 不可用（docker 权限受限），check 仅静态核对）
- [x] 3.2 实现 `POST /customers/inquiries/{inquiryId}/comparison-reports`：校验咨询归属（customer.inquiry.write.own/.all）+ 经签名上下文向房源域核验报告可读（owner=当前经纪人）；任一失败拒绝不落库；同 (咨询,报告) 幂等。（证据：common 新契约 `RagComparisonReportVerifyRequest`/`RagContextAuthorizedComparisonReportVerifyRequest`/`ListingComparisonReportVerifyRpcService`/`ListingComparisonReportVerifyResponse`；listing 侧 `ListingComparisonReportVerifyRpcServiceImpl`（compare.report.read.own，own=all=该操作）+ `ListingRagIdentity.verifyAuthorizedComparisonReportVerifyContext` + `ListingComparisonReportStore.findOwnedOrNull`（缺失/非本人统一 null 不抛）+ `ListingVerifyContractHash.comparisonReportVerifyRequestHash`；customer 侧 `ListingComparisonReportVerifyClient`（VerifiedDomainAuthorization.requireVerifiedContext + RagAuthEnvelope 签名 + 失败关闭）+ `CustomerVerifyContractHash.comparisonReportVerifyRequestHash`（与 listing 侧逐字节一致）；配置：两侧 application.yml/nacos（customer.comparison-report-verify、rag.trusted-callers 加 customer-comparison-verify-current / CUSTOMER_COMPARISON_VERIFY_SECRET）、docker-compose 两侧服务与 .env.example 接线）
- [x] 3.3 咨询列表/详情响应带出关联清单（reportId + 关联时间，按时间排序）；不在客户域缓存或转存报告内容。（证据：`CustomerInquiryComparisonEntity`（不继承 BaseEntity——表无 updated_at/deleted 列）+ `CustomerInquiryComparisonMapper` + `CustomerInquiryComparisonDomainService(Impl)`（listByInquiry/listByInquiryIds/find/insert）；`CustomerInquiryController` 新增 attach 端点；`CustomerInquiryResponse` 增 comparisonReports 字段并用兼容构造器保持既有调用方编译不变；`CustomerListingInquiryServiceImpl.listInquiries` 批量填充按 created_at 升序）
- [x] 3.4 测试：关联成功/报告不存在/非本人报告/越权咨询/重复关联幂等/多报告共存/清单读取不含报告正文。（证据：listing `ComparisonReportVerifyRpcServiceImplTest` 6 项——ACCESSIBLE 回带 reportId、报告不存在/非本人 owner 统一 NOT_ACCESSIBLE、验签失败/未启用/缺操作码抛异常且不触存储、customer-service 受众双收、哈希独立编码防漂移；customer `CustomerListingInquiryServiceTest` 新增 6 项——attach 成功落库、NOT_ACCESSIBLE/依赖异常拒绝不落库、越权咨询拒绝且不调核验、格式非法前置拒绝、重复关联幂等不重插、多报告按时间升序随清单返回；`ListingComparisonReportVerifyClientTest` 7 项——签名可验证、密钥缺失/响应异常/RPC 失败失败关闭、缺 compare.report.read.own 前置拒绝、reportId 格式前置拒绝、哈希独立编码防漂移；listing 159/159、customer 60/60 全部通过）

## 4. Supervisor 技能与装配

- [x] 4.1 编写 Supervisor 技能 `consultation-listing-comparison`：步骤（买方上下文→listing-agent 搜索版本化候选→选 2–6 套→真实 refs+expectedRevisions 对比→卡片结构原样透传+文字引导→有咨询时关联 reportId）与硬约束（禁编造房源/版本、版本冲突重搜一次仍冲突如实告知、权限/依赖失败明确上报、agent 观点只进文字）（证据：agent-service/src/main/resources/skills/supervisor/consultation-listing-comparison.md——步骤①买方上下文→②listing-search 版本化候选→③选 2–6 套→④listing-comparison 真实 refs+revisions→⑤卡片原样透传+文字引导→⑥reportId 留痕经客户域关联接口、agent 不直接写客户域；硬约束：版本冲突重搜一次仍冲突如实告知、不虚构、不降级）。
- [x] 4.2 装配测试：技能可被动态目录发现并指向 listing-agent 现有 `listing-search`/`listing-comparison` 能力；listing-agent 无新增技能；失败关闭（能力缺失/权限不足不回退）（证据：`ConsultationListingComparisonSkillTest` 2 项——显式技能+allowlist 绑定 a2a:listing-agent+SkillExecutionResolver 解析/非显式拒绝；systemPrompt 断言卡片透传/观点隔离/失败关闭约束；agent-service 285 项通过（6 skip 为既有））。

## 5. 前端契约文档

- [x] 5.1 编写卡片结构字段说明（含单位/unknownFields/highlights 语义、版本标识用途）与两种传输方式的使用场景（即时对话内嵌块 / 历史回看按 reportId 拉取），明确数据不含 UI 语义、前端自行设计呈现（证据：docs/consultation-comparison-cards.md——卡片/指标归属字段契约、两种传输方式（内嵌块/reportId 句柄）、渲染约定（未知不补值/单位显式/徽标）、咨询留痕关联 API、边界）。

## 6. 验收

- [ ] 6.1 端到端验收：买方咨询 → agent 匹配候选 → 对比 → 回答携带卡片结构 → 关联咨询 → 咨询历史回看按 reportId 拉取卡片；负例：版本冲突（搜索与对比之间房源变化→重搜一次→仍冲突告知）、越权关联、报告不可读。
- [ ] 6.2 回归：listing 与 customer 模块全量测试通过；受管 manifest 校验与 dry-run；按项目规范登记 LR（卡片投影同源一致性、关联单向引用）与新增 KI（如有）。
