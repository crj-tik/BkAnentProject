# Tasks

本清单跟踪最终第一种方案的实施进度，仅在行为实现并验证后勾选。执行前查阅 design/specs、LR-17/18、KI-17/18/19。确定性测试验证调用治理，真实模型评估仅观察正文流程遵循，不证明顺序强保证。

## 1. 请求选择与执行身份契约

- [x] 1.1 在 common 定义可选 skillSelection、owner/version/contentHash、AUTO/EXPLICIT_SKILL、稳定 capabilityId 及错误码；通过序列化和旧客户端兼容测试验证。
- [ ] 1.2 为 Supervisor 同步/异步与普通 chat 增补 skill、continueRunId/runId 和 WAITING_USER_INPUT 语义，区分新 run 与明确续接；通过作用域、同会话不继承、跨 owner/错误状态/重复续接和旧响应字段测试验证，续接沿用原 mode/session/请求政策与累计预算。
- [ ] 1.3 发布九 Agent 的 Card skill ID→本地 skill name/owner/version 及 explicit 支持版本映射；通过契约样例和唯一映射校验验证，不用默认 intent 或字符串猜测。

## 2. 真实动态能力目录

- [x] 2.1 修复 Agent Card discovery 描述、skills、streaming/task 等能力字段保真；通过真实字段样例和注册契约测试验证。
- [x] 2.2 修正 A2AClient 按 endpoint/能力版本刷新及执行中远端 Task 地址关联；通过同 agentId 地址切换与旧任务续查测试验证。
- [ ] 2.3 建立统一能力目录、稳定命名空间和实际工具定义，把 A2A、静态/动态 MCP、本地能力映射为无冲突名称；通过同名工具不覆盖和真实回调绑定测试验证。
- [ ] 2.4 接入目录授权可见性、逐轮快照及执行前目标/权限再校验；通过冷启动无假 Agent、动态增加/撤销、无权限及 MCP 禁用测试验证。
- [ ] 2.5 选定并记录运维环境支持 Agent Registry API 的 Nacos 版本与 HTTP Card fallback 配置；通过实际注册→Card→endpoint→调用冒烟验证，更新 KI-18 验证结论。

## 3. 共享技能加载和严格能力范围

- [x] 3.1 保持现有 frontmatter+Markdown 和知识标记，扩展 owner/version/capabilities 策略及显式专用发布标记；通过旧技能解析、非空 tools 映射、旧空 tools 兼容和不解析步骤 DSL 的测试验证。
- [ ] 3.2 接入 Supervisor 技能摘要目录和 skill 加载工具，保留 Subagent 既有组件；通过首轮摘要、成功加载后正文/原始任务/工具范围可见、加载失败不激活及 explicit-only 不被猜名绕过的测试验证。
- [ ] 3.3 在显式请求首轮模型前校验与加载全文/有效工具；通过自然语言参数理解所需上下文、未知/跨 owner/版本错、能力缺失和 allowMcp=false 冲突测试验证。
- [ ] 3.4 在模型面和实际回调边界共用有效范围，显式选择高于历史激活/hint且禁止换名/换版本/扩权；通过交集为空不开放全量、越界工具和 LR-9 旧 hint 可覆盖回归验证。
- [ ] 3.5 将成功加载的正文/能力范围/身份保存为执行快照，区分选择来源并以 run 隔离；通过热更新恢复、续接快照和同会话新请求不继承测试验证。

## 4. Subagent 共享契约同步

- [ ] 4.1 在 A2A metadata/input/executor 传递与校验 skillSelection 和 parentRunId/callId/parentSkill；通过父 skill 不自动变子 skill、显式/旧 hint 参数契约和身份绑定测试验证。
- [x] 4.2 对未支持 explicit、名称/归属/版本/内容身份不符返回结构化失败，不降级 hint；通过滚动发布兼容与无越权执行测试验证。
- [ ] 4.3 先接入 listing/compare/marketing，复用各服务 ReAct、本地工具和业务逻辑；通过三个服务的映射样例及相关范围回归验证。
- [ ] 4.4 再接入 trade/media/contract/settlement/notification 的共享契约与范围策略；通过五个服务的映射样例和默认/显式执行回归验证。
- [ ] 4.5 interview 只升级治理面契约；运行 LR-3/4/5/8/13 相关回归，确认高频话轮、确定性决策、状态机和 MCP 范围不迁移到 Supervisor。

## 5. Supervisor 通用模型工具循环

- [ ] 5.1 实现单轮模型适配，只返回 AssistantMessage/toolCalls，不内部执行回调；通过带工具调用的受控模型测试确认副作用次数为 0，再与 1.1.2.3 实际 API 做契约冒烟验证。
- [ ] 5.2 实现 A2A ToolCallback，把 instruction/结构化输入/可选下游 skill 转到既有 A2aExecutionService；通过真实响应规范化、稳定子调用身份和官方 Task/Artifact 关联测试验证。
- [ ] 5.3 将 MCP/本地回调接入同一 ExecuteTool 治理入口，保留实际 description/schema 和能力映射；通过 A2A/MCP 混合工具决策、参数错误及唯一执行次数测试验证。
- [ ] 5.4 用通用 PrepareContext/Model/Dispatch/LoadSkill/GuardCall/ApprovalGate/ExecuteTool/Observe/Complete 图替代新请求的领域路由；通过新增 Agent/skill 不增加业务节点、AUTO/EXPLICIT_SKILL 同循环和 nextHints 不自动调用测试验证。
- [ ] 5.5 实现 request_input 控制调用、待输入保存和 continueRunId 续接，把原始请求/补充信息/真实结果持续返回模型；通过追问无业务动作、幂等续接及 explicit 快照不改变测试验证，不用问号或关键词分类等待。
- [ ] 5.6 限制每轮至多一个单独的 skill/request_input 控制调用，控制与业务混合或多个控制调用均整批拒绝并为每个 callId 返回未执行结果；通过无技能激活/无等待状态变化/无业务副作用、下一轮分轮继续和消息关联测试验证。
- [ ] 5.7 增加模型轮次/工具预算/并发上限和有界同模式重试；通过独立调用并行、额度耗尽、模型不可用不转关键词和取消测试验证。
- [ ] 5.8 验证 GuardCall/Complete 只执行通用治理，不检查正文步骤顺序/前置业务步骤/文字流程完成；通过受控模型提出范围内重排/提前结束的测试及新拓扑审计验证第一种边界。

## 6. 调用账本、审批与恢复

- [ ] 6.1 提供调用账本和技能快照 SQL migration/映射，扩展 runnerVersion、模式、模型消息、待调用/输入/审批和预算的 checkpoint；通过独立测试库、runId+toolCallId 唯一和旧 checkpoint 读取测试验证，不新增技能步骤进度表。
- [ ] 6.2 实现执行前写调用记录、执行后保存结果及工具消息，接入远端 Task 续查/取消与 OUTCOME_UNKNOWN 对账；通过已完成结果复用、提交结果未知不盲重发、稳定子 task/thread 和重启测试验证。
- [ ] 6.3 将审批绑定实际调用 ID/目标/参数哈希，批准后恢复原调用、变参重新审批；通过未批准零调用、拒绝/取消不可绕过、重复回调及 LR-15 最新 checkpoint 待办回归验证。
- [ ] 6.4 扩展任务/产物/SSE 为 mode、capabilityId、callId、skill/version、待输入及实际结果；通过重放/断点、owner 可见性测试验证，不输出隐藏推理或虚构技能步骤状态。

## 7. 示范技能、切换与文档

- [ ] 7.1 根据真实 Card/tools/list 编写显式专用 Markdown “找房→对比→营销草稿”技能，正文说明调用对象、参数来源和信息不足行为；通过解析、能力范围绑定和发布元数据契约验证，不添加 DAG/顺序校验配置。
- [ ] 7.2 将各 Supervisor 入口和普通 chat 接到统一核心，保留原响应字段、allowMcp和审批入口，提供旧 domain/requireParallel/workflowType 迁移说明；通过同步/异步/续接接口契约测试验证。
- [ ] 7.3 按 runnerVersion 保留旧未完成任务恢复，新请求停用关键词/default listing/default intent、trade 自动交接与 nextHints handoff；通过旧审批恢复、新请求调用链和灰度不替换业务目标测试验证。
- [ ] 7.4 旧 run 排空后删除旧路由节点/配置/死链，更新 README、路线图、相交未完成变更并同步本 delta；通过配置/调用审计与严格校验验证，更新 LR-17/18 和 KI-17/18 状态，保留 KI-19 限制。

## 8. 验收与发布

- [ ] 8.1 运行 common/common-skill/common-a2a/agent-service 和受影响服务的针对性测试，使用 mvn -gs .mvn-settings.xml -s .mvn-settings.xml compile 验证全模块装配；新增相关状态转换点同步补 KI-2 要求的回归用例。
- [ ] 8.2 在真实分布式环境执行 design 的确定性验收，交付动态发现、混合协议、技能范围、审批/待输入恢复及访谈边界的请求和调用记录。
- [ ] 8.3 用代表性请求评估模型意图理解、正文顺序遵循、遗漏/重复动作、参数真实性和输出质量，记录模型版本、偏差、轮次、时延与成本并确定预算；报告明确评估通过不代表顺序强保证。
- [ ] 8.4 演练先服务端后 Supervisor 的滚动升级、新旧 runner 分别恢复以及暂停新接收的回滚；交付任务与已完成调用不重复推进的演练记录。
