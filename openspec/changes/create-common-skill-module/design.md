<!-- 当前适用边界：2026-10-05 -->

本变更保留共享模块与普通技能浏览的历史实施记录。自 2026-10-05 起，受管 A2A 执行的激活以成功的 SkillExecutionContext 快照为准，显式选择在首轮前固定正文、身份及范围，并优先于 hint/历史激活。无效加载不激活；显式选择不可换名、换版本或扩权；实际工具边界也校验范围。旧空 tools 的全量语义仅保留于未显式选择的兼容路径。当前契约以 realign-supervisor-tool-and-skill-orchestration 的 explicit-skill-execution / subagent-skill-contract 为准，不通过本次同步勾选此历史变更剩余任务。

## Context

见 proposal.md - Why。现状要点：

- Skills 基建原型 7 个类全部位于 `agent-service`（`com.bkanent.agent.skill`），除 `SubAgentSkillSupport` 外均有 Bean 装配且 Supervisor 侧已接线（`SkillMatchNode` → `OfficialPlanningGraphFactory`）。
- 经 import 审计，`SkillFileLoader`、`SkillRegistry`、`SkillMatcher`、`SkillFileWatcher` 四类零依赖 agent-service 内部包，仅依赖 common 契约（`SkillDefinition`/`SkillMatchResult` 已在 `common`）、spring-core、spring-context、snakeyaml、jakarta.annotation、slf4j。
- `SubAgentSkillSupport` 无任何 Bean 装配与消费者，是写完即封存的孤儿；其"动态 ChatClient 旁路"设计于官方 A2A 迁移之前，产物类型（ChatClient）与 `OfficialA2aAgentExecutor` 的入参类型（ReactAgent）不匹配。
- 官方运行时已核实 `ModelRequest.Builder`（spring-ai-alibaba-agent-framework 1.1.2.3）支持按调用重写 `systemMessage`、按名收窄 `tools`、替换 `dynamicToolCallbacks`；`A2aSupervisorContextInterceptor` 已提供拦截器改写 system message 的先例。
- 8 个子 Agent 均以 `*OfficialA2aAgent` 构造 ReactAgent（一次性构建，systemPrompt 来自 nacos yaml），工具面为全量本地 @Tool。
- 配置现况：`agent.skills.external-dir`、`agent.skills.watch-enabled` 以 `@Value` 散落在 `SkillRegistry`/`SkillFileWatcher` 中。

## Goals / Non-Goals

**Goals:**

- skill 基建成为双端可依赖的独立库模块，子 Agent 编译期可达。
- 子 Agent 运行时具备"LLM 自主选技能 → 换装 prompt → 收窄工具"的完整路由能力。
- 技能文件（skill md）成为能力的部署单元：存盘即上架，热加载生效。
- contract 领域作为样板端到端打通，其余服务可按服务粒度自行挂载。

**Non-Goals:**

- 不改动 Supervisor 官方图结构与硬编码词表（另行移交）。
- 不做领域业务逻辑 AI 化（第二期 `ai-ify-subagent-domain-logic`）。
- 不做 skill 的中央化管理/注册中心分发（skill 为进程内本地资源，与 AgentCard 的 Nacos 注册形成对照）。
- 不做技能的类型化参数 schema 与校验（类型化输入属于技能内业务工具）。

## Decisions

### D1：新建 common-skill 模块而非下沉到 common

`common` 现承载 DTO/RPC 契约与少量注解依赖（dubbo、mybatis-plus-annotation），团队约定"common 只放基本类"。skill 基建含运行时组件与 spring-ai 依赖，独立成 `common-skill` 使依赖面清晰且不拖累契约模块。备选"下沉 common"被否：会让纯契约模块引入 Spring AI 传递依赖。

### D2：core 4 类平移，runtime 2 类新写

平移件（Loader/Registry/Matcher/Watcher）import 审计为零内部依赖，平移即用；唯一顺带改动是 `@Value` 收敛为 `@ConfigurationProperties`（`SkillProperties`，前缀 `agent.skills`），对齐 AGENTS.md 配置约定。runtime 件（SkillTool、SkillRoutingModelInterceptor）为本变更唯一新逻辑。

### D3：路由采用"技能即工具"（单伪工具 + 运行时 enum），LLM 自主选择为主

模型输出通道只有文本与工具调用，技能选择作为决策必须走结构化通道；enum 由 Registry 生成可防幻觉并随热加载自动更新。**否决关键词主路由**：词面匹配无法覆盖语义关联（如"获取时事"→ web_search），且原设计中 LLM 选择仅是"建议"、关键词才是执行者，属半截子设计。关键词匹配降级保留在 `SkillMatcher` 中作为显式点名捷径，不挡路。

### D4：换装采用"工具结果送达正文 + 拦截器收窄"双机制

SKILL.md 正文以 SkillTool 工具结果返回（Claude Code 同款机制），无状态、零会话存储；工具收窄由拦截器在每次模型调用前扫描消息历史中最近一次 skill 调用完成（`ModelRequest.Builder.tools()`），线性扫描成本可忽略。备选"拦截器独揽换装"需跨调用状态或改写历史，复杂度高；备选"动态 ChatClient 旁路"与官方 Executor 类型不匹配，废弃。

### D5：技能参数规范为 `{name: enum, task: 必填, context: 可选}`，无类型化业务参数

技能是能力包不是函数：类型化输入属于技能内业务工具（`@ToolParam` 已承载）。`task` 强制模型在切换时做任务重述，作为换装后注意力锚点与路由审计依据；`context` 为自由对象，v1 可观察其必要性再定去留。catalog/enum 不做手写清单，杜绝两套规范漂移。

### D6：边界行为定义

- enum 外技能名：返回错误 + 当前清单，模型自纠；
- 同执行内再调 skill：解释为切换（最新生效），不允许嵌套；
- 技能 tools 为空：仅换 prompt 不收窄；
- 不调用 skill：默认 prompt + 全量工具（fallback 即常态）。

### D7：服务接入方式为拦截器链挂载，按服务粒度灰度

各子 Agent 在 `*OfficialA2aAgent` 构造 ReactAgent 时追加 `SkillRoutingModelInterceptor`（Registry 注入）。服务之间互不影响，contract 先行，其余服务后续逐个跟进即可，无集中开关。

### D8：agent-service 切换策略

`SkillConfiguration` 改为从 common-skill 引入 core Bean；`SkillMatchNode`/`SupervisorSkillService`/`SkillAwareToolProvider` 留在 agent-service（Supervisor 专属），import 换包。`SubAgentSkillSupport` 删除（其有效逻辑已并入 SkillTool 与拦截器）。Supervisor 侧行为零变化。

## Risks / Trade-offs

- [`ModelRequest.tools()` 收窄语义未运行时验证] → 第一期实现前做半天 spike：构造最小 ReactAgent + 拦截器，确认名称列表即允许清单；若语义不符则降级用 `dynamicToolCallbacks` 通道，spec 行为不变。
- [目录与 enum 随技能数增长膨胀] → 现阶段 8 领域少量技能不构成压力；目录按 domain 过滤（`findOperationalSkills`）已预留。
- [拦截器每轮扫描消息历史] → 线性扫描且 skill 调用在单执行内唯一，成本可忽略；如未来消息超长可在扫描时自尾部截断。
- [技能 description 质量决定路由质量] → 落编写约定（场景导向、回答"用户带着什么需求来"）；启用路由审计日志（name+task）为调优提供数据。
- [平移后 agent-service 与 common-skill 短暂并存同名类] → 同一提交内完成 import 切换并删除旧包，全模块编译验证兜底。

## Migration Plan

1. 新建 common-skill 模块并平移 core（与 agent-service 切换同一提交完成，不留并存窗口）。
2. 新写 runtime 两件并配单测；先跑 `ModelRequest.tools()` spike 确认收窄语义。
3. contract 服务接入（pom、拦截器、skill 文件、nacos 配置），冒烟验证技能命中与回退。
4. 其余子 Agent 服务按各自节奏跟进挂载，不阻塞发布。

回滚策略：拦截器挂载为各服务独立改动，从拦截器链移除即回退默认路径；common-skill 模块的存在不影响未接入服务。

## Open Questions

- `ModelRequest.tools()` 的精确收窄语义（名称列表 vs 其他）——由第一期 spike 解决，不影响 spec 行为定义。
- Supervisor 侧 ReactAgent（若启用路径存在）是否同样挂载路由拦截器——属 Supervisor 范围，移交处理。
