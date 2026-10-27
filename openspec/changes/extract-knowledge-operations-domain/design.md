# 经验知识运营域拆分设计

## Context

动机见 proposal.md。`InterviewCollectionService` 已生成脱敏逐字稿资产及 snapshotHash，`InterviewReportService` 已有异步案例报告、原声核验和任务回执；`PlaybookCardEntity`、`CommonalityReportEntity` 当前为占位。访谈运行面、状态机和证据脱敏具有既定边界（LR-3/4/5/6/8），不得迁成 Supervisor 的自由工具步骤。

逐字稿由访谈业务域接入；当前 Interview asset 只有 `AUTO|UPLOAD` 来源字段，没有持久化数据模式/provenance，不能据此判断生产真实性。初期服务知识运营人员与店长的经营提效，存量模式不明按 `UNKNOWN`，受控测试夹具可标记 `SIMULATED`。现有 Memory v2 共享经验是独立治理对象（LR-90），不能代替运营人员可编辑、审核、撤回的知识资产库。

## Goals / Non-Goals

**Goals:** 建立独立知识运营事实、来源证据与发布生命周期；保持访谈真源；向店长业务提供当前受权的知识版本。

**Non-Goals:** 不搬迁会话/话轮/逐字稿，不改变访谈状态机或原报告接口；不建设新人带教、自动绩效评定或直接共享个人 Memory；不把行业大脑语料建设 KI-13 当作本 change 已解决。

### 拆分边界

| 责任事实/代码 | 唯一归属 | 本 change 的交付边界 |
| --- | --- | --- |
| Interview session、turn、case、transcript asset、原始/脱敏正文、来源 hash/修订、原报告及报告任务 | `interview-service` / `bk_interview` | 保持真源；只增加窄只读来源契约和独立的知识用途授权生命周期，不迁移表、ID 或报告任务 |
| 新经营案例、打法、不可变 revision、审核、发布范围、来源依赖、生成任务/receipt、查询与当前使用检查 | `knowledge-service` / `bk_knowledge` | 全部新建并由知识服务负责，不回写访谈事实、不直连 `bk_interview` |
| 用户身份、角色/操作目录、签名 context/freshness 和可信业务身份映射 | `auth-service` | 只消费现有协议；本分支交付精确操作/身份登记差异供 Auth 协调合入，不改 Auth 实现或 Auth 数据 |
| 通用 Supervisor、Agent 授权协议和 Memory 生命周期 | `agent-service` / `memory-service` | Agent 仅增加 knowledge 的受众映射、能力映射和最小注册配置；不增 Graph 领域分支、不改 Memory 实现/全局白名单 |
| 服务间传输类型 | `common/knowledge/**`、`common/interview/transcript/**` | 只放序列化 wire DTO/枚举，不放 Entity、Mapper、Repository 或业务规则 |
| 门店经营读取/复盘 | Business 后续 change | 本 change 只交付冻结的 `domain-parallel-v1` HTTP 知识只读契约，不实现消费者或增加 Java 跨域依赖 |

`playbook_card`、`commonality_report` 已在 `interview-service`，本 change 只盘点并列映射建议，保留其表、ID 和内容；新知识以新 ID 建立。报告正文和报告任务不作为首期输入，报告 ID 仅可作不透明关联。Memory 私有/共享经验不是知识资产库，也不由知识发布自动生成。

## Decisions

### 1. 新增 knowledge-service，保留访谈采集引擎

新服务和 `bk_knowledge` 管理知识资产、修订、审核、范围、依赖及操作回执。相比继续向 interview 增加所有运营动作，独立服务能区分“发生过的访谈事实”和“运营人员批准的经营知识”；相比放入营销内容服务，知识证据及内部使用范围无需与渠道发布状态混用。

现有案例报告和报告任务留在访谈域，v1 只记录可选的不透明 report reference，不读取报告正文、不重放任务；知识域新增的打法/运营案例有独立 ID 和 revision，不能将旧 reportId/assetId 直接当作新业务对象。原 `playbook_card/commonality_report` 表只做存量盘点和来源映射，未验证为空前不删除、不迁移、不复用其固定置信度规则。

### 2. 通过窄逐字稿契约取得获准快照

在 common 定义访谈来源元数据、精确脱敏快照读取、知识用途授权签发/撤回及当前使用检查的 wire 契约；访谈实现只读 provider 和独立授权表，知识域实现 `InterviewTranscriptSource` 适配。授权绑定 `assetId`、`snapshotHash`、`contentHash`、`contentRevision`、`sanitizationRevision`、`knowledge_derivation` 用途、唯一 consumer `knowledge-service` 和目标知识范围，并保留签发主体/撤回审计。授权变化或其绑定字段变化时立即失效。

请求从 `A2aCredentialContext.Lease` 携带 Auth 签名 `AuthorizationContext` 与 `AuthzFreshness`。原 Auth context 仍以 `agent-service` 为 audience 并按其签名验证；知识服务使用自己的注册身份为精确请求体生成服务 proof，proof audience 为 `interview-service`，绑定 caller、操作、requestId 及签名上下文。Interview 本地验签 proof/context/freshness、硬期限、精确 Auth 操作、来源 grant 和当前案例关系。沿用 Marketing→Listing 的 `readAuthorizedContext` 模式；不转发用户 Bearer、不信任模型/请求体中的 actor、不对每次读取回退到旧 `AuthPermissionRpcService` 调用。

正文只能使用 Interview 脱敏 `content`，读取前核验 grant 和当前来源状态，读取后重验快照一致；`rawContent` 不进入 common、Knowledge、Memory 或模型。MCP 的 L2/L3 / `PUBLISHED` 仅影响访谈既有公开读路径，不等同知识用途授权。没有 grant、scope 未验证、来源不可达或被撤回时拒绝正文。跨库 SQL 和复制原始逐字稿均不是接入方式。

当前 `InterviewAssetEntity` 没有 `dataMode`/可信 provenance，`source` 仅表示 `AUTO|UPLOAD`。因此无独立服务端 provenance 的存量资产返回 `UNKNOWN`，不能从来源字段、L0–L3 或发布状态推断 `REAL`；SIMULATED 只由受控来源元数据或本地测试夹具明确给出。来源模式描述不得改写旧正文、`contentHash` 或 `snapshotHash`。多来源派生结果全为 REAL 才可为 REAL；含 UNKNOWN 为 UNKNOWN，REAL 与 SIMULATED 混合为 MIXED。

知识可保存必需的原声摘录和派生正文，但每条证据须记录 grantRef、assetId、snapshot/content hash、内容/脱敏修订、精确引用和相对脱敏快照可复核的 quoteHash/定位符；不可复用当前 `EvidenceVerifier` 的占位 `matchedAssetRef="asset"` 作为稳定来源锚点。来源失效后的查询先重核 Interview grant，再输出派生正文；通知/索引失效仅优化响应速度，输出前仍复核。

### 3. 单案例运营生命周期与证据质量分开

首期实体包括 KnowledgeCase、Playbook、不可变 KnowledgeRevision、SourceRef、ReviewDecision、OperationReceipt。版本状态为 `DRAFT / REVIEW_PENDING / PUBLISHED / WITHDRAWN`；编辑创建新 revision，提交/审核/撤回使用 expectedVersion、当前操作权限和稳定 requestId，正文、决定与回执同事务。审核要求当前审核权限和明确决定，是否要求编辑与审核由不同人员完成交给受管审核策略；提交者、最后编辑者与审核者均写入审计，不能由模型自行发布。

候选至少包含经营问题、情境、前提、行动、来源所述结果、适用边界及未核实项；原声片段由服务器在对应 snapshot 内逐字核验。L2/L3 仅为来源入库质量门，业务结果标记为 `REPORTED / EVIDENCED / UNVERIFIED` 等独立证据状态。多个案例重复出现可形成有来源的候选归纳，样本量不能直接提升为“已验证有效”。

生成过程使用持久任务身份和输入/规则版本 hash。已完成任务复用原结果，失败或结果未知沿原任务核对；生成完成不等于审核发布。现有访谈报告生成继续按原契约工作，不双写覆盖它。

### 4. 知识发布与个人记忆共享各守边界

知识域根据已验签 Auth context/freshness 中的可信主体、精确操作和已验证业务身份控制发布和读取。范围必须是 Auth 可信范围与所有来源用途授权目标范围的交集；客户端填写 `storeId`/`ownerId`/员工号不授予范围。当前 `AuthzContract` 仅接受 `BROKER` identity，没有可信 `STORE` identity 映射，因此门店级发布、搜索、读取和 check 在映射/登记到位前失败关闭；不得将测试夹具视为生产门店授权。普通草稿可按当前 Auth actor 记账，但不因此可发布到门店。查询、详情、引用、导出和 Agent 输出都复核当前 published revision、范围和来源依赖。

知识索引是派生能力：首期提供按经营场景、标签、关键词和版本的受权数据库检索；需要语义检索时独立通过 RAG 受管空间和可靠投影接入，首期不新增第二套向量/embedding。知识发布不写入私有偏好，也不自动发布 MemoryDomainExperience；后者继续要求独立治理、非个人归纳和保留授权（LR-90/94/97）。

### 5. 官方 Agent 使用既有接入模式

登记 `knowledge-service / knowledge / knowledge-agent` 的独立服务身份、公钥与必需 Memory caller 范围，使用 common-a2a/common-skill。新增案例提炼、打法审核准备、知识检索技能；审核与发布仍是带当前权限和参数版本的领域动作，不通过纯文字结论推进状态。Agent 仅增加 `knowledge-agent -> knowledge-service` 受众解析和 `a2a:knowledge-agent -> agent.workflow.invoke.knowledge` 精确 capability 映射；Supervisor 继续动态发现能力，不新增固定领域执行分支。

当前 `OfficialA2aAgentClient.audienceFor` 用硬编码 agentId switch 选择服务 audience，`nacos/agent-service.yaml` 的 capability map 只有 `local:milvusKnowledgeSearch` 且 `agent.distributed.agents` 为空。因此本 change 只增加 knowledge-agent 对应的 audience switch、`agent.authorization.capability-permissions` 项和最小 `agent.distributed.agents.knowledge-agent` 动态注册块（serviceId/baseUrl/Agent Card/A2A path/domain/skills）；不改通用发现或 Graph 逻辑。服务 baseUrl/key 由受管环境变量/协调配置供给，不硬编码 secret。

当前 Auth permission catalog 有 `agent.workflow.invoke.interview`/`.memory` 及访谈资产操作，但没有 knowledge Agent、知识生命周期或来源用途授权操作；`AuthzContract` 不支持 `STORE` business identity。需交付协调登记清单（精确 Knowledge 动作操作见下段）：`agent.workflow.invoke.knowledge`、`interview.asset.knowledge-use.grant`、`interview.asset.knowledge-use.read`、`interview.asset.knowledge-use.revoke`，以及最小可信调用者/公钥/密钥来源和角色策略差异。此 change 不修改 `auth-service`/Auth 数据，也不把 legacy permission RPC 当替代。新 Knowledge 服务使用独立 keyId/keypair，不复用 Interview key、bearer 或 Memory caller。测试中用精确注册夹具验证正反路径；未登记/缺密钥时真实模式拒绝。

知识域生命周期另需协调登记精确操作，Agent invoke 权限不能代替领域动作权限：`knowledge.case.create.own`、`knowledge.case.edit.own`、`knowledge.case.submit.own`、`knowledge.case.generate.own`、`knowledge.case.review.decide.own`、`knowledge.case.review.decide.store`、`knowledge.case.publish.own`、`knowledge.case.publish.store`、`knowledge.case.withdraw.own`、`knowledge.case.withdraw.store`、`knowledge.case.read.own`、`knowledge.case.read.store`。`search`、batch-read 和 `check` 使用匹配资源范围的 `.read.own` 或 `.read.store`；`own` 以验签 actor 对知识记录的所有权核验，`store` 还必须以 Auth 可信 STORE identity 校验。当前 Auth catalog 不包含这些操作且不支持 STORE 映射；它们仅是本 change 的协调交接，不新增本分支 Auth 代码或开放生产门店权限。Auth 角色授予/服务身份登记未合入前，涉及相应操作的真实调用失败关闭。

### 6. 并行会话与知识 v1 契约

本会话拥有 knowledge 模块、访谈窄读取/grant 适配、`common/knowledge/**` 和 `common/interview/transcript/**`；仅对 `agent-service` 增加上述必要 capability/audience/config 映射。不修改 `auth-service`、`memory-service`、房源、经营、营销的业务模块和类型。向下游提供 `domain-parallel-v1` 的 published/search、batch-read、check 三个 POST 接口，路径、数据模式和错误语义见 [并行总览](../../../docs/business-domain-change-roadmap.md)。经营消费者可以独立用其自有 HTTP fixture，不要求本会话先发布 Java 制品。

本域生成/审核/发布和持久回执都是真实实现；访谈来源夹具/injvm provider、Auth 签名 identity/operation 测试 registry 和 Memory RPC 测试登记只替代远程依赖。应在共同基线上完成全生命周期、grant/read/revoke、身份拒绝、范围交集及版本测试，不拉取其它三个 change。真实模式不使用这些测试替身，缺接线前置失败；真实 Auth/Memory 登记、可信 STORE 映射和下游互通在合并后核验。

工作区、分支、Maven仓库和数据库按总览独立。新增知识 SQL/check 在 `bk_knowledge` 隔离库验收；Interview 的唯一增量仅是独立的来源用途授权表及其 `bk_interview` migration/check，不改 `interview_asset` 的旧快照/hash，不触碰 report/task 表。交付两个 schema 的 manifest/allowlist 差异，由协调合并且不修改已应用 hash。本 change 可以独立完成，不以共享环境变更已执行为完成条件。

## Risks / Trade-offs

- [撤回后旧副本继续被引用] → 依赖记录、输出前复核及失效投影共同约束；保留操作事实但停止正文使用。
- [Auth 缺 `.knowledge`/来源用途操作与 STORE 身份] → 在本域用精确签名上下文 fixture 开发；真实知识 A2A/门店级发布读取待协调登记前关闭，不能用 payload ID 或 `businessAdmin` 代替明确操作/范围。
- [来源用途授权与知识撤回状态短暂不一致] → 每次正文读取、published/check、Agent 输出前重核来源 grant；通知和索引只作加速，不作授权事实。
- [模拟材料混入真实效果证据] → 来源和派生 revision 继承数据模式，模拟候选保留醒目标记且不兑换真实结果证据。
- [拆分重复生成旧报告] → 新资产与旧 report/source 引用分开，原任务与已完成结果不重放。

## Migration Plan

以下为合并后的共享部署计划。独立会话交付代码、配置片段、SQL/check、隔离验证和步骤，不直接迁移原开发库或等待 Auth/其它 change 上线。

1. 添加新模块、配置、`bk_knowledge` schema 和 Interview 来源授权增量及对应初始化片段；保留访谈资产/报告 API 与原表；核对旧占位表和报告存量，只产出映射清单。
2. 增加访谈来源用途授权表、授权签发/撤回与窄来源 provider，再实现知识 source adapter；验证签名上下文/服务 proof、grant/hash/撤回/UNKNOWN 模式后，接通单案例草稿及审核发布。
3. 先发布知识只读契约，再由协调会话登记 Auth 操作、`knowledge-service` 身份与 Memory caller；Agent 增加精确 audience/capability 映射。检验缺登记或 Memory 不可用时模型和业务工具均零执行。
4. 在模拟材料上验收发布、引用和撤回闭环；之后由独立的门店复盘 change 消费公开契约。
5. 回退停止知识新动作并取消能力发现，保留新 schema、版本及回执；逐字稿与原访谈运行能力继续由原服务承担，不回写/重建旧报告。
