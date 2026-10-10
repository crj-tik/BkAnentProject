# Design: create-interview-subagent

## Context

仓库现状（约束设计的既定事实）：
- 子 Agent 样板已跑通：`ContractOfficialA2aAgent`（ReactAgent + SkillTool + SkillRoutingModelInterceptor + `A2aOutputPolicy.structured(domain)`），7 个业务服务已是「A2A agent + MCP server」双面暴露，agent-service 是静态 MCP client。
- common-skill 已落地：技能 = `classpath:skills/{domain}/*.md`（frontmatter + systemPrompt），SkillRegistry 加载、SkillFileWatcher 热更新、换装拦截器无状态扫描消息历史。
- `A2aInput.metadata` 为自由 Map 透传，supervisor 命名空间已有 intent/domain/expectedOutput/idempotencyKey 等，无 skill_hint、无 principal。
- `A2aSupervisorContextInterceptor` 是各服务目录下的**副本惯例**（不在 common-a2）。
- gateway 路由集中在 `nacos/gateway.yaml`，目前无 `/interviews` 路由；MCP/A2A 服务间直连不经 gateway。
- 全仓库无任何访谈域代码，本 change 为全新领域。

方法论来源（S²访谈台 v12.0 提炼，详见 `docs/ai-interview-subagent-design.md`）：代码定决策、模型只造句；访中只还原故事；AI 产出过服务端证据核验；远端操作幂等可恢复。

## Goals / Non-Goals

**Goals:**
- interview-service 三面一体落地：治理面（A2A/ReAct）、运行面（REST+SSE 固定话轮管线）、能力面（MCP 只读资产）。
- 话轮决策全部纯函数化并可单测；每轮恰好一次造句模型调用；入模脱敏 + 出模质量门两道闸。
- 访后链路闭环：归集补偿 → L0–L3 确定性评级 → 案例卡报告 durable 任务（租约 + 幂等 + 原声核验）。
- skill_hint 跨链路打通（Supervisor → metadata → 拦截器预激活），向后兼容。
- 双入口（对话式/表单式）一库三读法，会话状态唯一属 interview-service。

**Non-Goals:**
- 语音模式、受访人公网链接、小凤双脑/导演台、社区 26 题、打法卡/共性提炼生成（P2/P3；`playbook_card`、`commonality_report` 两表本期只建表不实现）。
- 组织鉴权/花名册权限（MCP 面按「公开只读」边界实现）；`A2aSupervisorContextInterceptor` 副本收编（另立 change）。
- `verify_evidence` 下沉 common（留驻本服务）。

## Decisions

### D1 · 三面一体，运行面不走 ReactAgent

治理面对齐 contract 样板；运行面是独立的前台对话通道（gateway `/interviews/**` 直连），理由：A2A 语义是低频委托，话轮是高频流（15–60 分钟、几百次调用、6 秒 SLO），ReAct 的多轮工具循环、线性消息历史、输出无拦截点三者在话轮场景都不成立。
- 备选「每轮一次 A2A 委托」被否：往返开销 ×几百次，Supervisor 介入无增值。
- 备选「ReactAgent + 决策工具」被否：决策执行依赖模型自觉，属软约束（原产品收尾锁事故的教训）。

### D2 · 话轮管线：纯函数决策 + 单次造句 + 两道闸

```
turn → 入模闸(脱敏:姓名/手机/价格/地址,映射表仅存服务端)
     → 信号识别 → ProbeDecisionEngine(五动作) → DepthPolicy → AngleLadder
     → 记忆三段装配(已问台账/事实摘要/近期对话, >12轮拆三段)
     → 单次 ChatModel.stream(造句) —— system 基底 = live-probe 技能正文
     → 出模闸(违禁正则族/bigram换皮拦截/违禁词剥离/160字截断/收尾锁改写/空返回两级兜底→清单下一题)
     → SSE 下发 + 写队列落库
```
- 模型在运行面被降级为「按规格书写字」：决策、材料、字数上限全部由代码给定。
- live-probe 技能正文有两条消费路径：ReAct 内换装（治理面）+ `SkillRegistry` 直接取 systemPrompt（运行面 library call）——话术单一源头，SkillFileWatcher 热更新对两个面同时生效。
- 脱敏在入模前 ⇒ 证据核验与报告原声引用全部在**脱敏域内**比对（否则「李女士」永远核验不中），映射表不进任何模型上下文。

### D3 · 会话状态所有权与三方读写

会话状态唯一属 interview-service（一库三读法：Supervisor 低频 A2A 查询 / 前端高频直连 / MCP 公开只读）。治理面（开台、导演指令）、运行面（话轮推进）、补偿器（归集）三方写同一 `interview_session`：
- 状态推进收敛到 `InterviewSessionStateMachine` 单一服务方法，任何入口不得直接 UPDATE 状态列；
- 行级乐观锁 `version` 防并发覆盖；
- 状态机推进权限表：DRAFT→QUESTIONS_CONFIRMED 仅治理面；→IN_PROGRESS 仅治理面；→CLOSING_LOCKED 仅运行面（含导演指令触发的运行面收束）；→COLLECT_PENDING 仅补偿器；→ARCHIVED 仅归集完成。

### D4 · 报告任务：复用租约模式，独立表落位

`agent_async_task` 由 agent-service 独占写，跨服务写同表造成所有权耦合。故新建 `interview_report_task`，列设计照抄租约模式（lease_owner/lease_expires_at/lease_epoch/retries/status/input_snapshot/error_class）。幂等：输入快照哈希唯一键；已成功直接返回，不再调模型。

### D5 · MCP 面公开只读，管理动作零暴露

- 工具面收敛 5 个：`search_transcripts` / `get_case_report` / `submit_transcript` / `grade_transcript` / `generate_case_report`。
- 可见范围限定 L2/L3 已归档资产及其衍生报告；唯一写路径 = `submit_transcript`（只新增不改既有）。
- 系统暂无组织鉴权概念（用户决策），按「机器可见即平台级公开」实现；将来引入 caller 过滤时在 MCP 层加参数即可，不伤契约。

### D6 · skill_hint 链路与向后兼容

- 传递：Supervisor 技能匹配结果 → `OfficialA2aMetadataMapper` 在 supervisor 命名空间附加 `skill_hint`（建议性，可能缺席）。
- 消费：`SkillRoutingModelInterceptor` 首轮读取 hint，命中本域 Registry 即以激活态开局（注入技能正文 + 收窄工具面）；未命中或缺席 → 回退目录注入，行为与现状完全一致。
- 仅影响首轮：后续轮次仍以消息历史中最新 skill 伪工具调用为准（hint 不产生持久的隐藏状态，幂等且可覆盖）。
- 兼容保障：hint 缺席路径不改变任何既有拦截器分支；既有 4 个接入服务回归验证编译 + 既有单测。

### D7 · 双入口与凭据交接

- 表单式 REST 入口与 A2A 入口调用同一 `InterviewPrepService.openCase(...)` 领域服务；REST 入口必须落齐与 structuredContext 对齐的元数据字段（创建人、组织三级、场景、案例状态），保证 Supervisor 按创建人检索不漏。
- 会话凭据 = 会话级 HMAC 签名 ticket（sessionId + 过期时间 + 用途范围签名），治理面启动会话时签发，经 A2A 结构化输出返回；只放行本场 turn/monitor，非公网受访人链接（P3）。这是功能寻址所需的最小凭据，不构成鉴权体系。

### D8 · 模块内分包，runtime 不依赖 a2a

```
interview-service/
  ├─ a2a/       InterviewOfficialA2aAgent + interceptor 副本（依赖 tool/）
  ├─ mcp/       InterviewMcpTools implements McpTool（依赖 service/）
  ├─ tool/      InterviewTools（@Tool，依赖 service/）
  ├─ runtime/   话轮管线、状态机、REST/SSE、写队列（依赖 common-skill 的 Registry 取正文；不依赖 ReactAgent/a2a）
  ├─ engine/    纯函数决策层（零 Spring 依赖，可全量单测）
  ├─ service/   领域服务（开台、出题、归集、评级、报告）
  ├─ entity|mapper/
  └─ resources/skills/interview/*.md
```
engine 包零框架依赖是刻意边界：决策层可独立于 Spring/模型做穷举单测，也是「代码定决策」的可验证性保障。

## Risks / Trade-offs

- [三方并发写 session 导致状态覆盖] → D3 状态机收敛 + 乐观锁；状态推进只能走单一服务方法。
- [造句漂移（模型不守规格）] → 出模闸可拦截可改写可替换（清单下一题兜底），漂移不外溢给受访者。
- [脱敏域自洽被破坏（核验用了原文）] → 核验与报告引用统一从脱敏后资产读取，代码路径唯一。
- [skill_hint 引入回归] → 缺席路径分支不变 + 既有服务回归编译/单测；hint 只读不写持久状态。
- [MCP 工具面膨胀推高 Supervisor prompt] → 工具面硬收敛 5 个，长尾需求走 A2A 委托。
- [A2aSupervisorContextInterceptor 副本继续增殖] → 接受现状（仓库惯例），收编另立 change。
- [运行面单点（本服务挂则访谈中断] ] → P1 接受；写队列与状态机落库保证进程重启后可续场（话轮幂等键去重）。

## Migration Plan

1. 合入顺序：SQL migration → common-skill（skill_hint，先合先兼容）→ interview-service → agent-service（注册/mapper/MCP 连接）→ gateway 路由。
2. 部署：interview-service 独立部署注册 Nacos；`AgentRegistration` 与 MCP client 连接为配置增量，发布即生效（领域目录自动纳入 interview）。
3. 回滚：摘除 AgentRegistration 与 gateway 路由即从编排面下线；表与服务无存量消费者，直接停服即可。

## Open Questions

- 开台页面前端形态（本期后端 REST 先行，前端另行排期）。
- 行业大脑 Milvus 集合的内容建设节奏（接口先行，语料运营后续补）。
- 访谈结束主动通知接 notification-service 的时机（本期 Supervisor 轮询可查，事件推送列 P2 可选项）。
