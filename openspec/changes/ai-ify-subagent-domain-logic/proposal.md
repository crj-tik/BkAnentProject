## Why

Skills 接线（`create-common-skill-module`）解决了能力部署单元的问题，但能力本身仍是空转：领域服务业务逻辑规则化——`ContractTools.reviewContractRisks` 纯 if/else、`MarketingTools.createMarketingContent` 纯存库、compare 的 `generateAiConclusion` 是字符串拼接；而 LLM 领域能力早已以死代码形式存在（`ContractAgentServiceImpl`、`MarketingAgentServiceImpl` 均为零消费者）。本变更沿技能通道把领域逻辑"通电"：复活搁浅 LLM 实现、替换假 AI、补齐技能文件覆盖，并让 Supervisor 可向子 Agent 下发建议性技能提示。

## What Changes

- 复活 `ContractAgentService`：合同风险审查改为 LLM 解析 OCR 摘要与条款生成结构化风险 JSON，原规则逻辑降级为 LLM 不可用时的 fallback；`ContractTools.reviewContractRisks` 改为委托，输出对齐 `A2aOutputPolicy.structured("contract")`。
- 复活 `MarketingAgentService`：营销内容创建增加 LLM 文案生成模式（按房源、平台生成草稿后入库），`MarketingTools.createMarketingContent` 接入。
- compare-engine-service：`generateAiConclusion` 从字符串拼接替换为真实 ChatClient 调用（基于对比指标生成结论）。
- 补齐技能文件覆盖：listing、media、settlement、notification 四个服务新增各自领域的 skill md（trade 已有），全部 description 遵循场景导向编写约定。
- `A2aInput` metadata 支持 `skill_hint`：Supervisor 可向子 Agent 下发建议性技能提示，子 Agent 侧仅作目录置顶标记，不强执行。
- 各子 Agent 服务挂载技能路由能力（沿用 `create-common-skill-module` 的接入方式），启用路由审计日志（name + task）。
- 不改动：A2A 协议与输出契约结构、Supervisor 官方图、技能基建代码（仅消费）。

## Capabilities

### New Capabilities

- `subagent-ai-domain-logic`: 定义领域业务逻辑的 LLM 化行为——合同风险审查、营销文案生成、对比结论生成的输入输出契约、fallback 策略，以及技能文件覆盖与 skill_hint 下发行为。

### Modified Capabilities

无。

## Impact

- **代码**：`contract-service`（AgentService 复活、Tools 委托）、`marketing-content-service`（同）、`compare-engine-service`（结论生成）、`common-a2a`（A2aInput metadata 约定）、各子 Agent `resources/skills/`。
- **依赖**：无新增（LLM 能力复用各服务既有 ChatModel/ChatClient）。
- **配置**：各服务 nacos yaml 增加 LLM 生成相关参数（温度、超时、开关），遵循 `${VAR:default}` 约定。
- **前置**：依赖 `create-common-skill-module` 完成并合入。
