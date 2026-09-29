# Proposal: create-interview-subagent

## Why

平台的 multi-agent 体系已具备技能基建（common-skill 已落地、contract 技能路由样板已跑通）与子 Agent AI 化在途，但缺少「从业务对话发起、由 AI 主导深访」这一获取一手业务事实的能力。S²访谈台已验证「代码定决策、模型只造句」的 AI 深访方法论，本 change 将其核心能力提炼为平台的第一个「前台对话服务」型子 Agent（interview-service），使访谈能力可被 Supervisor 编排、可被其他 Agent 以 MCP 复用。

## What Changes

- **新建 `interview-service` 子 Agent 服务**（domain=interview），A2A 治理面对齐 contract-service 样板：ReactAgent + `skills/interview/*.md` + SkillTool + 换装拦截器 + `A2aOutputPolicy.structured("interview")`。
- **新增访谈运行面（前台对话通道）**：REST/SSE 经 gateway `/interviews/**` 直连 interview-service，不走 Supervisor；固定话轮管线——确定性决策引擎（五动作/深度分级/换角度阶梯/收尾双通道+持久锁/防重复/违禁拦截）+ 每轮恰好一次造句模型调用 + 入模脱敏闸与出模质量门闸。
- **访后链路**：会话结束自动归集逐字稿（补偿扫描）、L0–L3 确定性归档评级（纯规则不用模型）、标准案例卡报告（durable 任务复用 `agent_async_task` 租约模式 + 服务端原声证据核验）。
- **新增 MCP 能力面**（`/mcp`，STREAMABLE，对齐 7 个既有双面服务惯例）：公开只读资产工具——`search_transcripts` / `get_case_report` / `submit_transcript` / `grade_transcript` / `generate_case_report`（异步任务）。管理动作（删除/改名/重评级）不进 MCP 面。
- **Supervisor 侧接入**：`AgentRegistration` 注册 interview-agent；新增 `skills/supervisor/interview-orchestration.md` 知识技能；`OfficialA2aMetadataMapper` 增加 `skill_hint` 元数据；静态 MCP client 连接增加 `interview-mcp-server`。
- **skill_hint 预激活**（从 `ai-ify-subagent-domain-logic` 提前实现）：Supervisor 匹配到意图后通过 metadata 建议子 Agent 初始技能，`SkillRoutingModelInterceptor` 支持按 hint 预激活（向后兼容，未携带 hint 的调用行为不变）。
- **开台双入口**：对话式（Supervisor A2A 委托，产出访谈入口卡片工件）与表单式（开台页面直调 interview-service REST）；两者写同一个领域服务与同一组表，`getInterviewMonitor` 等进度查询对两个入口无差别可见。
- **数据模型**：新增 10 张表（case/prep_card/question/session/turn/fact_summary/asset/report/playbook_card/commonality_report，其中报告任务复用 `agent_async_task`），SQL 放 `sql/migrations/`。

**范围边界（本 change = P1 MVP，文字优先）**：不做 CAS/邀请码/花名册鉴权（身份经 metadata 可信传递）；语音模式、受访人公网链接（P3）；小凤双脑 + 导演台、社区 26 题、人工提纲路径、打法卡生成 + 共性提炼（P2）；`verify_evidence` 证据核验保留在 interview-service 内部，不下沉 common。

## Capabilities

### New Capabilities

- `interview-subagent`: A2A 治理面——开台磋商与题目确认制、双入口一致性、会话凭据交接、访谈入口卡片工件、Supervisor/gateway 注册约定。
- `interview-live-runtime`: 运行面——固定话轮管线与确定性决策引擎、会话状态机与收尾锁、入模脱敏与出模质量门、可靠落库、访后归集与 L0–L3 评级、案例卡报告 durable 任务。
- `interview-mcp-tools`: MCP 能力面——公开只读资产工具的暴露范围、隔离边界（无管理动作）、异步任务语义。
- `subagent-skill-hint`: Supervisor 技能提示——metadata 新增 `skill_hint` 键、子 Agent 预激活行为、未携带 hint 时的向后兼容。

### Modified Capabilities

（无——`supervisor-domain-catalog` 的注册表派生机制天然覆盖新域 `interview`，注册即生效，不需要规格变更。）

## Impact

- **新模块**：`interview-service/`（pom、`src/main/java`、`src/main/resources/skills/interview/*.md`、`nacos/interview-service.yaml`）。
- **修改**：`agent-service`（`OfficialA2aMetadataMapper` 增加 skill_hint、AgentRegistration 配置、静态 MCP client 连接、`skills/supervisor/interview-orchestration.md`）；`common-skill`（`SkillRoutingModelInterceptor` 支持 hint 预激活，向后兼容）；`nacos/gateway.yaml`（新增 `/interviews/**` 路由）。
- **依赖现有基建**：`agent_async_task`（租约任务）、`agent_task_artifact`（入口卡片工件）、Milvus（行业大脑/知识检索，DashScope 向量）、S3/对象存储（提纲/报告文档）。
- **数据库**：`sql/migrations/` 新增 10 张 interview 前缀表。
- **对既有服务零破坏**：common-a2a 的 metadata 为自由 Map 透传新键；common-skill 拦截器改动对未携带 hint 的既有调用行为不变。
