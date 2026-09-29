## Purpose

定义领域业务逻辑的 LLM 化行为契约：合同风险审查、营销文案生成、对比结论生成在 LLM 驱动下的输入输出、来源标识与 fallback 策略，技能文件的全领域覆盖，以及 Supervisor 建议性技能提示的传递行为。所有 LLM 化路径必须具备可回退的降级路径，LLM 故障不得导致领域功能不可用。

## ADDED Requirements

### Requirement: 合同风险审查 SHALL 由 LLM 生成结构化风险结论并保留规则回退

contract 服务的合同风险审查 SHALL 以合同详情（用印状态、归档状态、OCR 摘要、附件等）为输入，由 LLM 生成包含风险等级、风险因素、处置建议与理由摘要的结构化结论；输出 SHALL 同时保留既有规则字段（合同号、状态、用印状态、归档状态、附件数量），并携带 `source` 标识（`llm` 或 `rule`）；LLM 调用失败或超时 SHALL 回退到既有规则判断并标识 `source=rule`；评审开关关闭时 SHALL 直接走规则路径。

#### Scenario: LLM 正常生成风险结论

- **WHEN** 对存在 OCR 摘要的合同发起风险审查且 LLM 服务可用、评审开关开启
- **THEN** 返回包含风险等级（low/medium/high）、风险因素列表、处置建议与理由摘要的结构化结论，且 `source=llm`，同时保留合同号与状态等既有字段

#### Scenario: LLM 失败回退规则

- **WHEN** 风险审查过程中 LLM 调用失败或超时
- **THEN** 返回规则判断结果（沿用用印/归档/OCR 完整性检查），`source=rule`，不向调用方抛出错误

#### Scenario: 开关关闭直接走规则

- **WHEN** 合同风险评审开关配置为关闭
- **THEN** 不发起 LLM 调用，直接返回规则判断结果

### Requirement: 营销内容创建 SHALL 支持 LLM 生成模式且与显式提交兼容

marketing 服务的内容创建工具 SHALL 在文案参数为空时触发 LLM 依据房源信息与目标平台生成文案草稿；生成模式 SHALL 记录来源标识（持久化 `source=llm`），显式提交文案的既有行为 SHALL 保持不变（`source=manual`）；生成失败 SHALL 返回明确错误且不产生空内容记录。

#### Scenario: 空文案触发生成

- **WHEN** 内容创建请求未提供文案但提供了房源与目标平台且 LLM 可用
- **THEN** 返回 LLM 生成的文案标题与正文，内容成功入库且来源标记为 llm

#### Scenario: 显式提交保持兼容

- **WHEN** 内容创建请求携带完整文案
- **THEN** 行为与改造前一致，不触发生成调用，来源标记为 manual

#### Scenario: 生成失败不落库

- **WHEN** 生成过程中 LLM 调用失败
- **THEN** 返回错误说明且不产生空文案的内容记录

### Requirement: 房源对比结论 SHALL 由 LLM 基于真实指标生成并具备降级路径

compare 服务的对比报告 AI 结论 SHALL 基于已计算的对比指标由 LLM 生成；参与对比的房源指标数据缺失或不可用时 SHALL 返回明确说明而非编造内容；LLM 调用失败 SHALL 回退到既有模板拼接并输出可用结论。

#### Scenario: 基于指标生成结论

- **WHEN** 请求生成含 AI 结论的对比报告且房源数据完整、LLM 可用
- **THEN** 结论内容与对比指标一致且为 LLM 生成，不再使用固定模板拼接

#### Scenario: 指标缺失时明确说明

- **WHEN** 参与对比的房源数据不足以支撑结论
- **THEN** 结论内容为明确的数据不足说明，不包含编造的对比判断

#### Scenario: LLM 失败回退模板

- **WHEN** 结论生成过程中 LLM 调用失败
- **THEN** 返回既有模板拼接的结论，报告生成整体不失败

### Requirement: 技能文件 SHALL 覆盖全部接入技能路由的子 Agent

启用技能路由的子 Agent 服务 SHALL 至少具备一个本领域的技能文件，其 description SHALL 以场景导向描述适用情形（回答"用户带着什么需求来"），其工具引用 SHALL 与该服务真实工具名一致，正文 SHALL 包含可执行的步骤指引。

#### Scenario: 领域技能覆盖检查

- **WHEN** 检查各启用技能路由的子 Agent 的技能资源
- **THEN** 每个服务至少存在一个合法技能文件，且引用的工具名均真实存在

#### Scenario: 技能命中后的行为遵循技能指引

- **WHEN** 用户请求命中某服务的领域技能且模型选择该技能
- **THEN** 后续轮次在技能提示词与收窄工具下按技能正文步骤执行

### Requirement: Supervisor 技能提示 SHALL 为建议性传递

Supervisor 侧编排产物可通过 A2A 请求的 supervisor 元数据携带建议技能提示（skillHint）；子 Agent 接收后 SHALL 仅在技能目录中对该技能置顶标记，不得强制激活、不得绕过模型自主选择；未携带提示的请求行为不变。

#### Scenario: 携带提示的请求

- **WHEN** Supervisor 下发的 A2A 请求携带技能提示且子 Agent 已启用技能路由
- **THEN** 子 Agent 技能目录中该技能被置顶标记，选择决策仍由子 Agent 模型作出

#### Scenario: 无提示的请求不受影响

- **WHEN** Supervisor 下发的 A2A 请求未携带技能提示
- **THEN** 子 Agent 技能目录渲染与一期行为完全一致
