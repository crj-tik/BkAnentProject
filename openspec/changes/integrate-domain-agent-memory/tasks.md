## 1. 接入清单与验收基线（先于启用）

- [x] 1.1 查阅并记录 LR-3/6/17/18/22/23/31/33/34/38/40/41/53、KI-2/21/24/59/80/100/114 适用边界；盘点九个 SubAgent 源码接入口及目标环境实际启用集合，形成逐入口执行器、身份、可靠事实源与消费者清单。
- [x] 1.2 记录 Supervisor 通用 Graph、SubAgent ReAct、真实 ChatClient 入口及 interview REST/SSE 运行面调用路径；核实实际解析的 Spring AI 1.1.2 / Alibaba 1.1.2.3 制品与扩展签名，无真实消费者的适配不建设。
- [x] 1.3 用制品级测试验证 SummarizationHook 执行顺序、token估算、工具组切点、结构化工具结果及失败传播，证明可在可靠提交和版本核验后才替换窗口；无法满足保护契约的接法不得启用。
- [ ] 1.4 固定 Supervisor、marketing、interview 治理面 fixture 和正式 SSE 入口，记录改造前首可见事件、终态展示、成果及 Studio 可读的 P50/P95、负载、模型、拓扑、认证/审计配置和 I/O 次数；无目标环境的项目标为未测，不预设无依据毫秒门限。

## 2. 薄适配层与受管 RPC 契约

- [x] 2.1 在 common 定义框架无关 MemoryContextKey、事项/来源引用、版本、消息/工具结构、摘要、偏好块/历程页、经验、共享 grant/export、状态与错误 DTO；区分 conversationId/runId/A2A taskId/domainSessionRef，不泄漏框架 Message 或凭据。
- [x] 2.2 定义 memory v2 原生 Dubbo RPC，覆盖工作集读取、消息接收许可/交付、摘要提交、事项/实体查询、偏好/经验搜索详情及共享引用；固定 interface/group/version/operation 与请求hash身份，加入序列化及版本不兼容拒绝测试。
- [x] 2.3 按实际依赖面落实 common-memory 薄适配层；需要独立模块时配置 Java17 和现有框架版本、更新 reactor 依赖，无需求时沿既有共享层组织；验证 common DTO 不依赖 AI 框架、common-a2a 不反向依赖 Agent 业务实现。
- [x] 2.4 显式绑定原生 dubbo consumer/provider，补 KI-100 防回归测试，受管注册中心/服务映射只从部署配置获取；新请求不能选 URL、host、物理集合，不增加内网 HTTP 客户端或借 LR-34 放宽新 HTTP 地址禁令。
- [x] 2.5 保留受鉴权 v1 REST/RPC 真实消费者兼容，发布 v1/v2 能力与失败差异；验证旧 memory_json/summary、单值偏好和约束接口不会被误声明为完整新记忆链，也不静默双写覆盖 v2 历程。

## 3. 身份、授权与派生内容撤回

- [x] 3.1 实现当前用户及可信 caller/domain/agent 映射、请求操作/audience/hash绑定签名与防重放；A2A 租约只作为身份来源，不把原 A2A 信标重放给 memory，凭据只在专用传输与进程租约中存在。
- [x] 3.2 实现 DOMAIN_PRIVATE、TASK_SHARED、SESSION_SHARED 的 owner/参与者/purpose/版本/有效期授权，域内不同 Agent 默认隔离，ADMIN 的个人记忆仍 self-only；覆盖伪造 owner/domain/grant、仅持引用/namespace/contextId 的拒绝测试。
- [x] 3.3 实现服务端可持久核验的精确事件接收许可及已接受调用的受限结果交付许可，绑定 eventId/内容hash/来源身份；服务补交付与当前用户读取/后台加工权分离，不持久化用户token、不从owner换发凭据。
- [x] 3.4 测试用户租约到期/进程重启后的原 actor 重新认证、SERVICE精确事件补交付、伪造证明及不同内容重放拒绝；删除/撤回可阻止加工及发布，但不改写真实业务执行事实。
- [x] 3.5 为摘要、锚点、工作集及共享投影记录来源版本/grant依赖；实现恢复后、入模前与输出前当前授权复验，撤回来源时失效或重建派生内容；测试同owner旧checkpoint不能绕过来源权限。

## 4. 版本化真源与可靠交付

- [x] 4.1 新增 conversation/message/matter/summary/entity 聚合的版本化结构与复合身份、来源幂等键、消息sequence、covered cursor及索引；执行升级/首启/重复迁移测试，旧 summary 长度字段不承载新完整摘要。
- [x] 4.2 新增 preference_block/transition、experience、shared_export/grant、processing_job 结构；明确证据、有效时间、块revision、撤回/删除状态和保留策略，索引与实际查询计划匹配。
- [x] 4.3 仅在缺少可恢复事实源的入口增加必要最小交付 journal/outbox；已有不可变业务账本采用引用重建，验证 input接受前、调用接受时、完成结果后的可恢复顺序，禁止每域无条件复制完整正文。
- [x] 4.4 实现稳定消息/sourceEventId幂等追加、同身份异hash冲突、会话单调顺序、持久回执与覆盖核验；测试回执丢失/乱序/重启不重复消息或证据。
- [x] 4.5 实现入站真源变化与加工/索引job的事务提交；worker使用受管SERVICE和精确证据权限，在事务外执行模型/RPC，具备lease、有限重试、失败状态及授权重放，覆盖跨实例接管与提交前撤回复验。
- [x] 4.6 定义可靠记录失败、许可服务不可用、副作用已完成但交付失败的降级；明确未归档、保留材料与禁止压缩，不把业务完成改判未知或重执工具来补记忆。
- [x] 4.7 实现受管隐私删除/撤回清单和状态：即时禁止读取/装配，异步清理可还原副本、派生内容及索引；保留必要不含正文tombstone，迟到事件不复活，未完成不虚报全部删除成功。

## 5. Agent 工作集与执行接线

- [x] 5.1 实现完整 contextKey/授权/策略版本隔离的有界热工作集、冷加载摘要与近期消息、TTL/空闲回收；多模型轮次复用，不重复远程全窗口读取或双重历史注入。
- [x] 5.2 实现持久更新权、conversation revision/OCC与采用版本记录；测试并发输入、失效租约、跨实例接管和旧更新不会覆盖较新消息，恢复保留全部未覆盖尾部。
- [x] 5.3 在现有请求分析中输出上下文用事项标签及开始/继续/闭合候选，按目标与范围装配偏好/经验；事项标签不控制业务调用，不引入关键词路由、步骤DAG、每轮独立事项大模型或nextHints自动交接。
  验收边界：沿用 Supervisor 请求边界的显式 `matterIntent` 作为结构化候选；START 的用户目标成为事项标签，CONTINUE/CLOSE/REOPEN 只操作当前 context 中经版本核验的事项。Matter 只参与偏好及经验适用性和上下文，不驱动工具选择；未提供显式 intent 时不猜测事项或自动开新事项。偏好与经验均按目标/范围有界装配，同事项复用并在模型、工具及输出边界重验版本。
- [x] 5.4 在 Supervisor PrepareContext/Observe及续接恢复路径接相同生命周期，沿用原run/session/skill/审批/累计预算/远端任务；回归已完成调用复用、OUTCOME_UNKNOWN对账、不盲重发和输入不重复入模。
- [x] 5.5 在 common-a2a/marketing 的实际 ReactAgent 生命周期接入工作集、消息记录和受保护压缩扩展；显式注册Hook位置，模型/工具拦截器不重复执行业务，不以未使用ChatClient bean宣称主链接通。
- [x] 5.6 接 interview 治理面相同适配并测试私有隔离与可许可摘要/引用；保留运行面REST/SSE、三段上下文、脱敏与领域状态真源，不以创建场次终态关闭访谈事项。
- [x] 5.7 对 listing-master、business、contract 的原生 A2A 入口逐项接线并完成冷/热加载、身份/授权、可靠来源、开关关闭回归，未通过的域保持关闭。
- [x] 5.8 对 compare-engine、media-worker、notification、settlement 的原生 A2A 入口逐项接线并完成相同契约回归；没有数据库事实源的入口按第4组策略启用，不无条件新建业务库。
- [x] 5.9 核实实际 ChatClient 调用均为无 conversationId 的一次性任务，正式 Supervisor 对话已由 Graph 管理记忆；记录 LR-80，不为单次调用造会话或重复注入历史。
- [x] 5.10 明确区分历史无匹配与依赖不可用并向模型提供不猜测提示；当前用户修正优先，新的用户事件触发局部偏好重查；认证/授权失败仍失败关闭，回归必要历史缺失时澄清。

## 6. 双触发安全压缩与历史补忆

- [x] 6.1 实现可配置软token阈值、硬输入预算、估算器版本和近期保留量；token触发及有证据事项闭合进入同一协调器，模型提议/不充分子任务终态不自动关闭事项。
- [x] 6.2 实现覆盖范围冻结与确认、安全完整tool-call/全部响应组、审批/未知结果保留及responseData授权序列化；大工具结果使用完整真源和有界引用，测试超过原生邻域扫描长度的调用配对。
- [x] 6.3 实现结构化摘要目标/事实/决定/否定/修正/例外/未决/实体/证据、大小与来源校验，失败字符串/空结果/越权正文不作为正常摘要，防止记忆文本升级为指令。
- [x] 6.4 实现摘要与covered cursor同事务提交、conversation与run/checkpoint分别版本核验的工作集CAS；测试新输入、双触发竞争、摘要提交后CAS失败及提交后崩溃，恢复有效摘要与未覆盖尾部不丢不重。
- [x] 6.5 测试档案未确认、摘要失败/超时、版本冲突时保留旧窗口；硬预算无安全替代时有界返回CONTEXT_BUDGET_EXCEEDED，不无限增长、破坏性截断或发送超预算输入。
- [x] 6.6 实现事项关闭/重开、实体别名与来源锚点；先本地解析历史指代，有缺口才有界调用memory，只有明确请求与授权才能扩大范围，返回brief解释及证据，无证据则澄清。

## 7. 异步偏好完整历程

- [ ] 7.1 实现可信owner/subject/canonicalScope与稳定维度注册及唯一block身份，私有scope包含domainId+agentId、共享scope采用明确授权组身份；同owner同域同主体同维度的未共享Agent不碰撞/归并，明确共享组内才归并，测试逐句重复键、模糊主体和未授权范围拒绝。
  当前实现及回归覆盖 owner-self 的 domain+agent 私有键、稳定维度、显式共享组 ID 的身份隔离、重复键稳定哈希、伪造维度与跨 Agent 读取拒绝。经可信业务关联的其他 subject 及共享偏好授权尚未接入；按 LR-73 现有设计不扩展或宣称完成此项。
- [x] 7.2 实现异步先评估 PREFERENCE/TASK_CONSTRAINT/QUESTION/OTHER、肯定/否定、长期修正/临时例外后微批归并；记录规则/模型版本，评估故障不阻塞会话或SSE。
- [x] 7.3 实现源事件幂等、完整时间挂点、明确修正因果、块revision/OCC与未决冲突；同会话sequence可排序，跨会话不得比较局部序号，测试迟到旧表达不覆盖新修正。
- [x] 7.4 实现当前值与过去到现在历程详情、固定blockId/revision的有序keyset分页和完整度；任一检索分片解析为一个逻辑块，版本变化显式继续固定版本或要求重读，禁止静默混页。
- [x] 7.5 实现新事项按目标/范围检索与token内当前值/例外装配、同事项复用、纠正/撤回局部刷新；持久旧值不覆盖当前明确输入。
- [x] 7.6 分离confidence/activation/relevance/time weight，统一asOf与有效观察；读取/检索/注入/assistant复述不增证据或lastObserved，不将旧每次减0.05自动用于v2，覆盖否定、例外过期、撤回和删除回归。

## 8. 硬规则与领域经验

- [x] 8.1 核验硬规则来自受管约束配置和受权维护入口；bootstrap 只补缺、不重新激活停用项，旧 workflow 文字不恢复业务 DAG；经验与时间衰减不改硬规则（LR-17/LR-18/LR-82）。
- [x] 8.2 实现真实outcome/有效正负feedback异步评估与幂等经验修订，区分受理、部分完成、未知、模型自评和可核验结果；学习不阻塞当前执行。
- [x] 8.3 实现私有经验默认owner/domain/agent授权，受治理批准的独立非个人归纳才可域共享；公开归纳不授予个人原证据读取权，测试可还原引用拒绝、来源删除后派生保留授权。
- [x] 8.4 实现版本化relevance/confidence/activation/time评分与去重token预算、新事项选择同事项复用、负反馈/停用/重新确认；读取不增确认时间，衰减不删历史，停用/撤回立即排除。

## 9. 新记忆 RAG 投影与域最终核验

- [x] 9.1 注册新会话/摘要、偏好历程、经验及协作空间/schema；不同domain及需隔离SubAgent分配独立ES/Milvus目标，空间内owner/agent/grant过滤仍不可覆盖，未注册请求零后端访问。
- [x] 9.2 扩展可信memory授权scope及双路过滤；新增独立受管候选许可RPC，由RAG在派发重排正文前核验有界候选的真源revision/hash/删除/grant及派生source当前状态，许可只返回授权结论不取正文/不执行搜索，失败关闭；个人ADMIN仍self-only，后台投影权限不含通用用户读取权。
- [x] 9.3 实现新记忆受控完整投影、revision/hash/schema/eventId、块分片清单及旧片段tombstone；复用事务outbox/两路应用状态和迟到写补偿，测试增减片段/乱序/删除/部分应用不混版本。
- [x] 9.4 扩展memory领域搜索与稳定ID详情：候选后最终SQL/版本/hash/删除/grant再核验，返回安全当前正文/证据；至多一次补取且累计有界，详情不等待索引。
- [x] 9.5 测试默认STRICT与显式受限降级、无匹配/依赖失败区分、旧经验评分和领域衰减；索引未追平、来源或grant在召回后且重排派发前失效时正文进入重排次数为0，许可不可用失败关闭，合法派发后的撤回仍阻止最终输出；禁止Agent直接RAG私有搜索或旧ES/Milvus恢复兜底。

## 10. A2A 协作导出与 SSE 成果回归

- [x] 10.1 定义display/collaboration/durable export三种许可投影与大小上限，A2A保持正文/结构结果直传，Supervisor当前已收到结果不重复回取；跨域转交必须重新核授权。
- [x] 10.2 实现来源域不可变export版本、来源调用/hash关联、参与者grant、PENDING/COMMITTED及独立索引状态；持久确认后稳定ID可读，迟到回执/重试幂等，不把引用当认证信标。
- [x] 10.3 在A2A服务端输出、transport、normalizer、客户端及执行事件链贯通增量memoryRefs/exportState/version字段，验证现有content/result字段兼容，不只在一端metadata加字段就宣称贯通。
- [x] 10.4 保持artifact.created在成果保存成功后及既有tool/workflow完成时序，回归KI-24部分成功/回放；Studio生成与人工修订来源不混写，不把KI-114其他领域动作纳入本变更实现。
- [x] 10.5 回归正式SSE入口的parent/child关联、task过滤、visibility、eventId/sequence和Last-Event-ID去重；仅对本链路确认的过滤/可见性缺口做显式增量修复并记录KI，不进行全仓SSE重构。
- [x] 10.6 故障注入memory延迟2秒/不可用、索引迟到及撤回，验证逐delta/progress新增memory读写/embedding/总结调用为0，前端链不等待回取；单独观测delta限流/审计降级，不伪装成记忆性能问题。
- [ ] 10.7 扫查测试fixture和实际prompt/checkpoint/SSE/日志/导出确认无用户凭据、专用认证信标、脱敏映射及私有内部材料；所有secret来自ENV/密钥服务，SQL外部输入参数绑定。
  验证边界：已扫查仓库代码、配置、测试fixture与本机环境变量键名（不读取或输出密钥值），并修复硬编码票据密钥；本轮在 `bk-anent-dev` 原位重建 `memory-service`，readiness 为 `UP`。近20分钟服务日志中 bearer token、凭据赋值和经验 fixture 字符串扫描均为0；单测已确认经验正文不进入 Graph checkpoint。未启动 full profile 的 `agent-service` 及其附属依赖，因此 Supervisor 实际运行时 prompt、checkpoint、SSE 和应用日志仍须在最小联机验收组合可用后扫描。

## 11. 分阶段启用、回滚与交付验收

- [x] 11.1 发布逐域入站、影子加工、生产加工、索引派发、prompt读取开关及有界容量/TTL/token预算配置；影子模式允许评估但不影响生产投影或可见输出，指标不包含正文/凭据。
  验收边界：各域 ingress、Supervisor prompt read、archive、preference/experience worker 和 RAG dispatcher 均默认关闭或可独立切换；偏好 `SHADOW` 只写评估/作业状态、不写生产偏好投影，`PUBLISH` 才归并。经验评估由受信域结果事件和用户反馈驱动，七个领域 publisher 开关独立且默认关闭；Supervisor caller 精确限制为 `supervisor` + `supervisor-agent`，没有 outcome publisher 权限。经验 worker 只有独立开关，当前不提供影子投影，故保持关闭；影子模式当前只用于偏好提取。签名密钥只从环境注入，缓存、队列、批次、重试、TTL 与 token 上限有界。目标 `.env` 当前缺少 Supervisor Memory v2 signer 与领域 publisher 键，运行实例仍关闭，待迁移/fixture 验收后配置。
- [ ] 11.2 编写新版本SQL和初始化清单/checksum与受管空间运维步骤；盘点目标库及旧集合，保留种子/用户/共享记忆/workflow/checkpoint，未知owner/domain来源隔离；升级、首启、重复执行达到同版本。
  验收边界：清单已覆盖四项 Memory v2 漏项并修正既有 checksum；整份升级 dry-run、现有 `bk-anent-dev` MySQL 增量升级及紧接的重复执行均通过，040-240 全部版本都报告 `Verified`。缺少的共享导出运行态和四个 Memory v2 版本已写入各目标库版本账本，schema checks 全通过。升级前后认证用户（8）、workflow checkpoint（261）、共享记忆（26）、RAG projection（27）、RAG outbox（117）行数相同。2026-10-22 只读盘点确认 ES 共 5 个索引：`rag_memory_constraint_v1` 17 文档，其余 `rag_memory_preference_v1`、`rag_listing_knowledge_v1`、`marketing_content`、`listing_info` 均为 0 文档，alias 为空；Milvus 默认库共 3 集合：`rag_memory_constraint_v1` 17 行，`rag_memory_preference_v1` 与 `rag_listing_knowledge_v1` 为 0 行，均为 1024 维 COSINE schema 且无 alias。Nacos 声明的 35 个 Memory v2 逻辑目标分别映射 ES index/Milvus collection；旧物理清单中没有这些新目标名。只读检查没有读取正文或更改存储。fresh-volume 验收需要独立 MySQL 容器，本轮按资源约束未启动；35 个目标的受信 RAG 运行态 INSPECT/PROVISION 仍未执行，尚未创建空 schema。仓库要求当前 ADMIN 才能执行此类 INSPECT；本机可用的认证身份是 BROKER，且没有配置专用 `RAG_PROBE_ACCESS_TOKEN`，因此未绕过鉴权。任务保持未完成。
- [x] 11.3 先完成Supervisor/marketing/interview治理面完整fixture，再对其余启用SubAgent逐入口签收身份、接线、可靠来源及隔离清单；未通过者保持关闭，不将源码接通宣称真实环境已启用。
  验收边界：最小无容器 fixture 全部通过：Supervisor 真实 Graph 30/30；marketing/interview 共用 A2A Hook 的实际 ReactAgent fixture 8/8（覆盖 marketing 可信 owner/域/agent 绑定、归档与凭据隔离，以及 interview 同 A2A context 下跨域隔离）；Memory 受信 outcome、owner feedback/source、幂等处理和撤回测试 10/10；独立域治理审批、读取隔离和来源删除边界 5/5。其余 SubAgent 按 1.1、5.7、5.8 清单核验；当前 `bk-anent-dev` 只运行 MySQL/Nacos/Memory，Supervisor/marketing/interview 容器已停止，相关 signer 在本机 `.env` 缺失或为空，逐域 outcome 开关未显式开启，故真实进程保持关闭，未将源码接线宣称为运行态启用。测试不启动额外容器。正式 HTTP/SSE 入口的真实服务基线留给 1.4/11.4。
- [ ] 11.4 同环境比较改造前后各阶段P50/P95、RPC/DB/模型次数、队列、内存与冲突/重试，基于证据记录容量和启用预算；认证/审计仍计入端到端延迟，无前端源码/实例时明确浏览器验收未完成。
- [ ] 11.5 演练有界开发切流与回滚：先停新入口/prompt，保留交付接收直到有界排空，再停加工/派发；无法排空如实保留journal/job，不删v2历程/旧存储、不恢复撤回内容或旧退役RPC。
  代码与预检记录：A2A 域 Agent / Supervisor 已增加独立 `read-enabled`、`delivery-enabled` 和 delivery-only 模式；定向回归覆盖停读后在途交付，具体见 LR-96/KI-161。2026-10-22 初始只读预检显示 Memory archive、偏好处理、域投影/搜索关闭，经验开关未设置且默认关闭，Supervisor 与领域 A2A 服务未运行；`bk_agent.agent_memory_delivery`、`bk_memory.memory_processing_job`、`bk_memory.memory_experience_event` 均为空，`bk_memory.memory_rag_outbox` 117 项均为 `APPLIED`。随后按 Compose 默认设置重建共享服务镜像，23 个模块打包成功；运行目标只启动 `agent-service`，复用现有 Memory/MySQL/Nacos，按依赖启动 Redis 和 RocketMQ NameServer。单独向本机 Nacos 发布 `agent-service.yaml` 并读回校验，替换后的 Agent readiness 返回 `UP`。当前 Agent Memory 与 Memory archive/加工/投影/派发开关仍关闭，领域 A2A 消费服务未运行，没有活动入口或待排空队列可执行真实切流；未启动 release 副本或其他业务容器，因此真实有界回滚演练仍未完成，任务保持未勾选。
- [x] 11.6 运行仓库本地Maven settings的相关模块与全量compile/test，记录具体通过/失败；新运行状态transition同步增加KI-2回归。更新logic-rationale/known-issues的实际实现决策/缺陷与证据，不提前宣称潜在问题已修。
  验收记录：目标治理fixture按 11.3 定向回归 53/53 通过；2026-10-22 使用仓库本地 Maven settings 执行 `mvn -gs .mvn-settings.xml -s .mvn-settings.xml test`，23 个 Maven 模块全部 SUCCESS，Surefire 汇总 201 个测试套件 / 1,321 项，失败 0、错误 0、跳过 14。跳过项为 LiveSkillAgentCardsDockerTest、ApprovalClaimDatabaseConcurrencyTest、NacosRollingUpgradeDockerTest、SupervisorDistributedAcceptanceTest、SupervisorDockerDatabaseTest、SupervisorRealModelEvaluationTest、NacosAgentRegistryDockerTest、PreferenceLiveModelTest、MemoryArchiveMySqlTest、MemoryExperienceSchemaMySqlTest、MemoryMatterMySqlTest、MemoryRagOutboxMySqlTest、PreferenceHistoryMySqlTest、PreferenceProcessingMySqlTest；均需当前未配置的 Docker/Nacos/真实数据库/模型端点。测试期间未启动额外容器。编译和全量 Maven 回归通过；以上外部环境验收仍由 1.2/1.4/11.4 等任务覆盖，不将跳过项视为已验证。
- [ ] 11.7 核验七份能力所有Scenario证据、严格OpenSpec校验、迁移/接口/配置/失败/观测说明和未验项；每个已验证逻辑修改按仓库规则中文commit并push当前对应分支，实施完成后另行请求归档，不自动跳过验收。
