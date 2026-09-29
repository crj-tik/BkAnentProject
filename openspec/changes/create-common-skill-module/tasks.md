## 1. Spike 验证

- [x] 1.1 编写最小 spike（临时测试）：构造最小 ReactAgent + 自定义 ModelInterceptor，运行时确认 `ModelRequest.Builder.tools(List<String>)` 收窄语义（名称列表即允许清单）；若语义不符，确认 `dynamicToolCallbacks` 降级通道可行，并在此任务下记录结论
  - **结论（源码级确认，无需 runtime）**：框架自带 `ToolSelectionInterceptor`（spring-ai-alibaba-agent-framework 1.1.2.3）即权威用法——`request.getTools()` 为工具名列表，收窄 = 过滤名单后 `ModelRequest.builder(request).tools(filtered).build()` 传给 handler；`request.getToolDescriptions()` 提供 name→description 映射可用于目录注入。框架另有 `SkillsAgentHook`/`SkillsInterceptor`（read_skill/search_skills 机制），但其契约（3 工具、无领域过滤、无 task 锚点、自带技能文件格式）与本变更 spec 不符，仅镜像其消息扫描与 systemMessage 叠加机制，不直接复用。
- [x] 1.2 spike 完成后清理临时验证代码，结论补充到本文件 1.1 任务描述下方（本 spike 采用源码审计方式，未产生需清理的临时代码）

## 2. common-skill 模块搭建

- [x] 2.1 新建 `common-skill` 模块：pom（parent bk-agent-project；依赖 spring-context、spring-core、snakeyaml、slf4j-api、jakarta.annotation-api，runtime 包依赖 spring-ai 与 spring-ai-alibaba-agent-framework）并加入根 POM `<modules>` 与 dependencyManagement
- [x] 2.2 平移 `SkillFileLoader`、`SkillRegistry`、`SkillMatcher`、`SkillFileWatcher` 至 `com.bkanent.common.skill.core`，包名与 import 相应调整，行为不变
- [x] 2.3 新增 `SkillProperties`（`@ConfigurationProperties("agent.skills")`：externalDir、watchEnabled），替换 4 类中的 `@Value`，提供自动装配入口（如 `@EnableConfigurationProperties` 或 auto-configuration 配置类）
- [x] 2.4 core 单测：classpath 加载、外部目录合并与同名覆盖、非法 frontmatter 跳过、领域过滤、配置绑定

## 3. runtime 组件（新逻辑）

- [x] 3.1 实现 `SkillTool`：单一伪工具（工具名 `skill`），schema 含 `name`（Registry 运行时生成 enum）、`task`（必填）、`context`（可选对象）；合法调用返回 SKILL.md 正文 + 技能工具清单 + task 回显；enum 外名称返回纠错信息与当前清单；缺失 task 返回参数错误
- [x] 3.2 实现 `SkillTool` 边界行为：同执行内再次调用 = 切换（最新生效）；技能 tools 为空时结果中说明保留全量工具
- [x] 3.3 实现 `SkillRoutingModelInterceptor`：每次模型调用前扫描消息历史中最近一次 skill 调用（无状态），命中则按 `ModelRequest.Builder` 换装 `systemMessage`（技能正文叠加既有系统约束）并收窄 `tools`（按 1.1 spike 结论选通道）；未命中时在系统提示词附加轻量技能目录（场景导向 description）
- [x] 3.4 runtime 单测：enum 生成与热加载更新、结果送达内容、切换生效、纠错回显、收窄与不收窄（tools 为空）、未命中目录注入

## 4. agent-service 切换

- [x] 4.1 `agent-service` pom 增加 common-skill 依赖；`SkillConfiguration` 改为消费 common-skill 的 core Bean（SkillRegistry/SkillMatcher/SkillFileLoader/SkillFileWatcher）
- [x] 4.2 `SkillMatchNode`、`SupervisorSkillService`、`SkillAwareToolProvider` 的 import 换至 common-skill 包，保留在 agent-service 不迁移
- [x] 4.3 删除 `agent-service` 旧 `skill` 包中被平移的 4 类与 `SubAgentSkillSupport`（有效逻辑确认已由 runtime 承接），全模块编译通过
- [x] 4.4 Supervisor 侧回归：`SkillMatchNode` 相关既有测试通过，`nacos/agent-service.yaml` 增加 `agent.skills` 段（语义与原 @Value 默认值一致）

## 5. contract 样板接入

- [x] 5.1 `contract-service` pom 增加 common-skill 依赖
- [x] 5.2 `ContractOfficialA2aAgent` 拦截器链挂载 `SkillRoutingModelInterceptor`（Registry 由 Spring 注入），保留既有 `A2aSupervisorContextInterceptor`
- [x] 5.3 新增 `contract-service/src/main/resources/skills/contract/contract-risk-review.md`：description 场景导向（何时需要风险审查），tools 引用 `getContractDetail`、`reviewContractRisks` 真实名，正文含执行步骤指引
- [x] 5.4 `nacos/contract-service.yaml` 增加 `agent.skills` 段
- [ ] 5.5 contract 冒烟验证：风险审查请求命中技能（日志含技能选中与 task）；普通合同详情请求走默认路径不受影响 —— 运行时冒烟需 Nacos/DeepSeek/MySQL 分布式环境，当前以单测（SkillTool 命中/纠错/收窄语义 + 拦截器换装/回退）与全模块编译代替，环境可用后补充端到端冒烟

## 6. 收尾验证与文档

- [x] 6.1 `mvn -gs .mvn-settings.xml -s .mvn-settings.xml compile` 全模块编译通过
- [x] 6.2 `mvn -gs .mvn-settings.xml -s .mvn-settings.xml test` 相关模块测试通过
- [x] 6.3 按仓库约定分节点中文 commit 并 push（模块搭建 / runtime / agent-service 切换 / contract 样板）
