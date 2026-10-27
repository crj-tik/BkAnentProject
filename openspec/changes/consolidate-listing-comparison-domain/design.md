# 房源经营域合并设计

## Context

动机见 proposal.md。当前 `CompareAnalysisServiceImpl` 先要求 `compare.report.create`，再经 `CompareListingReadClient` 使用签名授权逐套读取 `ListingDTO`；`ListingMasterRpcService.getListingById` 这类旧无凭据读取入口会拒绝请求。KI-115 已修复：报告缓存按 owner 和快照指纹隔离，单位换算由 `ListingComparisonFacts` 处理。尚未覆盖的是稳定来源引用/版本，以及历史详情、分享和 PDF 返回前的当前来源复核。

比较服务目前包含 REST `/compare/**`、MCP `compare-mcp-server`、A2A `compare-agent` 和 Long ID-only 的 `CompareEngineRpcService`。报告缓存将 JSON 元数据与 PDF 文件写入 `compare-report-data` 命名卷；报告可由 owner 显式发布为匿名可读分享。`listing-master-service` 则以本地 `listing_info` 为主要数据模型，负责 CRUD 和 RAG outbox/index，尚无上游接口来源适配器。Studio 是另一条工作区命令链：它经签名 RPC 读取当前房源、核对候选版本并写入自己的 `listing.comparison` 工件和回执，不调用 Compare 报告服务。

用户确认：业务数据目前模拟，房源以接口接入。现有本地记录不因此自动成为上游 REAL 数据；本 change 需要增加来源端口，并为存量记录提供明确的 `legacy-local` 来源身份。现有测试包括 `ListingBusinessAuthorizationTest`、Compare 缓存/分析测试、Studio 授权读取和两域技能装配测试；合并时需迁移对应行为验证。

## Goals / Non-Goals

**Goals:** 在现有 `listing-master-service` 内统一授权快照、对比与报告；上游接口可由模拟实现替换，派生结果保留来源、版本和数据性质。

**Non-Goals:** 不建设客户 CRM、成交状态机或真实上游写入；不改 Supervisor 通用拓扑，不合并 Auth、Memory、RAG，不在本 change 实施知识或营销调整。

## Decisions

### 1. 保留房源模块名，比较成为内部业务包

将现有比较计算、报告缓存、PDF、响应模型和必要入口迁入房源模块的 `comparison` 包，保留 `listing-agent`，新增 `listing-comparison` 技能。Compare 的签名读取与 owner 隔离是已修复行为，迁移时应保留；新域再加入稳定来源版本和报告读取时的当前来源复核。相比继续保留两个对外 Agent，此方式减少同一组事实在两个身份间来回读取；相比同时重命名为新服务，保留模块名可缩小部署与身份变更范围。报告有自己的持久生命周期，但其事实来源仍归房源域。

### 2. 接口事实与本地维护记录分别持久化

定义 `ListingSourcePort`，提供有界搜索、批量详情和当前版本核验。首期提供显式 `legacy-local`、`fixture` 与 `http` 适配器；`legacy-local` 只读现有 `listing_info`，为旧 Long ID 建立稳定来源引用，不替代现有管理写入和 RAG 索引。HTTP 地址/凭据来自受管配置，端点未配置或不可用时返回不可用，不回退到 fixture 或 `legacy-local`。fixture 使用相同契约并标记 `SIMULATED`，测试可通过本地受控 HTTP 夹具覆盖映射。

输入固定包含 `sourceSystem/sourceId/sourceRevision/observedAt/dataMode`、事实字段及金额单位；无上游 revision 时用规范化投影 hash，不能用抓取时间冒充版本。`legacy-local` 无来源性质证明时标记 `UNKNOWN`；只有服务端配置能确认模拟数据时才标记 `SIMULATED`。新增稳定来源映射，外部字符串 ID 不截断或哈希成可能碰撞的旧 ID。DTO 兼容层显式映射身份。

来源模式由服务端适配器确认：已确认真实为 REAL、模拟为 SIMULATED、性质不明为 UNKNOWN；派生材料只有全部来源均为 REAL 才为 REAL，含未知来源为 UNKNOWN，已知真实/模拟混合为 MIXED。数据模式与指标是否可计算是不同字段，不以 UNKNOWN 来源替代具体指标缺口说明。

上游价格、可售状态等为外部事实，本地只维护资料补充、维护事项和受权投影。没有上游写接口时，完成本地待办只代表操作人提交了处理记录，不能宣称上游已调价或已核验。既有 SQL 管理 API 和 RAG 索引职责继续保留；`legacy-local` 是显式选择的迁移来源，不是 HTTP 故障时的回退路径。

### 3. 比较只使用同次授权和量纲一致的快照

比较请求使用 2–6 个稳定房源引用和预期 revision/contentHash，通过 `snapshots/batch-read` 获取同次获准快照；返回报告前调用 `snapshots/check` 复核当前身份、逐套读取权和来源版本。金额输入明确单位，旧万元字段只在 legacy 边界换算一次。新报告指纹按规范化的 `sourceSystem/sourceId/revision/contentHash` 和规则版本构造，不沿用仅以 Long ID 排序的旧 DTO 指纹作为跨来源身份。缺失字段显示未知，硬条件不能被模型解释覆盖。

生成、缓存命中、历史详情、分享和 PDF 导出前都核验当前来源和读取权。来源改变时历史报告标为 `STALE`，可明确请求新报告；撤权时不返回旧正文/PDF；依赖故障返回结构化不可用。owner-only 报告索引和正文/文件校验替代裸 shareCode 读取。当前实现允许 owner 显式发布匿名公开分享；本 change 按新契约退役该授权方式，旧公开标记不再放行匿名读取，原文件保留供受控核对。

Studio 保留自己的工作区事务、候选版本、幂等回执和 `listing.comparison` 工件；它改用稳定来源引用与当前版本核验，并复用房源域确定性比较逻辑，不转为生成通用 Compare 报告或 PDF。

### 4. 合并时保留历史执行和 Memory 身份

已接受的 compare 调用继续按原 descriptor/taskId 对账或排空，不创建 listing 替代任务；旧检查点仍只读。新请求使用 listing 身份及必需 Memory（LR-22/23/96）。历史 `compare/compare-agent` 数据不批量改名，也不因 owner 相同开放给 listing；确需引用时使用现有受权共享导出。先保留已登记只读历史能力，确认无在途依赖后再停旧实例，不提前撤销原验证公钥。

### 5. 对公开契约明确迁移

新的规范比较入口是 `listing-agent` 的 `listing-comparison` 技能及其稳定来源引用/预期版本输入。Supervisor 从 `compare-mcp-server:compareListings` 迁到该技能。现有 `/compare/**` REST 可暂作兼容适配，只将 Long ID 解析为 `legacy-local` 并执行当前授权和来源复核；旧 MCP、独立 `compare-agent` 和 `CompareEngineRpcService` 在消费者迁移后停止接收新请求。`CompareEngineRpcService` 的接口虽只有 Long ID 参数，当前分析服务仍要求已验证的操作上下文并经签名 Listing 读取；迁退它的原因是契约无法表达跨来源引用和预期版本，不是把它描述为当前无鉴权。

当前显式发布的匿名分享能力不迁入新域；旧分享码只保留为迁移定位符，未通过当前用户及资源检查时不得返回报告或 PDF。Studio 仍由工作区命令维护工件和回执，只迁移来源读取与确定性计算。通用动态目录不增加关键词路由，新增技能不引入顺序检查器。

Auth 协议由其他会话维护；本域只提供资源引用与操作，使用其最终可信 principal/scope 适配器，缺少授权事实即拒绝。产品字段 brokerId、来源 ID 和报告 hash 均不授予权限。

### 6. 并行会话交付边界

本会话拥有房源/比较模块、`common/listing/**`、原比较契约及必要调用方的最小兼容差异。公开提供 `domain-parallel-v1` 的 search、batch-read、check 三个房源 POST 接口，路径、字段、错误和模式规则固定于 [并行总览](../../../docs/business-domain-change-roadmap.md)。本会话用自有 HTTP夹具/生产者处理器测试这些线协议，不等待经营/营销消费方。

独立验收使用当前基线，Auth/Memory 以测试配置下的精确登记替身覆盖成功、拒绝、撤回和不可用；真实模式保留原失败关闭。不得修改 Auth 协议/服务的实现或共享在线登记。自己的独立 worktree/分支、`.m2repo`、测试数据库与端口按总览隔离；只 push 自身工作分支。

根 POM、部署和 Agent 必要引用只提交与 compare/listing 有关的小范围语义差异。全局 Memory/RAG 历史登记保留；新增/退役密钥或共享 Nacos 的真正发布交协调门禁。本 change 完成条件是独立业务/来源/迁移/回退验证与可应用配置差异，真实共享环境切流和排空不在独立会话执行。

## Risks / Trade-offs

- [上游字段和单位不同] → 规范输入独立于旧 DTO，适配器校验单位、必填身份和版本，拒绝模糊数据。
- [一次比较读取期间来源变化] → 结果冻结版本并在输出/使用前复核；不能取得可核验快照时明确失败。
- [已有显式公开分享用户依赖匿名链接] → 新域不提供 bearer 式匿名访问；旧公开分享按私有/退役处理，保留原文件和迁移清单，不能按分享码或文件路径猜归属。
- [报告文件卷迁移期间出现双写或遗漏] → 对账 `compare-report-data` 中的 JSON/PDF 清单，切换时先停旧 Compare 写入，再由 listing 单独挂载并成为唯一写入方。
- [停 compare 导致任务丢关联] → 先清点并排空/对账，保留旧身份及部署回退制品；迁移回滚不重放业务调用。
- [模拟结果误当真实事实] → 数据模式从来源继承到报告/PDF/Agent 输出，不能由请求者改成 REAL。

## Migration Plan

以下是合并后由协调会话执行的共享环境计划。本会话实现相应兼容、围栏和回退能力，并在私有夹具/隔离库验证，不等待其它 change、不操作现有开发栈。

1. 固化 `/compare/**`、Compare MCP/A2A/RPC、Supervisor 与 Studio 调用方、Compose/Nacos 发布项、`compare-report-data` 文件清单，以及历史任务和 Memory 身份；明确来源映射和必要的数据初始化，不把文件报告虚构成数据库记录。
2. 在 listing 中实现 `legacy-local`、fixture 和 HTTP 来源适配，以及稳定引用/版本契约；先用 fixture 和现有本地数据映射验证授权、版本、金额，再按受管配置接上游 HTTP。
3. 迁入比较计算、报告缓存和 PDF；切换时复用或受控复制旧报告卷，核验 JSON/PDF 与 owner/source hash 元数据，确保旧 Compare 停写后 listing 成为唯一写入方。
4. 发布 `listing-agent` 的 `listing-comparison` 技能并更新 Supervisor；Studio 保留工作区工件/回执语义，改用稳定来源版本。旧 `/compare/**` 仅保留当前授权的 `legacy-local` 兼容；迁移旧公开分享为私有/退役，按原身份排空或登记未知任务。
5. 验证没有新消费者和活动调用后，从 reactor/Compose/配置发布列表中退役独立比较部署；报告卷、历史 Memory 空间及账本保留。回退恢复原匹配版本、卷和路由；旧任务按原身份恢复，不重放或批量重生成报告。

## Open Questions

真实房源接口的受管地址、凭据、字段映射和速率额度由部署接入提供。规范契约、模拟验收和上述任务拆分不依赖这些具体值；其补齐仅决定真实 HTTP 适配的联机验收时点。
