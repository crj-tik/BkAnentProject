## Purpose

定义领域业务逻辑的 LLM 化行为契约：合同风险审查、营销文案生成、对比结论生成在 LLM 驱动下的输入输出、fallback 策略与开关控制，以及技能文件的全领域覆盖和 Supervisor 建议性技能提示的传递行为。

## ADDED Requirements

### Requirement: 合同风险审查 SHALL 由 LLM 生成结构化风险结论

contract 服务的风险审查 SHALL 以合同 OCR 摘要、条款与状态数据为输入，由 LLM 生成包含风险等级与风险因素的结构化结论；LLM 调用失败或超时 SHALL 回退到既有规则判断并明确标识结论来源；审查开关关闭时 SHALL 直接走规则路径。

#### Scenario: LLM 正常生成风险结论

- **WHEN** 对存在 OCR 摘要与条款内容的合同发起风险审查且 LLM 服务可用
- **THEN** 返回包含风险等级（low/medium/high）与风险因素列表的结构化结论，结论可追溯至条款依据

#### Scenario: LLM 不可用回退规则

- **WHEN** 风险审查过程中 LLM 调用失败或超时
- **THEN** 返回规则判断结果并标识为规则来源，不向调用方抛出错误

### Requirement: 营销内容创建 SHALL 支持 LLM 文案生成

marketing 服务的内容创建 SHALL 支持由 LLM 依据房源信息与目标平台生成文案草稿后入库；生成内容 SHALL 记录来源标识以便与人工提交内容区分；生成失败时 SHALL 返回明确错误而非空内容入库。

#### Scenario: 按房源与平台生成文案

- **WHEN** 请求为指定房源与目标平台生成营销文案且 LLM 可用
- **THEN** 返回包含文案标题与正文的草稿并成功入库，内容标记为 LLM 生成

#### Scenario: 生成失败不落库

- **WHEN** 文案生成过程中 LLM 调用失败
- **THEN** 返回错误说明且不产生空内容记录

### Requirement: 房源对比结论 SHALL 由 LLM 基于真实指标生成

compare 服务的对比报告 AI 结论 SHALL 基于已计算的对比指标由 LLM 生成；指标数据缺失时 SHALL 返回明确说明而非编造内容。

#### Scenario: 生成对比结论

- **WHEN** 请求生成含 AI 结论的对比报告且房源数据完整
- **THEN** 结论内容与对比指标一致且为 LLM 生成，不再使用固定模板拼接

### Requirement: 技能文件 SHALL 覆盖全部接入技能路由的子 Agent

启用技能路由的子 Agent 服务 SHALL 至少具备一个本领域的场景导向 description 技能文件，且工具引用 SHALL 与该服务真实工具名一致。

#### Scenario: 领域技能覆盖检查

- **WHEN** 检查各启用技能路由的子 Agent 的技能资源
- **THEN** 每个服务至少存在一个合法技能文件，且引用的工具名均真实存在

### Requirement: Supervisor 技能提示 SHALL 为建议性传递

Supervisor 侧编排产物可通过 A2A 请求 metadata 携带建议技能提示；子 Agent 接收后 SHALL 仅在技能目录中作置顶标记，不得强制激活或绕过模型自主选择。

#### Scenario: 携带提示的请求

- **WHEN** Supervisor 下发的 A2A 请求 metadata 含技能提示且子 Agent 已启用技能路由
- **THEN** 子 Agent 技能目录中该技能被置顶标记，选择决策仍由子 Agent 模型作出
