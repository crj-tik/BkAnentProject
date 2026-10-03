# Tasks

本清单是后续实现计划；本次仅编写方案，所有任务保持未完成。执行前使用本变更的 design/specs，并核对 LR-17、KI-17/18；验收矩阵见 design.md。

## 1. 请求模式与能力身份契约

- [ ] 1.1 在 common 定义显式 skill 选择、技能类型/归属/版本、稳定 capabilityId 和错误码；通过序列化及旧字段兼容测试验证。
- [ ] 1.2 为 Supervisor 同步/异步请求增加可选 skill，统一归一化为 AUTO/技能模式；通过缺省、未知技能、跨 owner、旧 context 不强制路由测试验证。
- [ ] 1.3 定义 A2A Card skill ID→本地 skill name、支持契约版本与元数据扩展格式；交付九 Agent 映射表并用契约样例验证唯一映射。

## 2. 真实动态能力目录

- [ ] 2.1 修复 Agent Card discovery 的 skills/description/streaming/task 能力保真；通过真实字段样例映射测试验证，不以 transport 存在代替 streaming。
- [ ] 2.2 调整 A2AClient 缓存为目标 endpoint/版本变化可失效，同一 agentId 更新地址后重建；通过地址切换及执行中任务关联测试验证。
- [ ] 2.3 增加统一能力目录和无冲突模型工具名，将 A2A、静态/动态 MCP、本地工具映射为稳定身份；通过同名工具不覆盖及目录快照测试验证。
- [ ] 2.4 将授权可见性、真实健康和调用前有效性检查接入目录；通过无权限、冷启动词表无实例、动态撤销能力测试验证。
- [ ] 2.5 选择并验证现有运维环境支持 Agent Registry API 的 Nacos 版本及 HTTP Card fallback 策略；交付版本记录并通过实际注册→Card→endpoint→调用冒烟验证。

## 3. 共享技能模型与显式执行策略

- [ ] 3.1 扩展 SkillDefinition/SkillFileLoader，支持 knowledge/instruction/workflow、version、owner、参数 schema 和 capability policy；通过新格式解析与旧技能回归测试验证。
- [ ] 3.2 实现显式 instruction 策略，校验完整 allowlist 或显式 inherit，模型面与实际调用边界共用限制；通过工具缺失不开放全量、越界调用和 SkillTool 换名拒绝测试验证。
- [ ] 3.3 调整共享拦截器优先级为 explicit→模型主动技能→hint→默认能力；通过历史激活冲突、旧 hint 可覆盖及未指定路径测试验证 LR-9 兼容。
- [ ] 3.4 实现固定技能内容快照/版本存储与请求作用域；通过热更新后旧 run 恢复及同会话新请求不继承测试验证。
- [ ] 3.5 调整 Supervisor 知识技能加载和模型主动 instruction 技能选用，禁止关键词激活固定 workflow；通过技能触发词不改变 AUTO 模式测试验证。

## 4. Subagent A2A 契约及分批接入

- [ ] 4.1 在 OfficialA2aMetadataMapper、A2aInputParser 和执行器传递/校验独立 skillSelection 与父流程 lineage；通过显式、旧 hint、父 skill 不自动变子 skill 的契约测试验证。
- [ ] 4.2 对目标未支持 explicit、名称/owner/版本/内容身份不符统一返回结构化失败；通过不同发布版本的客户端/服务端兼容测试验证，不允许降级为 hint。
- [ ] 4.3 接入 listing/compare/marketing 三个示范 Subagent 的共享策略及 Card 映射；通过各服务默认 ReAct 与显式技能越界测试验证。
- [ ] 4.4 核对并接入 trade/media/contract/settlement/notification 的共享装配、工具名称及技能策略；通过五个服务契约样例与各自相关测试验证。
- [ ] 4.5 interview 仅接入治理面共享契约，保留运行面与 MCP 范围；运行 LR-3/4/5/8/13 相关回归，确认话轮入口不进入 Supervisor ReAct。

## 5. Supervisor AUTO 工具调用与可靠执行

- [ ] 5.1 实现 A2aToolCallbackProvider，把模型指令/结构化输入/可选子技能转换到既有 A2aExecutionService；通过真实执行、身份绑定、父子调用 ID 分离测试验证。
- [ ] 5.2 将 MCP/本地 ToolCallback 接入统一执行边界，保留真实 schema 及 allowMcp 限制；通过 A2A/MCP 混合调用、MCP 禁用与权限检查测试验证。
- [ ] 5.3 实现可恢复 SupervisorReactAgent 的模型→工具→模型循环和结果消息关联；通过直接回答、补充输入、多步委托及 nextHints 不自动交接测试验证。
- [ ] 5.4 增加模型轮次/总预算/独立调用并发约束及同模式失败处理；通过额度耗尽、模型不可用不转关键词路由和并发上限测试验证。
- [ ] 5.5 新增调用账本和技能快照 SQL migration/映射，扩展版本化 checkpoint；通过唯一调用 ID、完成结果复用、旧快照读取和 SQL 独立测试库验证。
- [ ] 5.6 在执行前保存待调用记录，接入远端 Task 查询/取消及 OUTCOME_UNKNOWN 对账；通过提交结果未知不盲目重发、重启续查和幂等 key 稳定测试验证。
- [ ] 5.7 将审批暂停/恢复绑定到实际调用 ID 和参数哈希；通过批准后只执行原动作、参数变化重新审批、重复回调及 LR-15 latest-checkpoint 测试验证。
- [ ] 5.8 扩展任务/产物/SSE 观测为 mode、capabilityId、callId、skill/version/stepId；通过事件重放及权限归属测试验证，输出仅含过程摘要与结果。

## 6. 显式 workflow 技能执行

- [ ] 6.1 定义有限无环步骤 schema、依赖、条件、输入/输出引用和能力范围预检；通过循环、非法引用、缺参数和后续必需能力缺失时零前序副作用测试验证。
- [ ] 6.2 实现通用 a2a/mcp/local/llm/approval 步骤执行器及固定目标校验；通过目标不能被模型替换、必要步骤不能跳过测试验证。
- [ ] 6.3 实现依赖驱动有界并行、条件 skipped 和结果数据绑定；通过并行汇合、条件分支和真实输出类型测试验证。
- [ ] 6.4 接入技能步骤的 checkpoint/账本/审批恢复，固定版本与内容引用；通过步骤完成后重启、热更新和取消测试验证。
- [ ] 6.5 依据真实 Card/tools/list 和输出 schema 发布“找房→对比→营销草稿”示范 skill；通过真实 A2A→MCP→A2A 执行顺序及结果传递记录验证。
- [ ] 6.6 将 trade 风险→contract 的固定业务流程编写为独立可选 skill，不在 AUTO 自动套用；通过显式选择才执行条件审查、nextHints 不能改写固定流程测试验证。

## 7. 入口切换、兼容与清理

- [ ] 7.1 将 Supervisor 各同步/异步入口和普通 chat 接到统一核心，保留旧响应字段；通过接口契约、allowMcp 和 run/session 标识测试验证。
- [ ] 7.2 为旧 checkpoint 和未完成任务保留版本化兼容 runner，新增接收任务默认 AUTO/显式 skill；通过旧审批待办恢复、新任务不走旧节点测试验证。
- [ ] 7.3 停用新模式的 ParseIntent/PlanTask/default-listing、自动 nextHints、trade 条件交接，灰度仅控制 runner 和同一能力版本；通过新请求调用链检查和无隐式业务路由测试验证。
- [ ] 7.4 在旧 run 清空后删除入口关键词配置和不再引用的旧路由链；通过调用者/配置审计、相关回归验证，保留可查询历史数据。
- [ ] 7.5 更新 README/API 样例、路由路线图及相交未完成变更，实施后同步本 delta 并更新 LR-17/KI-17/18 状态；通过文档链接和 OpenSpec 严格校验验证。

## 8. 跨模块验收与发布门禁

- [ ] 8.1 运行 common/common-skill/common-a2a/agent-service 和受影响领域服务的针对性测试，并使用 `mvn -gs .mvn-settings.xml -s .mvn-settings.xml compile` 验证全模块装配。
- [ ] 8.2 在分布式环境执行 design Acceptance Matrix，验证动态发现、显式技能、混合协议、审批重启和访谈边界；交付请求、调用记录及关键响应的验收报告。
- [ ] 8.3 用代表性正常/技能请求测量模型轮次、时延和工具成本，确定配置限额；交付基线、限额及耗尽行为报告。
- [ ] 8.4 演练先服务端后 Supervisor 的滚动升级，以及新旧 runner 分别恢复和暂停新接收的回滚路径；交付无任务丢失/重复副作用的演练记录。
