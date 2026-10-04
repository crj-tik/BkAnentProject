# Bug 与已知问题清单

> **目的**：记录项目全部已知缺陷（已修/待修）与设计限制，让开发者与 AI 快速了解项目真实状态。
> **读者**：所有 AI 助手与开发者。**修复前先查重（避免重复排查）；修复后在条目更新状态与 commit；发现新问题必须当日追加。**
> **格式**：`KI-N [状态·优先级] 标题` → 现象 → 根因 → 处置（方案/修复 commit/防回归手段）。编号稳定不复用，引用时写 `KI-N`（常与 `docs/logic-rationale.md` 的 `LR-N` 互相关联）。
> **状态**：`OPEN` 待修 ｜ `FIXED` 已修（附 commit）｜ `LIMIT` 设计限制（附暂不修的原因）
> **维护规则**：与代码同仓同提交（见 AGENTS.md「项目认知清单」）。

---

## 待修复（OPEN）

## KI-1 [OPEN·P0] 表单式 prep 阶段凭据真空（鸡生蛋）

**现象**：`POST /interviews` 开台不返回 ticket，而 `POST /{id}/questions`、`POST /{id}/questions/confirm`、`GET /{id}` 都要求 `x-ticket` 头 → 表单前端开台后无法进行任何后续操作，前端页面被卡死。

**根因**：ticket 绑定在 IN_PROGRESS 签发（`startInterviewSession`），而表单入口的题目操作发生在 DRAFT/QUESTIONS_CONFIRMED——把「话轮凭据」用在了比话轮更早的端点上。详见 LR-1 的信任断点分析。

**处置方案（已对齐，待实施）**：
1. ticket 语义升级为会话凭据，`openCase` 开台响应即签发（表单入口）；
2. 补 `POST /{id}/start` 端点（QUESTIONS_CONFIRMED → IN_PROGRESS，**不重签** ticket，仅推进状态；含 AI_LEAD/ASSIST 模式选择）；
3. 权限分层改由状态机守卫承担（DRAFT/QUESTIONS_CONFIRMED 放行题目操作与 monitor；IN_PROGRESS 加话轮与导演指令），不由签发时机承担；
4. 对话式入口保持 start 卡片签发不变（见 LR-2）。

**关联**：LR-1、LR-2

## KI-11 [OPEN·P2] 导演台字幕流只有心跳

**现象**：`GET /{id}/stream` 仅 15 秒心跳，无真实逐句字幕推送；导演面板刷新依赖轮询 monitor。

**处置方向**：话轮落库后向会话级 Sinks 广播（或引入 Redis pub/sub 支撑多实例）。

## KI-12 [OPEN·P2] SSE 断线续取为单实例内存态

**现象**：`terminalReplies` 是 `InterviewController` 内的 `ConcurrentHashMap`——多实例部署时 `GET /{id}/replies/{reqId}` 经负载均衡命中另一实例会 404。单实例部署无此问题。

**处置方向**：多实例部署前迁移到 Redis 或 DB 暂存表。

## KI-13 [OPEN·P3] 行业大脑 Milvus 集合未建设

**现象**：访前预读缓存（`interview_session.industry_brain_cache`）无填充来源，访中「听懂」能力完全依赖 live-probe 技能 prompt 自身。接口先行、语料运营未启动（见 design.md Open Questions）。

## KI-15 [OPEN·P2] P2/P3 功能未实现（表已建，逻辑未接）

- 小凤双脑引擎 + 导演台监播面板（慢脑题卡/灯号/账本/MOT 缺口）
- 社区专家 26 题制式问答
- 打法卡生成（原声编号表机制）与共性提炼（`playbook_card`、`commonality_report` 表已建）
- 语音模式（VoiceLoop 防抢话三层）、受访人公网链接（Token 状态机）

定义见 `openspec/changes/create-interview-subagent/proposal.md` 的范围边界。

## KI-16 [FIXED·本提交] 前后端联合声明的四项 API 缺口

**现象**：前后端 README 共同声明的待补缺口——审批待办分页、审批决策 API、`POST /auth/refresh`、报告跨任务搜索（任务历史分页在 6b5cc0e 已先行补齐）。

**根因**：后端各面按需生长，前端任务中心/审批中心/登录态续期三个页面各自等待对应查询与决策接口。

**修复**：
1. 审批待办分页 `GET /agent/supervisor/approvals/pending`（userId/page/pageSize；判定逻辑见 LR-15，防回归单测 `SupervisorApprovalTodoServiceTest`——含「已恢复任务历史 WAITING 行不算待办」用例）；
2. 审批决策 API 已有（`POST /agent/supervisor/approvals/callback`，APPROVED/REJECTED/TERMINATED + 幂等 claim），本轮仅确认契约无需新增；
3. `POST /auth/refresh`（DTO `AuthRefreshRequest`；轮换语义与「出生即吊销」护栏见 LR-16，单测 `AuthControllerTest` refresh 三用例）；
4. 报告跨任务搜索：REST `GET /interviews/reports/search` + 治理面 `searchReports` 工具 + MCP 面 `search_reports` 工具（keyword 对 report_json LIKE + creatorWorkNo 经 case 解析 + 分页；返回摘要不回传全文，oneLiner 从 report_json 抽取）。

**已知限制**：报告搜索走 `LIKE '%kw%'` 全表扫（report_json 为 JSON 列、无 FULLTEXT 索引、LIKE 本身用不上 B-tree）——数据量大后需物化摘要列 + FULLTEXT 或外置检索（与 `search_transcripts` 同一债务）。

**关联**：LR-15、LR-16

## KI-17 [OPEN·P1] Supervisor 入口规则分流偏离显式 skill 的设计边界

**确认日期**：2026-10-03；最终方案于 2026-10-04 对齐。用户确认正常请求应由 LLM 根据 A2A/MCP 等能力 description 判断调用；本次指定 skill 后仍由模型理解原始请求，并遵循技能正文中的流程。

**现象**：默认 `llm-enabled=false`、`strategy=rule-first`，入口关键词与默认 listing/intent 选择 Agent，nextHints 和 trade 结果又可自动交接。现有 Supervisor 知识技能与 Subagent 正文加载是不同消费路径，Supervisor 尚未接入统一的 A2A/MCP 模型工具循环；skillHint 也不能承担本次显式选择、版本固定和严格能力范围契约。正文与工具白名单本身不提供业务步骤顺序强保证（KI-19）。

**处置**：已修订 `realign-supervisor-tool-and-skill-orchestration` 为最终方案：统一可调用能力目录，AUTO/EXPLICIT_SKILL 使用同一通用模型工具循环；显式技能首轮前加载正文，但模型仍理解请求并选择工具。保留 Markdown 指引，严格约束调用范围、参数、权限与审批，同步 Subagent 契约及调用级恢复；不增加 workflow/DAG、步骤调度或正文顺序/完成检查器。2026-10-04 本次仅更新文档，代码未修复，状态保持 OPEN；按 tasks.md 后续实施。

**关联**：LR-17、LR-18、LR-9、LR-15、KI-19；主规格 `supervisor-domain-catalog` 的冲突行为在本变更 delta 中明确替代。

## KI-18 [OPEN·P2] 动态 Agent 能力目录存在信息损失和地址缓存限制

**确认日期**：2026-10-03（静态代码/依赖检查，未连接部署环境）。

**现象**：`OfficialAgentCardDiscoveryClient.convertWrapper` 将 skills 置空、异步能力设为 false，并以 transport 存在简化流式能力；`OfficialA2aAgentClient.clientFor` 按 agentId 缓存客户端，endpoint 改变没有失效逻辑。Compose 配置 Nacos 3.0.3，本地 A2A Starter 使用 Agent Registry API，需实际验证服务端支持版本，不能把普通服务发现成功等同于 Agent Card 注册成功。

**处置**：作为 KI-17 的能力发现前置工作列入新方案：保真卡片字段、地址/版本变更重建客户端、动态目录契约测试、部署版本与 HTTP Card fallback 明确化。本次未修改代码和配置，保持 OPEN。

**代码位置**：`agent-service/registry/OfficialAgentCardDiscoveryClient`、`agent-service/client/OfficialA2aAgentClient`、`docker-compose.yml`。

**关联**：KI-17；`realign-supervisor-tool-and-skill-orchestration/tasks.md` 第 2 组。

## 设计限制（LIMIT）

## KI-14 [LIMIT] 运行面依赖 MySQL 单点读写

话轮管线每轮从 DB 重查重装三段记忆（无进程内会话缓存），延迟依赖 DB 且高并发下 `nextTurnSeq` 有竞态窗口（同会话并发话轮可能拿到相同 seq，靠幂等键去重兜底）。访谈会话天然单人串行，实际触发概率低；若未来支持多人同场访谈需引入分布式锁。

## KI-19 [LIMIT] 技能正文不提供业务步骤顺序强保证

**确认日期**：2026-10-04。

**现象**：现有 Subagent 的 SkillTool/技能拦截器加载 Markdown 指引并收窄工具集合，不验证调用顺序。最终待实施的 Supervisor 方案延续这一边界：即使本次显式指定 skill，模型仍可能漏掉、提前执行或重复正文中的业务过程，工具允许清单本身不能证明流程正确完成。当前 Supervisor 尚未实现该统一循环，本条不代表新功能已经落地。

**根因与选择**：用户最终接受由模型遵循正文的方案，以避免步骤 DSL、DAG 或业务顺序/完成检查器与通用 Graph 耦合。平台在实施后应硬约束能力范围、参数、身份、权限、预算和审批，但这些调用级守卫不构成正文流程的顺序保证。

**处置**：保留 LIMIT，不将强制顺序校验列入本次实施任务；通过清晰的技能正文、模型真实调用评估和运行轨迹观察改善遵循质量。评估通过不能证明顺序强保证；如果后续业务必须保证先后关系，应重新讨论设计并更新变更范围，不能在通用节点中暗加业务步骤判断。

**关联**：LR-17、LR-18、KI-17；`realign-supervisor-tool-and-skill-orchestration/design.md` 的非目标和验证边界。

---

## 已修复（FIXED，历史记录与防回归）

## KI-2 [FIXED·e85fa93] 状态机权限表错位（致命）

**现象**：权限表按「目标状态→源状态」错位映射，6 个 `transition` 调用点中 5 个抛 `IllegalTransitionException`——开台能创建会话但访谈永远无法启动。

**根因**：权限表（状态机文件）与全部调用方（工具层/运行时/补偿器）分居多文件各自编写，无人对账；状态机依赖 DB、零单测覆盖。

**防回归**：`InterviewSessionStateMachineTest`——每个真实调用点一个正向用例 + 越权反向用例 + 前置状态链完整性（8 用例）。**新增 transition 调用点时必须同步加单测。**

## KI-3 [FIXED·e85fa93] searchAssets 创建人过滤永远空结果

**现象**：创建人过滤条件写成 `id = -1`，任何带 creatorWorkNo 的检索返回空。
**根因**：创建人字段在 `interview_case` 表而 `interview_asset` 表没有，实现时留下残缺条件。修复为经 caseMapper 解析 caseId 集合再过滤资产（场景过滤同修）。

## KI-4 [FIXED·e85fa93] 话轮对话历史倒序装配

**现象**：`synthesize` 取最近 6 轮后未反转，模型收到从新到旧的「倒放」对话，严重干扰造句连贯性。

## KI-5 [FIXED·e85fa93] 报告任务 RUNNING 租约卡死

**现象**：租约扫描只含 PENDING/RETRYABLE，执行器进程在 RUNNING 状态崩溃后租约过期也无人重扫，任务永久卡死。修复：RUNNING 且租约过期纳入扫描（见 LR-10）。

## KI-6 [FIXED·e85fa93] CLOSE/ACK_AND_SWITCH 不标当前题已答

**现象**：收尾/重复抗议换向时当前题永远悬在 PENDING，影响归集评级与 `allDone` 判定。修复：分支内显式标记。

## KI-7 [FIXED·e85fa93] 话轮入口无状态守卫

**现象**：ARCHIVED 会话仍能推进话轮。修复：入口加 IN_PROGRESS 守卫（收尾锁走独立极短道别分支，见 LR-11）。

## KI-8 [FIXED·e85fa93] allDone 恒假判定

**现象**：`currentQuestion()` 只返回 PENDING 题，current 非空恰好说明没答完——原复合条件是恒假绕圈。化简为 `current == null`。

## KI-9 [FIXED·e848923] 题目确认制走不通（无插入题目路径）

**现象**：全链路只有 question 的 update 没有 insert，`confirmQuestions` 确认的是不存在的行，题目确认制形同虚设。
**修复**：领域层 `createCandidateQuestions`（自动编号/核心题深度上限/落 outline_route）+ 治理面 `generateQuestionSet` @Tool + REST `POST /{id}/questions`。

## KI-10 [FIXED·e848923] 导演指令不生效

**现象**：`sendDirectorCommand` 只返回回执不落库，运行面下一轮话轮不读取——指令不会真正生效。
**修复**：新表 `interview_director_command`（PENDING/CONSUMED + source 留痕）+ 双端点落库（A2A 工具 / `POST /{id}/director-commands` 直连）+ 话轮决策点消费（见 LR-7）。

---

**变更历史**：本提交 补齐联合声明 API 缺口（KI-16）；6b5cc0e 报告任务分页/详情；e848923 补齐运行面四缺口（含 KI-9/KI-10 修复）；e85fa93 排查修复 8 处缺陷（KI-2~KI-8）；3b0f8a5 初始实现；4934137 立项规划。
