## Why

项目已具备完整的 Skills 基建原型（Loader/Registry/Matcher/Watcher/SubAgentSkillSupport），但全部滞留在 agent-service 内部：8 个子 Agent 服务在 Maven 依赖方向上不可达，且 `SubAgentSkillSupport` 连 agent-service 自身都没有装配 Bean——skills 层全程悬空。同时子 Agent 业务逻辑规则化（如 `ContractTools.reviewContractRisks` 纯 if/else），LLM 能力没有部署单元。本变更先解决"接线"：建立可共享的 skill 基建模块，并以 LLM 自主选择为主路由接入官方 A2A 运行时，为第二期 AI 化改造提供能力部署单元。

## What Changes

- 新建 `common-skill` Maven 模块（parent `bk-agent-project`），含 `core`（加载/注册/匹配/热加载）与 `runtime`（SkillTool 伪工具、SkillRoutingModelInterceptor 换装拦截器）两个包。
- 将 agent-service 中零内部依赖的 4 个 skill 类（`SkillFileLoader`、`SkillRegistry`、`SkillMatcher`、`SkillFileWatcher`）原样平移至 `com.bkanent.common.skill.core`，`@Value` 配置收敛为 `@ConfigurationProperties`（`SkillProperties`）。
- 新写 `SkillTool`：单一伪工具，`name` 参数由 Registry 运行时生成 enum；工具结果返回 SKILL.md 正文作为执行指引；支持技能切换（最新生效）、enum 外名称纠错、tools 为空不收窄。
- 新写 `SkillRoutingModelInterceptor`：基于官方 `ModelInterceptor` 机制，无状态扫描消息历史中最近一次 skill 调用，按 `ModelRequest.Builder` 的 `systemMessage`/`tools` API 换装与收窄；未命中时注入技能目录（场景导向 description）。
- **BREAKING**（仅对 agent-service 内部）：`SubAgentSkillSupport` 删除，其 `buildCatalogPrompt`/`resolveSystemPrompt`/`resolveTools` 逻辑并入 SkillTool 与拦截器；`buildSkillClient`（动态 ChatClient 旁路）路径废弃。
- agent-service 切换：`SkillConfiguration` 改为消费 common-skill Bean，`SkillMatchNode`/`SupervisorSkillService`/`SkillAwareToolProvider` 保留在原模块，仅 import 换包，行为不变。
- contract-service 样板接入：pom 依赖 common-skill、`ContractOfficialA2aAgent` 拦截器链挂载 `SkillRoutingModelInterceptor`、新增 `skills/contract/contract-risk-review.md`、nacos 配置增加 `agent.skills` 段。
- 不改动：A2A 协议与输出契约、Supervisor 官方图结构、ReactAgent 唯一执行体地位、AgentCard 的 Nacos 注册机制。

## Capabilities

### New Capabilities

- `common-skill-module`: 定义共享 skill 基建模块的加载行为（classpath + 外部目录合并）、热加载、配置规范，以及 Supervisor 与子 Agent 双端可消费的模块边界。
- `subagent-skill-routing`: 定义子 Agent 运行时的技能路由行为——SkillTool 调用契约（enum/task/context、结果送达、切换与纠错边界）、拦截器换装与工具收窄、未命中回退默认路径，以及 contract 样板接入要求。

### Modified Capabilities

无。

## Impact

- **代码**：新增 `common-skill` 模块；`agent-service`（skill 包重构、pom）；`contract-service`（pom、a2a 配置、resources/skills）；`nacos/contract-service.yaml`、`nacos/agent-service.yaml`。
- **依赖**：common-skill 引入 spring-context、spring-core、snakeyaml、slf4j-api、jakarta.annotation-api；runtime 包另需 spring-ai 与 spring-ai-alibaba-agent-framework（消费方均已具备）。
- **配置**：`agent.skills.external-dir`、`agent.skills.watch-enabled` 语义不变，收敛到 `SkillProperties` 前缀 `agent.skills`。
- **风险**：`ModelRequest.tools()` 收窄语义需先做运行时 spike 确认；子 Agent 挂载拦截器按服务逐个灰度，contract 先行不阻塞其余服务。
