# 逻辑释义清单（设计根因索引）

> **目的**：记录本项目中「结论不显而易见、容易被误改」的设计逻辑。每条 = 一个结论 + 根因分析。
> **读者**：所有 AI 助手与开发者。**修改相关模块前先查这里；做出新的非显然设计决策后必须追加条目。**
> **格式**：`LR-N 标题` → 结论 → 根因 → 代码位置 → 关联。编号稳定不复用，引用时写 `LR-N`。
> **维护规则**：与代码同仓同提交（见 AGENTS.md「项目认知清单」）。修正某条逻辑时更新条目而非删除，注明变更原因。

> **四会话并行编号保留（2026-10-25）**：房源 LR-106–113，知识 LR-114–121，经营 LR-122–129，营销 LR-130–137。其它会话在本批期间从 LR-138 起选择未用编号；保留号不复用，禁止四会话各取同一 max+1。各域优先维护已有 LR-99/100/101/102，完整规则见 [并行总览](business-domain-change-roadmap.md)。

---

## LR-1 访谈票据只绑定用户/场次/用途，登录身份来自签名上下文

**结论**：登录用户身份由 Gateway 校验并签署在请求上下文中，Interview 入口本地验签；A2A/MCP 调用使用已绑定的 Auth context 和逐请求 proof，本地校验实际请求，不因 Graph 每次调用 subAgent 而请求 Auth。访谈 ticket 不再证明登录身份，只是在已认证用户与访谈场次/用途间的短期绑定：由认证后的 `POST /interviews/{sessionId}/credentials` 领取，存 256 位随机值的 SHA-256 摘要，最多 15 分钟有效，仅经 `X-Ticket` 头提交。每个领域动作仍校验当前 actor、关系、操作和状态。

**根因**：让模型生成的 userId/角色/管理员声明替换登录身份是不可信的；把每次内部工具调用都变成 Auth RPC 又会扩大依赖失败面。签名上下文固定可信身份，逐请求 proof 固定真实方法/正文，规划刷新边界更新授权；域内关系和状态继续由 Interview 真源判定。短期场次票据只解决浏览器场次用途，不得进入 URL、模型、普通 metadata 或恢复材料。

**代码位置**：`gateway` ingress签名过滤器；`common/authz/AuthzWireCodec`、`common-a2a/A2aAuthorizationVerifier`、`common/mcp/authz/McpAuthorizationFilter`；`interview-service/security/InterviewAuthorizationFilter`、`InterviewAuthorizationScope`、`InterviewParticipantTicketService` 和 `InterviewController.issueParticipantCredential`。

**关联**：LR-3/5/6/89/140/143/145；KI-157/170/174/202/203；change `unify-versioned-permission-context`任务6.1/6.5/7–9。运行模式保持默认关闭，源码回归不代表已部署启用。

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

## LR-8 MCP 公共投影只读，受限委托写操作单独授权

**结论**：Interview MCP 的四个公共工具只读已显式发布且来源有效的 L2/L3 逐字稿和派生报告；`submit_transcript` 与 `generate_case_report` 是另外两个受 Auth 签名操作限制的委托入口，分别要求 `interview.asset.submit` 和 `interview.report.generate`，仍核验可信 actor、case/asset 关联及域内规则。提交只新建 PRIVATE 资产；生成报告不会自动公开。未授权的删除、修改、重评级、题目确认和会话控制仍不暴露。Provider 仅在服务端 MCP proof guard 开启时注册。

**根因**：公开可见范围与已委托的私有业务操作不是同一种能力。把受限写操作说成“公开写”会扩大机器可见面；把它们误记为不存在又会漏掉签名操作和资源关系检查。公开投影始终只读，受限写工具必须由逐请求本地 proof 和稳定操作映射保护。

**代码位置**：`interview-service/mcp/InterviewMcpTools`、`config/InterviewMcpConfiguration`、`security/InterviewAuthorizationScope`；`application.yml` 与 `nacos/interview-service.yaml` 的 `tool-operations`/`public-tools`。

**关联**：LR-3/6/143/145；KI-174；change `unify-versioned-permission-context`任务6.5/9.1/9.6。新开关默认关闭，MCP生产接线未据此声明已启用。

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

**关联**：LR-40、LR-46、LR-48、LR-149；KI-115已修复。Compare请求级读取、报告owner/来源版本和显式公开分享已源码验证；真实服务密钥和入口仍待部署验收。

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

**关联**：LR-33/40/41/55/58、KI-129/130；change 3.2，4.2 的 grant/export 子集。当前 provider 已有签名 grant 创建/撤回及两阶段 export 接口，10.2 的不可变导出和运行态决策见 LR-85。来源当前删除/撤回及派生依赖复核仍按 3.5/4.7 执行，带上游 grant 的链式导出失败关闭。

## LR-60 精确归档接收与迟到结果只授予受限 SERVICE 交付权

**结论**：Memory 先以当前用户断言准入，再持久保存 caller/context/event/hash/purpose 许可，7 天内允许同一可信 caller 用 SERVICE 身份补交付。SERVICE 交付使用 Memory v2 Ed25519 proof 并受精确 ACL 限制；SERVICE 不能读取、申请新许可、提交摘要或撤回。已知结果事件必须精确 hash 相同；尚未完成调用仅能从已入库 TOOL/ACCEPTED 事实预约一个 callId 的结果身份，首次交付冻结 hash，后续不同内容冲突。ASSISTANT 工具调用提案不等于受理证明。

**根因**：用户租约结束不能让已授权入站事件丢失；让 owner 自动换发用户 token 则会把补交付升级为读取权限。服务签名与持久接收事实的交集限定补交付，且不保存 proof、token 或秘密。源事件、消息 ID 和 sequence 均有唯一约束；会话行锁、严格连续 sequence、真源/回执/待加工 job 同事务，丢回执或跨实例重试不增加消息与观察。乱序显式冲突后重投，绝不静默重排调用事实。

**代码位置**：common 的 MemoryRequestProof/Signer、MemoryResultAdmission、MemoryCanonicalHash；memory-service 的 MemoryArchiveService/Store、MemoryV2RequestSecurity；迁移 130。Native provider 固定原 interface/group/version/protocol，增加 withdrawSource，总计十操作。基础接收与归档由所有官方 Agent 必须使用，不设可旁路的 archive 开关。

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

**运行边界**：注入内容标记为数据而非指令，当前明确请求、政策和授权优先。无匹配、预算受限、偏好附加数据未应用分别表达；认证/授权错误不降级。删除/撤回使旧选择失效；当前错误或变更后的偏好只能在下一次可核验装配中替换，不能把后台尚未归并的修正说成已持久更新。当前仅支持 owner-self 的 domain+agent 私有偏好，不支持其他主体、共享组、经验装配或向量语义检索。

**代码位置**：`MemoryPreferenceAssemblyRequest/Response/Match/SelectionRef`、`MemoryV2Contract`、`PreferenceSelectionPolicy`、`MemoryPreferenceProperties`、`PreferenceHistoryService.assemble/selectionsCurrent`、`MemoryArchiveService.checkBoundary`、`SupervisorMemoryLifecycle.assemblePreferences/modelMessages/beforeTools/beforeOutput`；关联 LR-68/69/70/71/72、KI-140/141/142/144；change 7.5/7.6。

## LR-74 隐私撤回先封锁来源，再按受管投影确认清理

**结论**：撤回/删除在单个数据库事务中先让来源、摘要、锚点、事项、偏好与共享导出失效，并写入只含 context、event、source hash、动作和状态的清理清单；状态查询只返回生命周期元数据。bounded worker 以租约和 attempt fence 领取任务，逐个调用精确投影 cleaner，并记录每个 projection 的回执；仅全部已注册投影完成后才把清理标为 `COMPLETE`。删除立即擦除本地正文与回执正文，撤回保留受限的本地历史但不再允许读取/装配。最终 `DELETED` 来源的迟到事件继续以删除终态拒绝。

**根因**：本地事务无法与外部检索/共享投影原子提交。先撤权可立即关闭旧 checkpoint、工作集和边界读取；无正文 manifest 让异步执行可恢复而不把副本正文保留在任务里；精确 source hash、租约和幂等 cleaner 允许崩溃后至少一次重试，同时避免迟到回执复活来源或虚报删除完成。

**运行边界**：Supervisor 与官方 A2A Agent 的基础 Memory v2 读取、许可归档为必需接入；RAG 投影/搜索及外部投影 cleaner 独立登记和启停。因此清理完成只覆盖本地派生与当前注册 cleaner，不能声称清理未接入的外部索引。任何新 V2 投影启用前必须提供受管 cleaner，并在写入投影前对账 tombstone。用户明确删除会清除本地正文；撤回只冻结并阻断内容访问，保持其可恢复语义。

**代码位置**：`MemoryPrivacyCleanupStatusRequest/Response`、`MemoryArchiveService.withdrawSource/privacyCleanupStatus`、`MemoryPrivacyCleanupWorker`、`MemoryPrivacyProjectionCleaner`、迁移 190；关联 LR-61/65/68/69/73、KI-145/146；change 3.5/4.7。

## LR-75 经验以 owner/domain/agent 为私域身份并以版本化证据定位保存

**结论**：经验逻辑身份绑定 owner/domain/agent，不绑定单个 conversation；每个经验 revision 保存独立快照，证据分开保留 outcome/feedback、occurredAt/observedAt 和精确来源版本定位。`retention_until=NULL` 表示不自动清理；撤回立即停用但保留受限历史，删除清空正文和证据细节，只保留 revision/source hash 等反复投递防复活所需的无正文墓碑。经验读取仍关闭，表结构不代表已有消费者已启用。

**根因**：将经验按 conversation 建键会让跨会话的私有经验重复或无法选择；按 owner 单独建键会跨域/Agent 混入。不可变revision分离修改历史，来源倒排索引让撤回/删除能定位精确派生记录，retention截止值支持治理侧有界保留而不擅自定时抹除事实。硬规则不写入经验表，评分和衰减不能改写政策。

**代码位置**：迁移 `20261021_memory_experience_records.sql`、`MemoryExperienceSchemaMySqlTest`；与 `memory_preference_block/transition`、`memory_share_grant/export` 和 `memory_processing_job` 的现有真实查询计划核验；change 4.2。后续经验写入、评估和检索仍受 8.x 任务门控。

## LR-76 记忆可靠交付只持久引用真源，索引回执由租约围栏

**结论**：Supervisor 的 `agent_memory_delivery` 持久化 checkpoint 版本、消息序号、内容 hash、permit 与交付状态，消息正文仍从不可变 `agent_workflow_checkpoint` 重建；丢失回执时重放同一 event/hash，不重新执行已完成业务工具。Memory 将入站真源、消息回执和加工 job 在数据库事务内提交，受管 SERVICE worker 只处理精确已授权来源，模型/RAG RPC 在事务外执行。RAG outbox 以短 READ_COMMITTED 事务和 `FOR UPDATE SKIP LOCKED` 领取 PENDING/WAITING 事件，使用有界 owner/expiry lease；外部 RPC 仍携带稳定 eventId，终态回写必须匹配尚有效的 owner lease。有限重试、安全错误码及 EXHAUSTED 状态不伪装成索引已应用。可靠归档未确认时保留 checkpoint/journal 和未覆盖消息，不能压缩移除；索引失败不更改领域业务完成事实。

**根因**：给已有不可变业务账本再复制完整正文会制造双真源并扩大隐私清理面；仅有稳定索引事件 ID 虽能支持下游幂等，却不能阻止多个 dispatcher 同时投递，也不能阻止慢 worker 的旧回执覆盖新状态。短领取事务与事务外远程调用避免持锁等待，lease owner 与有效期共同围栏迟到结果。归档交付和领域副作用分离，补记忆只能重放原事实，不能重跑业务工具。

**代码位置**：`SupervisorMemoryJournal`、`SupervisorMemoryLifecycle.flush`、`MemoryArchiveService.deliverMessages`、`PreferenceProcessingWorker`、`MemoryRagOutboxDispatcher`；迁移 `20261021_memory_rag_outbox_lease.sql`。回归包括 `SupervisorMemoryIntegrationTest.completedBusinessToolIsNotRerunWhenLostMemoryReceiptIsRecovered`、偏好 worker 跨实例 lease/revocation 用例、`MemoryRagOutboxDispatcherTest.expiredLeaseCanBeTakenOverAndLateWorkerCannotOverwriteNewerState` 和真实 MySQL `MemoryRagOutboxMySqlTest`。RAG RPC 接口未改变，fixture 验证的是租约/回执边界，不代表本轮真实 RAG 服务联机验收。

**关联**：LR-60/61/64/70、KI-147；integrate-domain-agent-memory 4.3/4.5/4.6。基础 Memory 读取与归档必须接入所有官方 Agent；RAG 索引/投影由独立 dispatcher 管理，未确认接收、认证或授权失败仍按既有错误语义失败关闭。

## LR-77 A2A 记忆仅接入受信任 ReactAgent 完整轮次并按域隔离

**结论**：marketing 与 interview 的实际 ReactAgent 显式注册同一个 common-a2a `AgentHook`：在 `BEFORE_AGENT` 必须读取当前 owner/domain/agent 的获准工作集，在 `AFTER_AGENT` 许可归档完整轮次；进度回调不访问记忆。owner 来自当前 A2A task lease 的已认证 actor，caller/domain/agent 固定在各服务装配代码中；A2A `contextId` 先哈希，只用于会话映射，不作为授权。历史摘要及近期消息按记忆版本/来源依赖复核，summary 只接受有证据的结构候选；token 压缩由可靠提交协调器执行，未决、待审批及结果未知的工具调用组留在未覆盖尾部。基础 Memory 无开关；压缩仍是可选附加能力。

**根因**：A2A beacon 是 task 身份证明，不是 memory 的调用凭据；必须从有效 lease 派生当前用户身份，并用各服务独立配置的 Memory RPC 签名调用。单独使用 contextId 会让同一用户跨 domain/agent 读到不相关私有上下文。把记忆请求放在工具或 progress interceptor 中会重复执行业务或拖慢 SSE；只有 AgentHook 的完整轮次边界能同时读入授权历史、取得实际工具结果并在单次模型执行后提交消息。压缩前沿不得穿过待审批或结果未知的工具组。

**运行边界**：interview 仅接入治理面原生 A2A ReactAgent；REST/SSE、三段上下文、访谈状态机和领域事实源未迁移，task 完成不会闭合访谈事项。Memory 身份、工作集或授权预检不可用时在模型/工具前失败关闭，无降级旁路；当前运行态切流仍须按 change 11.5 验收，前端性能对比不属于本变更范围。

**代码位置**：`A2aMemoryAgentHook`、`A2aMemoryInvocation`、`A2aMemorySummaryGenerator`、`A2aMemoryHookFactory`、`OfficialA2aAgentExecutor`、`MarketingOfficialA2aAgent`、`InterviewOfficialA2aAgent`、`A2aMemoryAutoConfiguration`、`SupervisorMemoryLifecycle`；回归 `A2aMemoryAgentHookTest`、`SupervisorMemoryWiringTest`、`InterviewControllerTest`、`InterviewSessionStateMachineTest`。关联 LR-33/41/61/76、KI-148/149；change 5.5/5.6。

## LR-78 listing/trade/contract 记忆只保存有身份边界的会话证据

**结论**：listing、trade、contract 的实际 ReactAgent 复用 common-a2a 必需完整轮次 Hook，caller、domain、agent 分别固定为 `listing-master-service/listing/listing-agent`、`business-service/trade/trade-agent`、`contract-service/contract/contract-agent`。Hook 只归档经当前租约认证的用户、Agent 和只读业务工具消息；这些消息及其来源哈希说明“本轮看到了什么”，不替代 listing/analytics/contract 的数据库或 RPC 真源，也不产生业务事实。各服务使用自己的 Ed25519 身份私钥；核心 Memory 读取、授权核验与归档没有开关，压缩和索引独立治理。

**根因**：三个 Agent 的读取工具已有领域事实边界，长期会话可帮助解释用户目标和既往结果，但将工具返回的历史快照当作当前业务状态会绕过原有权限与新鲜度校验。复用相同 Hook 可保留冷/热工作集、来源可追溯和完整工具调用组，同时把记忆权限收窄到固定 Agent；身份、存储或授权未就绪时请求必须失败关闭，不能以关闭核心 Memory 开关来绕过预检。

**代码位置**：`ListingOfficialA2aAgent`、`TradeOfficialA2aAgent`、`ContractOfficialA2aAgent`；对应 `a2a.memory` 配置、Compose 环境变量和 `memory-service` trusted caller 白名单；回归 `ExplicitSkillAgentWiringTest` 与 `A2aMemoryAgentHookTest`。关联 LR-77；change 5.7。

## LR-79 compare/media/notification/settlement 的 A2A 记忆按真源与主体边界分别授权

**结论**：四个实际 ReactAgent 都接入 common-a2a 必需完整轮次 Hook，caller/domain/agent 固定为 `compare-engine-service/compare/compare-agent`、`media-worker-service/media/media-agent`、`notification-service/notification/notification-agent`、`settlement-service/settlement/settlement-agent`。Compare 与 settlement 在 memory-service 取得精确域授权；所有服务以本服务 Ed25519 身份签署 Memory 请求，核心读取、核验与归档没有开关。压缩仍为可选。Media 和 notification 目前未登记 memory-service caller，故在可信来源问题解决前其官方 A2A 请求会于模型/工具前失败关闭；media 任务结果只存于进程内 map，notification 工具的查询 `userId` 尚未绑定当前 A2A 租约主体（KI-117、KI-151）。没有新增业务库。

**根因**：完整轮次 Hook 只能持久化本轮获准的对话和工具证据，不能替代领域任务结果真源，也不能把模型提供的用户标识当作调用者身份。读写前必须同时满足来源可恢复、caller/domain/agent 精确匹配和消息主体授权；授权缺口作为入口故障处理，不通过关闭 Memory 或回退旧业务路径规避。

**代码位置**：四个 `*OfficialA2aAgent`、各服务 `a2a.memory` 配置、Compose/.env 示例、`memory-service` trusted caller 白名单及四项 `ExplicitSkillAgentWiringTest`。关联 LR-78、KI-117、KI-151；change 5.8。

## LR-80 无会话标识的一次性 ChatClient 不创建本地对话记忆窗口

**结论**：agent-service 的实际 `AgentChatService.call(systemPrompt,userPrompt,allowMcp)` 调用，以及领域服务的 ChatClient fallback，都是单次任务请求且没有稳定的 `conversationId`；正式 `/chat` 对话沿 Supervisor Graph 与 `SupervisorMemoryLifecycle` 管理累计消息和授权历史。无需给一次性调用套用 ChatMemory/Repository/Advisor，也不能从当前任务或临时请求文本猜造 conversationId。

**根因**：短任务没有跨轮对话边界，给它绑定隐式窗口会把不相关任务串到同一历史；而 Supervisor Graph 已自行读取、核验并装配历史，再由 ChatClient Advisor 注入会重复提供累计消息并绕过现有来源版本检查。

**代码位置**：`AgentChatService.call`、`AgentCapabilityServiceImpl`、领域服务 `*AgentServiceImpl` 的 ChatClient fallback、`SupervisorToolLoopGraph` 与 `SupervisorMemoryLifecycle`；change 5.9。只有未来出现稳定业务会话 ID、明确调用路径且不与 Graph 重叠的 ChatClient 对话入口时，才重新评估本地窗口适配。

## LR-81 历史无匹配与依赖不可用分别提示且当前请求优先

**结论**：A2A 工作集成功返回空历史时标记 `NO_MATCH`，向 Agent 插入不会归档的系统提示；Memory 身份、依赖、授权或版本预检故障标记为失败并在模型/工具执行前拒绝，不插入可降级执行提示。Supervisor 基础工作集始终必需；事项偏好这类附加装配无匹配与其专用 RPC 不可用分别写入当轮 SystemMessage。请求可独立完成时可以不使用缺失的附加偏好；若依赖缺失的历史对象、约定或偏好则向用户澄清且不猜测。新 user event 进入偏好装配复用键，触发局部刷新；当前轮明确修正优先于召回的旧偏好数据。认证/授权失败继续失败关闭。

**根因**：将空结果和依赖故障合并会误报服务状态，或让模型把未知历史当成已确认事实；基础 Memory 依赖故障必须停止执行，而空工作集仍是合法结果。A2A 的内部提示需要从后续归档切片中排除，避免实现指令进入用户档案。偏好读取属于附加能力，可单独没有匹配或未应用；按 user event 刷新可令明确修正即时成为当前输入，但不伪称后台偏好转换已持久化。

**代码位置**：`A2aMemoryAgentHook.noMatchUpdate/failRequiredMemory`、`A2aMemoryInvocation.currentInputStart`、`SupervisorMemoryLifecycle.prepare/assemblePreferences/modelMessages`、`SupervisorToolLoopGraph.prepare/model`；回归 `A2aMemoryAgentHookTest.noMatchIsReportedSeparatelyAndMissingHistoricalReferentsAreNotGuessed`、`SupervisorMemoryIntegrationTest.memoryUnavailableBlocksModelAndTools`、`explicitCurrentCorrectionOverridesAnOlderRetrievedPreference` 与 `missingAndUnavailableHistoricalPreferencesHaveDistinctPromptStates`；关联 KI-152、LR-73/96；change 5.10/11.5。

## LR-82 硬规则只由受管配置与授权入口维护，经验不能改变规则

**结论**：记忆约束种子来自 `system-constraints.yml`，启动 bootstrap 仅对数据库中不存在的 key 执行插入；已有内容、手工修改或停用状态均保持原样。后续修改和停用只能通过现有受权约束维护入口，经验评估、反馈和时间权重不能写入或重启硬规则。历史 skill/workflow 文字继续作为模型提示，由通用 Graph 按当前能力、权限、预算和审批执行调用，不转换成业务 DAG。

**根因**：软经验的正面结果、重复使用和近期读取都不能授予政策修改权；将初始化脚本当作持续覆盖同步会重新启用人工停用的规则，解析历史流程文字也会绕开已确立的模型选择与审批边界。Bootstrap 的“只补缺”及维护入口的授权分离同时保留初始化可恢复性与运行期治理。

**代码位置**：`memory-service/src/main/resources/system-constraints.yml`、`SystemConstraintBootstrapRunner`、`SystemConstraintMemoryService.bootstrapMissing/upsert`、`MemoryController`、`MemoryRagSecurity`；回归 `SystemConstraintBootstrapRunnerTest.disabledDoesNotReadOrWriteAndEnabledOnlyAddsMissing`、`MemoryRagLifecycleTest.bootstrapOnlyAddsMissingAndConstraintUpsertHandlesDisableRevive`；业务流程边界见 LR-17/LR-18；change 8.1。

## LR-83 经验只由可信结果或同一私域的用户反馈形成，检索不产生新证据

**结论**：本 change 采用用户确认的两类经验来源：受信域的真实结果事件，以及绑定同一 owner/domain/agent/conversation 已归档 USER 消息的用户反馈。业务结果只接受 operation-bound SERVICE 签名且在部署 allowlist 中启用经验发布的 caller；caller/domain/agent 必须精确匹配，配置默认关闭。反馈还必须绑定当前经验 revision。事件收件回执、幂等身份和 `ASSESS_EXPERIENCE` job 与结果一起持久化；worker 只验证精确来源并以 lease 和 expected revision 提交一份新快照，过期评估失败关闭。经验身份及来源查询统一按 owner/domain/agent 隔离；检索仅在当前范围内核验来源 revision，以版本化相关性、置信度、启用度、半衰期时间权重和 token budget 选取，读取不刷新观察时间或确认时间。硬规则仍由 LR-82 的受权入口单独维护。跨用户分享只能通过 LR-90 定义的独立受治理归纳对象，不能从私有经验行或 grant 推导。

**根因**：模型自评、请求已受理、检索命中和经验复用都不能证明真实业务结果；userId 或来源引用也不能单独证明反馈者拥有被引用消息。异步评估必须防止重试重复计分，并在多个旧 revision 竞争时阻止迟到 worker 覆盖新反馈。经验 ID 是调用方提供的稳定本地身份，查询若遗漏 owner/domain/agent 会把同号经验的证据混合；缓存命中也必须重新核对当前经验版本和来源授权。共享经验还需要独立治理批准和不可还原的脱敏契约，因此不得借现有私有行或 grant 推断共享权限。

**代码位置**：`MemoryExperienceOutcomeRequest`、`MemoryExperienceFeedbackRequest`、`MemoryExperienceService`、`MemoryExperienceProcessingWorker`、`ExperienceSelectionPolicy`、`MemoryV2RequestSecurity`、`MemorySourceAuthorizer`、`MemoryExperienceProperties`；迁移 `20261021_memory_experience_records.sql` 与 `20261021_memory_experience_processing.sql`；回归 `MemoryExperienceServiceTest` 的幂等重试、过期 feedback、跨 owner 同 ID 隔离、负反馈停用/重新确认、删除墓碑、评分与 token 预算用例。关联 LR-75/LR-82/LR-90；change 8.2/8.4。当前 publisher 与处理开关默认关闭，代码验收不代表真实业务域已开始发布经验事件。

## LR-84 私有记忆候选须在重排前取得当前真源许可

**结论**：memory v2 私有检索使用单独的 `MEMORY_SEARCH` 签名 RPC 和短期 owner/domain/agent scope；RAG 将不可覆盖的 owner、domain、agent、active 条件加到两路召回。融合后、把候选正文发送给通用重排前，RAG 只把候选稳定 ID、source type、revision、schema 和内容哈希交给 Memory 的专用许可 RPC；Memory 按当前投影事件、来源版本/哈希、删除状态和派生来源复验后只返回允许的 ID。许可失败关闭，Memory 最终仍以当前 SQL 真源装配正文并复验身份。协作候选 scope 还绑定接收方 domain/agent/conversation、matter、purpose 和明确 grant；Memory 对照当前导出 revision/hash、接收方投影回执、grant 历史快照及全部来源依赖，仅授予仍有效候选的 ID。

**根因**：如果在重排后才检查当前权限，已撤回、过期或跨域的旧索引正文已经进入重排模型。索引字段和 namespace 只能缩小召回，不能证明派生来源及共享 grant 此刻仍有效；RAG 需要在取用正文前调用唯一真源许可点，Memory 还需要在结果输出前复核当前事实。

**代码位置**：`common/src/main/java/com/bkanent/common/rag/MemoryRagAuthorizationScope.java`、`RagCandidateReference.java` 与 `RagCandidateAuthorizationRequest.java`；`rag-service/security/RagAccessControl`、`retrieval/RagRetrievalService`、`retrieval/RagMemoryCandidateAuthorizer`；`memory-service/service/MemoryRagCandidateAuthorizationService`、`MemoryRagSecurity`、`MemoryRagSearchFacade` 及偏好/经验服务。候选只授权 ID 的回归在 `RagRetrievalServiceTest`、`MemoryRagSecurityTest` 与 `MemoryRagSearchFacadeTest`；协作候选的当前来源删除、grant 撤回和旧索引回执回归在 `MemorySharingWriteServiceTest`。

**关联**：LR-31、LR-32、LR-33、LR-83；change 9.1–9.5。

## LR-85 共享导出先持久确认，再独立跟踪每个接收方的索引状态

**结论**：只有来源 owner 的受签名写请求可创建/撤回不可变 grant。导出提交记录来源调用 ID、请求 hash、精确来源依赖和内容 hash，状态先为 `PENDING` 且不返回可读引用；第二个绑定版本和 hash 的提交请求在重新核验 grant 与来源后才转为 `COMMITTED` 并返回稳定引用。协作投影按明确 recipient domain/agent/conversation 分开写入 RAG，持久索引状态由各 outbox event 的围栏回执汇总；撤回立即改变 SQL 授权状态并为当前投影排入删除事件，墓碑确认后报告 `INDEX_REMOVED`。旧提交记录没有新运行态回执时仍按已提交来源读取，索引状态视为 `NOT_REQUESTED`。共享写事务使用独立锁行，不与消息/RAG 写路径形成反向锁序。

**根因**：引用和 namespace 都只是定位符，不能授权读取；索引就绪也不能决定导出是否已持久提交。分开记录 sourceInvocationId/request hash、durable state 和每个 recipient 的索引回执后，重试可以复用同一版本、索引延迟不会隐藏 SQL 真源，撤回可以立即拒绝正文并让异步删除保持可观测。

**代码位置**：common 的 `MemoryShareGrantManagementRequest`、`MemorySharedExportSubmitRequest`、`MemorySharedExportCommitRequest`、`MemorySharedExportStatusResponse` 和 `MemoryCanonicalHash.sharedExportHash`；memory-service 的 `MemorySharingWriteService`、`MemorySharingStore`、`MemoryV2RagProjectionService`、`MemoryRagOutboxDispatcher`；迁移 `20261021_memory_shared_export_runtime.sql` 及部署 manifest。`MemorySharingWriteServiceTest` 覆盖签名创建、重试冲突、两阶段提交、每接收者索引确认和撤回墓碑；`MemorySharingAccessServiceTest.committedExportsWithoutNewRuntimeReceiptRemainReadable` 覆盖迁移兼容。

**关联**：LR-59、LR-84；KI-154；change 9.2、10.2。不以持有 export reference 或 RAG document ID 授权。

## LR-91 协作候选许可只核验当前投影、grant 与来源并返回ID

**结论**：COLLABORATION 空间在RAG双路召回前固定owner及recipient domain/agent、active、grant过滤。可达入口为受签名保护的 `MemoryV2RpcService.searchCollaborations`：Memory从当前SQL生成精确purpose/matter/接收方下有效grant集合签入短期scope，无授权grant不调用RAG。重排前，RAG以候选ID、source type、projection revision、schema和hash调用独立Memory许可RPC；Memory锁定候选核验事务，检查投影事件身份与规范hash、共享导出当前COMMITTED版本/hash、对应接收方投影回执、可信scope中的owner/recipient/matter/purpose和grant revision/状态/时间窗，再按导出版本逐项复核其来源依赖。共享导出写入与候选许可都先按grant ID稳定排序取grant锁；写入再更新export和投影账本，授权回调在grant锁后核验投影，避免两条路径反向取锁。删除、过期、撤回、冲突或无匹配只排除该候选；真源或授权依赖不可用则整个许可请求失败关闭。许可响应只返回稳定document ID，不读取或传递正文；RAG不得在获准前把候选内容交给重排。RAG正文只作为短暂候选，Memory用SQL当前COMMITTED导出和安全payload策略重建结果，并在用户proof复核后再次核验；COLLABORATION只许可受限搜索投影，详情读取仍需DURABLE_EXPORT。合法派发后发生的撤回不能收回已发送正文，但Memory最终查询仍复核当前版本与授权。

**根因**：索引中的grant字段及旧event回执都只是召回线索，不能代表此刻授权；候选正文若先到通用重排模型，之后的SQL拒绝无法撤回已经发生的数据派发。把协作候选权限放在Memory真源内核验且只回传ID，保留Memory为唯一授权点，RAG继续唯一执行搜索与重排，并让真源依赖故障与普通过期候选得到不同的失败语义。

**代码位置**：`MemoryRagSecurity.verifyCandidateCaller`、`MemoryRagCandidateAuthorizationRpcServiceImpl`、`MemoryRagCandidateAuthorizationService.collaboration`、`MemorySharingStore.findCurrentGrantForUpdate`、`MemoryV2RagProjectionService.sharedExport`、`MemoryCollaborationSearchService`、`MemoryRagSearchFacade.searchDomainCandidates` 和 `MemoryV2RpcServiceImpl.searchCollaborations`；测试为 `MemorySharingWriteServiceTest.collaborationCandidatesRequireCurrentGrantAndSourceBeforeAnyBodyCanBeReranked`、`MemorySharingWriteServiceTest.collaborationSearchReturnsCurrentSqlProjectionAndWithholdsRevokedCandidates`、`MemoryRagSearchFacadeTest.collaborationSearchSignsExactRecipientGrantPurposeAndMatterScope`、`RagRetrievalServiceTest` 的拒绝许可零重排用例，以及共享详情拒绝COLLABORATION-only grant的既有回归。

**关联**：LR-84、LR-85；KI-56；change 9.2、9.5。

## LR-86 A2A终态内容直传，跨域记忆导出只接收受限owner授权

**结论**：A2A display 继续在响应正文/结构结果直接返回，默认终态正文上限为64,000字符；Supervisor消费同一终态artifact并直接保留现有content/result，不因存在记忆引用而重复调用 Memory。显式 durable export 只能从 Supervisor 可信运行上下文的 `durableMemoryExport` 指令进入 `supervisor.memoryExport`，模型输出和工具参数不能生成指令或 `memoryRefs`。agent只在当前turn归档确认后创建以当前认证用户签名的grant，且来源限定为最终ASSISTANT消息；根字段只允许 `MemorySharedExportFields` 的安全集合，选中的payload与brief合计UTF-8字节不超过24,576，最多32个字段、64个接收方和30天有效期。协作索引只从已提交不可变export投影；只有 `DURABLE_EXPORT` grant 才允许详情读。terminal `memoryRefs/exportState/memoryVersion/memoryIndexState` 由服务端放在终态artifact metadata与结构part metadata，客户端仅接受 `terminal=true` 的回执并继续把原正文、结构字段和终态状态传入Supervisor执行事件。

**根因**：A2A内容是当前获准调用的工作结果，引用是后续共享对象的定位信息；若以引用代替正文会破坏现有Supervisor和SSE消费契约，若从普通metadata或模型JSON接受引用则可伪造权限状态。分开保留直传内容、受限owner签名grant、不可变payload投影和终态服务端回执，能让索引延迟不阻塞业务完成，也避免跨域只凭引用继承权限。

**代码位置**：`common/src/main/java/com/bkanent/common/agent/DurableMemoryExportDirective` 与 `common/memory/MemorySharedExportFields`；`common-a2a` 的 `A2aMemoryAgentHook`、`A2aOutputNormalizer`、`OfficialA2aAgentExecutor`；`agent-service` 的 `OfficialA2aMetadataMapper`、`OfficialA2aResponseNormalizer`、`OfficialA2aAgentClient`、`A2aExecutionService`；`memory-service/MemorySharePayloadPolicy`。回归覆盖指令allowlist/大小边界、可信metadata入口、final-assistant单源与USER签名grant、服务端终态metadata、传输client、伪造字段剔除和Supervisor事件结果。

**关联**：LR-59、LR-85；change 10.1/10.3。A2A/Memory代码回归不等于真实前端浏览器与部署拓扑验收；10.4–10.7仍分别验收。

## LR-87 SSE 父任务订阅只接受直接关联子任务并按会话核验回放游标

**结论**：实时及持久回放共享 `SessionStreamVisibility.isVisibleToTask` 的可见性和直接父任务关系：无 task 过滤时保留兼容行为；指定 parent task 时只接收 `taskId == parentTaskId` 的父事件及 `metadata.parentTaskId == subscribedTaskId` 的直接子事件，不递归收取孙任务。回放 SQL 用参数化的 JSON 父任务条件缩小候选，结果返回前再执行同一 Java 关系校验。`Last-Event-ID` 查找同时绑定 sessionId/eventId，回放与实时重叠按 session sequence 去重。子服务提交的 parent、visibility、terminal 和 memory receipt 元数据先剔除/脱敏，再由可信 Supervisor 请求和服务端终态结果写入。

**根因**：taskId 是单个执行者的身份，不能表达父调用所订阅的完整结果；仅对实时流加父子过滤会让断线重放静默丢掉子成果，仅信任客户端 metadata 又允许子任务伪造父链或终态回执。跨会话复用 event ID 还会把另一会话的序号当作游标。持久和实时出口必须共用关系规则，绑定会话后才能保持回放一致且不越权。

**代码位置**：`common/agent/SessionStreamEvent`、`SessionStreamVisibility`；`agent-service/stream/InMemorySessionStreamService`、`SessionEventAuditService`、`service/A2aExecutionService`。回归 `SessionEventAuditServiceTest`、`InMemorySessionStreamServiceTest`、`A2aExecutionServiceTest`；关联 KI-156，change 10.5。

## LR-88 Agent 输出统一脱敏，持久共享导出对敏感材料失败关闭

**结论**：跨服务展示/协作结果经 common `AgentOutputSanitizer` 递归移除敏感字段、对包含凭据或私有执行材料的自由文本整体替换；共享导出的 payload 字段由固定 allowlist 限制，brief 与递归字符串值经相同检测器核验，命中时拒绝写入而不截断保存。SSE 事件、terminal metadata 与输出 normalizer 复用统一策略，服务端可信生成的 terminal memory receipt 在远端值过滤之后单独映射。

**根因**：字段名过滤挡不住普通 `note`/`summary` 字段里的 bearer、凭据或脱敏映射；反过来，对已授权面向用户的结果可安全隐藏敏感段，但对不可变共享载荷做静默替换会令 owner 签认内容与实际导出内容不一致，因此共享写必须拒绝并由来源域修正。把 terminal receipt 和子端普通 metadata 混为一体会允许远端伪造权限状态。

**代码位置**：`common/agent/AgentOutputSanitizer`、`common/a2a/A2aOutputNormalizer`、`common-a2a/A2aMemoryAgentHook`、`agent-service/client/OfficialA2aResponseNormalizer` 与 `service/A2aExecutionService`、`common/memory/MemorySharedExportPolicy`。回归 `AgentOutputSanitizerTest`、`A2aOutputNormalizerTest`、`MemorySharedExportPolicyTest`、`A2aExecutionServiceTest`；关联 KI-155/KI-156，change 10.3/10.5/10.7。

## LR-89 [SUPERSEDED] 旧 HMAC 会话票据密钥已由数据库摘要票据替代

**结论**：旧 `INTERVIEW_TICKET_SECRET` HMAC 方案不再是当前票据机制。访谈票据改为 256 位密码学随机值，服务仅持久化 SHA-256 摘要，并通过已登录用户的受鉴权领取接口发放；无需在 Interview 使用共享签名密钥。Gateway 登录签名与 A2A/MCP Auth context 是独立身份凭证，仍按受管密钥配置和本地验签执行。

**根因**：ticket 已不再承担登录身份或长期会话签名。随机高熵值的摘要比保留第二套长期 HMAC secret 更简单，也避免不同服务共享会话签发密钥；认证用户、场次关系、用途和票据撤销状态分别由签名上下文与域数据库校验。历史 `INTERVIEW_TICKET_SECRET` 配置只属于已关闭的旧机制，不可据此恢复 HMAC fallback。

**代码位置**：`interview-service/service/InterviewParticipantTicketService`、`security/InterviewAuthorizationFilter`、`runtime/InterviewController.issueParticipantCredential`；旧行为见 KI-157。

**关联**：LR-1/145；KI-157/202；change `unify-versioned-permission-context`任务8.1/8.2。旧签名票据不应再被新入口接受；历史存量凭据扫描仍待 KI-202。

## LR-90 域共享经验是无个人来源链接的独立治理归纳

**结论**：私有 `MemoryExperience` 永远按 owner/domain/agent 授权。跨用户共享另用 `MemoryDomainExperience` 与独立 SQL 表，发布要求签名用户同时具备受管治理权限、调用方治理发布 allowlist、精确 domain/agent 范围，并显式确认独立非个人归纳及保留授权；发布必须携带有效期限、用途、政策版本和不透明治理审查引用。共享读模型不包含 owner、私有经验 ID、来源 ID、grant 或审查引用，查询只按认证 domain/agent 限域。来源删除不会级联删除该独立归纳；只有保留授权仍有效且该共享对象未被治理撤回时才可读取。撤回以新版本更新状态并清空当前可读载荷；调用方在装配和输出前用 `checkDomainExperienceUse` 核验原 revision/hash 仍是有效当前版本。

**根因**：复用私有经验行或现有共享 grant 会把个人证据读取权限误当成域知识权限；把来源引用嵌入共享文本又会令共享读者重建个人来源。单纯过滤新查询还不能约束调用方已装配的旧内容，因此共享身份、审批审计、当前载荷和版本复核必须独立，且旧版本失效时不能返回其正文。

**代码位置**：common 的 `MemoryDomainExperience*` RPC DTO 与 `MemoryV2RpcService`；`MemoryV2RequestSecurity`、`MemoryExperienceService`、`MemoryV2RpcServiceImpl`；迁移 `20261021_memory_domain_experience.sql`。回归 `MemoryDomainExperienceServiceTest`、`MemoryV2RpcContractTest` 覆盖治理权限、domain/agent 隔离、不可还原引用、来源删除保留、撤回和使用前版本检查；change 8.3。

**关联**：LR-59、LR-83、LR-84；change 8.3、9.2–9.5。

## LR-92 Nacos开发配置发布遵循服务端显式认证模式

**结论**：`NACOS_AUTH_ENABLE` 同时决定开发Nacos服务端与一次性配置发布任务的认证方式。关闭认证时，发布任务不尝试登录，只允许写入固定的 `BK-AGENT` / `DEFAULT_GROUP` 与 `publish-nacos.py` 中的 Data ID allowlist，并逐项回读校验；开启认证时必须使用显式用户名/密码登录，拿到的短期token只附于受限配置读写请求。发布目标仍限定为本地Compose Nacos，拒绝代理与重定向，日志不写凭据或配置正文。

**根因**：开发Compose默认关闭Nacos API认证，而旧发布任务无条件调用登录接口；Nacos 3.1 在该模式下返回403，使初始化任务永远无法发布配置。为部署强行开启认证会改变开发环境安全模式并引入账号初始化依赖；按服务端模式分支并保留固定namespace/group/Data ID白名单及回读校验，能让配置初始化与实际Nacos模式一致。

**代码位置**：根 `docker-compose.yml` 的 `nacos` / `config-init` 环境绑定，`scripts/deploy/publish-nacos.py`；回归 `scripts/deploy/test_publish_nacos.py` 覆盖认证关闭不登录、开启后携带登录token且不打印凭据，以及认证模式必填。关联 KI-158 与 change 11.2。

## LR-93 事项经验正文仅在执行进程内暂存，检查点只保存版本引用

**结论**：Supervisor 按当前事项、可信 context、事项版本及最新用户事件检索有界经验；执行进程用 runId 暂存本轮安全经验投影，供同事项的模型/工具轮次复用，结束或销毁时清除。Graph 检查点忽略经验正文，只保存经验 ID/revision、评分版本、事项 ID/revision/goal hash 等无正文引用。每次入模、工具调用和输出前由 Memory 对当前经验状态及事项目标版本复核；安全读取/解析也复用相同检查。进程恢复时若无短暂投影缓存，按当前事项重新检索，不能从检查点重建旧正文。

**根因**：持久化经验正文会在输出授权撤回或经验停用后残留于旧 checkpoint，并扩大跨轮持久化的私有材料范围；每个模型轮次都重新检索又会造成多余跨服务读取。将正文留在 run 生命周期内的进程缓存、将检查点降为可复核版本引用，可以复用同事项装配，同时使持久恢复必须重新取得当前授权内容。

**代码位置**：`agent-service/orchestration/ToolLoopState`、`agent-service/memory/SupervisorMemoryLifecycle`；`common/memory/MemoryExperienceSelectionRef`、`MemoryBoundaryRequest`；`memory-service/MemoryExperienceService.selectionsCurrent`、`MemoryArchiveService.checkBoundary`。回归 `SupervisorMemoryIntegrationTest.matterExperiencesAreInjectedAndReusedAcrossModelToolRounds` 与 `deactivatedExperienceCannotAuthorizeModelOutput`；关联 LR-73/75/83，change 5.3/8.4/10.7。

## LR-94 经验只接收受信域结果事件与有来源的用户反馈

**结论**：业务结果证据只接受经过签名的受信领域服务请求，并要求该 caller 对应的逐域 `experience-outcome-publisher` 开关显式开启；Supervisor/模型自身没有结果发布权限。用户反馈走当前 owner 的用户认证通道，必须引用该 owner 当前归档的 USER 消息和准确的经验 revision。两类事件均异步、幂等评估；调用受理、模型自评和工具完成信号本身不构成业务结果。

**根因**：模型生成的总结或工具终态不能自行证明业务结果达成；把反馈当成业务事实也会混淆意见与可核验结果。分别约束发布主体、来源消息和经验版本，才能保留两种证据的含义，同时阻止 Supervisor 把自己的输出自我验证。

**代码位置**：`common/memory/MemoryExperienceOutcomeRequest`、`MemoryExperienceFeedbackRequest`、`MemoryV2RpcService`；`memory-service/MemoryV2RequestSecurity`、`MemoryExperienceService`、`MemoryV2SecurityProperties`；`nacos/memory-service.yaml`、根 `docker-compose.yml` 的逐域开关映射；`MemoryV2DeploymentConfigurationTest`。关联 LR-75/LR-82，change 8.2/11.1。

## LR-95 管理探针只允许登记的 Memory v2 逻辑空间

**结论**：RAG 运维探针将 Memory v2 空间 ID 限定为 `MemoryRagRecordType` 的五类记录与七个明确登记的 domain/agent 组合，全部以 `memory-service` 作为 owner caller。操作只收逻辑 space ID；ES index、Milvus database/collection、schema 和维度仍由 RAG 受管配置决定。`INSPECT`/`PROVISION` 还必须使用当前 ADMIN 身份，provision 参数须与受管 schema 完全一致。

**根因**：运维工具需要为 Memory v2 空间提供可重复的 provision/status/replay 入口，但不能因此开放任意索引、集合、domain/agent 或维度选择。固定逻辑 allowlist 与服务端 managed registry 双重限制，使新增物理资源只能随审查过的 RAG 配置发布。

**代码位置**：`scripts/verification/RagRuntimeProbe.MEMORY_V2_DOMAIN_AGENTS`、`MEMORY_V2_SPACE_IDS`；`common/rag/MemoryRagSpaceIds`、`MemoryRagRecordType`；`nacos/rag-service.yaml`；`scripts/verification/README.md` 和 `docs/memory-v2-operations.md`。关联 LR-43/LR-92，change 9.1/11.2。

## LR-96 Memory 核心生命周期不可选，停用从 Agent 入口执行

**结论**：Supervisor 与所有官方 A2A Agent 必须在模型/业务工具执行前读取当前 Memory 工作集并复核身份、授权和版本；成功回合通过许可归档。基础接入、读取、复核和归档没有开关或无记忆旁路。Memory 预检失败时在副作用前拒绝本轮；查询成功但无匹配是合法空工作集。压缩、偏好/经验加工及 RAG 投影/搜索可以作为独立附加能力启停，不得控制或替代基础生命周期。停用/回滚先停止新 Agent 入口并处理已受理事实，不能让仍接收请求的 Agent 绕过 Memory。

**根因**：把基础 Memory 接入做成可选，会让同一业务请求在有历史和无历史的两个路径中静默执行，造成提示上下文、归档事实和授权复核不一致。Memory 是 Agent 的必需依赖；无历史是有效数据状态，服务不可用、缺少接线或授权无法验证则是执行故障，两者必须区分。附加加工可以未就绪，但不能因此关闭基础记忆。

**代码位置**：`common-a2a/A2aMemoryAgentHook`、`A2aMemoryHookFactory`、`OfficialA2aAgentExecutor`；`agent-service/SupervisorMemoryLifecycle`、`SupervisorToolLoopGraph`；Agent application/Nacos 配置、`MemoryPreferenceProcessingConfiguration`/`PreferenceProcessingWorker`、`MemoryPrivacyCleanupWorker`/`MemoryExperienceProcessingWorker` 与 `docs/memory-v2-operations.md`。回归覆盖 A2A 实际 ReactAgent 读取/归档和 Memory 不可用前置失败、Supervisor 每次运行读取/归档及 Memory 不可用前置失败、附加偏好评估配置缺失不阻断核心服务、后台 worker 使用生产注入构造器；关联旧策略 KI-161、KI-164/165、change 11.3/11.5/11.6。

## LR-97 偏好块固定本人私有，跨域交接使用独立共享导出

**结论**：本变更中的 preference block 固定以 `(ownerId, subjectRef=ownerId, DOMAIN_PRIVATE(domainId, agentId), registeredDimension)` 为唯一键。用户自身拥有该块的读取和管理权限；domain 或 agent 变化产生不同逻辑块。其他 subject 与 TASK_SHARED/SESSION_SHARED 偏好块不属于本变更。需要跨域交接的内容继续经独立 `MemorySharedExport` 与当前 grant 显式授权；导出授权只覆盖导出内容，不改变 preference block 身份，也不授予接收方偏好历程读取权。

**根因**：共享导出有独立的不可变来源、内容白名单、接收方授权及撤回状态；把它与偏好块身份合并会让一次内容交接扩大为长期偏好历程读取，并引入目前没有落地和验收的共享偏好归并。固定私有块身份可沿用已实现的服务端 owner/domain/agent 校验，同时保留显式导出作为受限跨域交接机制。

**代码位置**：`PreferenceHistoryService.append/searchAt/assemble` 从签名上下文派生 owner-self 与 domain+agent 私有键，校验维度注册，并在检索时复算 blockId；偏好搜索先拒绝非 owner subject；`PreferenceBlockKey` 和 `MemoryScope.privateScope` 提供稳定逻辑身份；跨域内容仍走 `MemorySharingWriteService`、`MemorySharedExport` 与 grant。回归为 `MemoryContractTest.privatePreferenceScopeCannotCollideAcrossDomainsAgentsOrEscapedSegments` 与 `PreferenceHistoryServiceTest.agentsCannotMergeOrReadEachOthersBlocksAndForgedSubjectOrDimensionsAreRejected`。

**关联**：LR-59、LR-70、LR-73；KI-142；change 7.1、10.1/10.2。

## LR-98 服务身份密钥跨协议复用私钥，但按协议域隔离签名

**结论**：每个调用服务只持有一组 Ed25519 身份私钥，可分别签署 A2A/Auth 信标与 Memory v2 请求；签名输入包含显式版本、算法、key ID 和不同的协议域标签（`BK-ANENT:A2A-AUTH:v1`、`BK-ANENT:MEMORY-V2:v1`）。Memory v2 与当前 Auth/A2A verifier 均只接受 Ed25519。Auth 与 Memory 各自只登记需要验证的公钥，并继续执行各自精确的 caller、operation、domain、agent 和用户授权；Auth 仅授予 `agent-service` `ISSUE_BEACON`，其他已登记 A2A 调用方只授予 `RESOLVE_BEACON`。信标签发由 binding 的来源 Agent 签名，信标解析由 binding audience 目标服务签名。

**根因**：每服务每协议配置共享签名 secret 会让调用方与验证方都能伪造请求，且密钥项随服务和协议数增长。每服务一组非对称密钥把签名能力留在调用服务；协议域分离允许复用身份而阻止跨协议重放；verifier 独立公钥登记与原 ACL 保证新增身份不自动扩大权限。Auth 曾在 A2A/Auth 迁移观察窗内保留旧 verifier；本机开发栈覆盖最长 300 秒信标租约及 300 秒回滚窗后，旧算法计数保持为零，现已删除旧 verifier 与密钥配置。RAG RPC 仍遵循自己的独立协议。

**代码位置**：`common/security/ServiceIdentityCrypto`、`common/rag/A2aAuthSignature`、`common/memory/MemoryRequestSigner`、`common-a2a/A2aBeaconAuthentication`、`auth-service/AuthA2aIdentityProperties` 与 `A2aBeaconRpcServiceImpl`、`memory-service/MemoryV2SecurityProperties` 与 `MemoryV2RequestSecurity`；验签计数器 `service.identity.signature.verified`、`docker-compose.yml`、`scripts/security/`、`docs/service-identity-operations.md`。Auth 只预注册 Ed25519 A2A/Auth 计数器。回归覆盖来源/目标身份边界、operation ACL、协议域、签名、key ID/公钥匹配、错误算法/降级拒绝、轮换撤销与 verifier ACL；旧 HMAC 运行观察和退役记录见 2026-10-23 11.9 验收。关联 KI-162、change 11.8/11.9。

## LR-103 版本权限集合、运行委托和新鲜证明分别管理

**结论**：新 `common/authz` 基础契约将用户稳定权限集合、签名运行委托和30秒权威新鲜证明分离。权限内容hash不含滑动签发时间、缓存TTL或签名，集合不可变；运行actor、session、run、原权限上限和hard deadline固定，子委托不得扩大范围或期限。通知只能使本地状态失效，不直接授予权限；新鲜证明只能在同版本、epoch、hash下更新。MySQL写入的授权版本和outbox同事务，角色多对多并集与独立business_admin只授予登记操作，个人Memory归属和业务状态仍独立检查。

**根因**：按run复制权限副本会产生发布竞争，按版本签含滑动TTL内容会使同版本重建hash变化；用用户ID或旧checkpoint重建授权则绕过持有凭据。新协议采用 `bkanent-authz-v1` 的类型分域Ed25519，并独立验证来源和接收方证明、nonce、正文和目标，不影响已有RAG签名协议。

**代码位置**：`common/authz/AuthorizationSnapshot`、`AuthzFreshness`、`AuthorizationContext`、`AuthzSignature`、`AuthzDeadlines`、`AuthorizationCache`、`AuthorizationSession`；`auth-service/authorization/AuthorizationRepository`、`EffectivePermissionService`、`RoleAuthorizationManagementService`；新MySQL迁移、Redis `authz/publish.lua`、`scripts/security/configure-authorization-redis-acl.sh`、`scripts/security/verify-authorization-redis-acl.sh` 与 `docs/authorization-redis-acl.md`。

**实施边界**：基础批次413项定向回归、隔离MySQL/Redis规则通过。续批按用户确认新增独立同run受限刷新，KI-169已修；新授权RPC/管理API仍默认关闭，实际Gateway run绑定与Graph/业务consumer迁移见KI-170，不能声称全链通知或零重复Auth已上线。续批276项定向及全23模块1519项回归通过（跳过14外部门控），新增发布/本地监控基础。关联LR-33/40/41/63/98/106；change `unify-versioned-permission-context`。

**Redis ACL实施补充（2026-10-26）**：授权Redis用独立 `authz-writer` 与 `agent-authz-reader`；writer 只访问 head/snapshot/fence/active 索引和指定通知channel，reader 只读 head 并订阅该channel。ACL脚本只改这两个专用用户，不改共享 `default` 或其他业务账号；Auth/Agent启动还要求各自明确的ACL配置标志、固定身份名与受保护传输。该次代码补齐策略和复核脚本，授权Redis真实实例上的执行记录仍需后续运维/开发栈验收；新授权开关继续关闭，不据脚本存在宣称已部署。关联OpenSpec任务3.3/11.1。

## LR-104 Supervisor请求幂等必须绑定持久会话与归一化输入

**结论**：重复run的actor、session和归一化输入/政策必须一致，变更正文或明确跨session返回409；省略session的同requestId合法重投则从可信持久原请求恢复session，不每次生成UUID后误报冲突。chat先claim持久session归属，audit普通查询先核Memory真实owner再按session+run筛SQL；业务诊断及全局治理需要独立管理许可和可靠访问审计。

**根因**：只核owner会串同用户不同会话结果，只有Memory复合key不能保护按session分发的SSE；在principal已随机补session后才核幂等又会误拒合法重投。审计全表查询与治理只检查登录会把普通用户身份当全局数据/写权限。

**代码位置**：`AgentPrincipalContext.bind`、`AgentController.chat`及各Supervisor入口、`OrchestrationStore.assertRequestIdentity/ownedRunScopes`、`SupervisorToolLoopRunner`、`SupervisorEventAuditQueryService`、`SessionEventAuditService.queryOwned`、`SupervisorGovernanceService`。当前管理许可仍走旧Auth接口，审计明确记录 `legacy-rpc-unversioned`，未伪造新版本接线；新目录consumer迁移和异步入队并发门禁仍待实施。关联KI-168、LR-15/22/23、KI-80；新增真实request-scope/MockMvc重投及H2审计反例。
## LR-99 房源与比较共享领域事实，合并保留历史身份

**结论**：`consolidate-listing-comparison-domain` 将当前 Listing 来源查询、快照校验、比较、报告与 PDF 入口收敛到 `listing-master-service`。业务 API 使用 `domain-parallel-v1` 稳定来源引用、来源修订号、内容 hash、单位和 `REAL/SIMULATED/UNKNOWN` 模式；客户端不能把未知来源声明为真实。比较创建前批量读取获准快照，报告缓存、历史与 PDF 读取时再次核对当前来源和当前操作。`legacy-local` 仅作为当前显式开发来源，不导入旧服务报告或历史身份。Auth 继续以 `compare.report.create` / `compare.report.read.own` 作为签名上下文中的基线动作标识，并把目录元数据归入 `listing`；它们不是角色权限。基线退役迁移会停用目录行并清除对应角色关联，Listing 目录迁移只更新元数据、修订号和 outbox，不重新启用或授予权限。`compare.report.share.public` 不迁入 Listing；在确认项目未上线且没有历史资产后，迁移 540 停用该旧目录码并清理角色关联。

**根因**：拆分的比较服务依赖 Listing 事实，却缺少可跨来源稳定验证的身份与版本；ID-only 缓存不能发现来源更新，也会把服务重构误作授权变化。稳定引用和当前签名 Auth 上下文共同限定每次读取，来源模式作为服务端事实保留。确认没有在途任务或可保留的历史资产后，独立 Compare 服务、RPC/A2A/MCP 信任和 Memory `compare/compare-agent` 空间一并退役；当前 Listing 只使用自身已登记的 Memory 身份。

**代码位置**：`common/listing` 中的来源与版本契约、`common/model/ListingComparisonFacts` 和 `common/rag/RagCanonicalHash`；`listing-master-service/source` 的 legacy-local、fixture、HTTP provider 与业务来源授权服务；`listing-master-service/comparison` 的比较、版本指纹、报告存储、兼容 JSON 与 PDF；Auth 目录迁移 `20261028_listing_comparison_authorization_domain.sql` 与退役迁移 `20261029_legacy_compare_promotion_retirement.sql`；Listing Agent 的既有 Memory Hook 与 `listing-comparison` skill。最终服务和权限边界见 `docs/listing-comparison-cutover.md`。

**关联**：LR-22/23/54/79/96/97/149；KI-115。定向契约、来源、授权、报告、Agent 装配、Studio 授权与配置矩阵测试通过；真实上游联机、共享 Nacos 发布、容器部署和线上切流仍待部署验收。

## LR-100 知识运营拥有派生资产，逐字稿继续归访谈域（规划）

**结论**：2026-10-25 独立 change `extract-knowledge-operations-domain` 规划新增知识运营域，管理案例/打法的修订、审核、发布、范围与撤回。访谈继续独占会话、运行状态和脱敏逐字稿，知识只通过受权来源接口消费精确快照并记录依赖。知识发布不自动创建 Memory 共享许可或个人偏好。

**根因**：逐字稿事实、运营归纳与个人记忆具有不同的编辑/使用权。复制原稿建第二个真源会破坏来源撤回和证据一致性，把审核发布交给 Memory 又会把知识可见范围误换成私有历史访问权。独立派生资产及来源检查使运营可修订知识，同时保留原访谈事实和共享边界。

**代码位置（当前与规划）**：当前 `interview-service/InterviewCollectionService`、`InterviewReportService`、逐字稿及占位打法/共性实体；拟新增 `knowledge-service` 和窄逐字稿读取契约。方案见 `openspec/changes/extract-knowledge-operations-domain/`；尚未实施，不据此宣称 KI-15 已修或知识服务已上线。

**关联**：LR-3/6/8/59/90/94/96/97；KI-15。

## LR-101 经营复盘分别保存建议、动作与效果，模拟输入贯穿来源（规划）

**结论**：2026-10-25 独立 change `implement-store-business-review` 规划在 business-service 管理经营事项、证据、计划/动作和观察，保留现有 `trade/trade-agent` 技术身份。当前模拟输入通过有版本的数据端口接入，房源和知识按公开来源契约引用；动作完成、人工反馈和上游确认分别表达，模拟结果不发布为生产 Memory 的经营有效性证据。

**根因**：模型建议、人工勾选和真实成交是不同事实；将它们折成一个完成状态会让模拟增长、建议受理或待办完成冒充业绩结果。保留数据模式、分母口径、来源版本和独立回执，才能先验收模拟业务流程，再以实际证据观察效率与业绩变化。产品职责调整也不需要顺带改写历史私域身份。

**代码位置（当前与规划）**：当前 `business-service/BusinessAnalyticsServiceImpl`、`TradeOfficialA2aAgent` 与 trade 技能；拟新增 review 业务包及经营数据端口。方案见 `openspec/changes/implement-store-business-review/`；尚未实施，不表示 KI-128 或 Studio 浏览器链路已通过。

**关联**：LR-22/23/53/73/75/82/83/94/96/97；KI-114、KI-128、KI-166。

**演进**：本条记录 2026-10-25 初版对服务归属的规划。2026-10-27 确认的独立拆分方案由 LR-122 取代“在 business-service 增加复盘、沿用 trade-agent 身份”这一实现边界；有来源事实、动作/结果分离和模拟结果不作为真实效果证据等业务原则继续适用。

## LR-122 门店复盘新增独立服务并隔离现有业务与 Memory 身份

**结论**：change `implement-store-business-review` 在独立 worktree 中新增 `business-review-service`、`bk_business_review`、独立端口 9016 和 Gateway/Nacos/Compose/密钥配置；端口 9015 由并行 knowledge-service 使用。现有 `business-service`、`business-service.yaml`、`bk_business`、`trade-agent` 及 `business-service/trade/trade-agent` Memory 私域保持不变。新 Agent 使用 `business-review-service/business-review/business-review-agent` 的必需 Memory Hook、独立服务公钥和精确 domain allowlist。Memory 只承载该 Agent 获准的上下文读取/归档，不能成为复盘事实真源；模拟或人工回报不发布为生产经营经验结果。配置同步覆盖环境模板、Nacos、Compose、Gateway、Maven、数据库 manifest、Nacos 发布/启动白名单和服务 keygen；Auth 的 STORE 身份与六项操作以及跨会话 Memory 注册由协调会话合并后才可进行生产接入。

**根因**：复盘事项、交易分析和 Agent 私域有不同数据所有权。把新业务继续塞入 business-service 会与并行服务拆分共享源码、数据库和配置写入范围，并使“更名”误触历史 Memory 归属。独立服务、数据库、路由、端口、Nacos Data ID、Agent/domain 和 key ID 构成完整隔离身份；共享注册只做各域唯一条目的合并，避免覆盖其它拆分会话的服务配置。

**代码位置**：`business-review-service/`、`nacos/business-review-service.yaml`、`nacos/memory-service.yaml`、Gateway/Agent 配置、`sql/migrations/20261027_business_review_schema.sql` 和 `docker/mysql/init-manifest.tsv`。`docs/business-review-auth-memory-handoff.md` 记录 Auth/Memory/来源生产者登记要求；代码与本地 fixture 不代表生产 STORE 映射、公钥生成或跨服务部署已完成。

**关联**：LR-96/97/101/105/139/140/142/145/151/152；KI-114、KI-128、KI-166。

## LR-123 经营复盘来源读取使用当前委托证明绑定 HTTP 路由和正文

**结论**：business-review 调用 `domain-parallel-v1` 来源接口时复用当前 Auth context/freshness，并由 `business-review-service-current` 对 `DelegatedAuthorizationRequest` 的 CallerProof 签名。每次来源 POST 绑定目标 audience、精确 `business.review.*.store` 操作、实际正文 hash、短期限和 nonce；`requestId=dp1|POST|<path>|<uuid>` 同时绑定实际 HTTP method/path。来源请求不转发用户 Bearer、不逐来源调用 Auth。Listing/Knowledge 入口需验证 Auth 签名、consumer service proof、方法/路径、store scope、操作及 body，并消费 nonce；任一条件不满足即拒绝，不以空集或 fixture 代替。

**根因**：将进入 business-review 的 Gateway 请求头原样转发，会把绑定错误的受众、方法和正文交给另一个服务；单独的 service key 又不能证明代表哪个用户、门店和授权操作。委托上下文保留 Auth 已确认的身份上限，consumer proof 标识受信服务，路由/body 绑定阻止跨 endpoint 重放；生产者仍需在其真实资源域验证 STORE 和来源授权。

**代码位置**：`business-review-service/domain/DomainParallelAuthorizationSigner`、`DomainParallelHttpReadAdapter`；协议交接见 `docs/business-review-auth-memory-handoff.md`。验证仅覆盖本地 HTTP consumer fixture。房源/知识生产者的验签、持久 nonce 消费和跨服务真实互通仍需其独立 change 合并与部署。

**关联**：LR-1/122/140/145；KI-204；change `implement-store-business-review` 任务 0.3/4.2/6.2。

## LR-102 内容推广同域保存发布事实，效果必须标明来源（规划）

**结论**：2026-10-25 独立 change `consolidate-marketing-promotion-domain` 规划将推广迁入营销域，统一内容修订、发布计划和平台尝试。外部发布保留原 attempt 与 UNKNOWN 对账，本地事务只确认本地受理；效果区分真实、模拟和未知来源，真实 ROI 要求可归属收入/成本，不能按线索数乘固定金额生成收入。

**根因**：当前跨服务状态回写无法随本地事务回滚外部发布；样例统计若缺来源标记，又会被经营诊断或效果看板解释成真实增长。让发布状态由同一领域管理并保留外部回执，能避免双写与重复发布；把来源和指标口径保存为事实，可限制模拟样例的使用含义。

**代码位置（当前与规划）**：当前 `MarketingAssetServiceImpl`、`PromotionManagementServiceImpl` 和 provider dispatcher；拟迁入营销 campaign 包，保留媒体 Worker 和原数据库迁移映射。方案见 `openspec/changes/consolidate-marketing-promotion-domain/`；尚未实施，KI-167 仍为 OPEN。

**关联**：LR-22/23/53/94/96；KI-114、KI-167。

## LR-106 同run刷新只收缩许可，发布新鲜度与业务执行身份分开

**结论**：独立refreshAuthorization使用真实access、原签名context和本次服务proof更新permissionRevision，保持actor/run/session/audience/policy以及原hard deadline；credentialEpoch变更必须新认证续接。同运行许可只收缩，已接受窄scope后旧宽context再授权限也不能恢复该运行许可。签名freshness每30秒以内按真实MySQL读取开始时刻重核，Auth发布outbox/current-version与Redis原子fence，消息只失效不授予；有效本地context检查不逐工具请求Auth。

**根因**：重新入口授权会重置截止，旧context回放会扩大已经收缩的范围，单纯修改Redis副本又无法更新消费者本地状态。分离稳定snapshot、当前运行scope和签名新鲜证明，并保持单调版本/epoch及不可延长时限，能在容许传播窗内复用且拒绝陈旧放行。MySQL注销PENDING摘要先阻重入，Redis外部revoke失败可按同摘要重试，不加第二次epoch；canonical token编码防原串别名绕撤销。账号写入不能只带actorId，密码计算后的锁内仍需实际凭据复验。

**单飞边界**：不同run可以共享同一actor/revision的稳定snapshot与head读取；签名refresh绑定原context、run/session和本次requestId/nonce/body，只有同一个已绑定执行的并发刷新可以合并，不能把一个run的Auth响应复用于同用户其他session。

**代码位置**：AuthAuthorizationRpcServiceImpl.refreshAuthorization、AuthzCanonicalHash.refreshRequestHash、AuthzSignature.verifyRefreshBinding、AuthorizationSession.refresh、AuthTokenService、AuthAccountMutationService、AuthorizationOutboxPublisher、RedisActiveAuthorizationActors和AuthorizationVersionMonitor。pub/sub/对账实现是基础，Gateway/Agent已验字节原型的signed run/session仍null，实际执行标签接入必须补KI-170而不能consumer填入签名或省资源owner检查；header最大256KiB也不等于部署预算已配置。

**关联**：LR-103/104、KI-169/170/171；本轮276项定向、1519项全回归（14跳过）、真实隔离MySQL22+Redis62项检查；签名/故障fixture与真实存储规则证据分开，不代表新协议已部署。

**多角色响应补齐**：AuthTokenDTO保留旧7参，UserItem/CurrentUser保留旧8/6参；新businessAdmin、roles、revision/epoch来自同一次SQL授权事实读取，legacy默认不从roleCode推新权限。新版用户role筛选在SQL分页前按有效关系EXISTS，count与page使用相同谓词/时刻，次角色可见不重复行。login/refresh/me的授权信息是客户端展示数据，不是可用于委托的签名凭据，token payload仍只有原三字段。新增5项真实H2/MyBatis/序列化回归，全23模块1524项失败/错误0、14跳过；actual run关联仍受KI-170阻塞。
## LR-105 四个 Change 以固定契约独立验收，合并后再核验真实互通（规划）

**结论**：房源、知识、经营和营销四个 change 从共同基线使用独立 worktree/分支、Maven仓库及测试资源。生产者和消费者遵循 `domain-parallel-v1`；经营/营销持有自己的wire DTO和实际HTTP适配，用自有fixture验证，不依赖其它change新增Java制品。所有本期任务可分别完成，真实服务互通、Auth/Memory在线登记、初始化allowlist和共享环境切换由协调会话在合并后核验。

**根因**：把计划放进四个目录仍可能在完成条件、Java依赖、SNAPSHOT缓存、同一checkout和在线配置上相互等待或覆盖。固定线协议、分配修改范围和独立资源，才能让四会话各自构建验收；真实模式未接线时仍失败关闭，不能通过模拟夹具伪称生产已接通。共享文件允许各分支提交最小语义差异，最终合并由协调核对其并集，而非声称永远无文本冲突。

**代码位置（当前与规划）**：四个change既有proposal/design/specs/tasks及 `docs/business-domain-change-roadmap.md`；规划要求未来实现各自来源/消费适配及隔离测试。本次只修订规划，没有创建四个会话或worktree、实施代码、修改运行配置或执行真实联调。

**关联**：LR-22/23/53/59/94/96/97/99/100/101/102；现有KI状态不因并行规划而改变。

## LR-130 发布计划必须绑定不可变内容和目标快照

**结论**：prepare 将内容 revision/hash、房源快照、素材 hash、目标账号 revision、平台参数 hash 和规则版本固化到计划快照；confirm 要求 expectedPlanVersion/hash 匹配，并重新检查内容审核、素材、房源和账号当前版本。计划过期后必须重新 prepare。

**根因**：用户确认的是某一具体内容、素材和发布范围。确认后任一输入变化都不能沿用旧批准，否则回执与用户看到的计划不一致。

## LR-131 发布身份和渠道账号授权由受信服务与登记记录决定

**结论**：当前 campaign 兼容路径从 Auth 解析 actor 并逐动作调用 `AuthPermissionRpcService`；计划只能引用营销库内已登记且 active、平台匹配、模式匹配的账号 grant。调用方不能凭请求里的账号名称授予自己发布权。Auth RPC 缺失或失败时拒绝 campaign 操作。该兼容调用不是 Auth 版本化签名上下文或受限委托的证明；营销内容 CRUD、MCP 和旧 Dubbo 资源授权尚未完成，必须由 `unify-versioned-permission-context` task 10.3 准入后方可声称全域授权完成。

**根因**：账号名和客户端请求字段不构成授权证据；营销域也不能在 Auth 未登记新权限时自行放行。

## LR-132 外部发布走持久 outbox，未知结果只按原 attempt 核对

**结论**：本地事务只生成 ACCEPTED 回执、稳定每目标 attempt 和 outbox；dispatcher 在事务外调 provider，并以 attemptVersion 和 leaseToken 条件回写。租约过期将原 attempt 置为 OUTCOME_UNKNOWN，进入同 attempt 核对，不自动创建第二个发布。

**根因**：数据库事务不能撤销平台已接受的发布；超时或进程中断后重新发送可能造成重复发布，迟到响应也不能覆盖较新的核对结果。

## LR-133 效果观察按来源事件与版本幂等保存

**结论**：每条效果记录包含来源系统、来源事件 ID/version、窗口、采集时刻、来源模式和 payload hash；同版本重放不重复计数，更高版本修订当前观察并追加 revision，较低版本不能回滚新值。缺来源的结果保持 UNKNOWN。

**根因**：渠道可能重复投递或迟到修正；仅按发布记录或日期累计会重复计算，缺字段也不能用样例补齐为真实值。

## LR-134 真实 ROI 需要同币种归属收入、成本和定义版本

**结论**：真实效果报告只有在记录具备可归属收入、成本、相同币种和相同 ROI 定义版本时才计算 ROI；缺收入时可报告已知线索成本，ROI 保持 null。模拟 fixture 不生成收入。

**根因**：线索数不是收入，跨币种或跨口径相加也不是可解释的投资回报；估算值如果进入 REAL 字段会误导经营结论。

## LR-135 内容创建默认草稿，审核和发布状态拥有明确写入口

**结论**：内容创建/变体写入强制 DRAFT；所有文案生成入口只能生成/保存草稿并准备计划，不能确认发布或发送通知。审核状态仅可由同域审核入口写入；该入口必须通过兼容审核授权，并在 Auth v2 准入后核验 `marketing.content.review.own/all` 精确操作和内容 owner；Supervisor 的营销发布技能可调用 campaign confirm，但该 capability 必须经过 `approval-capabilities` 审批；发布状态由 campaign provider 回执维护。旧内容 publish-status HTTP/RPC 写入返回 `LEGACY_WRITE_DISABLED`。

**根因**：内容正文的作者提交不等于审核通过，旧跨域回写也不能继续成为第二个发布状态真源。

## LR-136 旧业务 ID 必须带来源和实体命名空间

**结论**：旧 ID 通过 `(source_system, entity_type, legacy_id)` 映射到新营销目标身份；目标唯一性按实体类型和目标表约束。新 schema 可重复升级，但不直接合并两个库的自增值，也不在本 change 改写已应用初始化清单。

**根因**：不同旧库和实体类型可能有相同数字 ID；未核对旧数据就删除旧表或复用 ID 会破坏历史回执、素材和效果关联。

## LR-137 房源依赖使用固定公开契约，测试 fixture 不作为真实回退

**结论**：营销自有 `CampaignListingSnapshotClient` 按 `domain-parallel-v1` 调用公开 batch-read 路径，并校验来源身份、revision、hash、时间、数据集和 REAL 模式。SIMULATED 时使用显式本地房源 fixture；REAL 来源故障时失败关闭。

**根因**：独立 change 不能依赖房源分支尚未合入的 Java 类型，也不能让真实流量在来源不可用时静默读取测试房源。

## LR-138 入口授权与持久执行身份分两步绑定

**结论**：Gateway不能预猜Supervisor最终持久化的run/session。Agent消费Gateway签发的未绑定入口后，先用持久owner、request幂等和session事实确定实际执行tuple，再调用独立`BIND_EXECUTION`；Auth将完整入口hash唯一绑定到actor/run/session/operations/policy/executionHash。新nonce重试仅对同tuple幂等，不能换身份、扩大scope或延长入口硬截止。refresh不承担身份绑定，Agent也不重签或覆盖原入口证明。直接Agent HTTP缺少完整入口证明时，以Agent服务身份对token与实际method/path/query/body发起独立Auth resolve；不得从固定marker恢复身份。

**根因**：Gateway位于JSON解析及持久run创建之前，`requestId`、`continueRunId`和`sessionId`尚不能代表最终执行资源。把这些字段提前写进Gateway签名会产生错误关联；由consumer填字段或重签会破坏原始请求证明。每个Graph节点或子Agent重复调用执行绑定又会把控制流复杂度和失败面放大。独立绑定把“谁能做什么”与“本次落在哪个已核验执行资源”分开，只在Supervisor执行入口增加一次`BIND_EXECUTION`；业务权限RPC和资源守卫迁移至本地版本上下文仍由任务5.x/6.x验收。

**代码位置**：`common/authz/ExecutionBinding`、`AuthzSignature.verifyExecutionBinding`、`AuthAuthorizationRpcServiceImpl.bindExecution`、`AuthorizationRepository.bindIngressExecution`、`agent-service/AgentExecutionBindingCoordinator`、`AgentRagCredentialFilter`、`OrchestrationStore.executionHash`。入口原始三签保留在请求scope，绑定context不写入模型输入或checkpoint。

**关联**：LR-104/106；KI-170；change `unify-versioned-permission-context`任务4.4。功能开关默认关闭；Gateway启用环境路由/预算、Graph真实模型与工具执行、A2A及异步跨实例重启恢复仍需独立验收。

## LR-139 Agent能力用稳定映射本地授权，Graph只在重规划刷新

**结论**：Supervisor目录的权限码必须来自可信配置的稳定CapabilityId映射：A2A按agentId、MCP按server+tool、本地工具按tool；不得由Agent Card的domain首项、LLM参数、server级可读权限或统一chat权限推断。缺映射时能力隐藏且执行入口拒绝。映射值仍必须精确存在于当前Auth签名权限集合中，管理员也不获得通配动作。每个业务Model重规划只检查一次签名head；同版复用本run会话，版本变化后才按同run/session签名refresh并刷新能力目录。Graph节点和工具回调仅使用已验证的本地会话，不逐次请求Auth。

**根因**：Agent Card和工具参数表达目标能力，不是授权事实；首domain不能代表多域Agent的唯一操作，server可读也不证明对其中每个MCP工具具有调用权。若改成每个节点或工具都访问Auth，会扩大依赖失败面并把模型规划轮次变成远程身份校验次数。稳定映射加Auth签名操作交集使身份/授权来源确定，head/refresh仅发生在规划边界，同时保留权限变更后的当前授权。

**代码位置**：`AgentAuthorizationProperties.capabilityPermissions`、`AgentPermissionService`、`SupervisorCapabilityCatalog`、`SupervisorToolLoopGraph.checkReplanningAuthorization`、`AgentAuthorizationConsumer.checkPlanning`、`AuthorizationVersionMonitor.checkPlanning`、`AuthorizationSession.refreshSingleFlight`、`SupervisorGovernanceService.auditAccess`。版本化模式下治理审计也从当前签名context记录actor、permissionRevision和credentialEpoch；未登记映射隐藏，不从管理员身份或模型生成内容补齐。

**关联**：LR-103/106/138；KI-170；change `unify-versioned-permission-context`任务5.1–5.7。Agent本地consumer、owner边界和治理审计已有源码及定向回归；生产启用和跨实例恢复不由此推定。

## LR-140 A2A 复用 Auth 绑定上下文并由 Agent 逐请求证明

**结论**：Supervisor 的已绑定 Auth 签名 context/freshness 是同一 run 内 Agent 调度的身份和操作上限。Agent 通过稳定 `CapabilityId` 取精确操作码，为每个 A2A JSON-RPC 请求重新生成 `DelegationBinding`，绑定实际序列化 body、caller、audience、actor、run/task、requestId、nonce 与短截止，再以 Agent 服务 key 签署请求 proof。接收端在官方 TaskStore/Queue 前本地验 Auth context/freshness、Agent proof、请求方法/ID/task/body/目标/操作/期限及已有 owner；A2A send/stream/control 与 SSE 不因每个边界向 Auth 远程验证。Graph 规划发现版本 DIRTY 或 freshness 需更新时，才由 Supervisor/Auth 刷新；刷新失败不得签发新请求。用户身份只来自已验证 context，模型生成的 `userId`、role 或 admin 声明不作 actor。

**根因**：Supervisor 已在入口持有经 Auth 签发且绑定真实 run/session 的有限上下文。重新为每次 A2A 派发向 Auth resolve/issue 会把 Graph 调度次数变成认证依赖次数；从 tool 参数或 structuredContext 读取 actor 又允许模型内容覆盖登录身份。逐请求 body proof 保留完整性和调用服务认证，本地验签减少远程依赖；权限刷新仍沿已绑定运行边界进行，不把本地 proof 解释为可扩权 bearer。跨实例/重启任务 owner 与 nonce 持久化独立由 KI-170/任务6.2验收，Memory/RAG 仍需其各自权限与资源迁移。

**代码位置**：`common/authz/AuthzWireCodec`、`common-a2a/A2aAuthorizationVerifier`、`A2aAuthenticatedRequestGuard`、`BeaconA2aHttpClient`、`A2aCredentialContext`、`agent-service/OfficialA2aAgentClient`、`AgentPermissionService`。新协议开关默认关闭；测试证明本地传输/验签不代表生产密钥登记、跨实例恢复或未迁移 RAG/Memory 已可启用。

**关联**：LR-41/106/138/139；KI-59/170；change `unify-versioned-permission-context`任务6.1–6.3。

## LR-141 A2A任务owner耐久保存，执行lease不耐久恢复

**结论**：版本化A2A的request nonce、task owner tuple和在途预留必须写共享数据库；owner tuple至少绑定taskId、actor、caller、A2A context、Auth run与session。get/cancel/resubscribe只对已存在且完整匹配的owner记录通过，不能凭taskId创建owner或恢复身份。跨实例在途预留按request nonce更新/释放，只有原请求转发和执行命令都结束才释放；崩溃遗留预留不超过签名context/freshness的硬截止。执行lease仍留在进程内存，重启后必须用新鲜可信上下文重新认证；没有SDK任务/队列记录时返回not-found，不合成task。

**根因**：单机Map无法阻止另一实例重放同nonce、抢占同taskId或并发发送。若把整个执行lease序列化到task记录，又会使taskId变成重新取得身份/授权的恢复凭据。只持久化最小owner/replay/预留事实可以跨实例守住归属和互斥，同时让线程scope、Graph lease与SDK队列仍有明确进程边界。当前SDK的TaskStore/QueueManager仍是InMemory实现，因此耐久owner只保证安全拒绝与重认证，不承诺重启恢复任务内容或流队列。

**代码位置**：`common-a2a/A2aTaskAuthorizationStore`、`JdbcA2aTaskAuthorizationStore`、`A2aAuthorizationVerifier`、`A2aAuthenticatedRequestGuard`、`A2aCredentialContext`；schema `sql/migrations/20261026_a2a_task_authorization.sql`。版本化模式启动时要求耐久store及schema，运行开关默认关闭。

**关联**：LR-41/140；KI-170/172；change `unify-versioned-permission-context`任务6.2。

## LR-142 Memory复用签名身份，但每个执行边界核当前来源

**结论**：Memory v3请求proof携带Auth签发且已绑定run/session的`AuthorizationContext`与`AuthzFreshness`，调用服务对每个Memory RPC的实际body、owner/context、nonce和期限签名；Memory本地验证调用服务proof及Auth签名上下文、actor、版本/credential epoch/hash、ACTIVE状态、`agent.chat.use`和硬截止，启用后不为每个内部请求调用Auth。权限上下文只证明身份和粗操作许可，不能替代owner/domain/agent/context、共享grant、purpose/recipient、来源revision/hash/撤回/删除、writer fence、permit及最终结果真源检查。A2A Agent在prompt载入时冻结来源依赖集；后续模型、工具和最终输出边界重核该集合，不能把刷新后的工作集用于认可已注入旧prompt。

**根因**：为每个Memory操作或子Agent边界重新远程验证Auth会把Graph调度次数变成身份服务依赖次数；只缓存来源授权又会在来源被撤回、而账号权限版本未变时继续使用旧正文。把已绑定的Auth证据放进逐请求body证明，允许服务本地验证而不信LLM提供的userId；把Memory资源依赖和账号权限分开，来源变化仍由领域真源及时否决。旧`session.check()`可刷新当前工作集，但不能改变已注入prompt，因此活跃轮次必须检查原冻结依赖，而不是采用新快照。

**代码位置**：`common/memory/MemoryRequestProof`、`MemoryRequestSigner`、`AuthzCanonicalHash`；`common-memory/MemoryRpcClient`；`memory-service/MemoryV2RequestSecurity`与`MemoryV2SecurityProperties`；`common-a2a/A2aMemoryAgentHook`、`A2aMemoryInvocation`；`common-memory/MemoryWorkingSession.sourcesCurrent`。

**关联**：LR-40/63/89/98/140/141；KI-133/173；change `unify-versioned-permission-context`任务6.3/6.4。Memory签名上下文模式默认关闭；开启前需配置Auth公钥并完成部署验收。

## LR-143 MCP逐请求proof本地验签，actor来自已绑定run

**结论**：版本化Supervisor只从当前有效的已绑定Auth run/session取得context与freshness；Agent专用MCP transport为每个`tools/call`签发新的短期body绑定和服务proof，覆盖JSON-RPC request id、实际正文、服务audience和已登记tool操作。MCP服务在分发前本地验证Auth签名、Agent签名、caller、actor、run/session、期限与nonce，不逐工具调用Auth/Redis。普通tool参数中的`userId`或admin声明不定义actor；MCP业务adapter仍须使用`McpAuthorizationScope`中的可信actor执行资源/owner检查。外部MCP只有被可信清单明确列为无私有数据的能力才可在无用户委托下调用。进程内nonce防重放仅覆盖单个服务实例，跨实例/重启的重复副作用受KI-174跟踪。

**根因**：Graph一次运行内会多次调度subAgent和MCP tool，重复远程鉴权会把正常编排耦合到Auth/Redis每次可用性；直接复用context而不重新绑定真实JSON-RPC正文又会允许请求重放或改参数。Auth签名父上下文、Agent逐请求proof、接收端本地验签把身份来源和请求完整性固定，同时保留规划刷新边界。权限码只说明允许的操作，不能代替业务资源归属核查。

**代码位置**：`common/mcp/authz/McpAuthorizationContract`、`McpAuthorizationFilter`、`McpAuthorizationScope`、`McpAuthorizationReplayGuard`；`agent-service/AgentMcpAuthorizationTransportCustomizer`、`AgentMcpAuthorizationSupport`、`SupervisorCapabilityCatalog`；`notification-service/NotificationMcpTools`的本人消息查询从可信actor解析，不采纳模型传入的其他userId；Interview MCP provider仅在本地验证guard启用时注册。新开关和操作映射默认关闭，未声明生产MCP部署已启用。

**关联**：LR-106/139/140；KI-174；change `unify-versioned-permission-context`任务6.5。

## LR-144 RAG保留域HMAC并本地消费Auth签名权限上下文

**结论**：版本化RAG scope保留既有独立owner-domain HMAC，并在HMAC覆盖范围内嵌入且只能嵌入一种可信Auth证据：Auth签名的`AuthRagPrincipal`，或当前Supervisor run/session绑定的Authz snapshot/context/freshness。Agent对实际RAG请求签scope/body hash，版本化消费时不传用户bearer；RAG本地核验两层签名、actor、授权版本/credential epoch、ACTIVE与freshness/硬截止、精确操作和空间许可，不对每次召回请求Auth。权限版本或新鲜度需要更新时沿原Supervisor规划刷新边界联系Auth并为后续请求签发新scope。房源scope的`businessWideRead`与`activeOnly`须和可信businessAdmin及显式权限一致；普通用户房源范围仅ON_SALE；个人Memory总是self-only，管理员不扩展owner。RAG操作许可不代替领域资源、owner、删除、grant或来源真源校验。未知范围、过期/缺失证明、签名不匹配和旧弱scope在版本化模式拒绝。

**根因**：Auth证明账号身份和操作集合，不能替代RAG域对owner、空间和资源状态的约束；RAG HMAC证明scope由登记的领域消费者签发，也不能替代Auth的用户授权。嵌套证据并由外层HMAC一起绑定，避免序列化/传输时将两类事实拆开或替换。Graph一次run可能多次检索，逐次远程调用Auth会把编排频次转成身份服务可用性依赖；逐请求正文/范围签名和本地Auth验签保留请求完整性，同时在授权刷新边界更新权限，不信任模型生成的用户身份。出站网络目标仍由已有受保护transport检查，不因鉴权迁移增加任意URL回调。

**代码位置**：`common/rag/AuthRagPrincipal`、`RagAuthorizationScope`、`RagCanonicalHash`、`RagScopeSigner`、`RagRequestSigner`；`agent-service/AgentRagRequestAuthentication`、`AgentMemoryMilvusService`；`auth-service/RagPrincipalRpcServiceImpl`；`listing-master-service/ListingRagIdentity`、`ListingRagGrantService`；`memory-service/MemoryRagSecurity`、`MemoryRagSearchFacade`；`rag-service/RagAccessControl`、`RagSecurityProperties`。

**关联**：LR-98/139/140/142；KI-57；change `unify-versioned-permission-context`任务6.6/6.7。版本化RAG开关默认关闭；本地回归不代表Auth公钥、部署密钥和生产调用链已验收。

## LR-145 请求级签名上下文防止模型替换 actor，业务资源仍由接收域校验

**结论**：外部入口由 Gateway 对登录身份与实际 method/path/query/body 签署 Auth 上下文；已绑定 run 内部的 A2A/MCP 请求携带该上下文/freshness，并由调用服务对每个真实 JSON-RPC 正文和 nonce 生成逐请求 proof。接收端本地验签 actor、caller、audience、操作、请求内容和截止，不为每个 subAgent/tool/流项远程请求 Auth；Auth 刷新只发生在 Supervisor 规划边界。模型参数中生成的 userId、role、admin、permissions 均不是身份来源。签名许可仍不能替代接收域对 owner、参与关系、资源来源、业务状态和撤回版本的核查。

**根因**：在 Graph 内逐工具访问 Auth 会把模型编排次数放大成身份服务依赖次数，并让普通下游故障扩大为整条请求失败；仅传递一个已签父上下文而不绑定真实请求又允许调用方篡改 body 或重放。父上下文提供不可替换身份上限，逐请求 proof 提供实际请求完整性，本地验签减少同步 RPC；领域资源检查继续维护各业务的真实授权边界。

**代码位置**：`common/authz/AuthzWireCodec`、`common-a2a/A2aAuthorizationVerifier`、`common/mcp/authz/McpAuthorizationFilter`、`agent-service/SupervisorToolLoopGraph`、`InterviewAuthorizationFilter` 与 `InterviewAuthorizationScope`。

**关联**：LR-1/139/140/142/143/144；KI-170/174/202/203；change `unify-versioned-permission-context`任务5–9。各新消费开关默认关闭；本地验签和模块测试不能替代跨实例nonce、真实浏览器、真实MySQL/Redis或部署启用验收。

## LR-146 Auth维护显式broker映射，公开房源范围与私有归属分开

**结论**：Auth维护类型化且唯一的`userId→brokerId`映射，并将其纳入授权快照、请求上下文签名与版本失效；不从相同数字、登录角色、请求体或模型参数推断业务身份。Customer档案及其跟进、委托、收藏等子记录按映射brokerId限于本人；房源创建、变更、删除和含业主/证件/合同字段的详情按映射brokerId限于本人。`ListingDTO`与RAG公开摘要继续保持既有范围：经纪人只看未删除且ON_SALE的房源，可跨broker浏览；业务管理员使用显式`listing.read.all`业务范围查看全部状态。管理员没有broker映射时不生成虚构brokerId，因此不能以管理员身份新建个人归属数据。Customer调用Listing时转发原Auth签名响应与绑定该响应摘要及精确房源查询的服务请求证明；Listing本地验签、核对`listing.read`和房源状态，不逐次回Auth，也不转发用户Bearer。

**根因**：brokerId是业务库主体键，不等于平台userId，模型或请求中的同名字段不能证明归属。房源摘要是既有在售业务检索面，不能因为新增身份映射而改变LR-31的状态范围；详情响应含证件及合同资料，写操作也会改变主体资源，必须再验证资源归属。请求级Auth上下文让Listing/Customer在入口本地验签并复用主体映射，RAG继续独立检查签名范围和房源状态。

**代码位置**：`common/authz/AuthzContract`、`AuthorizationSnapshot`、`AuthorizationContext`、`AuthzRequestScope`、`AuthzCanonicalHash`；`common/rag/RagAuthorizedListingReadRequest`；`auth-service/AuthorizationRepository`、`RoleAuthorizationManagementService`、`AuthAuthorizationRpcServiceImpl`；`listing-master-service/ListingAuthorizationIngressFilter`、`ListingManagementServiceImpl`、`ListingRagIdentity`、`ListingMasterRpcServiceImpl`；`customer-service/CustomerAuthorizationIngressFilter`、`CustomerManagementServiceImpl`、`ListingReadClient`；`rag-service/RagAccessControl`。

**关联**：LR-31/145；KI-55；change `unify-versioned-permission-context`任务4.6、10.1。Listing/Customer新入口开关默认关闭；跨服务RPC调用、Gateway与各环境公钥配置、业务映射回填及运行栈验收完成前不得启用。

## LR-147 Business与Settlement使用精确域操作，未映射财务主体保持关闭

**结论**：Business分析方法在REST、Dubbo、A2A与MCP共享服务入口检查各自的精确`business.*.all`操作；宽域分析只有当前签名授权显式包含对应操作时才执行。Settlement读取、规则维护、结算计算、分账、汇总读取/重建、批次和状态动作分别登记操作码。Auth上下文在REST、MCP与A2A请求线程本地复用，不逐工具访问Auth；不具备可信上下文的Dubbo/RPC及后台任务不切为SERVICE全权。尚无userId→employee/store/payee映射或签名Contract调用时，结算计算、合同自动生成、批次、状态确认及未经签名的银行回调均在读写数据或跨服务调用前拒绝；旧“已付款”模拟不会继续写PAID。

**根因**：Auth目前仅维护显式userId→brokerId映射；Business数据使用员工/门店标识，Settlement还依赖合同和收款员工标识，不能把brokerId、userId或LLM字段互相转换。即使权限码允许某项业务动作，它仍不能证明目标员工和合同的关系；SERVICE也不能因定时任务/内网RPC身份获得任意用户数据。将已实现操作独立授权、未知关联和真实支付硬拒绝，可以在不增加逐节点远程Auth调用的前提下保留入口级授权，并明确剩余数据关系。

**代码位置**：`common-a2a/VerifiedDomainAuthorization`、`A2aCredentialContext`、`common/mcp/authz/McpAuthorizationScope`；`business-service/BusinessAuthorizationIngressFilter`、`BusinessAnalyticsServiceImpl`；`settlement-service/SettlementAuthorizationIngressFilter`、`SettlementManagementServiceImpl`、`SettlementRpcServiceImpl`、`SettlementController`、`SettlementAutoGenerateJob`；迁移`20261026_business_settlement_authorization.sql`。

**关联**：LR-138/139/140/145/146；KI-204；change `unify-versioned-permission-context`任务10.2。REST ingress、A2A版本化委托及MCP工具映射保持默认关闭/空配置；定向fixture回归不代表员工关系、跨域RPC proof、真实支付或部署启用已验收。

## LR-148 Marketing的A2A房源摘要读取复用run context并逐请求证明

**结论**：Marketing在版本化A2A lease中读取房源时，转发Auth签名的`AuthorizationContext`与`AuthzFreshness`，并为实际单房源摘要RPC生成服务HMAC proof；proof绑定完整序列化请求及两项签名证据。Listing按原Auth context受众`agent-service`本地验证context与freshness签名，再验证Marketing caller HMAC、actor/revision/epoch/hash、run/session及硬截止；随后检查精确`listing.read`或显式`listing.read.all`，只按`ListingDTO`公开摘要返回未删除的在售房源，显式全读操作可读取全部状态。此路径不转发用户Bearer、不把LLM字段当actor、不逐次调用Auth，且由Listing授权开关控制并默认关闭。

**根因**：A2A版本化lease没有legacy `RagCredentialContext`，因此Marketing旧的Listing RPC在同一Graph运行内会因为缺少Bearer/beacon而失败关闭。把父context/freshness直接交给Listing验签并用调用方HMAC绑定真实子请求，可以继承不可替换的run身份与原授权上限，同时保留Listing的房源状态真源。不得伪造完整`AuthzResponse`/管理员快照来填补DTO，也不能用模型生成的用户字段或重新查询Auth绕过请求级协议。

**代码位置**：`RagContextAuthorizedListingReadRequest`、`RagCanonicalHash.requestHash`、`ListingMasterRpcService.readAuthorizedContext`、`ListingRagIdentity.verifyAuthorizedReadContext`、`MarketingListingReadClient.getWithVersionedContext`。

**关联**：LR-140/145/146；KI-205；change `unify-versioned-permission-context`任务6.1/10.3。该A2A读取路径仅源码/定向回归验证；Listing/Marketing密钥、生产调用链与数据库迁移尚未部署或启用。

## LR-149 Compare报告与Media后台生成继承签名身份并继续校验资源owner

**结论**：原独立 Compare 在每次生成时以已验签 Auth actor 读取当前 Listing 快照，再按 actor+比较指纹复用缓存；来源版本 hash 对应报告内的不可变 Listing DTO 快照。此处的 Compare 分享规则是迁域前背景，当前 Listing 行为见 LR-99：`compare.report.create` / `compare.report.read.own` 是签名基线动作标识，不是角色权限；`compare.report.share.public` 不迁入 Listing。Media 任务在提交前要求 `media.task.submit.own/all`、`media.generate.background` 及 Listing `listing.assets.bind.own/all`，Listing 本地验 Auth context 或 response、调用方 HMAC 和 broker 映射后核实目标行归属。签名后台 permit 再绑定 taskId、精确请求 hash、actor/broker/listing、Auth context/freshness、服务 key 与截止；worker 本地验签、原子领取，过期排队任务终结，不逐节点查询 Auth。未建立可验证的 Marketing 素材关联，因此拒绝 `contentId` 任务。

**根因**：Compare shareCode和Media taskId都是定位符，本身不证明用户；缓存若只按输入ID命中会把其他用户报告或过期来源混给当前请求。后台任务跨越原请求线程后也不能把MQ正文、callerService或任务编号当身份。将用户签名上下文限制到精确资源读取/后台操作，并保留Listing/缓存owner真源，可避免模型字段替换身份且不把每次Graph内部动作变成Auth RPC。公开分享是发布人主动改变范围，必须使用独立许可。

**代码位置**：`compare-engine-service/CompareListingReadClient`、`CompareAnalysisServiceImpl`、`CompareReportCacheServiceImpl`、`CompareAuthorizationIngressFilter`；`common/rag/RagAuthorizedListingAssetWriteRequest`、`RagContextAuthorizedListingAssetWriteRequest`；`listing-master-service/ListingMasterRpcServiceImpl.authorizeAssetWrite*`；`media-worker-service/MediaListingAuthorizationClient`、`MediaBackgroundPermitSigner`、`MediaGenerationTaskServiceImpl`、`MediaTaskStoreServiceImpl`。

**关联**：LR-145/146/148；KI-115/117；change `unify-versioned-permission-context`任务10.4。新操作只登记、无推断角色授予，REST/Listing授权默认关闭；Media任务状态仍为进程内存，重启恢复未验且未实现。

## LR-150 Notification actor与recipient分离并按操作边界授权

**结论**：Notification中当前登录/委托用户只从本地已验证Auth context读取。REST兼容字段`userId`仅能作为可选一致性断言，查询始终使用actor；Agent/MCP本人消息查询不暴露该参数。标读先查消息recipient必须等于actor。站内本人发送和向他人发送分别要求`notification.message.send.own`与`.send.all`；邮件、机器人、事件消费审计查询使用独立显式操作，角色或Memory caller登记都不替代recipient范围。Supervisor工作流事件由服务端从已绑定run写入actor和run metadata，Notification仅消费事件白名单并检查recipient与执行关联。旧Dubbo通知签名没有请求证明，不能安全推断actor或目标，因此关闭；Contract/Customer定时任务不能把brokerId当userId，保持停用，见KI-206。

**根因**：用户是消息动作的主体，recipient是消息目标。共用`userId`字段名会把目标错当主体；而同号broker/user数据也不形成映射。把本人读取、本人发送、跨用户发送和外部通道分成精确操作，才能避免模型改写主体或在工具之间继承过宽权限。一次run内部继续验证本地已签名请求上下文，权限刷新留在Supervisor/Auth边界，不为每条消息调用Auth。

**代码位置**：`notification-service/NotificationController`、`NotificationManagementServiceImpl`、`NotificationTools`、`NotificationMcpTools`、`NotificationWorkflowEventService`、`NotificationRpcServiceImpl`；`contract-service/ContractReminderServiceImpl`、`customer-service/CustomerReminderServiceImpl`；权限迁移`20261026_notification_authorization.sql`；契约`docs/notification-api-auth-contract.md`。回归覆盖actor查询/目标篡改、消息owner、精确发送操作、服务端事件recipient、旧RPC拒绝与提醒关闭。

**关联**：LR-103/106/143；KI-151/206；change `unify-versioned-permission-context`任务10.5/10.6。HTTP和MCP验证开关默认关闭；SQL权限码登记不授予普通角色，也不证明生产密钥或调用链已启用。

## LR-151 A2A耐久授权状态随provider数据库发布，Auth执行绑定留在Auth库

**结论**：Auth 的 `auth_authorization_execution_binding` 属于 `bk_auth`；A2A nonce、持久 task owner 和在途 reservation 属于实际处理该 A2A 请求的 provider 数据库。相同的 A2A 状态迁移分别登记到有持久 DataSource 的 Interview、Listing、Marketing、Notification、Business、Contract 与 Settlement 数据库。Compare 与 Media 当前没有受管关系型 DataSource，因此版本化 A2A 模式必须保持关闭，直到耐久状态存储被接入和验收。所有授权 schema 迁移只前向应用，应用制品回滚不删除授权事实。

**根因**：`JdbcA2aTaskAuthorizationStore` 使用 provider 自己的 DataSource 来实现 nonce 防重放、task owner 约束与请求 reservation；把这些表集中到 Auth 或 Agent 数据库会新增跨服务存储依赖，并不能证明 provider 本地队列副作用前已经完成同一事务边界。Auth 执行绑定则由 Auth repository 在 `bk_auth` 持久化。按服务数据库重复应用同一幂等 SQL，使同一 provider 的多实例共享授权事实；没有耐久数据库的服务不允许退回进程内 Map。

**代码位置**：`sql/migrations/20261026_authorization_execution_binding.sql`、`sql/migrations/20261026_a2a_task_authorization.sql`；`docker/mysql/init-manifest.tsv`与对应 schema checks；`scripts/deploy/run-init-manifest.sh`；`common-a2a/JdbcA2aTaskAuthorizationStore`；`auth-service/AuthorizationRepository`；`docs/authz-versioned-rollout-rollback.md`。

**关联**：LR-41/141/145；KI-170/172/208；change `unify-versioned-permission-context`任务6.2/11.6/11.7。manifest 静态验证不表示真实数据库迁移、应用回滚或多实例验收已完成。

## LR-152 版本化鉴权秘密只经 Compose 注入，Nacos仅保存占位符

**结论**：Auth/Agent 新授权开关分别使用 `AUTH_AUTHORIZATION_ENABLED` 与 `AGENT_AUTHORIZATION_ENABLED`，默认均为false。Compose仅把Auth Redis writer密码传给Auth、reader密码传给Agent，并把TLS设置传给两者；Nacos只保留环境变量占位符，绝不保存密码明文。开发栈普通业务Redis继续使用6379；版本化授权读写使用独立TLS端口6380、独立JVM信任库与Git忽略的持久ACL文件，Auth/Agent各自只持有自己的ACL凭据。Auth Redis启用和writer/reader就绪声明仍是独立开关，只有服务端ACL持久化、TLS hostname验证和正反权限验收后才能设为true。Nacos发布成功与容器配置更新是两个不同动作；运行中容器需按部署流程重新创建才会接收新的Compose环境值。

**根因**：Nacos模板是可共享配置，不是容器秘密存储；Compose若不显式透传，`.env`值不会进入应用进程，而把值写进Nacos会扩大凭据读取面。主授权开关、Redis ACL就绪声明和密码各表达独立事实，合并或自动推导会导致不完整部署被误判为安全就绪。专用TLS端口将新增授权传输保护与现有业务Redis客户端隔离，避免让没有TLS配置的既有客户端被隐式切换。

**代码位置**：`docker-compose.yml`的Redis ACL/TLS服务及Auth/Agent environment；两服务 `application.yml` 与 `nacos/auth-service.yaml`、`nacos/agent-service.yaml`；`scripts/security/configure-authorization-redis-acl.sh`、`verify-authorization-redis-acl.sh`；`docs/authorization-redis-acl.md` 与 `docs/authz-versioned-rollout-rollback.md`。

**关联**：KI-209/210；change `unify-versioned-permission-context`任务11.1/11.5/11.7。Redis专用通道与ACL已在本地开发栈验收；Nacos中的 `trusted-callers` 仍为空，服务健康及Redis链路通过不代表浏览器/Gateway到Agent的用户授权调用已完成端到端验收。

## LR-153 Auth授权RPC选择构造器序列化并规范查询集合的wire类型

**结论**：Auth授权provider仅对 `AuthAuthorizationRpcService` 声明Dubbo `fastjson2ctor`序列化及优先协议，不全局切换RPC序列化。`RequestBinding.query` 在签名规范化后存为普通 `ArrayList`，访问器每次返回防御性 `ArrayList` 副本；调用方可修改副本但不能改变原上下文，query仍保留排序、重复项和哈希语义。

**根因**：Auth授权DTO是record且含参数化查询列表，Dubbo默认Fastjson2 JSONB在真实provider调用上不能稳定重建查询集合；改成Hessian会触发Java 17模块反射限制，全局改协议则会影响其他Dubbo服务。使用仓库已有的构造器序列化SPI并固定wire集合类型，把兼容范围限制在Auth授权provider；返回防御性副本则同时维持逻辑不可变性与可序列化类型。

**代码位置**：`auth-service/AuthAuthorizationRpcServiceImpl`；`common/authz/RequestBinding`；`common/serialize/Fastjson2ConstructorSerialization`；`common/authz/AuthzSerializationTest`。

**关联**：KI-211；change `unify-versioned-permission-context`任务4.2/11.5。Dubbo序列化协商取决于provider/export协议声明，见[Apache Dubbo Serialization Protocol Upgrade](https://dubbo.apache.org/en/overview/mannual/java-sdk/reference-manual/serialization/serialization-upgrade/)；全局序列化配置未变。

## LR-114 知识派生通过访谈域独立用途授权跨域

**状态**：这是本 change 的历史设计，已由 LR-116 取代；当前方案以 LR-116 为准。

**结论**：本 change 新增的经营案例、打法、revision、审核/发布事实与任务回执归 `knowledge-service`/`bk_knowledge`；Interview 继续独占 session、turn、transcript/raw+sanitized body、来源版本、报告及报告任务。知识读取逐字稿必须先获得 Interview 独立签发且可撤回的用途授权，绑定精确 `assetId`、snapshot/content hash、内容/脱敏修订、`knowledge_derivation`、唯一 consumer `knowledge-service` 与目标知识 scope。`visibility=PRIVATE|PUBLISHED`、L0–L3 和报告 ID 不替代该 grant；grant 不改变访谈公开状态。知识只保存授权/来源引用和经验证的必要派生材料，不复制 raw transcript 或重用 Interview/报告 ID。知识来源访问继承当前 Auth 签名 context/freshness，由 `knowledge-service` 对完整请求生成服务 proof，Interview 本地验证 proof、来源关系与 grant，不转发用户 Bearer、不逐次调用旧 Auth permission RPC。来源 grant 撤回或精确绑定失效后，知识正文停止读取和输出。

**根因**：Interview 报告、归档等级和公开可见性各自服务于访谈域内用途，不能表达“这个主体允许哪个服务把哪份冻结逐字稿用于哪个知识范围”。把知识事实继续写入 Interview 或复制原稿会造成双重权威；只靠发布状态会将公开读取权扩大成知识派生权；只缓存 grant 则会让撤回后的已发布知识继续输出。独立 grant 把用途授权放回来源真源域，并用精确版本及目标范围限制派生。

**代码位置**：现有来源字段为 `interview-service/InterviewAssetEntity` 与 `InterviewAssetService`；现有用户/案例授权入口为 `InterviewAuthorizationService`、`InterviewAuthorizationScope`；新 grant/provider 规划在 `interview-service`，wire 类型在 `common/interview/transcript/**`，知识依赖和校验在 `knowledge-service`。跨服务签名请求沿用 `A2aCredentialContext.Lease` 的 Auth context/freshness 及 Marketing→Listing `readAuthorizedContext` 模式。

**关联**：LR-3/6/8/59/90/148；KI-15/204；change `extract-knowledge-operations-domain`。本条记录该 change 的目标边界，不表示 Interview grant、knowledge-service 或真实 Auth/Memory 登记已经实现/上线。

## LR-115 来源数据模式必须来自可信 provenance

**结论**：Knowledge 来源 `dataMode` 必须由服务端可信 provenance 提供；当前 `InterviewAssetEntity.source` 仅为 `AUTO|UPLOAD` 且没有 `dataMode` 字段，因此无独立可信来源信息的存量逐字稿返回 `UNKNOWN`。L0–L3、公开状态、自动采集/人工上传都不推断 REAL 或 SIMULATED；仅受控来源元数据或测试夹具能标记 SIMULATED。新增模式描述不改写既有脱敏正文、contentHash 或 snapshotHash；派生 knowledge revision 保留每个来源的模式。

**根因**：`source` 记录资产进入方式，archive grade 记录质量门槛，都不是现实/模拟的证据。把它们当数据模式会把未知样本错误包装成真实经营效果，并污染后续搜索与运营决策；对内容快照哈希混入模式字段也会在没有正文变化时重写来源版本。

**代码位置**：interview-service/InterviewAssetEntity.source/archiveGrade/contentHash/snapshotHash、InterviewCollectionService、InterviewAssetService；当前 Knowledge 来源映射与校验规划在 knowledge-service/InterviewTranscriptSource 和 bk_knowledge 只读视图。

**关联**：LR-3/6/59/114；KI-15；change `extract-knowledge-operations-domain`。当前源码没有持久 provenance，该 change 对存量模式按 UNKNOWN；测试标记不证明生产数据模式。

## LR-116 Knowledge 通过只读视图消费 Interview 当前已发布快照

**结论**：Interview 目前没有返回逐字稿正文的 REST/RPC/MCP API。为满足新增 Knowledge 域且不与并行拆分会话冲突，本 change 不修改 Interview Java、应用配置、源表或迁移，而在 bk_knowledge 建 SQL SECURITY DEFINER 的版本化只读来源视图。该视图仅投影当前 PUBLISHED、L2/L3、脱敏版本达标、未删除且案例/会话关系有效的来源及脱敏 content，不投影 rawContent 或敏感映射；Knowledge 主账号仅获 bk_knowledge 业务表读写权，独立来源账号仅获此视图 SELECT；Compose 应用容器不持有 root 权限。PUBLISHED 是本期唯一现有来源准入信号，用户执行 Knowledge 动作仍需独立通过 Auth 签名上下文、freshness、精确操作和 Knowledge 资源范围。每次使用复核来源状态和 publication/content/sanitization 修订，发布撤回或检查失败时停止输出。

**根因**：Interview 公开来源工具只有元数据，不能满足知识提炼对正文和精确证据的需要；向 Interview 加 grant/provider 会改动同时拆分的旧服务并产生迁移冲突。只读视图把最小数据面放在 Knowledge 自己的 schema 内，使 Knowledge 主账号没有 Interview 表权限，来源账号也不能读取源表或写入；来源访问仅依赖现有显式发布状态。相比直接授予源表列权限，受限视图能在数据库边界排除未发布正文；代价是 Knowledge 需镜像 Interview 当前表结构与公开资格规则，必须通过版本锁定、SQL/check 和资格对照测试发现漂移。

**代码位置**：Interview 权威条件仍在 `interview-service/InterviewAssetMapper.selectPublicAssets`、`InterviewAssetService.publish/revokePublication/isPubliclyEligible/requireUsableSource`、`InterviewAssetEntity` 和 `Sanitizer.VERSION`；本 change 镜像在 `knowledge-service/.../InterviewTranscriptSource`、`sql/migrations/20261027_knowledge_domain.sql` 与 `docker/mysql/checks/knowledge-domain.sql`。SQL view 不投影原文敏感列，Java 双阶段读取绑定精确快照并复核标题/正文凭据形态与正文 hash。该实现通过本地源码与 H2 适配测试，不代表迁移已应用或源库联机验收完成。

**关联**：LR-3/6/8/59/90/115；KI-15；change extract-knowledge-operations-domain。旧 LR-114 所述 Interview 用途 grant 方案已由本条替代，本 change 不增加 Interview grant API 或表。

## LR-117 Knowledge Agent 的 Memory 对话流不得承载 Interview 正文

**结论**：knowledge-agent 使用 A2aMemoryHookFactory 固定为 caller knowledge-service、domain knowledge、agent knowledge-agent，并在 ReactAgent 与 OfficialA2aAgentExecutor 共用同一 hook。现有 hook 在模型/工具执行前加载工作集，完整轮次结束后复核和归档；Memory 配置只允许 Knowledge 公钥与 knowledge: [knowledge-agent] caller/domain/agent 组合，并通过 Dubbo Nacos registry 连接；不授予经验发布或共享 grant。该 hook 会归档用户/助手文本以及工具调用参数和工具响应，因此逐字稿正文、原声 quote、rawContent、敏感映射和完整 Knowledge revision 不得进入 Agent 消息流。正文提炼与证据定位在 Knowledge 服务内部完成，Agent 工具只传 assetId、revision、状态、任务 ID 和受限摘要；以 Memory 哨兵测试验证归档中不含来源正文。

**根因**：Memory 的工作集读取和 A2A 请求归档是 Agent 生命周期的必需部分；只确认 hook 已接入，不能保证来源正文不会沿着通用 tool-call/tool-response 投影写入 Memory。按 agent/domain 隔离并限制消息形状，才能同时复用既有工作集能力与保持逐字稿来源的存储边界。公钥、caller 白名单、Nacos data ID、Compose 环境、.env.example 和 Knowledge 配置模板必须同步；Nacos 仅含密钥占位符，私钥保留在服务独立秘密挂载中。

**代码位置**：`common-a2a/A2aMemoryHookFactory`、`A2aMemoryAgentHook.beforeAgent/afterAgent/project`；本 change 的 `KnowledgeOfficialA2aAgent`、`KnowledgeInputGuardHook`、`KnowledgeAgentTools`、`nacos/memory-service.yaml` trusted callers、Knowledge `application.yml`/Nacos、Compose 与 `.env.example`。本地输入哨兵测试验证正文/原声不能进入 Agent 用户消息；完整 Memory 实际归档哨兵、密钥登记和 Nacos 运行态仍待协调验收。

**关联**：LR-90/94/97/116/152；change extract-knowledge-operations-domain。Memory service Java 和已有 caller 权限不变；本条记录新增 Knowledge 接入约束，不表示 Knowledge caller/key/config 已部署。

## LR-118 v1 案例与对应打法共用一个 Knowledge 聚合身份

**结论**：domain-parallel-v1 将一个经营案例及其对应结构化打法建模为一个 Knowledge 聚合：稳定 `knowledge_id` 同时标识案例元数据与打法，不额外创建可变 `playbook` 表或第二个 `playbook_id`；结构化打法、证据级别和未核实项保存在不可变 `knowledge_revision.body_json`，每次编辑/生成创建新 revision。编辑 revision 继承前版的 SourceRef 和 dataMode，来源仍须在提交、审核、发布和读取前实时复核；人工编辑内容保持 `UNVERIFIED`，不因已有来源引用而升级为已核验。旧 Interview `playbook_card.id` 不复用。若将来要求一个案例关联多个独立审核/发布的打法，应单独扩展版本化领域契约。

**根因**：当前冻结消费者契约只引用 `knowledgeId + revision`；将案例与其打法拆成两个身份会让搜索引用、审核、发布和撤回到底针对哪个 revision 变得不明确，也会产生重复权限与双重生命周期。将事实元数据与不可变打法正文分层存储，可以维持一个授权/回执/来源边界，同时让每次内容变化可审计。

**代码位置**：`knowledge-service/.../KnowledgeCaseRecord`、`KnowledgeRevisionRecord`、`KnowledgeLifecycleService`；`sql/migrations/20261027_knowledge_domain.sql` 的 `knowledge_case`、`knowledge_revision.body_json`；`docs/contracts/knowledge-domain-parallel-v1.openapi.yaml`。

**关联**：LR-115/116/117；change `extract-knowledge-operations-domain`。该模型为本 change v1 实现范围，不迁移 Interview 旧打法表或其存量 ID。

## LR-119 OWN案例审核权限只开放待审候选并由owner完成发布

**结论**：`knowledge.case.review.decide.own` 代表非 STORE 范围内的审核员权限，不代表案例 owner 权限。审核员可查看并审核 `REVIEW_PENDING` 的当前候选 revision；该权限不能读取草稿、已发布、已撤回内容，也不能用于搜索。审核人必须不同于 owner；APPROVE 只写入审核决定并保持 `REVIEW_PENDING`，owner 再凭 `knowledge.case.publish.own` 和当前 revision 的 APPROVE 执行发布。Auth 角色分配仅作为协调建议：作者使用 own 生命周期操作，审核员只持有 review.decide.own；当前 Auth `businessAdmin=true` 可获得所有 active catalog 权限，但不能由 `roleCode=ADMIN` 推断，且 Knowledge 仍执行资源 owner 检查。

**根因**：若审核操作复用 owner 校验，独立审核员不可能审核；若仅去掉 owner 校验而沿用详情读取，又会把审核许可扩大为全状态读取。将待审候选读取限定在同一精确 review 操作和单一生命周期状态，既满足分离职责也不改变 owner 对草稿、发布和撤回的边界。

**代码位置**：`knowledge-service/KnowledgeAuthorization.requirePendingOwnReviewRead`、`KnowledgeLifecycleService.read/review/publish`；`docs/knowledge-service-operations.md` Auth 操作与角色矩阵；OpenSpec knowledge-operations 权限要求。回归覆盖非 owner 审核、owner 自审拒绝、review 权限仅读待审版本。

**关联**：LR-90/116/117/118；KI-216；change `extract-knowledge-operations-domain`。

## LR-120 Compose Knowledge数据库URL缺省使用服务网络DNS

**结论**：Knowledge 的主库与来源库在 Compose 内默认连接 `mysql:3306/bk_knowledge`；部署者提供非空 `KNOWLEDGE_PRIMARY_DB_URL` 或 `KNOWLEDGE_SOURCE_DB_URL` 时使用显式值。`.env.example` 保留空 URL 以便 Compose 默认与宿主机/外部数据库配置分开；宿主机直接运行服务必须填写完整 JDBC URL。数据库用户名/密码仍必须非空，空凭据不得回退 root 或 H2。

**根因**：Compose 会显式向服务传递 `.env` 中的空字符串，Spring 的 `${KEY:default}` 不会把已定义的空值当成缺省值。仅在 `application.yml` 写默认 JDBC URL 会因此失效并阻断服务启动。把容器 DNS fallback 放在 Compose 插值层能与 Compose MySQL 服务一致，同时保留显式外部数据库覆盖。

**代码位置**：`docker-compose.yml` 的 Knowledge datasource environment；`knowledge-service/src/main/resources/application.yml` 与 `KnowledgeDataSourceConfiguration`；`scripts/deploy/test_knowledge_config.py`。

**关联**：LR-116/119；KI-217；change `extract-knowledge-operations-domain`。静态配置验证不代表远端数据库可达或账号已解锁。
## LR-154 权限码只保护受限拦截点，基础操作保留身份与资源校验

**结论**：权限码只用于默认不向所有有效登录用户开放、必须显式授予的受限能力；普遍开放的基础功能不要求逐 endpoint、CRUD、Graph 节点或 subAgent 配码。无权限码不表示匿名或无边界访问：所有操作仍验证有效 actor/签名上下文；涉及资源或状态转换时，业务域按可信身份检查 owner/参与关系和业务状态。`.own`/`.all` 是作用范围，不自动决定是否要码；本人范围签章、发布等高风险能力仍可设为受限拦截点。现有目录码和角色授予须逐项核对消费者后再经受控迁移精简，不能直接批量删码或移除守卫。

**根因**：把每个接口操作都登记为权限码会造成目录和角色授予膨胀，并将普通 Graph/subAgent 调用耦合到多余的授权判断；反过来，把“免权限码”理解为免认证会让用户身份与业务数据隔离失守。将精确权限码限定为受限能力，同时保留始终执行的 actor、owner/关系和状态检查，能减小目录且不放松数据和状态边界。

**代码位置**：`docs/auth-authorization-permission-system.md`；OpenSpec delta specs `openspec/changes/unify-versioned-permission-context/specs/versioned-authorization-context/spec.md`、`specs/authentication-baseline/spec.md`、`specs/domain-authorization-adoption/spec.md`；本次已完成精确分类与目录退役，见LR-155。

**关联**：LR-139/140/145/146/155；KI-170；change `unify-versioned-permission-context`。权限码边界原则与本轮已确认清单分开记录，防止把“普遍基础”误解为免认证。

## LR-155 基础动作签入请求上下文，但不进入权限快照

**结论**：经逐项复核，将现有目录中的26个普遍基础动作标识作为签名 `AuthorizationContext.allowedOperations` 的可路由动作集合；Auth授权 `snapshot.permissions`、角色授予和有效权限并集只保留受限拦截点。共享 `AuthzOperationPolicy` 是唯一精确分类源；消费者必须同时验证签名身份/请求关联，按业务检查owner、参与关系和状态。Agent本地能力只有被明确登记在 `AGENT_AUTHORIZATION_BASELINE_CAPABILITIES` 的精确 `local:<tool>` 才免权限码，并仍要求有效actor和持久run/session；默认空集。目录迁移停用这26条权限、清除精确角色授予并发布所有用户的新revision/outbox，保留目录行供审计。

**根因**：基础动作仍需绑定到签名请求，防止LLM或请求正文替换用户/动作；但把这些动作放在权限快照中会继续把“有效登录即可使用”的功能误建模为角色授予。区分签名动作集合与受限权限集合，既保留业务域身份及数据/状态边界，也使常规Graph/subAgent动作无需逐项授予或对Auth远程查询。受限权限仍须在入口请求、服务ACL、用户角色/管理员快照中全部满足，基础动作不能借此提升为未分类的新操作。

**代码位置**：`common/authz/AuthzOperationPolicy`、`AuthzResponse`、`AuthzRequestScope`、`AuthorizationSession`、`common-a2a/VerifiedDomainAuthorization`、`common/rag/AuthRagPrincipal`；`auth-service/EffectivePermissionService`、`AuthAuthorizationRpcServiceImpl`、`AuthPermissionRpcServiceImpl`；`agent-service/AgentAuthorizationProperties`、`AgentPermissionService`；`sql/migrations/20261028_baseline_permission_retirement.sql`及`docker/mysql/init-manifest.tsv`；`docker-compose.yml`、`nacos/agent-service.yaml`、`docs/auth-authorization-permission-system.md`与OpenSpec delta。

**关联**：LR-154；KI-170/208；change `unify-versioned-permission-context`任务1.4/2.6/11.6。Nacos配置发布与开发MySQL实际应用需分别留存验收证据；不能仅凭源码/清单校验称为数据库已更新。

## LR-156 已应用迁移按账本校验哈希后跳过历史状态复验

**结论**：受管MySQL runner先比较 `bk_schema_migration` 中记录的migration文件及schema check哈希；记录存在且哈希完全匹配时，将该项作为已应用迁移跳过，不重放SQL，也不重复验证其当时的状态断言。只有账本中没有该项时才执行SQL、运行schema check，并在成功后写入哈希账本；哈希变化继续硬拒绝。

**根因**：schema check描述迁移刚执行时的后置条件，不一定是永久不变量。之后的合规迁移可能有意停用权限或改变状态；重放所有旧SQL会引入副作用，复验旧check则会把正确的新状态误判为损坏。最终现状应由最新的领域检查验证，而历史账本负责证明具体迁移制品未变、且该迁移已经完成。

**代码位置**：`scripts/deploy/run-init-manifest.sh` 的manifest哈希与已应用分支；`scripts/deploy/test_init_manifest.py`；`docker/mysql/init-manifest.tsv`及当前迁移的独立schema check。

**关联**：KI-213；change `unify-versioned-permission-context`任务2.6/11.6。此规则不替代fresh数据库验收、末端状态检查或安全制品回滚演练。

## LR-157 旧 promotion 库保留作迁移回退，不默认开放运行期读取

**结论**：营销生命周期的活动读写只落 `bk_marketing`。旧 `bk_promotion` 库、数据库地址和旧 Nacos 配置在迁移及回退窗口内保留，但不作为有效发布 writer，也不默认授予 MySQL MCP 只读访问；确有迁移审计需要时由运维通过显式、受审配置开放。

**根因**：保留历史数据和可回退制品并不要求把旧服务重新加入日常运行面。默认仍开放旧库会让合并后排查和工具查询继续依赖两套数据口径，且增加误读旧数据的机会；直接删除旧库又会破坏迁移核对和回退能力。

**关联**：LR-90/116/117/118；KI-212；change `consolidate-marketing-promotion-domain`。

## LR-158 Knowledge内容摘要不等于修订身份，幂等回执绑定目标

**结论**：Knowledge revision 的身份由 `(knowledge_id, revision_no)` 决定；`content_hash` 仅指正文内容，因此标题/标签变化或正文回滚可在不同 revision 中复用同一摘要。已有 request id 的操作回执，除请求摘要和 actor 外，还必须匹配原 `target_id`；复用 request id 对另一个 Knowledge 目标操作时返回 `IDEMPOTENCY_CONFLICT`。create 尚无调用方目标 ID，重放时按原请求和 actor 返回第一次创建的目标。完成操作回执需要将预留响应从 `{}` 更新为最终 JSON，应用账号因此需要对该表拥有 UPDATE。

**根因**：幂等键 `(request_id, operation)` 并不天然约束请求体之外的目标对象；正文内容哈希也不代表整个聚合修订。把二者混为唯一身份会拒绝合法的元数据修订，忽略回执目标则可能把 A 案例的成功结果错误返回给 B。

**代码位置**：`knowledge_revision` 迁移与 `knowledge_operation_receipt` 最小授权；`KnowledgeLifecycleService.replay/reserve`；Knowledge migration manifest 与静态配置检查。

**关联**：KI-220；change `extract-knowledge-operations-domain`。

## LR-159 营销草稿在素材生成前完成授权预检

**结论**：MCP 草稿创建入口在调用素材生成 RPC 前，先校验 `marketing.content.create.own` 与可信 broker identity；底层 `MarketingAssetService.saveContents` 继续保留同一授权校验，作为持久化边界的独立守卫。入口预检不取代服务层授权，也不允许无可信身份的请求触发外部素材生成。

**根因**：MCP 工具会先调用可选的 Media Worker，再进入资产服务。只依赖服务层的写入校验虽然能拒绝未授权持久化，却会让未授权调用先触发外部副作用。将授权检查提前到工具入口可阻止这一副作用，同时保留服务层防绕过保护。

**代码位置**：`marketing-content-service/MarketingMcpTools.createMarketingContentDraft`、`MarketingAssetServiceImpl.saveContents`、`MarketingAuthorizationBoundaryTest`。

**关联**：KI-221；本记录来自修复 CI 暴露的过期授权边界测试，不改变 Knowledge 服务拆分范围或 Interview 源服务边界。

## LR-160 四域新操作登记不推断角色与门店身份

**结论**：知识运营与门店复盘的显式操作及 Agent 调用能力登记到 Auth 操作目录，但不自动新增角色授权；STORE 请求仍须 Auth 签发可信用户—门店映射。

**根因**：操作目录是可分配能力，角色授予和业务资源归属是不同事实；根据已有角色名或请求中的 storeId 猜测权限会越过当前授权边界。Auth 对 businessAdmin 使用既有的全有效目录规则，业务资源与审核人分离规则继续生效。

**代码位置**：`sql/migrations/20261028_four_domain_authorization_catalog.sql`、`docker/mysql/checks/four-domain-authorization-catalog.sql`；关联 KI-223、LR-119/122。

## LR-161 未上线环境移除旧比较服务的默认部署

**结论**：用户于 2026-10-29 确认项目没有正式上线或需保留的历史资产，独立 Compare 模块的源码、维护测试、RPC 契约、Compose 服务、独立 Nacos data ID、Auth/Memory 信任和 Compare Memory 空间均退役；房源比较继续由 Listing 提供，旧 `/compare/**` 响应字段由 Listing 兼容 DTO 承接。

**根因**：项目未正式上线且没有需要迁移的旧报告或在途任务，不再需要保留不可调用的独立服务或身份。Listing 自有回归覆盖来源版本、owner 隔离和兼容响应；数据库迁移只在确认旧 Promotion schema 为空后删除，并停用未迁移的公开分享权限。

**代码位置**：根 `pom.xml`、`docker-compose.yml`、Listing 比较兼容 DTO/测试、Auth/Memory/RAG 配置、受管迁移 540；关联 LR-99、KI-115、KI-224、KI-227。

## LR-166 统一 Agent Card 同时维护多个内部技能域

**结论**：一张平台经营 Agent Card 可以发布多个内部技能域，并在技能热更新时一起重建；技能仍保留各自 owner、版本和内容摘要。同名技能或重复 owner 直接拒绝发布。

**根因**：对外只有一个 Agent，但现有技能选择与执行校验依赖内部域和精确快照。单 owner 的卡片刷新会丢失其他分支，按名称选择时的同名技能也会产生歧义。

**代码位置**：`common-a2a/.../SkillAgentCardPublisher.java`、`LiveSkillAgentCards.java` 和对应测试；平台经营运行接线仍待完成。

**关联**：LR-28、LR-165；change `consolidate-platform-operations` 任务 2.5。

## LR-165 平台经营单入口先验能力再分支

**结论**：平台经营 Agent 的路由器根据 A2A 请求 metadata 中的精确 `platformCapability` 选择 analytics、review 或 knowledge，先校验对应签名操作，再调用该分支；取消请求委托给持有任务的执行器核验任务凭据。

**根因**：合并成一个外部入口后，模型或请求不能仅凭自报能力取得其他分支的工具和记忆；知识输入防护与各分支的 Memory 生命周期也不能被绕开。请求体完整性由现有 A2A 授权绑定校验，能力路由仍须自行检查具体操作。

**代码位置**：`platform-operations-service/.../PlatformAgentRouter.java`、`PlatformAgentConfiguration.java` 及其测试；三个内部 Agent 以平台经营服务调用 Memory，分别使用 analytics、review、knowledge 的独立记忆标识，Auth 和 Memory 配置模板登记精确身份；`sql/migrations/20261029_platform_operations_authorization.sql` 登记能力操作但不授予角色。统一配置尚未接入运行启动类，实际身份密钥和运行配置、跨分支隔离及完整链路仍待实现和验收。

**关联**：LR-117、LR-160、LR-163；change `consolidate-platform-operations` 任务 2.5、4.2。

## LR-164 平台经营知识来源使用独立锁定的视图所有者

**结论**：平台经营知识来源视图由 `platform_operations_view_owner` 持有，该账号保持锁定且只拥有访谈源的必要列读取权；平台经营应用账号和只读来源账号不能直接读取访谈表。

**根因**：合并服务不能把逐字稿源表权限扩展给经营分析或复盘，也不能在退役旧知识服务后依赖其视图所有者。已应用的 570 迁移不可改，通过 580 前向切换视图所有者；重跑按账本跳过历史状态检查，当前状态使用独立检查验证。

**代码位置**：`sql/migrations/20261029_platform_operations_schema.sql`、`sql/migrations/20261029_platform_operations_view_owner.sql`、`docker/mysql/checks/platform-operations-current.sql`。

**关联**：LR-116、LR-156、LR-163；change `consolidate-platform-operations` 任务 3.1–3.3。账号凭据配置与服务运行态访问仍须单独验收。

## LR-163 平台经营门店身份由 Auth 维护且允许多人共享门店

**结论**：一个用户最多关联一个可信 STORE 身份，同一门店可关联多个用户。门店关系由有业务身份管理权限的业务管理员在 Auth 中维护，并进入签名授权快照；请求中的门店 ID 不能自行建立授权范围。

**根因**：原业务身份表为 BROKER 设置了 `(identity_type, identity_id)` 唯一约束，直接复用会错误地限制每个门店只能关联一个用户。独立门店关系表保留原 BROKER 唯一性，门店关系变更通过 writer lock、用户授权版本和 outbox 撤销旧上下文。

**代码位置**：`AuthzContract`、`AuthorizationRepository`、`RoleAuthorizationManagementService` 和 `sql/migrations/20261029_auth_store_identity.sql`。

**关联**：LR-160；KI-223；change `consolidate-platform-operations` 任务 2.5。登记门店身份不自动赋予业务操作权限。

## LR-162 新领域服务身份在 Auth 与 Memory 分别登记

**结论**：Knowledge 与 Business Review 在 Auth A2A 中只登记 `RESOLVE_BEACON`，Memory 只登记各自独立的 caller、domain 和 agent；Compare caller 与五类旧 Compare Memory 空间均从 Auth、Memory、RAG 默认配置完整移除。

**根因**：服务公钥、信标操作权限与 Memory 私域分别校验，单独配置某一服务不等于其他验证方已接入；旧服务退出运行后不需要保留默认访问许可。

**代码位置**：`nacos/auth-service.yaml`、`nacos/memory-service.yaml` 及对应服务的 `application.yml`；关联 KI-225、KI-226。
