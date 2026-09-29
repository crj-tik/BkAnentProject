## 1. contract 风险审查 LLM 化

- [x] 1.1 `ContractAgentService` 接口新增领域方法 `reviewRisks(ContractDetailResponse detail)`；`ContractAgentServiceImpl` 实现：注入 `ChatModel`（非绑定工具的 contractChatClient），构造风险评审 prompt（输入 = 合同状态 + OCR 摘要 + 附件信息，输出 = riskLevel/riskFactors/recommendedActions/summary 的 JSON），复用花括号截取 + 解析降级策略；`ContractAgentProperties` 新增 `llmRiskReviewEnabled`（默认 true）
- [x] 1.2 `ContractTools.reviewContractRisks` 改为委托：开关开启且 LLM 成功 → 叠加 LLM 字段、`source=llm`；LLM 失败/超时/开关关闭 → 现有 3 项规则检查映射为 `riskFactors` + 建议动作、`source=rule`；既有字段（contractId/contractNo/status/sealStatus/archiveStatus/attachmentCount）全场景保留
- [x] 1.3 nacos/contract-service.yaml 增加 `contract.agent.llm-risk-review-enabled`、评审模型参数与超时（`${VAR:default}` 约定）
- [x] 1.4 单测：LLM 成功（source=llm、字段完整）、解析失败回退规则、开关关闭走规则、既有字段全场景保留

## 2. marketing 文案生成模式

- [x] 2.1 `MarketingAgentService` 接口新增 `generateCopy(String listingSummary, String platform)`；`MarketingAgentServiceImpl` 实现：无工具绑定 ChatModel + 专用生成 prompt（平台风格/字数/卖点结构）；`MarketingAgentProperties` 新增 `llmGenerationEnabled`
- [x] 2.2 `MarketingTools.createMarketingContent` 的 `copywriting` 参数改为可空：为空且房源/平台有效 → 经 RPC 取房源摘要并调用生成；不为空 → 既有路径不变；生成失败抛可读错误且不落库
- [x] 2.3 `sql/migrations/` 新增 `marketing_content.source` 列迁移（默认 `manual`）；实体与 mapper 同步；入库时按路径写 `llm`/`manual`
- [x] 2.4 nacos/marketing-content-service.yaml 增加生成开关与参数
- [x] 2.5 单测：空文案触发生成（source=llm）、显式提交兼容（source=manual、不触发生成）、生成失败不落库

## 3. compare 对比结论真 AI 化

- [x] 3.1 `CompareAnalysisServiceImpl.generateAiConclusion` 注入 ChatModel：指标齐备时基于对比指标生成结论（prompt 强制仅基于给定数据）；指标不足时维持现有明确说明短路；LLM 异常回退模板拼接（不抛出）；新增 `aiConclusionEnabled` 配置与属性类
- [x] 3.2 nacos/compare-engine-service.yaml 增加开关与参数；`includeAiConclusion=false` 路径不变；报告缓存结构不动
- [x] 3.3 单测：有数据走 LLM（mock）、缺数据短路说明、LLM 异常回退模板、开关关闭走模板

## 4. 技能文件全覆盖与挂载

- [x] 4.1 listing-master-service：`skills/listing/listing-search.md`（description 锚点：找房/查房源/推荐房源；tools：searchListings、getListingDetail）+ pom 依赖 + 拦截器/SkillTool 挂载 + nacos `agent.skills` 段
- [x] 4.2 media-worker-service：`skills/media/media-generation.md`（出视频/出图/生成素材；tools：submitMediaTask、queryMediaTask）+ 同构挂载
- [x] 4.3 settlement-service：`skills/settlement/settlement-processing.md`（佣金/结算/分成/打款批次；tools：calculateSettlement、getCommissionSummary、createPayoutBatch）+ 同构挂载
- [x] 4.4 notification-service：`skills/notification/notification-sending.md`（发通知/站内信/邮件提醒；tools：sendStationMessage、sendEmailNotification）+ 同构挂载
- [x] 4.5 技能文件编写约定核对：全部 description 为场景导向、工具名与真实 @Tool 一致

## 5. skill_hint 通道

- [ ] 5.1 Supervisor 侧（`OfficialA2aAgentClient` 请求构建处）将建议技能名写入 supervisor 元数据键 `skillHint`（来源：规划产物中的领域意图映射，建议性）
- [ ] 5.2 `SkillRoutingModelInterceptor` 渲染目录时读取 context 中的 `skillHint`：存在且为本领域技能 → 置顶并追加"（Supervisor 建议）"标记；缺失/不匹配 → 目录与一期一致
- [ ] 5.3 单测：带提示置顶标记、无提示行为不变、提示技能不属于本领域时忽略

## 6. 收尾

- [ ] 6.1 `mvn -gs .mvn-settings.xml -s .mvn-settings.xml compile` 全模块编译通过；contract/marketing/compare 测试全绿
- [ ] 6.2 按仓库约定分节点中文 commit 并 push（contract / marketing / compare / 技能覆盖 / skill_hint）
