## Context

见 proposal.md。技能基建（common-skill：自动装配、SkillTool、SkillRoutingModelInterceptor）与 contract 样板挂载已由一期交付，本变更只消费不改动基建语义。经代码核查的搁浅资产现状：

- `ContractAgentServiceImpl` / `MarketingAgentServiceImpl`：均为**旧 A2A 信封服务**——接口是 `invoke(AgentTaskInvokeRequest): AgentTaskInvokeResponse`，LLM 核心（system prompt、用户 prompt 构造、JSON 提取解析、生成参数）与 A2A 信封耦合在同一方法内；零消费者。
- 两服务均存在专用 ChatClient Bean（`contractChatClient` / `marketingChatClient`），但**绑定了自己领域的全量工具**（`defaultToolCallbacks`），用于旧设计的自循环 ReAct；直接复用会让"工具内嵌套 LLM 调用"再次陷入工具循环。
- 两服务的 `*AgentProperties` 均有 model/temperature/maxTokens/systemPrompt 字段，**无启用开关**。
- `ContractTools.reviewContractRisks`：3 项 if/else（seal_pending / archive_pending / ocr_summary_missing），输出为合同状态字段 + risks 列表。
- `CompareAnalysisServiceImpl.generateAiConclusion`：模板拼接（最便宜/最大面积），报告有缓存层（`CompareReportCacheServiceImpl`）；compare 服务存在可用 ChatModel（官方 Agent 构造在用）。
- `MarketingContent` 实体无来源列（有 tags/platformVariant 等，无专用字段）。
- A2A metadata 通道：`A2aInput.metadata` → `OfficialA2aAgentExecutor` 逐项写入执行上下文 → 子 Agent 拦截器经 `ModelRequest.getContext()` 读取（Supervisor 上下文拦截器已验证该链路）。

## Goals / Non-Goals

**Goals:**

- 三个规则化/假 AI 点接入真实 LLM 逻辑，全部具备降级路径（规则回退或模板回退），LLM 故障不导致领域功能不可用。
- 技能文件覆盖全部启用路由的子 Agent（补 listing/media/settlement/notification 四家并挂载路由）。
- Supervisor → 子 Agent 的建议性技能提示通道打通（不强制）。
- 旧 AgentService 的 LLM 资产被领域工具真正消费，消除"死代码"状态。

**Non-Goals:**

- 不新增第三方 LLM SDK；不做旧 `invoke()` 协议方法的迁移或删除（保持现状，避免触碰潜在兼容面）。
- 不改技能基建行为与 A2A 输出契约结构；不动 Supervisor 图。
- 不做生成内容的自动发布、不做对比报告缓存结构升级（aiConclusion 保持 String 契约）。

## Decisions

### D1：复活 = 抽取 LLM 核心，不复活 invoke 协议

旧服务的价值在 LLM 调用核心（prompt 构造、JSON 提取、生成参数），而非 `invoke()` 的 A2A 信封。做法：在现有 `ContractAgentService` / `MarketingAgentService` 接口上**新增领域方法**（`reviewRisks(ContractDetailResponse)`、`generateCopy(...)`），实现内复用同一 ChatModel 与 properties；`invoke()` 保持原样。备选"直接让工具调用 invoke()"被否：把 A2A 信封对象塞进工具调用是协议倒挂，且旧信封的上下文构造与工具入参不匹配。

### D2：嵌套 LLM 调用使用无工具绑定的 ChatModel

工具内发起的 LLM 调用必须是纯文本进/JSON 出：注入 `ChatModel` 并按 properties 构造一次性 options（模型/温度/ tokens），不注入任何 ToolCallback——否则嵌套模型会尝试在工具调用中再调工具，产生不可控行为。既有的 `*ChatClient` Bean（绑定工具）不用于本变更。备选"复用绑定工具的 ChatClient"被否：嵌套工具循环风险。

### D3：contract 输出契约 = 规则字段保留 + LLM 字段叠加 + source 标识

`reviewContractRisks` 返回 Map 结构扩为：既有字段（contractId/contractNo/status/sealStatus/archiveStatus/attachmentCount）+ 新增 `riskLevel`（low/medium/high）、`riskFactors`（LLM：条款级因素；规则：现有 3 项检查）、`recommendedActions`、`summary`、`source`（llm/rule）。规则路径 = 现 3 项 if/else 原样保留并映射到新字段（`decision` 概念并入 `recommendedActions`，不单独输出）。开关：`ContractAgentProperties` 新增 `llmRiskReviewEnabled`（默认 true），nacos 可关。

### D4：marketing 生成模式以"文案缺省"触发，零破坏兼容

`createMarketingContent` 的 `copywriting` 参数允许为空：为空且房源 ID/平台有效时，先取房源摘要作为上下文调用 `generateCopy`（专用生成 prompt：平台风格、字数、卖点结构），生成结果入库；不为空时走既有路径。来源标识：`marketing_content` 表新增 `source` 列（`llm` / `manual`，默认 `manual`），经 `sql/migrations/` 迁移脚本添加，实体与 mapper 同步。生成失败抛出可读错误、不落库。备选"新增独立生成工具"被否：工具面膨胀且与内容创建语义重叠。

### D5：compare 结论生成遵循"指标护栏"

`generateAiConclusion` 注入 ChatModel，prompt 输入为已计算的对比指标（总价/单价/面积/户型分布等），明确要求"仅基于给定数据"；`includeAiConclusion=false` 或数据不足时维持现有明确说明文案；LLM 异常时 catch 后回退模板拼接（不抛出，报告整体不失败），日志记录来源。`aiConclusion` 保持 String 契约与缓存结构不变；来源仅日志记录不进响应 DTO（避免缓存实体与契约变更）。

### D6：skill_hint 走 Supervisor 既有元数据通道

Supervisor 侧在构建 A2A 请求时，将建议技能名放入 supervisor 元数据（与现有 supervisor 上下文同通道），键约定 `skillHint`（单技能名，本期不做多技能列表）。`SkillRoutingModelInterceptor` 从 `ModelRequest.getContext()` 读取该键：存在且匹配本领域技能时，目录渲染将该技能置顶并追加"（Supervisor 建议）"标记；不匹配或缺失时目录渲染与一期一致。提示仅影响排序与标记，不改变选择权。

### D7：技能文件清单与挂载（复用一期样板）

| 服务 | 技能文件 | description 场景锚点 | tools |
|---|---|---|---|
| listing-master-service | `skills/listing/listing-search.md` | 找房/找房/查房源/推荐房源 | searchListings、getListingDetail |
| media-worker-service | `skills/media/media-generation.md` | 出视频/出图/生成素材/宣传物料 | submitMediaTask、queryMediaTask |
| settlement-service | `skills/settlement/settlement-processing.md` | 佣金/结算/分成/打款批次 | calculateSettlement、getCommissionSummary、createPayoutBatch |
| notification-service | `skills/notification/notification-sending.md` | 发通知/发消息/站内信/邮件提醒 | sendStationMessage、sendEmailNotification |

四家服务 pom 加 common-skill 依赖，`*OfficialA2aAgent` 拦截器链挂载 `SkillRoutingModelInterceptor` + 工具面追加 `SkillTool`（与 contract 完全同构），nacos yaml 增加 `agent.skills` 段。

### D8：配置与可观测性规范

开关命名：`llmRiskReviewEnabled`（contract）、`llmGenerationEnabled`（marketing）、`aiConclusionEnabled`（compare），nacos 中以 `${VAR:default}` 透出。可观测性：技能选择审计（name+task）一期已在 SkillTool 落日志，本期补齐三条 LLM 路径的来源与耗时日志（服务名、动作、source、耗时、成功/降级）。

## Risks / Trade-offs

- [工具内嵌套 LLM 调用增加该次工具的响应时延] → 生成参数限制 maxTokens；超时配置独立可调；失败立即回退，不重试堆叠时延。
- [LLM 结构化输出解析失败] → 沿用旧服务的"花括号截取 + 解析失败降级"策略，解析失败视同 LLM 失败走回退。
- [marketing source 列迁移] → 迁移脚本默认值 manual，向后兼容；未执行迁移的服务以启动校验报错提示（沿用仓库迁移纪律）。
- [对比结论 LLM 幻觉] → prompt 强制"仅基于输入指标"，指标为空直接短路返回说明，不给模型发挥空间。
- [skill_hint 与目录排序冲突] → hint 仅置顶标记，priority 排序逻辑不变；hint 技能不存在于本领域时静默忽略。
- [复活代码与现行 A2aOutputPolicy 契约漂移] → 本期 LLM 核心不直接产出 A2A 响应（工具返回 Map 由官方 Executor 统一封装），天然规避契约漂移。

## Migration Plan

contract → marketing → compare → 四家技能文件与挂载 → skill_hint 通道，按序小步提交。回滚：三个开关关闭即回到一期行为；source 列保留不回删。

## Open Questions

无——挂载方式、输出契约、降级矩阵、通道键名均已确定。
