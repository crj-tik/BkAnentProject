<!-- 当前适用边界：2026-10-05 -->

本变更保留共享模块与普通技能浏览的历史实施记录。自 2026-10-05 起，受管 A2A 执行的激活以成功的 SkillExecutionContext 快照为准，显式选择在首轮前固定正文、身份及范围，并优先于 hint/历史激活。无效加载不激活；显式选择不可换名、换版本或扩权；实际工具边界也校验范围。旧空 tools 的全量语义仅保留于未显式选择的兼容路径。当前契约以 realign-supervisor-tool-and-skill-orchestration 的 explicit-skill-execution / subagent-skill-contract 为准，不通过本次同步勾选此历史变更剩余任务。

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
- [x] 5.5 contract 冒烟验证：风险审查请求命中技能（日志含技能选中与 task）；普通合同详情请求走默认路径不受影响 —— 运行时冒烟需 Nacos/DeepSeek/MySQL 分布式环境，当前以单测（SkillTool 命中/纠错/收窄语义 + 拦截器换装/回退）与全模块编译代替，环境可用后补充端到端冒烟
  - **2026-10-10 执行前置检查（未完成）**：本机 8848（Nacos）、3306（MySQL）、9012（contract-service）、11434（本地模型）均不可连接；运行中的 Docker 容器未包含本项目依赖，进程环境及仓库 `.env` 未提供 `DEEPSEEK_API_KEY` 或兼容模型验收地址。历史 Supervisor 真实模型评估使用下游业务夹具，不证明 contract 子 Agent 自主选技能或普通详情默认路径。源码检查另确认受管激活路径缺少技能名/task 日志（KI-46）。本次未发送真实模型请求、不修改技能语义、不以既有单测或历史验收勾选此项；需可用的独立验收 Nacos/MySQL、真实模型配置及授权使用的合同测试数据，并补齐受管激活观测后完成两类 A2A 冒烟。
  - **2026-10-10 端到端冒烟（已完成，证据见 `docs/acceptance/contract-skill-smoke-20261010.json`）**：本机 Nacos（隔离命名空间 bk-acceptance-smoke）+ MySQL（合成库 bk_contract_smoke_fe6bbaf091，合同 101）+ 本地真实模型 Ollama qwen3:4b-instruct-2507-q4_K_M（OpenAI 兼容端点，非云 Key，如实记录）+ 宿主机 contract-service。风险审查两次请求：模型自主调用 skill 伪工具加载 contract-risk-review（激活日志含技能名、task SHA-256 指纹、callId、threadId，task 原文不落日志），随后真实调用 getContractDetail（SQL DEBUG 日志）并输出 RISK_ALERT/high，风险因素逐项对应真实字段（用印 PENDING、归档 PENDING、OCR 条款缺失）；普通详情请求：激活日志计数不变（默认路径），getContractDetail 真实执行返回详情。模型措辞偏好等质量边界如实记录（KI-29），不将 4B 本地模型视为生产云模型质量证明。

### 5.5 验收补充任务（2026-10-10）

- [x] 5.6 配置去重：`.env`/示例只保留基础设施、启动连接和秘密，移除 Compose 重复业务覆盖；Nacos 明确管理模型、技能与业务开关，并验证有效配置及文档。
  - **2026-10-10 验证**：`docker compose config` 渲染后已无任何业务覆盖键（INTEGRATION_MODE/SEARCH_USE_ES/RANKING_USE_REDIS/STREAM_PROVIDER/MILVUS_ENABLED/TOKEN_TTL_SECONDS）；九个 nacos/*.yaml 关键业务值以字面值维护（auth TTL 3600/604800、四类集成 local、ranking redis、stream rocketmq、ES 开关、contract 模型 deepseek-chat/0.2/2000）；README、nacos/README、docs/contract-integration-providers.md、docs/linux-deployment-init.md、scripts/deploy/init-environment.sh 同步指引；Compose 保留 NACOS_USERNAME/NACOS_PASSWORD/MINIO_PUBLIC_BASE_URL 的 .env 插值覆盖（KI-40 处置）。
- [x] 5.7 修复 KI-46 受管激活日志：成功快照记录技能名、调用/线程和安全 task 指纹；验证成功、无效加载、默认未激活与日志注入边界，不改变 LR-21。
  - **2026-10-10 实现**：`SkillRoutingToolInterceptor` 成功激活日志含技能名、task SHA-256、字符数、callId、threadId（外部身份字段消除控制字符并限长 96，task 原文与技能正文不落日志）；指纹工具抽取为共享 `SkillLogFingerprints`；旧 `SkillTool`（未受管路径）遗留的 task 原文日志同步改为指纹形式。回归：`SkillRoutingToolInterceptorTest`（8 用例，含日志注入边界）、`SkillToolTest` 新增脱敏断言，common-skill 54 测试全过。端到端证据见 5.5（真实激活日志、默认路径零激活日志）。
- [x] 5.8 逐个启动验收必需容器，在独立 Nacos 命名空间及合成合同数据库执行真实 contract A2A 风险/详情两类请求，记录实际模型、调用证据及未通过边界，不将夹具视为真实验收。
  - **2026-10-10 执行（证据 `docs/acceptance/contract-skill-smoke-20261010.json`）**：MySQL + Nacos 容器逐个启动（Nacos 为当日新建空库，配置经 v3 admin API 发布到隔离命名空间）；contract-service 宿主机直跑（不启动完整服务集）；真实模型为本地 Ollama qwen3:4b-instruct-2507-q4_K_M（已如实记录，未声称 DeepSeek 云）。过程发现并修复 KI-47（自定义 AgentCard 抑制 Starter 属性装配，九服务均受影响的真实启动缺陷）。遗留边界如实记录：Starter 对含 `configuration` 字段的请求返回 -32700（客户端须按 SDK 序列化格式）；模型措辞偏差见 KI-29。

## 6. 收尾验证与文档

- [x] 6.1 `mvn -gs .mvn-settings.xml -s .mvn-settings.xml compile` 全模块编译通过
- [x] 6.2 `mvn -gs .mvn-settings.xml -s .mvn-settings.xml test` 相关模块测试通过
- [x] 6.3 按仓库约定分节点中文 commit 并 push（模块搭建 / runtime / agent-service 切换 / contract 样板）
