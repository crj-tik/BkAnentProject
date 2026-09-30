# 逻辑释义清单（设计根因索引）

> **目的**：记录本项目中「结论不显而易见、容易被误改」的设计逻辑。每条 = 一个结论 + 根因分析。
> **读者**：所有 AI 助手与开发者。**修改相关模块前先查这里；做出新的非显然设计决策后必须追加条目。**
> **格式**：`LR-N 标题` → 结论 → 根因 → 代码位置 → 关联。编号稳定不复用，引用时写 `LR-N`。
> **维护规则**：与代码同仓同提交（见 AGENTS.md「项目认知清单」）。修正某条逻辑时更新条目而非删除，注明变更原因。

---

## LR-1 ticket 签发时机：凭据跟着信任断点走

**结论**：会话凭据（ticket）的签发时机由「前端第一次直连 interview-service 的时刻」决定，与状态机阶段无关。对话式入口在 `startInterviewSession`（IN_PROGRESS）签发；表单式入口在开台响应（DRAFT）签发。两个签发点不对称是同一原则对两种通道拓扑的投影，不是需要消除的毛刺。

**根因**：ticket 是鉴权缺位时代的归属证明（本服务不知道调用者是谁，用持有证明顶上）。对话式 prep 阶段（出题/确认）由 Supervisor 作为可信中间人执行 A2A 工具，前端只聊天不直连，信任链未断，凭据无必要性；且卡片是第一个可靠交付点（让 LLM 在对话里转述长 HMAC 随机串不可靠）。表单式从第一个请求起就是前端直连，信任链即刻断开，凭据必须覆盖会话全生命周期（create-then-hold）。

**推论**：「能做什么」由状态机分层管（DRAFT/QUESTIONS_CONFIRMED 只放行题目操作与 monitor；IN_PROGRESS 加话轮与导演指令；CLOSING_LOCKED 后只读），**不由签发时机管**。ticket 原文只在签发响应出现一次，DB 只存 hash；重签作废上一张，权威签发点 = 各入口的「最后一步响应」。

**代码位置**：`InterviewSessionStateMachine.issueTicket` / `InterviewPrepService.openCase` / `InterviewTools.startInterviewSession`

**关联**：KI-1（表单式 prep 凭据真空，待修）。将来平台网关统一鉴权落地后，ticket 可退化为纯会话定位符，本条争议自然消失。

## LR-2 卡片是导航，按钮是状态动作

**结论**：对话式入口点击「进入访谈」卡片 = 纯前端路由（状态已在对话内由 `startInterviewSession` 推进，点击不触发任何状态变化）；表单式「开始访谈」按钮 = `POST /{id}/start` 状态动作（QUESTIONS_CONFIRMED → IN_PROGRESS）。不变式：**前端进入访谈页时，手里必有 ticket，会话必在 IN_PROGRESS**。

**根因**：对话式的启动动作由发起人那句「开始吧」承载（口头指令即显式动作），模型调工具完成推进；表单式没有对话通道承载这个动作，按钮就是「我准备好了」的声明。对齐原产品四步节奏：开台 → 出题 → 确认 → 显式选择启动方式（AI 主导/辅助），点击开台不是一步到访谈。

**代码位置**：`InterviewTools.startInterviewSession`（A2A 面）；`InterviewController`（表单 start 端点，见 KI-1 待补）

**关联**：LR-1；不建议把对话式点击也做成状态触发（ticket 需跨轮转手或增设取回端点，链路更长更脆）。

## LR-3 三面一体：一个服务三个协议面

**结论**：interview-service 同时暴露治理面（A2A/ReAct，Supervisor 低频委托）、运行面（REST/SSE，前端经 gateway `/interviews/**` 直连，不走 ReAct）、能力面（MCP 公开只读）。三面共享同一批领域服务（一库三读法），会话状态唯一属 interview-service，Supervisor 不持有访谈状态副本。

**根因**：A2A 语义是低频委托，受访者话轮是高频流（15–60 分钟、几百次调用、6 秒 SLO）。若话轮走 ReactAgent：① 决策权落入模型自由意志（硬约束失效）；② 线性消息历史做不了每轮三段记忆重装；③ ReAct 输出直回调用方，播出前质量门无插入点。

**代码位置**：`interview-service` 的 `a2a/`、`runtime/`、`mcp/` 三包；分包刻意让 runtime 不依赖 ReactAgent。

## LR-4 代码定决策、模型只造句

**结论**：话轮管线中追问/换角度/转题/收尾的决策全部由 `engine/` 包的纯函数作出（零 Spring 依赖、可全量穷举单测）；模型每轮恰好一次调用，只按给定决策生成一句话。访中只还原故事，方法论全部留访后（策略提炼类问句被出模闸正则族全局禁止）。

**根因**：凡决策经过模型自由意志必有漏网。原产品「收尾锁」事故的教训：prompt 软约束治不住「受访者说完谢谢，AI 又追问十几次」，改为纯函数硬决策才根治。

**代码位置**：`engine/ProbeDecisionEngine`、`AngleLadder`、`ClosingDetector`、`ProhibitedQuestionFilter`；`runtime/InterviewRuntimeService.synthesize`

## LR-5 状态机三方写入的权限表

**结论**：`interview_session` 状态推进唯一入口是 `InterviewSessionStateMachine.transition`，任何入口不得直接 UPDATE 状态列。推进权限表：→QUESTIONS_CONFIRMED / →IN_PROGRESS 仅治理面（GOVERNANCE）；→CLOSING_LOCKED 仅运行面（RUNTIME，含导演指令触发的收束）；→COLLECT_PENDING / →ARCHIVED 仅补偿器（COMPENSATOR）。乐观锁 `version` 防并发覆盖。

**根因**：治理面（开台/导演指令）、运行面（话轮收尾）、补偿器（归集）三方写同一实体，各自直改状态列必然互相踩踏。曾因权限表按「目标状态→源状态」错位映射导致 6 个调用点中 5 个抛异常、开台后访谈永远无法启动（KI-2）——权限表与调用方分居多文件各自编写、无人对账是根因，防回归靠「每个真实调用点一个单测」。

**代码位置**：`runtime/InterviewSessionStateMachine`；防回归单测 `InterviewSessionStateMachineTest`

## LR-6 脱敏域自洽

**结论**：姓名/手机/价格/地址在**入模前**脱敏，映射表仅存服务端不进任何模型上下文；证据核验、报告原声引用、逐字稿落库、报告任务的全部文本处理**统一在脱敏域内**进行。

**根因**：脱敏发生在入模前，意味着落库文本就是脱敏文本；核验若用原文比对，「李女士」永远对不上「13812345678」。脱敏域与核验域必须同域，代码路径唯一。

**代码位置**：`engine/Sanitizer`（入模闸）、`engine/EvidenceVerifier`（核验）、`InterviewCollectionService`（落库）

## LR-7 导演指令：人工干预的边界

**结论**：导演指令（WRAP_UP / NEXT_QUESTION / PINNED_QUESTION）FIFO 落库消费（原子置 CONSUMED 防双消费）。两条硬边界：① **收尾信号优先于指令**——受访者道别时未消费的指令不得阻止收尾；② 收尾锁定后指令不再消费。PINNED_QUESTION 逐字播出，走质量门（违禁拦截/截断）但**不做换皮比对**（人工指定语义优先）；被违禁拦截则回退引擎决策。

**根因**：人工干预是「决策的另一个来源」，覆盖引擎决策但不污染引擎纯函数（指令消费插在决策之后、造句之前）；但收尾是受访者的权利，优先于发起人的操作。

**代码位置**：`runtime/InterviewRuntimeService.consumeDirectorCommand` 及决策点 ⑤'；`tool/InterviewTools.sendDirectorCommand`

## LR-8 MCP 面公开只读

**结论**：MCP 面（`interview-mcp-server`）可见范围限定 L2/L3 已归档逐字稿及其衍生报告；唯一写路径 `submit_transcript`（只新增不改既有资产）；删除/改名/重评级/题目确认/会话干预等管理动作零暴露。

**根因**：系统暂无组织鉴权概念（用户决策），机器可见即平台级公开。将来引入 caller 过滤时在 MCP 层加参数即可，不伤契约。

**代码位置**：`mcp/InterviewMcpTools`（5 工具，implements `common` 的 `McpTool` 标记接口）

## LR-9 skill_hint 只影响首轮

**结论**：Supervisor 的 `skillHint`（supervisor 命名空间）命中本域技能时以激活态开局（注入技能正文 + 收窄工具面）；后续轮次仍以消息历史中最近一次 `skill` 伪工具调用为准，hint 不产生持久隐藏状态。缺席或跨域时回退技能目录注入，行为与升级前完全一致（向后兼容）。

**根因**：幂等且可覆盖；无状态拦截器靠扫消息历史判定激活态，hint 只作首轮捷径。

**代码位置**：`common-skill/runtime/SkillRoutingModelInterceptor`；发送侧 `agent-service` 的 `OfficialA2aMetadataMapper`

## LR-10 报告任务幂等与租约

**结论**：报告任务以「输入快照哈希」幂等（相同 taskType+caseId+assetId 复用同一任务）；已成功且有报告直接返回不再调模型。租约 10 分钟、`lease_epoch` 乐观递增防旧执行复活、retryable/blocked 错误分级、maxRetries 3；**RUNNING 且租约过期纳入重扫**（进程崩溃恢复）。可复制性三轴：全场逐字命中 + L3 资产最高也只到 L2（成功案例不得直接判 validated）；存在转述引用封顶 79 分。

**根因**：AI 只做抽取归纳，等级与分数由服务端确定性规则给出，防模型自证成功。

**代码位置**：`service/InterviewReportService`

## LR-11 收尾双通道与持久锁

**结论**：硬道别（约 30 词表）无条件收尾；软完结（「我说完了/就这些」）仅全部确认题问完才收尾，否则推进下一题；一旦收尾，持久锁生效——后续任何输入只获得极短对等道别，不再产生新问题。

**根因**：治「说完谢谢又追问十几次」；软硬双通道防止受访者阶段性收口被误判为终局。

**代码位置**：`engine/ClosingDetector`、`engine/SignalDetector`；`InterviewRuntimeService` 入口的 closingLocked 分支

## LR-12 禁成功预设

**结论**：`lost/churned` 案例自动启用禁成功预设问法约束与必采清单（真实卡点、竞品胜出原因、决策停滞点）。

**根因**：未成交/流失案例的受访者没有「成功复盘」可做，问成功经验等于诱导虚构。

**代码位置**：`engine/OutlineRouter.noSuccessPreset`；`interview_case.no_success_preset`

## LR-13 live-probe 技能的双消费路径

**结论**：访中造句话术（`skills/interview/interview-live-probe.md`）有两条消费路径：治理面经 SkillTool 换装拦截器（ReAct 内模型自主切换）；运行面经 `SkillRegistry.getByName` 直接取 systemPrompt（library call，不走 ReAct）。话术单一源头，SkillFileWatcher 热更新对两个面同时生效。

**根因**：把话术放技能文件而非硬编码 prompt 的最大红利——调话术不用重启服务，且两条通道不会漂移出两个版本。

**代码位置**：`runtime/InterviewRuntimeService.liveProbeSystemPrompt`；`common-skill` 的 SkillTool / SkillRoutingModelInterceptor

## LR-14 双入口一库三读

**结论**：开台双入口（对话式 A2A / 表单式 REST）调用同一 `InterviewPrepService.openCase`，落同一组表与同一组元数据字段（创建人、组织三级、场景、案例状态、`entry_source` 标记来源）。进度查询（`getInterviewMonitor`、`GET /{id}`）对两个入口无差别——Supervisor 按创建人检索不漏直开场次。

**根因**：不存在「注册回 Supervisor」机制，因为 Supervisor 从不持有访谈状态；两个入口在状态所有者眼里完全等价。

**代码位置**：`service/InterviewPrepService.openCase`；`runtime/InterviewSessionStateMachine.listByCreator`

## LR-15 审批待办以「任务最新 checkpoint」判定

**结论**：审批待办列表（`GET /agent/supervisor/approvals/pending`）的判定依据是**每个 task 最新一条 checkpoint 的 workflow_status 是否仍为 WAITING_USER_APPROVAL**，不是「存在 WAITING 行」。查询路径：先取曾经 WAITING 的 task 集合（只选 task_id 列）→ 按 task 分组取 MAX(id) 定位最新行 → 批量取行二次过滤状态 → 解析快照还原审批卡与 state.userId。

**根因**：`agent_workflow_checkpoint` 按 (task_id, checkpoint_version) 追加写且历史行不删除——任务恢复执行后会写入新状态的行，旧的 WAITING 行还在。若直接 `WHERE workflow_status='WAITING'` 分页，已恢复/已完成的任务会从历史 WAITING 行里被重新捞成待办，前端出现「点了批准还在待办里」的灵异现象。owner 过滤从快照 JSON 的 state.userId 解析（与单任务工作流查询 `assertCanReadWorkflow` 的 owner 制同口径）；解析失败的行在 owner 过滤模式下保守隐藏、在无过滤（治理诊断）模式下保留可见。

**代码位置**：`agent-service/service/SupervisorApprovalTodoService`；权限断言 `AgentPermissionService.assertCanReadApprovalTodos`（复用 `agent.workflow.read`）

**关联**：KI-16；LR-5（同为「状态唯一权威 + 权限分层」思想在 agent-service 的投影）

## LR-16 refresh token 一次性轮换与「出生即吊销」护栏

**结论**：`POST /auth/refresh` 换发新 token 对时，旧 refresh token 用后即废（revoke）；**仅当新签的 refresh token 与旧串不同才执行 revoke**。前端必须以响应的新 refreshToken 替换本地存储。

**根因**：本服务 token 载荷是 `userId|expiresAt|type`、HMAC 签名、**无 nonce 且秒级时间戳**——同一秒内对同一账号重签会得到完全相同的字符串。若无条件先 revoke 旧串再签新串（或反过来），同一秒内的刷新会让新 token「出生即被吊销」，用户会话当场死亡。护栏写法：先签新串、比较、不同才 revoke 旧的。将来 token 格式引入 nonce/jti 后此护栏自然失效可删。

**代码位置**：`auth-service/controller/AuthController.refresh`

**关联**：KI-16
