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

**关联**：LR-31、LR-34、LR-46；KI-102、KI-110。实际Spring AI自动配置及离线503单次调用、原lazy/base启动分别验证；不新建embedding客户端或裸Milvus CRUD，不承诺取消能撤回远端副作用。私有probe只输出安全计时与操作后重新认证的stage/code，运行能力必须用新制品实测。2026-10-15确认能力证据60秒无自动续验是未修限制（KI-110）。同日实施有界续验：`requireReady`遇到缺失／incomplete／过期证据时按空间单飞续验——恰一个真实四阶段re-inspect（永不PROVISION），并发调用者对在飞Operation有界等待完成闩锁而不竞速，续验占用该空间overall预算（嵌套检索路径经`RagHttpDeadline.capture`取较小期限），失败续验照常发布失败证据使后续读者继续失败关闭；所有既有阶段期限、真实执行租约、失败关闭及协调器唯一发布约束保留。RAG索引调度改为逐tick读取`rag.index.worker.enabled`（`@ConfigurationProperties`可Nacos热重绑），与Memory dispatcher入口检查对齐，实现真实暂停／排空；不引入永久ready、忽略SPACE_NOT_READY或无限重试。

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

**关联**：LR-41、KI-106；实际正确Listing端点的修复前500证据保留，零delegate交互由本地回归验证，不能据HTTP500补造真实队列或模型零执行。7e7ef1a新四服务发布后，正确Listing9004/a2a单次无专用凭据message/send实测609ms返回HTTP200／JSONRPC2／匹配String ID／-32001／AUTHENTICATION_FAILED，拒绝响应缺陷已运行闭合；队列／模型实际计数仍UNKNOWN，不替代五操作与身份租约验收。

## LR-52 ES对象schema验证兼容官方隐式object表示但不弱化字段约束

**结论**：创建mapping仍显式type=object；读回递归比较仅在expected type为object、actual确实没有type键且具有非空properties Map时接受省略。显式null、nested或其他type不等价；所有properties子字段、两个dynamic strict、date_nanos及格式继续严格校验。已有索引不PUT／覆盖，初始化只复用兼容schema。

**根因**：ES v8.15.3官方ObjectMapper序列化在存在子字段时省略type=object，由properties隐式识别对象；空对象才显式输出type。parser同样只对properties对象推断object。创建的正确mapping与读回JSON并非逐叶相同，原递归subset比较在metadata.type上误拒绝。当前实际单次读回唯一差异正是该省略，25受管字段与双strict均匹配，不能推广为任意missing字段可忽略或放宽数值／日期等价。

**代码位置**：rag-service/storage/ElasticsearchProjectionStore.containsMapping及ElasticsearchProjectionStoreTest的创建读回／已有兼容／冲突回归。官方来源：https://github.com/elastic/elasticsearch/blob/v8.15.3/server/src/main/java/org/elasticsearch/index/mapper/ObjectMapper.java#L701-L744（序列化）、L260-L308（解析）；Spring Data5.5.0 IndicesTemplate／RequestConverter将Document作为TypeMapping传入，不额外嵌套mapping。

**关联**：KI-107、LR-36、LR-47、LR-49；首次PROVISION失败与partial原样保留。7e7ef1a新RAG生产signed ADMIN INSPECT已在原受保护HEAD200／NONE55ms及MAPPING200／NONE28ms验证保留partial，Listing仅VECTOR缺失；mapping误判运行闭合。官方归一化兼容不证明三空间ready，不借此开启worker或直接写后端。

## LR-53 Studio 草稿与回执同事务，生成来源和执行政策保持原身份

**结论**：Studio 的 work/context/UI/run/command 和人工草稿不可变修订归属 agent 库，Memory 只保存未改写的生成成果，sourceArtifactId 用于溯源；不双写正文。seriesId 是稳定成果身份，revisionId/version 是不可变版本。AI 建议另建系列，明确引用基准，不按标题合并。上下文、UI及工作修订号分开。

**根因**：原 Memory artifact 是不可变生成输出，没有事务编辑/OCC及回执接口。跨服务双写无法原子绑定用户修改和幂等结果；把旧 versionNo 当人工版本也会冲突。Studio 修订与回执同事务可在响应丢失时找回原结果，expectedVersion防止覆盖。NEW复用既有异步执行，CONTINUE仅 WAITING_USER_INPUT并冻结原context/skill/allowMcp和有效contextRevision；新增引用需装配授权的不可变内容快照，不能把Studio系列ID误当Memory产物ID。

**代码位置**：agent-service/studio；sql/migrations/20261015_studio_workspace.sql；docker/mysql/init-manifest.tsv。事件水位先于状态读取，接受重放重叠；客户端定时及断线读取权威状态，以覆盖持久事件不可用的回退。工作与原事件保持owner-only，公开能力发现不代表健康、allowedActions不授予权限；新模型执行使用原审批门，完整领域写策略仍待对接。

**关联**：LR-40、LR-41、LR-42；KI-114。仅完成源码/编译与契约，未应用迁移或声明运行闭合。

## LR-54 比较读取先授权真源，万元只在量纲边界转换一次

**结论**：用户已确认旧 ListingDTO.totalPrice/数据库总价为万元。Studio CNY金额乘10000成为元，单价共享公式为 totalPrice×10000/area 元/㎡；缺面积/总价不假造单价。来源指纹与 compare-yuan-v2 一起隔离旧缓存，先重新读取当前授权来源，再查缓存。

**根因**：旧比较缓存只含ID/AI flag，旧公式直接除面积却标元，既会过时也相差一万倍。旧 compare RPC 仍调用已停用的无凭据 Listing.getListingById，不能为接通前端重新放行旧读取。Studio 只读比较走现有签名READ并复用 common 纯计算，限定候选及来源版本，结果和回执在同一事务登记，不依赖旧缓存/分享入口。

**代码位置**：common/model/ListingComparisonFacts、ListingDTO；agent-service/studio/StudioListingReader、StudioCommandService；compare-engine-service/CompareAnalysisServiceImpl。Listing READ公开接口声明RagException沿LR-48受管序列化；Studio按LR-46精确使用现有桥，不增加重复Dubbo引用。

**关联**：LR-40、LR-46、LR-48；KI-115。旧compare读取/分享权限迁移仍待实施，编译通过不等于真实服务可用。

## LR-43 治理重排按响应钉扎模型身份，同 alias 漂移失败关闭

**结论**：`rag-service` 对外部 Reranker-BGE-V2-M3 治理接口（`/api/v1/rerank`，`RAG_RERANK_PROTOCOL=governed`）的重排调用在每次响应上核对 `model.alias/revision/scoring_version/artifact_digest` 与 `score_semantics` 必须精确等于受管配置（`RAG_RERANK_EXPECTED_REVISION`/`EXPECTED_ARTIFACT_DIGEST` 与空间 rerank-profile 的 provider/modelVersion/scoringVersion/scale）。任何一项不匹配抛 SCORING_PROFILE_MISMATCH，不尝试重新归一化或继续用旧分数。请求固定 `truncate=false`，响应声明截断发生即 RERANK_PROTOCOL_ERROR；400/413 映射为不可重试的 INVALID_ARGUMENT，401/403 仍为身份错误。

**根因**：外部服务的模型 alias 是部署级标识，不是推理身份；同一 alias 下 revision、评分实现或 artifact 变化会改变分数尺度（logit vs sigmoid、数值分布）。若客户端只按 URL/alias 信任，模型热替换后旧 profile 的 sigmoid/范围校验会在错误尺度上运行，产生静默的排序偏差而非显式失败。`truncate=false` 若被服务端静默截断，重排分数对应的文本与候选实际内容不一致，无法通过事后校验发现。400/413 是请求参数/预算漂移（配置与预算不匹配），重试只会重复失败并白耗阶段预算。

**代码位置**：`rag-service/src/main/java/com/bkanent/rag/rerank/BgeRerankService.java`（governed 分支与身份核对）、`RerankEndpoint.java`（钉扎字段）、`config/RagRuntimeConfiguration.java`（端点装配）；`nacos/rag-service.yaml`（expected-revision/artifact-digest 与三空间 measured-governed-profile 锚点）、`docker-compose.yml`/`.env.example`（env 接线与 bkanent-rerank-dev 网络声明）。

**关联**：LR-31、LR-34；KI-57（目标合规部分已接线，真实联调未做）。分数归一化仍由 `RerankProfile.normalize` 单点执行（LR-31）；身份钉扎值来自外部项目 `docs/capability-results-dev.json` 实测记录，外部升级模型时必须同步更新三处（endpoint env、nacos rerank 段、空间 rerank-profile 的 scoring-version），漏改即失败关闭而非漂移。

## LR-55 记忆逻辑身份、完整事实形状与授权证明分别定义

**结论**：common memory DTO 使用 `(ownerId,domainId,agentId,conversationId)` 复合身份，事项/run/A2A task/domainSession 独立关联；私有偏好 scope 同时包含 domain 与 agent，用长度分帧避免分隔符碰撞。封闭 JSON record 树保留工具 responseData，完整工具信封与执行结果状态分开；摘要和派生内容显式记录来源 revision/hash/grant 依赖，偏好详情页固定 block/content revision/authorization revision。构造器只校验形状，不授予读取、共享、加工或压缩权限。

**根因**：Graph threadId 在现有执行器分别绑定 run 或 A2A task，不能自动当跨 run conversation；同 owner/domain 的不同 Agent 仍私有。任意 Object/框架 Message 会把框架依赖和不可控 wire 传入公共契约；只保留摘要文本则无法证明工具全部响应及原结构事实。分页不固定授权/内容版本会混页；可信来源真实性和当前 grant 必须由服务端核验，而不是 DTO 自证。

**代码位置**：common/src/main/java/com/bkanent/common/memory 的 ContextKey/Association/Scope/JsonValue/ToolGroup/Summary/PreferenceHistoryPage/MemoryException 及两个 focused 测试。MemoryException 只携枚举，无 provider 文本/cause/suppressed/stack；后续 RPC 须遵 LR-48 显式 throws 并验证实际选定 wire。本批不修改既有 v1，不创建闲置 ChatClient 适配或新框架依赖。

**关联**：LR-33/40/41/48/53、KI-117/118；integrate-domain-agent-memory 2.1。官方 fastjson2 异常测试与 ctor-SPI 普通 DTO 测试是分离证据，不能据此声称后续 v2 RPC 同一 wire 已支持两者；当前 ctor-SPI 仅有 RagException envelope。官方接受名单虽按精确类型注册，第三方 handler 仍有类名前缀匹配语义，测试明确保留该限制，不以“精确条目”等同“精确匹配”。

## LR-56 原生压缩只提供候选生成能力，实际窗口采纳候选需要更新边界保护

**结论**：Alibaba 1.1.2.3 的 SummarizationHook 不独立直接注册到生产窗口。公开 MessagesModelHook.beforeModelAction 返回候选，不立即更新 state；外围 ModelHook 可拒绝候选，公开 Hook.getKeyStrategys 可在实际 update 边界校验版本。可复用原生 token trigger/候选能力，但不能把 gate 返回前检查加普通 REPLACE 当 run/checkpoint CAS。

**根因**：原生会把模型异常文本当 SystemMessage 并继续 REPLACE，getText 总结丢全部 responseData，邻域切点不能保证完整工具组。实际测试还证明候选返回后新输入会被普通 REPLACE 丢弃。依赖公开更新策略保护无需反射或框架补丁，但本地策略不提供分布式租约、数据库覆盖或跨库事务。

**代码位置**：common-a2a/src/test/java/com/bkanent/a2a/memory/SummarizationHookContractTest.java；真实 ReactAgent BEFORE_MODEL 顺序、25 次制品测试与四制品 hash 见基础验收记录。fixture 的覆盖/提交/revision 只是可控性证明；生产仍须安全结构投影、来源/grant核验、摘要与 covered 同事务、独立 run CAS、未覆盖尾部恢复及硬预算失败关闭。

**关联**：LR-22/23/55、KI-116/117；change 1.3、5.5、6.1–6.5。未修改默认框架版本或原生产窗口，未以反例通过宣称压缩启用。

## LR-57 memory v2 RPC 用封闭操作集与构造期版本拒绝，信封只做身份不做凭据

**结论**：v2 契约为单一接口（group `bkanent-memory`、dubbo version `2.0.0`、CONTRACT_VERSION=2）加九个封闭 operation 常量；每个请求绑定 `MemoryRequestEnvelope(operation, caller, audience, requestId, bodyHash, contractVersion)`，requestId+bodyHash 构成请求 hash 身份用于幂等/重放关联。版本不匹配在构造期即抛 `UNSUPPORTED_VERSION`，operation 与请求类型在构造期互绑——错误请求无法存在，而不是到服务端才被拒绝。九个方法全部显式 `throws MemoryException`，沿官方 fastjson2 按接口 throws 精确注册异常信封。

**根因**：DTO 层（LR-55）只定数据形状；RPC 层必须额外钉住"哪个请求属于哪个操作"和"哪个版本能上 wire"。放开 operation 字符串或推迟版本校验会让服务器拒绝成为唯一防线，错配请求可穿过序列化层。信封不含凭据、不是 bearer token——当前用户认证与签名核验是服务端 3.x 职责，形状检查在客户端构造期即可完成。permit/交付拆成两段（先许可后交付）对应 D2 可靠交付，许可不能兑换读取权，因此是独立响应类型而非一个授权对象。

**代码位置**：common/src/main/java/com/bkanent/common/memory 的 MemoryV2Contract、MemoryRequestEnvelope、MemoryV2RpcService 及 18 个请求/响应 record；测试 MemoryV2RpcContractTest（6 项：信封 wire 轮转/版本拒绝/形状拒绝/九请求 operation 互绑/分页与 hash/sequence 约束）与 MemoryV2RpcExceptionEnvelopeTest（1 项：真实接口注册后全 15 错误码 wire 轮转）。

**关联**：LR-48/55、KI-118（v2 接口 throws 注册即 2.2 承诺的 ctor-SPI 异常联合边界验证）、KI-126；change 2.2/2.4。Memory v2 当前用户签名与防重放基础见 LR-58；provider/client 尚未接入，不等同于 RPC 运行链路已受保护。

## LR-58 Memory v2 以可信域调用者签名承载当前用户断言

**结论**：域服务先沿自己的入口认证当前用户，再用该服务专属 Memory key 签署专用短时证明；客户端 signer 要求 `AuthenticatedPrincipal` 与请求 ownerId 完全相等。证明绑定固定 caller、memory audience、RPC operation、requestId、canonical body hash、完整 contextKey、keyId、有效期和单次 nonce。Memory 只接受部署配置中明确登记的 caller，并按精确 domain→agent 映射授权，向 Auth 复核 owner 账号仍启用且具备 Agent 使用权限，再以 SQL 唯一键跨实例消费 nonce。A2A beacon 不是 Memory 凭据，用户 token 不进入 Memory proof 或 Memory 持久化。

**根因**：memory envelope 和 ownerId 只是请求数据，不能自证当前用户；共享 A2A request beacon 也只能证明原 A2A 请求，不能授权另一个 memory operation。域服务已经持有入口认证上下文，因此用其专属 key 对精确用户断言签名，同时由 Memory 限定服务可代表的域/Agent，并独立确认用户资格。持久 nonce 处理进程重启和多实例重放；清理保留有效期后 5 分钟缓冲，降低小时钟偏差重开窗口的风险。Caller key 缺失时关闭失败。

**代码位置**：common 的 `MemoryCanonicalHash`、`MemoryRequestSigner`、`MemoryRequestProof`；memory-service 的 `MemoryV2SecurityProperties`、`MemoryV2RequestSecurity`、`MemoryV2ReplayGuard`；迁移 `20261019_memory_v2_request_nonce.sql`。测试覆盖签名绑定、授权范围、用户资格、认证依赖失败、持久 nonce 唯一性。

**关联**：LR-33/41/55/57；integrate-domain-agent-memory 3.1。当前 v2 provider 与真实消费者尚未注册/接入，调用点接线与 A2A 租约续期仍待后续任务，未配置 caller 的运行环境保持拒绝。
**确认日期**：2026-10-19。

## LR-59 共享读取分别核验接收身份、来源引用和当前持久 grant

**结论**：共享请求的 contextKey 表示接收方，sourceContext 保留在 exportRef；两者 owner 必须等于真实当前用户。grant 的接收者绑定 domain/agent/conversation，且 scope、matter、purpose、grant revision、有效期和 DURABLE_EXPORT 投影都必须匹配。域私有工作集要求完整四重 context 相等，ADMIN 无跨 owner 分支。SQL 先查不含正文的 export 元数据及当前 grant，核对不可变 grant 历史快照后才读取一个指定版本；不按 grant 批量返回历史。

**根因**：把来源 context 当调用者身份会误拒合法接收域，放开来源域映射又会扩大调用者权限。只限制 domain/agent 而不绑定接收会话，会让同 Agent 的其他任务复用一次共享许可。exportId/sharedScopeId 都是定位数据；当前 SQL grant 才是授权真源。短事务按 grant→export 顺序加锁，Auth RPC 在事务外执行，正文读取后再次认证并核对当前 grant/export 版本，阻止在 Auth 等待窗口发生的撤回。grant 日期字段按 UTC 微秒截断写入；授权使用原快照时间，不依赖数据库时区。

**代码位置**：`MemoryGrantRequest`、`MemoryShareGrant.Participant`；memory-service 的 `MemoryAccessPolicy`、`MemorySharingStore`、`MemorySharingAccessService`；迁移 `20261019_memory_share_authorization.sql`。共享读取只接受服务端 SQL 记录，不接受请求携带的 grant 对象；索引未追平不影响已获准详情读取。

**关联**：LR-33/40/41/55/58、KI-129/130；change 3.2，4.2 的 grant/export 子集。v2 provider 与真实客户端仍待后续接线；来源当前删除/撤回及派生依赖复核属 3.5/4.7，带上游 grant 的链式导出在该复核实现前失败关闭。grant 签发/更新与来源域发布属后续 10.2，本次没有对外写入口或运行 grant 数据。

## LR-60 精确归档接收与迟到结果只授予受限 SERVICE 交付权

**结论**：Memory 先以当前用户断言准入，再持久保存 caller/context/event/hash/purpose 许可，7 天内允许同一可信 caller 用显式启用的 SERVICE 交付能力补交付。actor 本身纳入 HMAC；SERVICE 不能读取、申请新许可、提交摘要或撤回。已知结果事件必须精确 hash 相同；尚未完成调用仅能从已入库 TOOL/ACCEPTED 事实预约一个 callId 的结果身份，首次交付冻结 hash，后续不同内容冲突。ASSISTANT 工具调用提案不等于受理证明。

**根因**：用户租约结束不能让已授权入站事件丢失；让 owner 自动换发用户 token 则会把补交付升级为读取权限。服务签名与持久接收事实的交集限定补交付，且不保存 proof、token 或秘密。源事件、消息 ID 和 sequence 均有唯一约束；会话行锁、严格连续 sequence、真源/回执/待加工 job 同事务，丢回执或跨实例重试不增加消息与观察。乱序显式冲突后重投，绝不静默重排调用事实。

**代码位置**：common 的 MemoryRequestProof/Signer、MemoryResultAdmission、MemoryCanonicalHash；memory-service 的 MemoryArchiveService/Store、MemoryV2RequestSecurity；迁移 130。Native provider 固定原 interface/group/version/protocol，增加 withdrawSource，总计十操作，默认 `archive-enabled=false`。

**关联**：LR-33/41/55/58/59、KI-131/132；change 3.3/3.4/4.1/4.4。真实执行器可靠事实构造、journal 选择与 Agent 接线另验收；进程内重建/SQL 并发测试不冒充真实 A2A token 续期或目标 Agent 全链验收。

## LR-61 归档摘要覆盖与来源撤回受 SQL 真源约束

**结论**：摘要保存完整结构，采用累计前缀覆盖、当前 conversation revision 和精确来源依赖；全部覆盖消息必须已归档，完整工具组及非等待/非未知结果才可压缩。摘要与 covered cursor 同事务。run/checkpoint revision 属执行器，当前归档 provider 拒绝自称已核对 runVersion 的提交，后续运行面须独立完成 CAS。冷恢复只读有效摘要与全部未覆盖尾部，超过 500 消息或 1MiB 响应返回 CONTEXT_BUDGET_EXCEEDED，保留原文，不裁剪冒充完整。

**根因**：保存摘要不能证明执行 checkpoint 已采用它；失效摘要也不能继续遮住原文尾部。消息/共享来源的版本、删除、grant 和传递依赖必须再查当前 SQL。工作集、实体/事项和共享详情输出前再次检查用户及来源，Auth RPC 不占 SQL 锁。私有来源撤回时保守失效整个会话派生集合和来源导出，读取排除不可用内容；删除立即清正文并保留事件/hash tombstone 和 PRIVACY_CLEANUP job，外部副本未确认时始终 DELETION_PENDING。领域执行账本与业务成功状态不改写。

**代码位置**：MemoryArchiveService、MemorySourceAuthorizer、MemorySharingAccessService、memory_source_dependency 和 memory_processing_job。传递核验限 8 层/500 次，未知领域真源和未支持的链式 export 失败关闭；分页游标含 conversation revision 和末项身份 hash，元数据解析有界，255 字符实体 ID 仍可分页。

**关联**：LR-22/23/55/56/59/60；3.5/4.5/4.7/6.x 的服务端基础，未据此完成 Agent checkpoint 入模/输出接线、异步处理/外部删除 worker 或 RAG 投影任务。用户读取失败不触发工具重执行；档案未确认禁止移除窗口，后续客户端沿相同错误语义接线。

## LR-62 无时区 DATETIME 以 UTC 字面量跨 JDBC 驱动读写

**结论**：Memory v2 许可和 job 的 DATETIME 使用 LocalDateTime.ofInstant(..., UTC) 写入；读取同时接受 MySQL 的 LocalDateTime 与 H2 的 Timestamp，后者以 toLocalDateTime 再按 UTC 解释。

**根因**：MySQL Connector/J 对 DATETIME 的 getObject 返回 LocalDateTime，直接转 Timestamp 会抛类型异常；直接混用 Timestamp/系统时区还会使有效期偏移。真实开发 MySQL 测试首次暴露该差异，固定字面量同时回归两种返回类型。

**代码位置**：MemoryArchiveStore.utcInstant/enqueue、MemoryArchiveService.requestReceivePermit/deliverMessages；MemoryArchiveServiceTest、MemoryArchiveMySqlTest。

**关联**：KI-132、LR-59/60；只调整 Java 读写，不更改已经应用的迁移字节。

## LR-63 Supervisor 工作集缓存只复用数据，SQL fence 与边界复核才控制写入和授权

**结论**：本地缓存按完整 contextKey 分区并受容量、字节数、TTL 限制；它不是身份、授权或写入权缓存。每次工作集检查、许可、交付和摘要提交仍使用当前 actor 证明与来源边界。conversation 一旦登记 writer 行，permit/summary 就必须匹配当前 caller、run、fence 与有效期；释放或过期后不回退到无 fence 写入。SERVICE 只能凭原始精确 permit 补交付。

**根因**：内存快照可减少模型轮次间重复整窗读取，但不能延长授权有效期。仅以 runId/owner 作为分布式锁会允许旧实例在接管后覆盖 cursor 或摘要；仅在模型调用前检查一次也无法覆盖摘要提交、工具调用和输出前的撤回。

**代码位置**：`common-memory` 的 `MemoryWorkingSetCache`/`MemoryWorkingSession`；`memory-service` 的 `MemoryArchiveService` writer fence 和 `checkBoundary`；迁移 140。租约 120 秒，只在受管节点边界续期；模型或工具耗时超过租约时采取失败关闭。

**关联**：LR-58/60/61、KI-133/134；integrate-domain-agent-memory 5.1/5.2/6.4。摘要库提交与 agent checkpoint adoption 是两个事务，提交后 run adoption 失败由 checkpoint/journal 冷恢复，不宣称跨库原子提交。

## LR-64 Supervisor 记忆交付引用不可变 checkpoint，绝不复制业务工具正文到 journal

**结论**：`agent_memory_delivery` 只保存 context/event/run/checkpointVersion/messageOrdinal/sequence/hash/permit/state；原始 user/assistant/tool 事实仍以现有 `agent_workflow_checkpoint` 为恢复真源。permit 在 SERVICE 交付前写入 journal；服务端回执确认后才标记 RECEIVED。writer acquire 和 receive-permit 使用当前 run/fence/事件集合生成稳定 requestId，签名 nonce 每次新建；响应丢失时重试命中同一服务端记录。交付丢回执后按同一 event、hash 和 permit 重建，依赖 Memory 幂等追加，不重新执行工具。

**根因**：无事实源的入口需要 outbox；Supervisor 已有不可变 checkpoint，另存一份完整 JSON 会制造双真源、增加撤回清理面并复制大型工具结果。引用的 checkpoint 版本、消息序号和内容 hash 能检测缺行、变更和错误 run。

**代码位置**：`SupervisorMemoryJournal`、`agent_memory_cursor`、`agent_memory_delivery`，迁移 150；真实 Graph + 数据库 checkpoint saver 集成用例覆盖正常归档与丢回执重建。checkpoint graphName、runnerVersion、owner、session 或 ordinal 不匹配时拒绝重放。

**关联**：LR-60/61、KI-133/134；integrate-domain-agent-memory 4.3/5.4。工具调用响应文本中的 `ACCEPTED`/`RUNNING` 只是回调返回数据，投影态为 `RETURNED`，不能满足服务端“已受理调用”结果许可。

## LR-65 V2 请求、审批、工具观察和最终答复都从当前授权来源通过边界

**结论**：V2 的模型输入只由已授权摘要、历史资料和本次完整消息投影组成；不会再把原始 request context 或 checkpoint 窗口拼入另一条 prompt。模型之后的审批展示、技能与补充问题、工具结果观察和终态答复均需当前来源核验；失效时保存执行 checkpoint 和已完成调用，返回安全错误，并允许同 run 续接原节点。禁用 V2 后，带 V2 checkpoint 的 run 不回退读取原始正文。

**根因**：只保护 `beforeModel`/普通 answer 会留下旁路：历史引用可能在模型之后撤回，等待审批或技能文本仍对外展示；旧 system prompt / request context 会将原始正文重新拼入压缩窗口；对已完成工具再执行一次来补交付会破坏至少一次副作用约束。

**代码位置**：`SupervisorToolLoopGraph` 的 PrepareContext/Model/ApprovalGate/WaitInput/ExecuteTool/Observe/Complete；`SupervisorToolLoopRunner` 的当前输出核验和 memory phase 恢复；`SupervisorMemoryLifecycle`。每 delta/progress 无新增 Memory RPC。

**关联**：LR-22/23/60/61/63/64、KI-133/134；integrate-domain-agent-memory 3.5/5.4/5.10/6.4/10.6。外部 A2A 的 durable export/grant 链仍未实现；当前有历史摘要或前序 run 时，外域能力在调用前失败关闭。

## LR-66 大结果引用只缩小模型视图，补忆必须先核准精确来源再读 checkpoint

**结论**：Supervisor 的原始结构化工具结果仍完整保存在不可变执行 checkpoint 与 Memory 档案。超过内联阈值时只改变入模视图，给出 event/call/版本/hash/执行状态和确定性引用；摘要持久保留这些锚点。`memory_read_result` 是独立只读 Graph 控制节点，单独调用并计入既有工具预算，以 JSON pointer 和有界 JSON 文本分页读取。先从 journal 查元数据，当前 Memory 精确来源授权成功后才打开 checkpoint，返回前再次核验；引用本身不授予权限。

**根因**：直接截断会把不完整结果冒充完整，把已压缩窗口里的来源正文全部回填又会破坏预算。journal 只保存引用，冷恢复仍须通过当前身份和精确来源检查。补忆输出已受独立字符数上限约束，必须内联该页，不能再次转成引用导致递归回取。JSON 文本页含完整度、下一偏移和编码，避免把局部字段或半页当成完整业务结果；分页边界不拆 UTF-16 代理对。

**代码位置**：`SupervisorMemoryResultView`、`SupervisorMemoryLifecycle.readResult`、`SupervisorMemoryJournal.receivedSource/readReceived`、Graph/Runner 的 `MemoryRecall`；common 的 `MemoryToolResultAnchor`、摘要 `toolResults`；Memory provider 校验引用确实对应已归档完整结果的原 call/name/state。

**关联**：LR-61/64/65、KI-138；change 6.2。当前只支持本 Supervisor conversation 中已经 RECEIVED 的执行来源，不扩大到跨域共享或任意 checkpoint。

## LR-67 实体摘要身份来自冻结来源，名称相似不构成实体合并

**结论**：压缩模型只生成严格结构的候选；条目与实体均引用冻结 evidence 下标。新实体 ID 由 context、conversation revision 和候选序号确定，新别名必须逐字出现在所引 USER 文本或工具标量值中；不能用 assistant 自述、元数据键或相似名称建立身份。更新旧实体只引用明确的旧下标、保留既有别名并递增 revision。未更新的实体原样携带到累计摘要，provider 只允许相同内容原 revision 复用，禁止同 revision 改写。

**根因**：让模型指定 ID 或按别名自动去重，会把重名实体错误合并；每次压缩重建所有实体则会丢失旧指代。实体 hash 覆盖自身结构、版本与来源依赖，提交和详情均核验；摘要 hash 覆盖实体和结果锚点。条目支持目标、事实、决定、否定、修正、例外与未决，模型失败字符串、空结果、凭据形状与非法 evidence 均拒绝采用，保留旧窗口。

**代码位置**：`SupervisorMemorySummaryGenerator`、`MemoryCompactionCoordinator.compactStructured`、`MemoryCanonicalHash.entityHash/summaryHash`、`MemoryArchiveService.submitSummary/queryEntities`。

**关联**：LR-55/61/66、KI-139；change 6.3。实体锚点不等于完整事项状态机或自动指代解析，事项关闭/重开与 task 6.6 仍需后续接线。

## LR-68 偏好逻辑块跨本 Agent 会话归并，来源顺序与显式修正决定当前值

**结论**：本阶段偏好归并只接受可信调用者与当前用户签名下的已评估 USER 来源，owner/subject 默认为该用户，scope 固定为 domain+agent 私有，维度取部署注册表。blockId 由完整逻辑键确定；同一来源事件在同一块只产生一个 transition，候选 hash 不同则冲突。原生 RPC 不授予 SERVICE 加工权，也不把来源许可兑换为用户读取权。其他主体、共享偏好、异步评估及 Agent 装配尚未接入。

**根因**：按每句话创建键会丢失历程，按 owner 或 domain 归并又会混入未共享 Agent。处理结束顺序不能决定当前偏好：同 conversation 只比较真实 sourceSequence；跨 conversation 不比较局部 sequence，只有明确 corrects 关系才跨源建立优先级，否则不同值/极性保留未决。更早来源迟到时补入历史，不覆盖新修正。问题/事项约束/其他评估不计为长期偏好；assistant 复述不构成新观察。

**代码位置**：`MemoryPreferenceAssessment`、写入/详情 DTO、`PreferenceHistoryService`、`PreferenceStateReducer`；迁移 160。每块以 revision/OCC 和行锁保护，原回执幂等不受后来 revision 影响。写入者必须也是原消息的可信 source_caller，来源与正文 hash、USER 角色和当前状态均核验。

**关联**：LR-55/61、KI-140/141；change 7.3 及 7.1 的自身私有子集。显式确认只可确认同值/同极性，临时例外不覆盖长期默认。来源撤回使依赖块立即不可读；删除清除可还原的快照/历程正文，保留定位与 hash，外部清理仍由既有 DELETION_PENDING 语义表达。

## LR-69 偏好不可变版本、来源时间轴与 asOf 有效视图分别表达

**结论**：每次成功归并追加不可变 transition 和块 revision 快照；`block.currentState` 是该 revision 归并时的快照，实时消费必须使用详情响应独立的 `effectiveState` 与 `asOf`。临时例外按事项和有效期选择，过期后恢复仍有效的长期值，读取不写 revision、confidence 或 lastObservedAt。极性使用显式字段，否定不伪装成肯定值。

**根因**：把时钟变化写成新观察会污染证据；让旧版本页面读取最新值会混页。分页固定 blockId/content revision/authorization revision，按可信接收时间、来源 context、sequence 和 transition ID 排列。游标 ordinal 是该固定版本的展示位置；入库 ordinal 是归并账本位置。迟到旧源可在新版本时间轴中归位，旧版本排除后来引入的事件，继续页不移位。分页用来源元数据连接上的复合 keyset，不使用 offset。

**时间边界**：`memory_message_observation.accepted_at` 与首次归档同事务保存，重投不刷新；它记录 Memory 源事件首次被接受的时间，不冒充用户原始发言的客户端时间。transition.occurredAt 使用该受信源事件时间，observedAt 记录首次归并观察时间，lastObservedAt 基于来源时间；缺少可信时间的旧源拒绝自动回填。

**代码位置**：`PreferenceHistoryService.read/detail/transitions`、`PreferenceHistoryCursor/Page`、`MemoryArchiveService.deliverMessages/withdrawSource`；迁移 160。来源元数据先授权，才读快照/历程正文，Auth RPC 不占 SQL 锁，输出前再次复核。单块容量为 10000 条、总正文/来源元数据各 8MiB，单响应 1MiB；超过上限显式失败，原历史不截断。来源时间排序包含有界排序，不宣称无 filesort 或已建立性能基线。

**关联**：LR-62/68、KI-140；change 7.4。物理检索片段应携稳定 blockId 转详情；本批不实现 V2 RAG 派发。共享授权与其他主体尚不支持，不能从引用扩大范围。

## LR-70 偏好后台以精确入站事件授权，评估回执与归并分别可靠提交

**结论**：Memory 内部 SERVICE 消费已经与消息同事务入库的 ASSESS_MESSAGE；启用必须同时打开 archive 与 processing，并显式注册 domain→agent 加工范围。来源仍须属于当前可信 caller 的对应映射且密钥配置有效。worker 只读取该 job 的精确、当前有效 USER 证据，不签发 USER proof、不调用通用搜索、不保存用户凭据。来源元数据与传递依赖在读正文前、模型返回后及归并发布前核验；普通用户租约过期不冒充用户续签，也不阻断获准的后台加工。原生 USER 偏好写 RPC 的权限不变。

**根因**：模型和数据库不能共享原子事务，用户在线请求也不能等待模型评估。评估任务使用每次唯一 lease_owner、attempt fence、到期接管、有限重试和安全错误码；模型在事务外、无工具、单次 30 秒超时且 SDK 不自行重试。严格 JSON/字段/结束状态、稳定注册维度、原文值与证据片段核验后，结果和 ASSESSED 状态同事务保存。重启从回执归并，不再调用模型；规则版本、模型标识及规则内容 hash 写入 extractionVersion。归并按稳定逻辑块及同源 sequence 微批排序，历程、revision、索引待办和 job 终态同事务提交；批次失败后逐事件隔离，不让坏候选耗尽其他块的重试。

**修正边界**：模型只判断语义，不选择主体 ID、scope、来源 ID 或 corrects ID。服务端仅链接该维度在同 conversation 的最近较早挂点；跨会话缺少受信关联时不推断被修正对象。无可链接对象的长期修正保留未决，撤销无目标则拒绝。临时例外只采用源消息已有 matter，或逐字出现的 ISO instant 有效期；没有任何可靠适用挂点时不写长期默认。assistant/工具消息跳过模型，问题、事项约束和其他主体不增加偏好证据。分类语义质量不等同于授权证明，参见 KI-142。

**代码位置**：PreferenceProcessingWorker、PreferenceModelEvaluator、MemoryPreferenceProcessingConfiguration、PreferenceHistoryService 的包内追加/同会话关联；迁移 170 的 memory_preference_evaluation 只保存派生结果和来源定位。来源删除在原撤回事务中清除本会话评估正文，保留 tombstone/hash；外部隐私清理状态仍不冒称完成。

**启用与容量**：默认关闭、默认 SHADOW，空加工映射不扫描正文。SHADOW 只保存评估并标记 SHADOWED，不创建生产偏好/索引；切换 PUBLISH 不自动重放旧影子结果。默认微批 4（1–16），最多 4 次（2–10），lease 90 秒（60–300），重试 30 秒（1–3600）；单条输入 16000 字符、最多 32 个注册维度、最多 8 个候选、输出 24000 字符，原档案不截断。各实例使用受管 UTC 时钟且需正常校时；attempt fence 防止过期旧 worker 提交。任务到期按轮询处理，测试使用固定时钟而非假设刚写入的时间立即满足查询条件。

**关联**：LR-61/68/69；change 7.2、4.5 的偏好加工子集。INDEX_SUMMARY/INDEX_PREFERENCE/PRIVACY_CLEANUP 消费者、其他主体/共享偏好及 Agent 装配另行实现，不据本 worker 完成 4.5 或其他任务。

## LR-71 事项状态由显式用户操作推进，关闭证据与压缩覆盖分别校验

**结论**：Supervisor 用户请求的可选 `matterIntent` 单独表达 START/CONTINUE/CLOSE/REOPEN，保留原始 userMessage。模型工具与 A2A/Studio 代建请求不开放此字段；模型说 done、callback 返回和子任务终态均不构成关闭确认。typed intent 与原 USER 消息同档案签名/hash 保存，Memory 只接受当前 USER、原可信 source_caller、同 context 精确已接收来源、有效 writer fence 和 OCC。START 的稳定身份来自 context+源事件；CONTINUE 只选择已打开事项，不增加事项内容版本；关闭/重开追加不可变版本，旧回执幂等且不回退当前视图。

**根因**：事项用于上下文组织，不能决定工具调用、审批或业务完成。用户关闭上下文也不能让待审批/未知结果消失。关闭触发与 token 触发共用冻结覆盖、完整工具组、摘要提交和 run 采用流程；关闭触发另要求当前持久 CLOSED 的 USER_CONFIRMATION，并把 cut 限制为该事项的连续前缀。未归属的精确 START 证据可纳入，其他事项的进行中资料保持原窗口。无安全 cut 或摘要失败不虚报压缩。token 压缩仍保留未决条目，不更改事项状态。

**持久边界**：每条 StoredModelMessage 在创建时冻结 matterId，切换事项不回标旧消息；新增空字段不进入旧 journal JSON，避免恢复时制造新 event。旧 MemoryMessage 的空 intent 不改变内容 hash，新请求签名形状要求尚未启用的 caller/provider 同步升级。关闭待压缩标记进入原 Graph checkpoint；RPC 成功而 checkpoint 未保存时按源事件回执恢复，并重新读取当前真源，禁止采用旧回执版本。

**代码位置**：MemoryMatterIntent、MemoryMatterService、SupervisorMemoryLifecycle、StoredModelMessage、MemoryCompactionCoordinator；迁移 180 的版本表与源事件回执。关联 LR-61/64/65/67、KI-144；change 6.1/6.6。自动事项候选与偏好/经验装配仍属 5.3/7.5。

## LR-72 历史指代先用本地精确锚点，补查不能把不完整扫描解释为唯一匹配

**结论**：只读 `memory_resolve_reference` 复用既有 MemoryRecall Graph 节点与全局工具额度。先匹配当前获准摘要中的稳定实体 ID/精确别名；没有匹配才查同 context 的实体页，最多两页、每页 20。同名不同 ID 必须澄清，不按相似文本合并。末页仍 hasMore 时返回 INCOMPLETE_NEEDS_CLARIFICATION，即使已见一个候选也不声称唯一；无证据返回澄清，brief 和证据随候选返回。

**根因**：引用只是定位信息，不能授权其他 owner/domain/Agent/conversation。接口不接受扩大范围字段，matterId 只在已授权 context 中缩小匹配。Memory 先读派生定位与依赖元数据、检查当前来源后再开正文；Agent 补查后及输出前再次检查，并将消费来源存入 tool/assistant 的派生依赖，撤回后不能通过旧 checkpoint 继续输出。输出和依赖超预算明确失败，不静默删减证据。其他 context 的显式共享入口仍由后续共享任务接入。

**代码位置**：SupervisorMemoryReferentResolver、SupervisorMemoryLifecycle.resolveReference/beforeOutput、MemoryArchiveService.queryEntities/readMatters；关联 LR-55/61/67、KI-144；change 6.6。

## LR-73 事项偏好装配固定身份、评估时点与来源版本

**结论**：偏好装配由已签名的 Memory RPC 按当前 owner、domain、agent 和事项读取；主体固定为 owner，scope 固定为该 domain+agent 的私有 scope。目标匹配只使用部署注册的稳定维度与别名，不调用模型或通用 RAG。服务端为一次装配冻结同一 `asOf`，分别返回 relevance、confidence、activation 和 time weight，并在 token 预算内选择当前有效值/事项例外。既有 `PreferenceBlockKey` 的完整逻辑键及 blockId 不变。

**根因**：事项标签只能缩小上下文适用范围，不能扩大主体权限或变成业务路由。只按维度相关匹配可能混入不相干偏好；使用模型检索会额外增加未授权语义和调用边界。把选择结果当永久缓存又可能让修正、撤回或事项版本变化后的旧值继续进入 prompt。因此每个选择附 block revision/content hash、authorization revision、matter revision 与有效状态 hash；Memory 先核来源元数据再读正文，Agent 在入模、工具调用和输出前重新核验。复用键覆盖 context、事项 revision、当前输入事件；新输入重新装配，同事项同一输入的工具/模型轮次复用。

**运行边界**：注入内容标记为数据而非指令，当前明确请求、政策和授权优先。无匹配、预算受限、可选记忆不可用分别表达；认证/授权错误不降级。删除/撤回使旧选择失效；当前错误或变更后的偏好只能在下一次可核验装配中替换，不能把后台尚未归并的修正说成已持久更新。当前仅支持 owner-self 的 domain+agent 私有偏好，不支持其他主体、共享组、经验装配或向量语义检索。

**代码位置**：`MemoryPreferenceAssemblyRequest/Response/Match/SelectionRef`、`MemoryV2Contract`、`PreferenceSelectionPolicy`、`MemoryPreferenceProperties`、`PreferenceHistoryService.assemble/selectionsCurrent`、`MemoryArchiveService.checkBoundary`、`SupervisorMemoryLifecycle.assemblePreferences/modelMessages/beforeTools/beforeOutput`；关联 LR-68/69/70/71/72、KI-140/141/142/144；change 7.5/7.6。

## LR-74 隐私撤回先封锁来源，再按受管投影确认清理

**结论**：撤回/删除在单个数据库事务中先让来源、摘要、锚点、事项、偏好与共享导出失效，并写入只含 context、event、source hash、动作和状态的清理清单；状态查询只返回生命周期元数据。bounded worker 以租约和 attempt fence 领取任务，逐个调用精确投影 cleaner，并记录每个 projection 的回执；仅全部已注册投影完成后才把清理标为 `COMPLETE`。删除立即擦除本地正文与回执正文，撤回保留受限的本地历史但不再允许读取/装配。最终 `DELETED` 来源的迟到事件继续以删除终态拒绝。

**根因**：本地事务无法与外部检索/共享投影原子提交。先撤权可立即关闭旧 checkpoint、工作集和边界读取；无正文 manifest 让异步执行可恢复而不把副本正文保留在任务里；精确 source hash、租约和幂等 cleaner 允许崩溃后至少一次重试，同时避免迟到回执复活来源或虚报删除完成。

**运行边界**：当前开发配置不启用 Memory V2 归档/索引，未注册外部投影 cleaner；因此此版本的完成状态只覆盖本地派生清理和当前注册集合，不能声称清理未接入的外部索引。今后任何 V2 投影在启用前必须提供受管 cleaner，并在写入投影前对账 tombstone。用户明确删除会清除本地正文；撤回只冻结并阻断内容访问，保持其可恢复语义。

**代码位置**：`MemoryPrivacyCleanupStatusRequest/Response`、`MemoryArchiveService.withdrawSource/privacyCleanupStatus`、`MemoryPrivacyCleanupWorker`、`MemoryPrivacyProjectionCleaner`、迁移 190；关联 LR-61/65/68/69/73、KI-145/146；change 3.5/4.7。

## LR-75 经验以 owner/domain/agent 为私域身份并以版本化证据定位保存

**结论**：经验逻辑身份绑定 owner/domain/agent，不绑定单个 conversation；每个经验 revision 保存独立快照，证据分开保留 outcome/feedback、occurredAt/observedAt 和精确来源版本定位。`retention_until=NULL` 表示不自动清理；撤回立即停用但保留受限历史，删除清空正文和证据细节，只保留 revision/source hash 等反复投递防复活所需的无正文墓碑。经验读取仍关闭，表结构不代表已有消费者已启用。

**根因**：将经验按 conversation 建键会让跨会话的私有经验重复或无法选择；按 owner 单独建键会跨域/Agent 混入。不可变revision分离修改历史，来源倒排索引让撤回/删除能定位精确派生记录，retention截止值支持治理侧有界保留而不擅自定时抹除事实。硬规则不写入经验表，评分和衰减不能改写政策。

**代码位置**：迁移 `20261021_memory_experience_records.sql`、`MemoryExperienceSchemaMySqlTest`；与 `memory_preference_block/transition`、`memory_share_grant/export` 和 `memory_processing_job` 的现有真实查询计划核验；change 4.2。后续经验写入、评估和检索仍受 8.x 任务门控。

## LR-76 记忆可靠交付只持久引用真源，索引回执由租约围栏

**结论**：Supervisor 的 `agent_memory_delivery` 持久化 checkpoint 版本、消息序号、内容 hash、permit 与交付状态，消息正文仍从不可变 `agent_workflow_checkpoint` 重建；丢失回执时重放同一 event/hash，不重新执行已完成业务工具。Memory 将入站真源、消息回执和加工 job 在数据库事务内提交，受管 SERVICE worker 只处理精确已授权来源，模型/RAG RPC 在事务外执行。RAG outbox 以短 READ_COMMITTED 事务和 `FOR UPDATE SKIP LOCKED` 领取 PENDING/WAITING 事件，使用有界 owner/expiry lease；外部 RPC 仍携带稳定 eventId，终态回写必须匹配尚有效的 owner lease。有限重试、安全错误码及 EXHAUSTED 状态不伪装成索引已应用。可靠归档未确认时保留 checkpoint/journal 和未覆盖消息，不能压缩移除；索引失败不更改领域业务完成事实。

**根因**：给已有不可变业务账本再复制完整正文会制造双真源并扩大隐私清理面；仅有稳定索引事件 ID 虽能支持下游幂等，却不能阻止多个 dispatcher 同时投递，也不能阻止慢 worker 的旧回执覆盖新状态。短领取事务与事务外远程调用避免持锁等待，lease owner 与有效期共同围栏迟到结果。归档交付和领域副作用分离，补记忆只能重放原事实，不能重跑业务工具。

**代码位置**：`SupervisorMemoryJournal`、`SupervisorMemoryLifecycle.flush`、`MemoryArchiveService.deliverMessages`、`PreferenceProcessingWorker`、`MemoryRagOutboxDispatcher`；迁移 `20261021_memory_rag_outbox_lease.sql`。回归包括 `SupervisorMemoryIntegrationTest.completedBusinessToolIsNotRerunWhenLostMemoryReceiptIsRecovered`、偏好 worker 跨实例 lease/revocation 用例、`MemoryRagOutboxDispatcherTest.expiredLeaseCanBeTakenOverAndLateWorkerCannotOverwriteNewerState` 和真实 MySQL `MemoryRagOutboxMySqlTest`。RAG RPC 接口未改变，fixture 验证的是租约/回执边界，不代表本轮真实 RAG 服务联机验收。

**关联**：LR-60/61/64/70、KI-147；integrate-domain-agent-memory 4.3/4.5/4.6。开发默认不启用 Memory V2 归档/索引；未确认接收、认证或授权失败仍按既有错误语义失败关闭。
