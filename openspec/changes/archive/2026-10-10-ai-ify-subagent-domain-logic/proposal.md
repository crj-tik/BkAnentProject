## Why

Skills 接线（`create-common-skill-module`，已完成）解决了能力部署单元的问题，但能力本身仍是空转：领域服务业务逻辑规则化——`ContractTools.reviewContractRisks` 纯 if/else、compare 的 `generateAiConclusion` 是字符串拼接、营销文案完全依赖外层模型自由发挥；而 LLM 领域能力早已以死代码形式存在（`ContractAgentServiceImpl`、`MarketingAgentServiceImpl` 均为零消费者的旧 A2A 信封服务）。本变更沿技能通道把领域逻辑"通电"：复活搁浅 LLM 核心、替换假 AI、补齐技能文件覆盖，并让 Supervisor 可向子 Agent 下发建议性技能提示。

## What Changes

- **contract 风险审查 LLM 化**：从 `ContractAgentServiceImpl` 抽取 LLM 评审核心（prompt 构造、JSON 解析、生成参数），`ContractAgentService` 新增领域方法 `reviewRisks(ContractDetailResponse)`；`ContractTools.reviewContractRisks` 改为委托 LLM 评审，输出契约合并（保留现有规则字段，叠加 `riskLevel`/`riskFactors`/`recommendedActions`/`summary`/`source`）；LLM 失败/超时/开关关闭三态回退现有规则判断。
- **marketing 文案生成模式**：`MarketingAgentService` 新增领域方法 `generateCopy(...)`（专用生成 prompt + 温度参数）；`MarketingTools.createMarketingContent` 在 `copywriting` 参数为空时触发专用生成（保持既有显式提交模式完全兼容）；内容实体新增 `source` 列（SQL 迁移）标记 `llm`/`manual`；生成失败不落库。
- **compare 对比结论真 AI 化**：`CompareAnalysisServiceImpl.generateAiConclusion` 从"最便宜/最大面积"模板拼接替换为基于已计算对比指标的 ChatModel 生成；仅 `includeAiConclusion=true` 时触发；LLM 失败回退模板拼接并日志标记；沿用既有报告缓存。
- **技能文件全覆盖**：listing、media、settlement、notification 四个服务新增各自领域的场景导向 skill md，并挂载 `SkillRoutingModelInterceptor`（复用 contract 样板接入方式）。
- **skill_hint 通道**：Supervisor 侧在 A2A 请求 metadata 的 supervisor 上下文中携带建议性 `skillHint`；子 Agent 拦截器渲染技能目录时对该技能置顶标记，不强制激活。
- 不改动：A2A 协议与输出契约结构、Supervisor 官方图、技能基建代码（仅消费）、旧 `*AgentService.invoke()` 协议方法（保留现状）。

## Capabilities

### New Capabilities

- `subagent-ai-domain-logic`: 定义领域业务逻辑的 LLM 化行为——合同风险审查、营销文案生成、对比结论生成的输入输出契约与 fallback 策略，技能文件全领域覆盖，以及 Supervisor 建议性技能提示的传递行为。

### Modified Capabilities

无。

## Impact

- **代码**：`contract-service`（AgentService 接口与实现、ContractTools、ContractAgentProperties、config）、`marketing-content-service`（同构）、`compare-engine-service`（CompareAnalysisServiceImpl、配置）、`agent-service`（A2aAgentClient 写入 skillHint）、`common-skill`（拦截器读取 hint，小改）。
- **依赖**：无新增第三方依赖（复用各服务既有 ChatModel 与 spring-ai 配置）。
- **配置**：`nacos/contract-service.yaml`、`nacos/marketing-content-service.yaml`、`nacos/compare-engine-service.yaml` 增加 LLM 评审/生成开关与参数（`${VAR:default}` 约定）。
- **Schema**：`marketing_content` 表新增 `source` 列（`sql/migrations/` 迁移脚本，默认 `manual` 向后兼容）。
- **前置**：依赖 `create-common-skill-module` 已合入（common-skill 自动装配、拦截器、SkillTool 均就绪）。
