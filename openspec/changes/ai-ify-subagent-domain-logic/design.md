## Context

见 proposal.md。技能基建（common-skill）与 contract 样板接入由前置 change `create-common-skill-module` 交付，本变更只消费不改动基建。搁浅资产现状：

- `ContractAgentServiceImpl`：已有 ChatClient 解析 + 结构化风险 JSON + 规则 fallback 骨架，零消费者，输出契约未对齐官方 A2A。
- `MarketingAgentServiceImpl`：已有 `marketingChatClient` 生成调用与意图解析骨架，零消费者。
- `CompareAnalysisServiceImpl.generateAiConclusion`：模板拼接（最便宜/最大面积），无 LLM。
- 技能覆盖现况：trade/compare/marketing 各 1 个文件，listing/media/settlement/notification 为空。

## Goals / Non-Goals

**Goals:**

- 三个假 AI/规则点换成真实 LLM 逻辑，且都有规则/默认 fallback，不因 LLM 故障不可用。
- 技能文件覆盖全部启用路由的子 Agent，形成"能力部署单元"的完整内容。
- Supervisor → 子 Agent 的建议性技能提示通道打通。

**Non-Goals:**

- 不新增第三方 LLM SDK（复用各服务既有 ChatModel/ChatClient 与 DashScope/DeepSeek 配置）。
- 不改技能基建行为与 A2A 输出契约结构。
- 不做生成内容的自动发布（发布链路维持现状）。

## Decisions

### D1：复活优先于重写

两处搁浅 AgentService 的 LLM 调用骨架、prompt、解析逻辑可复用，复活成本低；改造点集中在输出契约对齐（`A2aOutputPolicy.structured` 字段约定）与工具入口委托。备选重写被否：收益不抵回归风险。

### D2：LLM 逻辑藏于工具实现内，通过既有 @Tool 暴露

ReactAgent 工具面不变（`reviewContractRisks` 等入口签名不变），内部从规则直算改为委托 AgentService 的 LLM 路径。这样 Supervisor 与技能层零感知，灰度开关也可在服务内收敛。备选"新增独立生成工具"被否：工具面膨胀且拆散入口语义。

### D3：fallback 保留规则路径并标识来源

LLM 输出结构中带 `source`（llm/rule）字段；超时/异常/开关关闭三种情况统一走规则。调用方（模型）可从结果中获知结论来源，避免把降级结论误当 LLM 分析。

### D4：skill_hint 走既有 A2A metadata 通道

不新增协议字段，metadata key 约定 `skill_hint`；子 Agent 拦截器渲染目录时置顶标记。建议性而非强制性，最终选择权留给子 Agent 模型，与 `subagent-skill-routing` 的自主选择原则一致。

## Risks / Trade-offs

- [LLM 生成内容质量不稳定] → prompt 中强制"仅基于输入数据"，指标缺失时明确说明；路由审计日志同步记录生成来源。
- [复活代码的输出字段与现行 A2A 契约漂移] → 改造前先对照 `A2aOutputPolicy.structured` 字段清单逐项校准，并补契约测试。
- [对比结论引入 LLM 增加报告生成时延] → 结论生成仅在 `includeAiConclusion=true` 时触发，沿用既有报告缓存。

## Migration Plan

contract → marketing → compare → 技能文件补齐 → skill_hint 通道，各服务独立灰度可独立回滚（开关关闭即回到规则路径）。

## Open Questions

无——范围与前置依赖均已明确。
