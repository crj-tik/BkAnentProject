## 1. contract 风险审查 LLM 化

- [ ] 1.1 对照 `A2aOutputPolicy.structured("contract")` 字段清单校准 `ContractAgentService` 输出契约，补契约测试
- [ ] 1.2 `ContractTools.reviewContractRisks` 改为委托 `ContractAgentService`，规则逻辑降级为 fallback（LLM 失败/超时/开关关闭三态），结果含 `source` 标识
- [ ] 1.3 nacos/contract yaml 增加生成开关与超时配置（`${VAR:default}` 约定）；单测覆盖 LLM 成功、fallback、关闭三路径

## 2. marketing 文案生成

- [ ] 2.1 复活 `MarketingAgentService`：按房源+平台生成文案草稿，内容标记 LLM 来源；失败不落库
- [ ] 2.2 `MarketingTools.createMarketingContent` 增加生成模式接入（保持既有提交模式兼容）；单测覆盖生成成功与失败两路径

## 3. compare 对比结论

- [ ] 3.1 `generateAiConclusion` 替换为基于对比指标的 ChatClient 调用，指标缺失返回明确说明；沿用报告缓存；单测覆盖有数据/缺数据两路径

## 4. 技能文件全覆盖

- [ ] 4.1 listing 服务新增技能文件（房源检索指引），description 场景导向
- [ ] 4.2 media 服务新增技能文件（素材生成任务指引）
- [ ] 4.3 settlement 服务新增技能文件（结算与打款指引）
- [ ] 4.4 notification 服务新增技能文件（通知发送指引）
- [ ] 4.5 各服务挂载技能路由拦截器并各自冒烟（沿用 contract 样板接入方式）

## 5. skill_hint 通道

- [ ] 5.1 Supervisor 侧编排产物在 A2A 请求 metadata 写入 `skill_hint`（建议性）
- [ ] 5.2 子 Agent 拦截器渲染目录时对提示技能置顶标记；单测覆盖有/无提示两路径

## 6. 收尾

- [ ] 6.1 全模块编译 + 相关模块测试通过
- [ ] 6.2 按仓库约定分节点中文 commit 并 push
