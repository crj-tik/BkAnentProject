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
