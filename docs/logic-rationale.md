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

## LR-17 正常请求与显式技能都由模型理解并选择调用（已实施）

**结论**：正常 Supervisor 请求的 Agent/MCP 调用由配有真实能力 description/schema 的 LLM 决定；本次显式指定 skill 时，平台在首轮模型调用前校验、固定版本并加载正文和有效能力范围，LLM 仍结合原始请求理解意图、提取参数、处理缺失与冲突，再逐轮选择调用。技能正文中的调用顺序、条件和结果使用方式由模型遵循，不由平台保证业务步骤顺序。入口关键词、默认 listing、默认 intent、trade 条件和 nextHint 自动交接不属于新默认路径，知识技能与旧 skillHint 只提供提示。

**根因**：用户原始设计中的“按规则调用”指技能正文内已经说明的 Agent/工具协作流程；此前将规则前移到工作流入口，导致模型无法依据任务语义选择，含否定、多意图或新增领域的请求也会被固定分流。仅打开旧 JSON 规划器仍不能实现完整的 A2A/MCP 模型工具循环。2026-10-04 用户最终选择由模型遵循正文的方案，指定 skill 不能成为绕过意图理解的开关。

**实现边界**：AUTO 与 EXPLICIT_SKILL 使用同一通用模型工具循环，不增加 workflow/DAG、技能步骤调度器或业务顺序/完成条件检查。explicit selection 与 LR-9 的建议性 hint 分开；平台硬约束能力范围、参数、身份、权限与审批，正文流程属于模型指引（LR-18、KI-19）。Subagent 同步共享技能契约并复用已有领域 ReAct；访谈运行面的 LR-3/4/5/8/13 边界继续有效。checkpoint、租约和调用账本记录执行事实，不能被模型或技能越过。

**方案位置**：`openspec/changes/archive/2026-10-05-realign-supervisor-tool-and-skill-orchestration/`（proposal/design/specs/tasks）。2026-10-04 修订最终方案，替代 2026-10-03 的 instruction/workflow 双执行器方案。2026-10-05 新请求路径和九 Subagent 契约已实施；本机独立验收 MySQL 仅发现 7 条已终态旧流程记录（3 COMPLETED、4 FAILED），无待恢复旧任务。按 7.4 完成旧 Graph 执行链清理；历史 checkpoint 保留只读查询，不能再恢复执行。该本机结果不代表生产数据库排空。

**关联**：KI-17、KI-19；LR-9、LR-15、LR-18；`docs/supervisor-routing-roadmap.md` 的旧“LLM 失败后关键词兜底”方向由本方案替代。

## LR-18 技能正文为模型指引，调用治理由通用 Graph 执行（已实施）

**结论**：skill 正文中的业务过程是模型遵循的软约束；有效能力范围、参数 schema、认证身份、权限、预算及审批是平台的硬边界。Supervisor 单轮模型节点只提出调用，通用 Graph 的工具执行节点持有业务调用的唯一执行权，避免模型客户端内隐式执行后外层再次执行。Graph 不检查正文步骤顺序、前置业务步骤是否完成或全部文字步骤是否完成。

**根因**：2026-10-04 用户最终选择“模型遵循技能正文”的第一种方案，希望新增技能、Agent 和 MCP 时无需增加业务节点或维护步骤 DSL，降低业务流程与 Graph 的耦合。正文不转换成 DAG，也不增加隐藏的下一合法步骤或流程完成检查器；选择该方案意味着模型可能偏离正文顺序，这一限制已明确接受。

**代码位置**：`agent-service/orchestration/SupervisorModelTurn` 禁止内部执行，`SupervisorToolLoopGraph` 提供固定通用节点、调用级审批和执行边界；`SupervisorToolLoopRunner` 及 `OrchestrationStore` 保存恢复事实。`common-skill/runtime/SkillExecutionContext`、`SkillTool` 与模型/工具拦截器由九 Subagent 复用。基础实现见 `c2f5a4c` 之前的本变更提交，网络与事件修复见 `993cb7e`、`b18ab33`。

**验证边界**：契约测试验证模型节点无工具副作用、执行节点范围/参数/权限/审批守卫以及恢复不重复调用；真实模型场景评估检查正文遵循质量，但通过评估不能证明步骤顺序强保证。新增审批或状态转换调用点仍须遵守 KI-2 防回归要求。

**关联**：LR-17、LR-9、LR-15；KI-17、KI-19；`openspec/changes/archive/2026-10-05-realign-supervisor-tool-and-skill-orchestration/`。

## LR-19 能力身份按协议与连接隔离并转义分段

**结论**：能力身份使用 a2a:agentId、mcp:connection:tool、local:tool；分段先转义百分号，再转义冒号，避免分隔符及字面转义串产生碰撞。它与模型工具别名、实例地址分离，调用选择不得由别名猜测业务身份。

**根因**：不同 MCP 连接可发布相同工具名，协议间也可重名；直接拼接任意分段还会把连接名中的冒号误作边界。稳定身份必须在目录、技能范围与执行端保持同一映射（LR-17/18）。

**代码位置**：`common/agent/CapabilityId`；兼容与序列化测试 `OrchestrationContractTest`。2026-10-04 已完成共享契约，Supervisor 循环仍按任务清单实施。

**关联**：LR-17、LR-18；KI-18。

## LR-20 新调用刷新地址，已接受任务保留原远端关联

**结论**：A2A 客户端按逻辑 Agent 的 endpoint/版本/协议能力变化重建；已经接受的远端 Task 独立保存原请求、描述和客户端，查询/取消使用原关联。进程恢复必须从调用记录重建关联，缺失时明确报错，不猜测当前实例承载旧 Task。

**根因**：Agent 注册地址变化不意味着旧实例上的任务已迁移；反过来，按 agentId 永久缓存又会让新任务继续访问旧地址。两类调用的生命周期必须分离。

**代码位置**：`agent-service/client/OfficialA2aAgentClient`；回归 `OfficialA2aAgentClientTest`，持久化恢复接入按本变更第 6 组继续实施。

**关联**：KI-18；LR-17、LR-18。

## LR-21 技能激活以成功加载为准，执行范围独立于模型工具可见性

**结论**：官方 A2A 执行使用请求专属 SkillExecutionContext；显式技能在模型前校验并固定正文、内容身份及范围。失败的加载不改变激活态，模型与工具拦截器共用该快照，实际越界调用直接拒绝；注册表热更新不替换正在执行的正文。旧 hint 仍是建议，不能覆盖显式选择。

**根因**：仅扫描 AssistantMessage 中尝试过的 skill 调用会把失败调用误作成功，并在下轮读取热更新后的不同正文；仅过滤模型工具列表不能阻止模型提出注册但不可用的回调。

**代码位置**：`common-skill/runtime/SkillExecutionContext`、`SkillRoutingToolInterceptor`；九服务 OfficialA2aAgent 接入共享拦截器和 executor。真实 ReAct 越界调用及显式不兼容无模型调用回归已验证。

**关联**：LR-9、LR-17、LR-18；KI-19。持久化与 Supervisor 已接入；部署验证见验收记录。

## LR-22 通用工具循环保存调用事实，不把不确定结果当作可重试任务

远端 Task 被接受时由官方客户端观察器立即持久化原 descriptor、Task ID 和请求；调用方超时后的迟到接受仍可补入 OUTCOME_UNKNOWN 账本。恢复服务只还原原关联后续查/取消，不创建替代任务；没有远端关联时保留未知状态。完成结果复用不再访问远端。

**结论**：新请求使用 llm-tools-v1 固定拓扑，成功加载的技能和模型消息按 run 保存。skill/request_input 须单独调用，混批返回全部未执行结果。业务执行前账本固定 runId+callId、目标及规范参数哈希；已完成结果复用。执行结果未知时停止继续调用并保留 OUTCOME_UNKNOWN，而不是让模型换一个 callId 重发。

**根因**：模型节点内部执行工具会绕过外部审批；旧消息推断技能或按 nextHints 自动交接会制造隐藏业务决策；网络失败不能证明远端没有产生副作用。新的 Graph 只检查调用级治理，提前完成文字技能流程仍允许（KI-19）。

**代码位置**：`agent-service/orchestration/SupervisorToolLoopGraph`、`SupervisorToolLoopRunner`、`OrchestrationStore`；数据库 migration `20261004_supervisor_orchestration.sql`。2026-10-04 已实现基础循环和控制/审批/待输入回归，远端任务对账及完整发布验收继续按清单实施。

**关联**：LR-17、LR-18、LR-20、LR-21；KI-20。

## LR-23 续接与审批恢复使用原执行身份和累计事实

**结论**：补充输入同时记录 requestId 内容哈希与 checkpoint 的 lastInputId，重复请求先核对输入身份；崩溃后由取得租约的 owner 恢复相同输入而不重复追加。审批决策写入 checkpoint 后可以恢复账本 claim；遇到下一次审批时重放上次回调只返回原调用后的状态。审批绑定每次实际调用及参数哈希。

**根因**：数据库 claim 和 graph checkpoint 跨边界，单独依靠 PROCESSING 或 session 会把崩溃后的内部续接永远锁住，或把旧审批错误地用于下一次调用。新 run 与续接不能混用；普通 chat 与同步/异步入口都必须进入同一个执行 owner。

**代码位置**：`SupervisorToolLoopRunner`、`ApprovalResumeClaimStore.completeRecovered`、`AgentOrchestratorService`、`SupervisorToolLoopGraph.await/executeCall`。执行前检查原 run 租约与取消事实，队列/期限有界；工具完成事实先落库，SSE 故障不会改判 OUTCOME_UNKNOWN。

**关联**：LR-15、LR-18、LR-22；KI-2。

## LR-24 技能目录更新与执行快照分离

**结论**：注册表热加载一次发布完整不可变目录；已激活执行继续使用自己的正文、内容身份与有效范围快照，新请求才读取新目录。

**根因**：逐项更新共享索引会让模型看见半个目录，而将目录热更新直接带入执行会改变本次已选技能。目录发布原子性与执行身份固定分别解决两种问题。

**代码位置**：`common-skill/core/SkillRegistry`、`runtime/SkillExecutionContext`；对应并发目录及热更新执行回归。

**关联**：LR-21、KI-23。

## LR-25 Nacos 发布与发现必须保留同一份技能扩展

**结论**：技能 Card 的扩展是显式选择的网络契约，注册与发现两端都必须保留；不能根据 skill 名称补猜 owner/version/hash，也不能因为丢失契约降级为 hint。

**根因**：Starter 1.1.2.3 在 Nacos 双向转换中丢弃扩展。只修 HTTP Card 或只修 Supervisor 一端，会让真实注册链仍拒绝所有显式下游技能。共享适配复用官方 SDK 注册与 endpoint，保持原 latest 发布策略；默认注册未启用时适配不主动开启。

**代码位置**：`common-a2a/NacosSkillCardMapper`、`SkillNacosCompatibilityAutoConfiguration`、`agent-service/registry/OfficialAgentCardDiscoveryClient`；真实混合协议验收 `SupervisorDistributedAcceptanceTest`。

**关联**：LR-17、LR-19、LR-21；KI-22、KI-25、KI-26。

## LR-26 新旧 runner 恢复按持久化身份分离（旧执行器已退役）

**历史结论**：实施早期曾保留新旧 timeline 分别恢复，旧 checkpoint 已选 Agent 时仅允许原目标，目标下线不按 domain 改选，查询和审批均验证原 owner。2026-10-05 本机验收库只读核对发现的旧流程均为终态后，按 7.4 删除旧 graph 执行器；该旧目标绑定规则不再构成可恢复路径。

**根因**：当恢复兼容仍启用时，用新图解释旧审批会改变已批准行为；灰度或注册表变化也不能把旧调用替换为另一个业务目标。此次只检查了本机验收库，不能据此声称生产旧任务已排空。

**代码位置**：历史实现已删除；当前新 runner checkpoint 由 `DatabaseCheckpointSaver` 按 graphName 精确过滤，历史状态由 `DbGraphCheckpointStore` 只读查询。

**关联**：LR-15、LR-22；KI-27（历史问题）。旧远端提交结果未知仍需人工对账，不能由恢复自动重发。

## LR-27 下游显式身份校验属于调用治理，不是业务步骤规则

**结论**：模型只提出发布目录中的本地 skill name/version；平台在审批和 claim 前验证该身份，不从能力 ID 或父技能猜测。确定的无效身份返回模型纠正，已发送调用才进入未知结果对账语义。

**根因**：JSON schema 只能校验形状，不能确认目标实例的发布映射；把无效身份当作未知副作用会无谓停止执行。此校验只约束目标身份，不判断正文步骤顺序或强制调用某个 Agent。

**代码位置**：`SupervisorCapabilityCatalog.validateArguments`、`SupervisorToolLoopGraph.validateCall/prompt`；目录与 Graph 回归、真实模型评估。

**关联**：LR-17、LR-18、LR-22；KI-28、KI-19。

## LR-28 技能热加载需要同步更新发布面，执行快照仍固定

**结论**：注册表完整替换后通知共享 Card 发布器，HTTP 读取最新不可变 Card，Nacos 通过已经启用的官方注册适配异步重发布；失败每 5 秒重试最新待发布版本。生成的本地技能条目按 owner 重建，新增、正文修改与删除均反映在技能描述及 version/contentHash 扩展中。九个服务的 YAML 不再重复声明本地技能。

**根因**：只刷新本地注册表会使远端长期携带旧 hash；直接在文件监听线程注册则让网络故障阻塞目录加载。SDK Card 是不可变对象，必须更新 HTTP 提供者读取的引用，而不是仅重新创建一个未被引用的 Bean。连续更新只保留最新待发布版本，旧发布完成不能清除较新的待发布项。

**代码位置**：`SkillRegistry.onReload`、`SkillAgentCardPublisher`、`LiveSkillAgentCards`、`LiveSkillAgentCardAutoConfiguration`；`LiveSkillAgentCardsTest` 覆盖启动接线、HTTP 路由、增改删和 Nacos 重试。

**边界**：发布与发现为最终一致，Nacos 故障及 Supervisor 的成功缓存刷新间隔内仍可能观察到旧 Card；此时继续拒绝不匹配的显式身份，不猜测或降级。现有执行快照不随发布变化，注册关闭时不会主动开启 Nacos。

**同版本更新**：真实 Nacos 3.1.0 证明 `releaseAgentCard` 对已存在版本不覆盖内容。启动时核对及热加载重发布先使用原 SDK 注册 Card/endpoint，再比对目标版本技能与能力；仅内容不同时调用官方 Maintainer `updateAgentCard`，维持原 version、namespace、SERVICE 寻址及 registerAsLatest 策略。更新客户端按需创建，复用 A2A Nacos 地址与凭据，需配置具有该命名空间 Card 更新权限的 `NACOS_USERNAME/NACOS_PASSWORD`；服务端关闭客户端鉴权也不能免去 Admin API 鉴权。Maintainer 依赖排除额外的非 shaded common/client-basic，使用现有匹配版本 SDK 中的类，避免运行时缺失 gRPC。

**接口依据**：[Nacos 3.1 官方同版本更新 API](https://nacos.io/docs/v3.1/manual/admin/maintainer-sdk/#73-%E6%9B%B4%E6%96%B0agentcard)、[Nacos 3.1 鉴权说明](https://nacos.io/docs/v3.1/manual/admin/auth/)。

**关联**：LR-21、LR-24、LR-25；KI-30、KI-31。

## LR-30 自定义 AgentCard 必须自带属性装配，Starter 条件装配不可依赖

**结论**：子 Agent 以自定义 AgentCard bean（`SkillAgentCardPublisher.publish`）发布技能 Card 时，该 bean 自身的参数所依赖的 `A2aServerProperties`/`A2aServerAgentCardProperties` 不能指望 Starter 的 `A2aServerAgentCardAutoConfiguration` 提供——后者类级 `@ConditionalOnMissingBean(AgentCard)` 会因自定义 Card bean 存在而整体跳过。共享层（common-a2a）统一注册这两个属性 bean（幂等），九服务 Card bean 才能在真实容器装配下启动。

**根因**：Starter 的"有自定义 Card 就不干预"语义与"自定义 Card 构造仍要消费 Starter 属性"并存，条件装配的短路会连带吞掉其 `@EnableConfigurationProperties`。既有接线测试用反射直接 new 参数对象，绕过了容器装配，掩盖了这一点；只有真实进程启动才暴露（KI-47）。

**代码位置**：`common-a2a/src/main/java/com/bkanent/common/a2a/LiveSkillAgentCardAutoConfiguration.java`（属性注册）；九服务 `*OfficialA2aAgent.contractPublishedAgentCard`（消费方）。

**关联**：KI-47、LR-28。

## LR-29 未发送拒绝与已提交结果未知必须区别记录

**结论**：新增调用账本终态 `REJECTED`，只允许从 `PENDING` 原子转入；与执行 claim 竞争时，只有取得 `EXECUTING` 的调用能进入实际工具。明确拒绝的记录不能再次 claim 或被对账覆盖为未知。并行批次使用提交时统一期限，队列中超时且未 claim 的调用记录未执行；已 claim 的调用无法证明取消时保留结果未知。

**根因**：仅返回 EXECUTOR_BUSY 会让账本停在 PENDING；把所有本地超时都当作副作用未知又掩盖真实的未发送事实。目录、审批、执行器容量的治理只限制身份与执行安全，不决定业务步骤，不增加 Graph 节点或规则路由。

**代码位置**：`OrchestrationStore.reject/claim/unknown`、`SupervisorToolLoopGraph.execute/executeCall/awaitUntil`、`OfficialA2aAgentExecutor.executeStream`。下游流式执行受 `agent.a2a.execution.stream-timeout-ms` 限制，默认 300000 毫秒（2026-10-10 由 120000 放宽，适配 gpt-5.6-terra 多轮工具链的单请求时长），超时/中断释放订阅。

**关联**：LR-18、LR-22、LR-23、LR-27；KI-32、KI-33、KI-35、KI-36。

## LR-31 RAG 执行集中与领域权责分离（实施中，未切流）

**结论**：`extract-isolated-rag-service` 选定独立 RAG 服务唯一执行本次 ES 关键词召回、Spring AI Milvus/embedding、融合及通用语义重排，默认双路加重排。领域保留真源、语料、资源授权、生命周期及最终业务排序；通过受控契约影响候选/策略开关，不自己执行某一路。不同知识空间使用独立 ES 索引和 Milvus 集合，集合名称仍不等于访问授权。

**根因**：旧 `split-rag-by-domain` 的共享 common-milvus、域内执行路线会让检索执行继续分散；用户后续明确改为独立服务并迁出清理旧逻辑，且不能将 ES 留在房源域。已有 Spring AI 封装与 embedding 接入应直接复用，不为集中化重写裸 SDK/HTTP 或引入双实现适配器。通用语义排序不能替代领域时间衰减/重要性；足量候选、评分尺度、真源最终检查和可靠事件须分别定义。

**方案位置**：`openspec/changes/extract-isolated-rag-service/`（proposal/design/specs/tasks）。apply 已启动独立 `rag-service`、公共契约、授权及领域迁移实现；任务勾选以实际完成与验证为准。真实配置生效、旧数据映射、回填、在途核对、有界开发切流及水位回滚仍须单独验收；真实 REST 包装继续兼容，无消费者旧 RPC 不要求兼容周期；新增源码不代表运行环境已迁移。旧 change 原样保留仅供历史参考，不再作为本方向实施依据。营销未接入本次 RAG 的独立业务 ES 搜索不被声明已迁移。

**关联**：LR-17、LR-18、LR-19、LR-22；KI-55、KI-56、KI-57。最新个人开发授权明确公司模型为预定目的地，受管内网按 LR-34 的精确 host/CIDR 模式准入，不再要求公开 ES/重排或更换提供方；凭据保护、逐连接校验及真实运行验收仍不可省略。

## LR-32 RAG 逻辑身份、物理键与可见版本分别管理

**结论**：领域持久 revision/tombstone 不因源记录删除重建而归零；新 Milvus 空间使用稳定 UUID 物理键，逻辑 documentId 保存在 metadata。Spring AI delete/add 不被称为原子 upsert；融合与重排前每路命中必须匹配最新账本 revision/hash/schema 且两路已应用。允许暂时漏检，不允许迟到旧正文可见。

**根因**：Spring AI 1.1.2 的默认主键上限为 36 字符，add 执行 insert，取消超时不能撤销已在网络中的旧写。数据库锁只能串行本进程正常执行，不能为多后端制造事务；持久身份及核对补偿才覆盖删除复活和迟到覆盖。旧集合映射不明时不能猜测或删除。

**代码位置**：`common/rag/RagDocumentIds`、`rag-service/storage/SpringAiMilvusDocumentStore`、`rag-service/index/RagIndexService` / `RagIndexWorker`、`listing-master-service/rag/ListingRagOutbox`、`memory-service/service/MemoryRagProjectionService`。

**关联**：LR-31；KI-56、KI-58；真实旧数据及回填/切流仍未验收。

## LR-33 RAG 读取凭据是进程内租约，不是持久化工作流数据

**结论**：真实用户凭据只经专用上下文/RPC 参数传播；异步 run 的有界进程内租约到期或进程重启后需重新认证，不从 userId 或 checkpoint 换发凭据。A2A 使用 auth 所有的短期不透明信标和实际 SDK transport，请求、工具执行及输出前均重新核对当前账号/角色/token，不将 token 或信标塞入模型参数、metadata、RunnableConfig 或 checkpoint。

**根因**：身份定位信息不具持有凭据的证明能力；长期 token 入快照会扩大暴露面，并把已撤销用户的读取权固化到任务恢复。原 SDK 的 Card 自定义客户端未被任务 transport 保留，最小构造器修补已获用户授权，由仓库模块可复核生成而非修改缓存或反射。源码接线不代替当前构建及真实 send/stream/query/cancel/recovery 验收；具体 transport 和任务绑定见 LR-41。

**代码位置**：`agent-service/security/AgentRagCredentialLeaseStore`、`AgentRagCredentialFilter`、`common/rag/RagCredentialContext`；auth/领域/RAG 的 signed principal 与 scope 消费链。

**关联**：LR-18、LR-22、LR-23、LR-31；KI-55、KI-59。

## LR-34 HTTP 校验必须覆盖原始 DNS 答案和实际连接

**结论**：HTTP 仅使用 `rag.http.mode/allowed-hosts/internal-networks`。PUBLIC_ONLY 默认只允许精确获准 host 的公开地址；MANAGED_INTERNAL 显式额外允许该 host 对应的规范 RFC1918/IPv6 ULA CIDR，不能借其他 host 的范围。受管单标签和 host.docker.internal 还须有自己的非空 CIDR map。localhost、环回、链接本地、mapped、组播和其他保留/特殊地址始终禁止；通配、0/0、任意 URL、代理及自动重定向不支持。每次新连接使用不可变策略快照检查全部原始 A/AAAA，绑定已校验地址，保留原 host 的 TLS/SNI/证书校验。

**根因**：预解析后再次按 hostname 连接会产生换址窗口；JDK `getAllByName` 可将 mapped AAAA 折叠为 Inet4Address，之后只按字节长度检查无法证明拒绝全部映射地址。使用 JDK DNS provider 读取记录不等于新增业务 HTTP 客户端，部署须核验 `java.naming` / `jdk.naming.dns` 模块。生产 configured 路径在两种模式都要求非空精确 host 集合；旧公开地址测试入口的空集合兼容语义不能成为缺配置的生产授权。

**代码位置**：`rag-service/security/http/RagEndpointPolicy`、`RagPublicDnsResolver`、`RagDnsRecords`、两代受保护 transport；`storage/ElasticsearchDeadlineReader` 使用同一 ES RestClient 的可取消异步读取。

**关联**：LR-31；KI-57、KI-83；固定Java17 JRE实际命令已核对java.naming／jdk.naming.dns，应用目标与默认检索仍单独验收。私有probe编译需另用真实JDK，不能据JRE版本推断含jdk.compiler。

## LR-35 两路硬过滤必须保留数值边界与时间顺序

**结论**：新 schema 的 NUMBER 使用 double，但仅接受有限、非零不下溢且 `BigDecimal.valueOf(double)` 数值 round-trip 相等的输入；不支持的精度明确拒绝。INSTANT 编码为固定 UTC 九位小数，ES 使用 date_nanos，限制 epoch nanos 在 [0, Long.MAX_VALUE]；不得将此新编码声明为兼容未知旧数据。

**根因**：类型化 AST 仍可能在后端数值转换中移动硬过滤边界；ISO 时间字符串若小数位数或时区不固定，Milvus 字符串比较不等价于时间顺序。SQL DATETIME/DECIMAL 又可能规范化请求值，记忆领域必须在同一事务锁内重新读取持久化表示，再生成完整投影与 hash，否则合法候选会在最终真源核对中被误剔除。

**代码位置**：`rag-service/storage/ProjectionFilters`、`ElasticsearchProjectionStore`；`memory-service/service/UserPreferenceMemoryService`、`SystemConstraintMemoryService`、`MemoryRagProjectionService` / `MemoryRagSearchFacade`。

**关联**：LR-31、LR-32；KI-62、KI-63；旧数据时间编码与真实 MySQL 仍需独立验收。

## LR-36 RAG 管理与依赖权限错误不能借服务写权限或降级放行

**结论**：INSPECT/PROVISION/REPLAY 同时要求 key/空间允许操作、USER 身份、已验签真实 principal 与 adminRoles；SERVICE writer 即使获 operation grant 也不能管理。真实 HTTP 401/403 与 gRPC 认证/权限错误不可重试或降级。Spring AI 1.1.2 丢失 HTTP cause 时，只对其两种 typed retry exception 的完整固定前缀 `HTTP 401 - ` / `HTTP 403 - ` 分类，不扫描任意正文。

**根因**：operation grant 仅验证调用服务，不替代用户角色授权；空 adminRoles 无法阻断原 SERVICE 管理分支。真实依赖权限拒绝若归入普通不可用，会被 ALLOW_DEGRADED 绕过，管理 probe 和实际搜索必须一致保留硬拒绝。

**代码位置**：`rag-service/security/RagAccessControl.admin`、`config/RagRuntimeConfiguration.capabilityFailure`、`config/ManagedRagSpaceRegistry`、`retrieval/RagRetrievalService.classify`、ES/Milvus/BGE 边界及对应反例测试。

**关联**：LR-31、LR-33；KI-64、KI-65；应用默认角色仍为空以失败关闭，选定开发 Nacos 模板明确 ADMIN/BROKER；模板发布不证明运行实例已读取或授权已生效。

## LR-37 确定只读知识调用以首个终态结果为准

**结论**：仅 `local:milvusKnowledgeSearch` 使用 `completeKnowledgeRead` 条件更新；超时、已 claim 的遗留只读调用及迟到结果不能覆写首个完成事实或重发。原一般工具 `complete`、副作用 OUTCOME_UNKNOWN 和 Graph 拓扑保持不变。

**根因**：远程知识读取已无业务副作用，确定失败应作为已完成失败事实供模型观察；迟到成功覆盖超时审计会使 checkpoint 与调用事实不一致。不能据只读工具安全性顺带放宽所有业务工具重试或改变审批恢复。

**代码位置**：`agent-service/orchestration/OrchestrationStore.completeKnowledgeRead`、`SupervisorToolLoopGraph`；生产 Graph 回归覆盖 timeout/late/reuse/legacy claim 与异步专用凭据租约。

**关联**：LR-18、LR-22、LR-23、LR-33；KI-67；本次没有新增状态机 transition 调用点。

## LR-38 记忆扩池累计预算与最终评分快照分开管理

**结论**：一次领域 search 的所有候选和最多两次远程池共用一个评分 asOf；首池与扩池按 documentId 合并、重复身份采用后次候选，合并后重新核对当前 SQL。最终 topK 不提前裁剪候选池，公共候选 metadata 只包含领域声明的返回字段。召回总预算为 max(500, 首次选中路数量之和)；合法显式首池数量不改写，耗尽则不扩池，首池未填满也不盲目追加。

**根因**：逐候选采样时间可把相同输入评分变成遍历时序差异；只用第二池会丢首池幸存者，逐次有界不等于总成本有界。保留候选不能跳过第二次真源变化核对，也不能为补足 topK 重新执行域内召回。owner/user 字段存在于授权索引不等于允许全部公开为候选 metadata。

**代码位置**：`memory-service/service/MemoryRagSearchFacade` 及确定时钟/合池/预算/公共 metadata 回归。

**关联**：LR-31、LR-35；KI-70、KI-71、KI-72；实际评分 profile 注册和真实双路验收仍未完成。

## LR-39 回填页与遗留重建证明均不等于迁移完成

**结论**：memory 回填按源身份和持久投影身份分阶段 keyset 游标做至多500项的单页，包含持久删除身份，在同一领域锁事务重新读取快照并发布同一 outbox；返回游标与 QUEUED receipts，不宣称双后端已应用。个人偏好源、持久身份、游标、scanned 和 receipts 都严格限定当前 owner，ADMIN 亦不例外。最新用户已确认旧索引 RPC 无真实调用方，直接清理 ID-only RPC 与无消费者的 signed listing legacy reconcile/证明链，不再建设旧事务证明或兼容周期。

**根因**：全表扫描持有全局 writer 锁会把迁移成本转成无界在线阻塞；只过滤最终正文仍可能通过游标、扫描计数或回执泄漏他人偏好。历史 ID-only 入口无法证明旧事务，增加无调用方的证明适配只会制造无效迁移负担。正常 index/backfill/outbox 的完整真源装配及 RAG worker 对实际 revision/hash 的核对补偿仍是必要职责，不能因 reconcile 同名而删除。

**代码位置**：`memory-service/service/MemoryRagBackfillService` / `controller/MemoryController`；`listing-master-service/rag/ListingRagBackfillService` / `ListingRagOutbox`。

**完成边界复核**：任务8.1验收受权回填实现、同锁完整快照/outbox及更新/删除交错测试；未知通用知识按规格保持隔离，不要求建设新语料，也不宣称该空间迁移完成。真实回填、两路APPLIED/实际投影水位与切流由1.2/8.2/8.3等运行任务单独验收。同理7.2验收首次消费者切流版本源码/配置清理，不以未部署重复阻塞已完成实现；1.3的当前有效 ADMIN/BROKER、MANAGER 拒绝和个人 owner-only 基线仍需核对，不建设资源 grant 规范，也不能据测试 ADMIN 替代真实身份。

**关联**：LR-32、LR-35、LR-40；KI-73、KI-74、KI-75；逐空间清单见 `docs/acceptance/isolated-rag-migration-inventory.md`。旧 RPC 成功路径要求由最新无消费者直接删除决策替代，真实应用水位、开发切流及回滚仍需验收。已授权执行范围与尚缺真实证据见 `docs/acceptance/isolated-rag-unblocking-steps.md`。

## LR-40 最小业务全读与个人记忆 owner-only 是两种不同权限

**结论**：本期只有 ADMIN 和 BROKER；MANAGER/未知角色不因配置列入允许集就自动准入。ADMIN 全读未删除房源（包括非 ON_SALE），BROKER 仅 ON_SALE。listing-v2 的显式 businessWideRead/activeOnly 与 rag.scope.v2 签名表达业务范围，空资源列表不是通配；RAG 和领域分别按当前 signed principal 复核。个人记忆的所有读写、会话/产物内部访问、回填游标/计数/回执均只属于真实 owner，ADMIN 也不得跨用户；后台维护不能获得用户访问权。

**根因**：用户明确暂不建设业务资源 grant 规范，但对个人记忆要求强隔离。broker_id 是业务归属不是认证 userId；将管理员业务全读推广到偏好会破坏该边界。仅验证入站 owner/body 还不够，更新既存 session 必须检查当前持久 owner；输出前重新认证并核对授权条件，避免执行期间离职、停用、删除、角色失效或 token 撤销后仍获得内容。

**代码位置**：`common/rag/RagAuthorizationScope` / `RagScopeSigner`；`rag-service/security/RagAccessControl` / `rpc/RagRetrievalRpcServiceImpl`；`listing-master-service/rag/ListingRagIdentity` / `ListingRagFacade`；`memory-service/controller/MemoryRequestAuthenticationFilter` / `MemoryController`、`service/MemoryRagSecurity` / `MemoryRagBackfillService`。

**输出复验位置**：最后一次 SQL 或锁内真源评分完成后再认证原凭据并保持 actor 不变，不能将召回后、等待 writer 锁之前的认证当作最终输出认证。房源单 ID 同样依据 SQL 后的当前角色检查，ADMIN 降为 BROKER 后不得输出非 ON_SALE 项；HTTP controller 的响应保护不能替代直接 Dubbo 路径。

**关联**：LR-31、LR-33、LR-39；KI-55、KI-78、KI-79、KI-89；当前源码及运行验收分别记录，不据授权声明测试通过。

## LR-41 A2A 请求信标、任务归属和模型上下文分别保存

**结论**：auth 先验证真实 access token/current account/role，再签发绑定 method/task/requestId/bodyHash/caller/audience/actor 的不透明短期信标。每个 send/stream/get/cancel/resubscribe 都重新签发精确请求绑定，官方 handler 在 TaskStore/队列访问前验证并核对原任务 owner/caller/context；线程、工具与输出使用专用有界租约且前后重新认证。请求签名最长60秒，身份租约最多300秒且不得超过原 token/agent 租约；二者不能混作长期授权。任何 task lease 不得被另一 owner 或在途 lease 覆盖，容量与注册须原子化。

**根因**：A2A 不经过网关，SDK ServerCallContext 也没有自动承载可信凭据。只保护 AgentExecutor 会漏掉查询/取消；ThreadLocal 不会自动跨 SDK/Graph executor 或 SSE callback，metadata 则会进模型和快照。原 SDK 的 URL/Card 构造器及默认重定向 transport 需最小受管修补；仓库生成官方 A2AClient/JdkA2AHttpClient 两个选定源文件的补丁制品并复用 JSON-RPC，不改 .m2、final 字段或重写业务客户端。专用信标不发给 Card discovery，也不得随 redirect 转发。

**发送生命周期边界**：按 SDK 实际序列化的 `configuration.pushNotification` 在官方 handler 前拒绝未支持推送，不能只检查相似字段名。在途 send/stream 必须在 TaskStore／队列副作用之前原子预留；同 owner 也不能替换租约。预留仅在原 guard forwarding 和整个 SDK executor command 均结束后条件释放，不能在模型 executor 返回时释放，否则旧队列关闭或 runningAgents 清理可能干扰新任务。未成功转交的请求可释放其自身预留，但原 owner/caller/context 仍不可更换。

**代码位置**：`a2a-sdk-transport-patch`；`common-a2a/BeaconA2aHttpClient` / `A2aAuthenticatedRequestGuard` / `A2aCredentialContext` / `A2aContextExecutorProvider` / `A2aAuthenticationToolInterceptor`；`auth-service/service/A2aBeaconStore` / `rpc/A2aBeaconRpcServiceImpl`；`agent-service/client/OfficialA2aAgentClient`及专用 executor/Graph lease 接线。

**关联**：LR-18、LR-20、LR-22、LR-23、LR-33；KI-59、KI-80、KI-81、KI-88。当前SDK制品已精确重建／逐字节核验，并经完整本地回归；真实五操作和跨服务当前身份联调仍单独验收，不以 fail-closed stub 宣称正向能力完成。

## LR-42 同一版本清单驱动首启和升级，但不重放历史种子

**结论**：`docker/mysql/init-manifest.tsv` 以固定顺序、目标数据库、不可变版本、SQL 和 schema check 的规范 LF SHA-256 定义初始化；首启执行九步，既有库升级执行六步。共用 runner 先验证全清单、再执行 DDL/检查并记 checksum history，已应用版本不能静默改 SQL/check。fresh-only 种子不对现存库重新播放；本次只补 RAG 与 agent 当前编排结构，不把全仓历史迁移当升级链。

**根因**：MySQL CLI 的 USE 不跨独立连接，MySQL8.4 不支持历史 ADD COLUMN IF NOT EXISTS 写法；Windows CRLF 又能使正确 SQL 的字节 hash 不一致。版本/checksum/schema 三者必须一起验证，容器 healthy 不是结构完成。现有开发库虽房源/偏好为空，仍有17约束、3共享记忆、2用户、3workflow及46checkpoint，不能删库重建。RAG runtime 仅两个控制表 SELECT/INSERT/UPDATE，DDL/备份由单独管理身份完成。

**代码位置**：`scripts/deploy/run-init-manifest.sh` / `apply-migrations.sh`；`docker/mysql/001-manifest.sh` / `init-manifest.tsv` / `checks`；六份受管 SQL；`docker-compose.yml` 首启挂载。

**关联**：KI-37、KI-38、KI-39、KI-82；实际 MySQL 首启、升级、重复执行及保种子已验，隔离恢复副本原计数一致；Nacos paused快照已在独立internal网络恢复，12原config/hash一致且无有效host端口/mount；这不是graceful cold备份。当前应用/config/索引事件水位回滚另验，不把历史全仓初始化债务一并标已修。

## LR-44 共享Dubbo运行依赖与私有直连序列化不属于SDK补丁

**结论**：common-a2a为所有实际共享消费者声明OkHttp运行依赖，官方A2A SDK patch仅保留原依赖和两个transport构造器修补。私有无registry探针固定使用提供方标准支持的fastjson2 wire参数，原URL仍拒绝userinfo/query/fragment；不使用add-opens、不降低Auth签名或provider检查。

**根因**：真实Listing日志的Nacos discovery事件刷新invoker，经Protocol.Adaptive.refer进入RestProtocol及默认OkHttpClientFactory时缺okhttp3.Callback；编译和部分Agent测试依赖不能证明下游Boot运行classpath完整。裸Dubbo直连缺注册协商prefer metadata会回落Hessian2，Java17实际Auth反射失败；官方3.2.15默认支持fastjson2,hessian2，使用可用标准序列化不等于身份绕过。准确区分依赖所有者与真实协议协商，避免把无关库加入官方SDK补丁或为测试放开Java模块。

**代码位置**：`common-a2a/pom.xml` / `OfficialRuntimeDependenciesTest`；`scripts/verification/RagRuntimeProbe.java`。Agent排除未使用的DashScopeAgent自动配置而保留业务DeepSeek，营销两个必要RPC配置采用独立Bean名，不复制模型凭据或删除有效桥。

**关联**：LR-33、LR-41；KI-94～97。真实调用栈仅证明REST扩展加载链，不推断具体provider metadata；无网络链接/round-trip/扫描测试、当前完整制品与实际签名调用分别取证。

## LR-45 DNS记录类型与RPC消费者协议须显式，不靠可达性推定

**结论**：受保护HTTP以同一平台JNDI上下文分别请求A／AAAA，在一个整体deadline内两次成功并校验完整原始答案后连接；不使用混合属性生成ANY、不忽略AAAA错误。纯Dubbo消费者显式consumer.protocol=dubbo，provider协议与HTTP发现保留，不给纯RPC补REST注解或删除官方REST扩展。

**根因**：OpenJDK17多属性查询会转成ANY，Docker内部地址服务处理A／AAAA而当前ANY超时；getent结果不能代替实际守卫。Dubbo3.2.15可把同名HTTP-only通知合成REST通配服务，未限定消费者协议会匹配并触发先HTTP工厂、后REST注解解析。端口或provider name=dubbo正确仍不足以限定消费者。两处修复均用官方受支持机制，不扩大地址／身份权限。

**代码位置**：rag-service/security/http/RagDnsRecords及RagDnsRecordsTest；已有纯Dubbo消费者application.yml及common-a2a官方适配器／配置绑定回归。

**关联**：LR-34、LR-44；KI-98～100。实际旧制品失败与新源码测试分别保存，默认检索与发现调用必须经新制品验证后才完成。

## LR-46 Dubbo字段引用与显式桥共存时，消费方绑定权威桥名

**结论**：Listing身份、检索与索引的ObjectProvider精确限定既有ragPrincipalRpcService／ragRetrievalRpcService／ragIndexRpcService显式桥；保留Dubbo字段ReferenceBean及旧AuthPermission独立用途，不加任意Primary、不回退缺失桥。

**根因**：官方Dubbo3.2.15把@DubboReference字段名注册为接口产品，而Distributed的@Bean又返回同一代理。两个产品对象相同仍有两个Spring候选名字，按类型getIfAvailable会抛NoUniqueBeanDefinitionException。直接new(provider)单测绕过构造器解析，只有真实双候选上下文可覆盖；消除正常引用或扩大旧Auth用途会破坏接线／签名边界。

**代码位置**：listing-master-service/rag/ListingRagIdentity、ListingRagFacade、ListingRagDispatcher及ListingRagIdentityTest；ListingRpcReferenceConfiguration保留原桥。

**关联**：LR-40、LR-45；KI-101。Memory的ReferenceConfig非FactoryBean与RAG直接Dubbo字段并非此模式，不作无证据同类改动；真实领域调用单独验收。

## LR-47 空间诊断共享绝对期限，取消不提前释放实际执行租约

**结论**：INSPECT固定按KEYWORD／VECTOR／EMBEDDING／RERANK执行，排队、惰性构造、阶段等待及后端读取共用一个overall绝对截止时间，阶段仅取剩余预算。线程与队列有界；inspect／provision按空间互斥，Future取消后仍执行的worker必须真实退出才释放租约，worker不发布能力证据。未执行、整体过期、缺probe和容量拒绝不能成为显式降级依据；已调用probe的TIMEOUT保留已知失败，由既有选择阶段与失败策略判断，默认能力仍不就绪。

**根因**：消费者等待超时不等于远端结束。旧全registry synchronized可使其他空间排队，四阶段独立预算和SDK重试又能超过调用期限；仅在线程绑定deadline不能取消同步ES读取。取消Future的完成状态也不能证明schema副作用结束，因此不能据此允许同空间PROVISION。没有执行证据和已知瞬时失败必须区分，否则会错误降级或破坏既有显式降级契约。

**代码位置**：rag-service/config/ManagedRagSpaceRegistry及其并发回归；storage/ElasticsearchProjectionStore与ElasticsearchDeadlineReader以现有受保护异步客户端执行HEAD／mapping；SpringAiMilvusDocumentStore只读schema核对先配置独立单次RetryParam再配置remaining gRPC deadline；RagRuntimeConfiguration为实际Spring AI模型提供单次无退避RetryTemplate，检索执行器继续拥有有界重试。

**关联**：LR-31、LR-34、LR-46；KI-102。实际Spring AI自动配置及离线503单次调用、原lazy/base启动分别验证；不新建embedding客户端或裸Milvus CRUD，不承诺取消能撤回远端副作用。私有probe只输出安全计时与操作后重新认证的stage/code，运行能力必须用新制品实测。

## LR-48 公共RPC安全异常须显式声明，沿官方接口初始化精确注册

**结论**：四个RAG纯RPC接口的七个已有失败方法显式声明unchecked `throws RagException`，消费者与提供方采用同一契约制品。保留异常final字段、构造器、STRICT和Serializable检查，不新增包级allowlist或Java模块开放。

**根因**：Dubbo3.2.15官方SerializeSecurityConfigurator.registerInterface遍历方法声明的异常并递归注册字段类型；未声明的RuntimeException不会因此受信。实际fastjson2 readThrowable拒绝公共RagException，使正确的SPACE_NOT_READY被序列化错误遮蔽。声明异常不改变二进制方法descriptor；官方wire可重建final code／安全field，问题不是无参构造器缺失。

**代码位置**：common/rpc/RagPrincipalRpcService、RagRetrievalRpcService、RagIndexRpcService、RagSpaceAdminRpcService；common-a2a/OfficialRuntimeDependenciesTest逐接口隔离官方初始化及writeThrowable／readThrowable回归。

**关联**：LR-44、LR-46；KI-103。未声明接口先拒绝、实际声明契约后156种code／field组合恢复，未知Throwable与非Serializable对象仍拒绝；离线固定wire ID不是实际协商ID。收到业务错误不等于空间就绪，也不承诺领域HTTP已具该错误映射。

## LR-49 受保护ES连接池由唯一官方RestClient执行reactor生命周期

**结论**：HC4连接管理器设shared=false，由Boot唯一单例ES RestClient启动和关闭原reactor；保留Spring manager.shutdown作为官方幂等后备，覆盖client未构造的INACTIVE资源。原逐连接完整DNS校验、地址pinning、TLS、proxy／redirect拒绝均不变。

**根因**：HC4 shared=true不仅影响关闭，还抑制client的reactor线程；client.start可显示ACTIVE而原reactor仍INACTIVE，连接请求入队直到读取期限。仅构造client或mock响应测试无法证明实际reactor执行。官方pool shutdown以CAS保护资源清理，因此Spring后备不需要自造第二所有者或执行器。

**代码位置**：rag-service/security/http/RagElasticsearchTransportConfiguration及RagHttpTransportTest；package-private manager仅保留原reactor状态枚举供生命周期回归观察。

**关联**：LR-34、LR-47；KI-104。真实Boot单例、ACTIVE到SHUT_DOWN、重复client.close及无client后备均验证；真实HEAD仍在原policy拒绝环回且不连接。源码缺口与当前运行HTTP／schema结果分开取证，不再把该故障归为已修的JNDI ANY。

## LR-50 Milvus集合缺失只按schema操作的兼容枚举分类

**结论**：describeCollection的官方ServerException兼容枚举CollectionNotExists，或同一异常的精确组合getStatus=100且compatibleCode=UnexpectedError，映射SPACE_NOT_READY／vectorSchema；原revision query仍按依赖错误处理。裸R.status=100、DataCoordNA、null兼容枚举及异常文本均不是分类依据。先遍历cause链保留认证、权限、期限和原RagException，再分类缺schema。诊断仅固定操作、nullable数字和枚举，不打印reason／message／目标或异常。

**根因**：SDK2.5.8把现代Status.code和旧error_code合并为ServerException.getStatus，两个命名空间数字不能混同；兼容enum的100是DataCoordNA。v2.4.17官方merr.ErrCollectionNotFound的现代code为100，原merr.Status映射兼容CollectionNotExists，但DescribeCollection proxy为PyMilvus兼容明确把旧enum改UnexpectedError而保留现代100。UnexpectedError旧值为1，同一异常merged100／compatibleUnexpectedError不可能来自legacy fallback，因此该组合确证现代collection-not-found。它仍可能表示notAvailable，不自动证明物理集合缺失或允许覆盖；显式初始化前须结合当前只读catalog。revision查询失败不能自动初始化或变成成功空记录。固定来源：milvus-io/milvus v2.4.17 pkg/util/merr/errors.go:64-66、utils.go:72-91／148-149、internal/proxy/task.go:632-649、internal/rootcoord/meta_table.go:597-633；milvus-proto v2.4.17 common.proto旧enum与独立code字段。

**代码位置**：rag-service/storage/SpringAiMilvusDocumentStore的schema-only检查及既有SpringAiMilvusDocumentStoreTest；ElasticsearchProjectionStore沿原受保护reader输出固定SCHEMA_HEAD／SCHEMA_MAPPING状态、错误码和单调耗时。

**关联**：LR-36、LR-47、LR-49；KI-104。真实运行typed值仍须新制品取证，历史collection_count=0不代替当前错误；HTTP200但mapping不兼容也不是missing-only。安全日志只作为原生产路径证据，不引入旁路客户端或放行PROVISION。

## LR-51 A2A拒绝响应ID须转换为官方支持的Java标量

**结论**：认证／任务访问拒绝沿现有JSONRPCErrorResponse返回固定-32001和安全枚举；请求String／32位Integer id转换为Java String／Integer，null／missing／其他类型使用Java null，不把Jackson节点对象传入官方响应构造器。不扩张SDK支持类型或绕过原鉴权。

**根因**：官方SDK的响应父类Assert仅接受null／String／Integer；JsonNode即使表示合法String仍不属于这些类型，会在处理原RagException时二次抛异常，Starter返回HTTP500空正文。只用malformed body测试使解析失败、id保持null，会错过正常JSON拒绝路径。

**代码位置**：common-a2a/LiveSkillAgentCardAutoConfiguration及LiveSkillAgentCardsTest的官方序列化／Starter Router MockMvc参数化回归。官方Utils ObjectMapper省略Java null id，回归断言缺失而非强行序列化id:null；String和Integer仍核对原值。14组参数化及两个既有Card测试16项、实际guard8项，共24定向通过，不含不存在的A2aInflightRequestGuardTest。

**关联**：LR-41、KI-106；实际正确Listing端点的修复前500证据保留，零delegate交互由本地回归验证，不能据HTTP500补造真实队列或模型零执行。

## LR-52 ES对象schema验证兼容官方隐式object表示但不弱化字段约束

**结论**：创建mapping仍显式type=object；读回递归比较仅在expected type为object、actual确实没有type键且具有非空properties Map时接受省略。显式null、nested或其他type不等价；所有properties子字段、两个dynamic strict、date_nanos及格式继续严格校验。已有索引不PUT／覆盖，初始化只复用兼容schema。

**根因**：ES v8.15.3官方ObjectMapper序列化在存在子字段时省略type=object，由properties隐式识别对象；空对象才显式输出type。parser同样只对properties对象推断object。创建的正确mapping与读回JSON并非逐叶相同，原递归subset比较在metadata.type上误拒绝。当前实际单次读回唯一差异正是该省略，25受管字段与双strict均匹配，不能推广为任意missing字段可忽略或放宽数值／日期等价。

**代码位置**：rag-service/storage/ElasticsearchProjectionStore.containsMapping及ElasticsearchProjectionStoreTest的创建读回／已有兼容／冲突回归。官方来源：https://github.com/elastic/elasticsearch/blob/v8.15.3/server/src/main/java/org/elasticsearch/index/mapper/ObjectMapper.java#L701-L744（序列化）、L260-L308（解析）；Spring Data5.5.0 IndicesTemplate／RequestConverter将Document作为TypeMapping传入，不额外嵌套mapping。

**关联**：KI-107、LR-36、LR-47、LR-49；首次PROVISION失败与partial原样保留。官方归一化兼容不证明新发布或三空间ready，不借此开启worker或直接写后端。

## LR-43 治理重排按响应钉扎模型身份，同 alias 漂移失败关闭

**结论**：`rag-service` 对外部 Reranker-BGE-V2-M3 治理接口（`/api/v1/rerank`，`RAG_RERANK_PROTOCOL=governed`）的重排调用在每次响应上核对 `model.alias/revision/scoring_version/artifact_digest` 与 `score_semantics` 必须精确等于受管配置（`RAG_RERANK_EXPECTED_REVISION`/`EXPECTED_ARTIFACT_DIGEST` 与空间 rerank-profile 的 provider/modelVersion/scoringVersion/scale）。任何一项不匹配抛 SCORING_PROFILE_MISMATCH，不尝试重新归一化或继续用旧分数。请求固定 `truncate=false`，响应声明截断发生即 RERANK_PROTOCOL_ERROR；400/413 映射为不可重试的 INVALID_ARGUMENT，401/403 仍为身份错误。

**根因**：外部服务的模型 alias 是部署级标识，不是推理身份；同一 alias 下 revision、评分实现或 artifact 变化会改变分数尺度（logit vs sigmoid、数值分布）。若客户端只按 URL/alias 信任，模型热替换后旧 profile 的 sigmoid/范围校验会在错误尺度上运行，产生静默的排序偏差而非显式失败。`truncate=false` 若被服务端静默截断，重排分数对应的文本与候选实际内容不一致，无法通过事后校验发现。400/413 是请求参数/预算漂移（配置与预算不匹配），重试只会重复失败并白耗阶段预算。

**代码位置**：`rag-service/src/main/java/com/bkanent/rag/rerank/BgeRerankService.java`（governed 分支与身份核对）、`RerankEndpoint.java`（钉扎字段）、`config/RagRuntimeConfiguration.java`（端点装配）；`nacos/rag-service.yaml`（expected-revision/artifact-digest 与三空间 measured-governed-profile 锚点）、`docker-compose.yml`/`.env.example`（env 接线与 bkanent-rerank-dev 网络声明）。

**关联**：LR-31、LR-34；KI-57（目标合规部分已接线，真实联调未做）。分数归一化仍由 `RerankProfile.normalize` 单点执行（LR-31）；身份钉扎值来自外部项目 `docs/capability-results-dev.json` 实测记录，外部升级模型时必须同步更新三处（endpoint env、nacos rerank 段、空间 rerank-profile 的 scoring-version），漏改即失败关闭而非漂移。
