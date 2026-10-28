# Tasks

## 0. 独立会话与契约基线

- [x] 0.1 按并行总览确认本会话独立 worktree/分支与共同 base SHA、独立 .m2repo 和测试资源，记录路径/分支/基线并验证未导入其它三个实现分支。记录：worktree=`C:/Users/User/.codex/worktrees/consolidate-listing-comparison-domain/BkAnentProject`，branch=`codex/consolidate-listing-comparison-domain`，执行基线=`46d6828a797f0566144e0359793ba41af06ef331`，独立 `.m2repo/` 与 `.work/2026-10-27/01a17c2e-f800-7fb3-9906-0d0e49b07378/`；知识/营销现有分支历史共同祖先为 `6d13117169fb6eeb3c15165b43a08cc08b142889`，本会话采用其后 Auth/Memory/Listing 鉴权已接线且未包含另外领域实现提交的基线，分歧留给后续协调。
- [x] 0.2 固定本域修改范围、LR/KI编号段及共享文件最小差异，用文件清单验证不改 Auth/Memory 通用协议、Auth 角色默认授权、共享在线登记和其它 change 的任务。本域仅改 listing/compare、稳定来源契约、必要 Agent/Studio 调用方和配置；沿用 LR-99、KI-115。并行总览预留的 LR-106–113/KI-170–177 已与当前 Auth 清单条目冲突，本会话不占用、不改写；仅在发现新问题时按当时清单协调分配。文件范围已检查，不改 Auth/Memory 通用协议、角色授权、共享在线登记及其它 change。
- [x] 0.3 对照 domain-parallel-v1 固化房源三条 HTTP 接口及样例，用生产者契约测试验证包络、字段、CURRENT/STALE/拒绝/不可用语义，无需等待下游消费者。证据：`docs/listing-business-api-v1.md`；`ListingSourceContractTest`、`ListingBusinessSourceControllerTest`。
- [x] 0.4 建立逐键配置矩阵，覆盖本地 `.env`、`.env.example`、服务配置模板、Nacos YAML、`docker-compose.yml` 和 Nacos 发布清单；逐项标注来源、默认值、密钥属性、消费者、增删/迁移动作及退役门槛。证据：`docs/listing-comparison-cutover.md`、`ListingConfigurationMatrixTest`；本地 `.env` 只审计键名/默认开关，不入库。
- [x] 0.5 核对 `MEMORY_V2_AUTHORIZATION_CONTEXT_ENABLED` 在 `.env.example`、Nacos 和 Compose 的默认值/传递差异；统一为默认关闭并显式透传，记录只有 Auth 公钥和部署验收完成后才可开启。证据：三处配置矩阵断言及切换手册门禁；Listing 必需 Memory Hook 保持启用。

## 1. 房源来源契约与适配

- [x] 1.1 在 common/房源域定义稳定来源引用、版本、单位及数据模式 DTO，并用契约测试验证跨来源同 ID、legacy-local ID 映射、UNKNOWN 和禁止客户端转正。证据：`common/listing` DTO、`ListingSourceContractTest`、`ListingLegacyReferenceMapperTest`。
- [x] 1.2 实现 ListingSourcePort、只读 legacy-local 适配器及固定版本 fixture provider，用相同查询/详情样例验证来源身份、版本、模式标记、空匹配和无随机业务事实。证据：`ListingSourceAdapter` 三实现及 `ListingSourceAdapterTest`。
- [x] 1.3 实现受管 HTTP 来源适配与有界错误映射，用本地 HTTP 夹具验证单位映射、超时、错误响应及不会回退 fixture；真实目标未提供时单独记录未联机状态。证据：`ListingSourceAdapterTest` 覆盖 search/batch-read/check、错误、超时及 provider 模式；切换手册标明未联机。
- [x] 1.4 消费 Auth 当前签名上下文/新鲜度并复核每套来源；用精确签名测试上下文覆盖 `listing.read`/`listing.read.all`、无权、撤权、停用、过期与旧快照，确认缺少接线时拒绝且不信任 brokerId；不修改 Auth 协议或等待其它会话。证据：`ListingBusinessSourceServiceTest`、`ListingVersionedAuthorizationTest`、`ListingBusinessSourceControllerTest`；来源返回后会重验权限。

## 2. 比较与报告迁入房源域

- [x] 2.1 将现有比较计算、报告缓存、REST 模型/控制器、工具和 PDF 服务迁入房源 comparison 包，用迁移后的测试验证 2–6 套稳定引用、未知字段与万元到元单价。证据：`listing-master-service/comparison`、比较 service/controller 测试及元/平方米事实计算。
- [x] 2.2 增加稳定来源映射和报告文件迁移/初始化检查；盘点 `compare-report-data` 卷中的 JSON/PDF 与 owner/source hash 元数据，用旧 Long ID、外部字符串 ID、重复迁移和文件对账夹具验证映射及保留稳定。证据：legacy ID mapper 与临时报告卷测试；旧文件 byte-for-byte 保留，新文件独立使用 `.listing.json`。
- [x] 2.3 实现授权快照报告和缓存/历史读取前的当前来源复核，用 `compare.report.create`、`compare.report.read.own` 与逐套 Listing 读取权限的允许/缺失/撤回组合，加上来源调价、删除和依赖失联，验证 STALE/拒绝/不可用语义；确认缓存指纹不代替当前核验且不自动授予角色权限。证据：`ListingComparisonServiceTest`、`ListingBusinessSourceServiceTest`、授权测试；缓存命中和历史/PDF 均重新校验当前来源与 actor。
- [x] 2.4 迁移报告详情、分享和 PDF 读取，用仅持分享码、旧 `publicShare=true` 记录及历史无 owner 文件的反例验证不返回正文或下载字节，并保留文件供受控迁移核对。证据：`ListingComparisonReportStoreTest` 检查 owner、分享码拒绝及旧 JSON/PDF 原字节保留。

## 3. 调用方与部署身份切换

- [x] 3.1 在 listing-agent 发布 `listing-comparison` 技能和工具，迁移 compare 技能调用方；确认 `a2a:listing-agent` 使用精确稳定映射 `listing.read`，比较创建和报告读取仍分别要求 `compare.report.create` 与 `compare.report.read.own`，不映射通用 `agent.chat.use`。用签名上下文验证缺映射/缺操作失败关闭。证据：Listing skill/tools、Nacos capability mapping、比较授权和 Agent 装配回归。
- [x] 3.2 复用 `listing-master-service/listing/listing-agent` 的既有必需 Memory Hook 与 trusted-caller 登记，不新增 Listing 比较 Agent/身份；用装配测试验证读取、来源复核、成功归档，以及 Memory 不可用/未授权时模型、来源和报告工具均未执行。旧 Compare Memory 仅按原身份留作受控历史读取。证据：`ExplicitSkillAgentWiringTest` 与配置矩阵保留旧 Compare caller/domain/key；仅 Memory v2 可选上下文开关维持关闭。
- [x] 3.3 更新 common 稳定来源比较契约、Supervisor 工具/技能配置、Listing ingress Auth 配置和旧 HTTP 兼容入口；Studio 保留工作区工件与回执，改用稳定来源版本和房源域确定性比较逻辑。用契约测试验证当前授权、预期版本和拒绝语义。证据：listing API 文档/控制器、Studio `ListingReader`、回执 schema/API 文档及签名授权回归。
- [x] 3.4 按配置矩阵同步本地 `.env`、`.env.example`、服务配置模板、Nacos 与 `docker-compose.yml`：新增 Listing 来源 provider/HTTP 配置、`listing.authorization` 开关和公钥注入、Agent capability mapping 与 Memory 可选上下文显式默认；删除或迁移不再使用的 Compare endpoint，新增旧 REST/RPC 默认关闭及可控回滚项。保留本地秘密、不提交 `.env`；Compare Auth/Memory 身份仅在排空门禁后移除。证据：矩阵回归覆盖应用/Nacos/Compose与旧回滚项；本地 `.env` 只补齐缺项、秘密值留本机；旧身份待排空后退役。

- [x] 3.5 编制 REST/MCP/A2A/RPC 入口、调用方/Card/Memory 身份、Auth 权限映射、配置项、Compose 服务/卷的迁移清单；用报告卷、legacy ID、A2A 任务关联及身份配置夹具验证文件、taskId、历史记忆和授权范围保留，真实运行清单由合并后门禁现场核对。证据：切换手册、报告卷/legacy mapper 测试、A2A client taskId correlation tests、`LegacyCompareIngressConfigurationTest` 和 Auth/Memory/Compose identity matrix；运行中任务只在原 Compare 身份下对账，不自动迁移或重发。
- [x] 3.6 停止旧 compare 能力接收新委托并完成原身份对账；旧 `/compare/**` 仅保留当前授权的 legacy-local 兼容，匿名公开分享不再可读。仅在消费者和历史读取不再依赖时退役旧 Nacos、Compose、环境变量和 Auth/Memory 信任项；保留报告卷定义并迁挂到 Listing。代码默认关闭旧 A2A/MCP、旧 Compare REST/RPC 与 profile、移除 Agent endpoint、Gateway 路由改至 Listing；共享 Nacos 发布和线上排空仍交协调人在切换门禁执行。
- [x] 3.7 同步 Auth 数据库中的比较权限目录归属：保留 `compare.report.create` / `compare.report.read.own` 稳定权限码与现有角色关联，将其目录 domain/operation 迁至 `listing`，递增授权修订号并发布 outbox；不自动新增角色授权。证据：`20261028_listing_comparison_authorization_domain.sql`、`listing-comparison-auth-domain.sql` 与 MySQL 初始化清单 450。
## 4. 验收与交付

- [x] 4.1 在仅有共同基线及本 change 的 worktree 中运行房源/比较和必要调用方聚焦测试/编译，记录模块与独立 Maven 仓库，验证无需其它 change 的新类型或制品。证据：common、listing-master-service、agent-service、compare-engine-service 聚焦构建成功；使用独立 `.m2repo/`。
- [x] 4.2 用模拟接口执行查询、对比、报告、PDF 及来源失效闭环，验证每个输出保留模拟标记，重试不重复建报告，旧未知调用不被替代重发。证据：`simulatedProviderCompletesSearchComparisonHistoryAndPdfWithoutLosingMode`、来源失效与报告缓存测试；A2A correlation fixture保留请求/远端 taskId。
- [x] 4.3 在自身隔离库/夹具验证既有 SQL/check、报告文件保留和回退；逐键核对环境变量、Nacos 占位符、Compose 传递、默认值和敏感项，检查 Compare 残留引用及历史信任/报告卷保留。交付迁移版本、配置矩阵与切流手册；不发布共享 Nacos、不执行容器操作或真实上游联机。新增 Auth 目录迁移无业务表 DDL，现有角色关联不变；报告仍由卷持久化。20261028 迁移/检查的清单引用与 SHA256 已静态核验，见 4.5。
- [x] 4.4 同步本域 LR 与独立验收证据；保持 KI-115 的 FIXED 状态并更新迁移后的代码/验证引用，除非新回归证据证明该缺陷复发。OpenSpec 严格校验与 diff 检查通过；本 change 将以中文 commit 并 push 本会话分支，仓库提交不含 `.env` 或秘密，不推 dev-fix 或代合并其它会话。
- [ ] 4.5 在隔离 `bk_auth` 数据库执行 20261028 权限目录迁移及对应 check，并确认迁移前后 `auth_role_permission` 对旧权限码的显式关联集合完全相同。此环境验收未在本会话连接或执行；部署前由数据库协调人完成。
