# 经验知识运营域拆分设计

## Context

动机见 proposal.md。`InterviewCollectionService` 已生成脱敏逐字稿资产及 snapshotHash，`InterviewReportService` 已有异步案例报告、原声核验和任务回执；`PlaybookCardEntity`、`CommonalityReportEntity` 当前为占位。访谈运行面、状态机和证据脱敏具有既定边界（LR-3/4/5/6/8），不得迁成 Supervisor 的自由工具步骤。

用户指定逐字稿由访谈业务域接入，数据当前模拟；初期服务知识运营人员与店长的经营提效。现有 Memory v2 共享经验是独立治理对象（LR-90），不能代替运营人员可编辑、审核、撤回的知识资产库。

## Goals / Non-Goals

**Goals:** 建立独立知识运营事实、来源证据与发布生命周期；保持访谈真源；向店长业务提供当前受权的知识版本。

**Non-Goals:** 不搬迁会话/话轮/逐字稿，不改变访谈状态机或原报告接口；不建设新人带教、自动绩效评定或直接共享个人 Memory；不把行业大脑语料建设 KI-13 当作本 change 已解决。

## Decisions

### 1. 新增 knowledge-service，保留访谈采集引擎

新服务和 `bk_knowledge` 管理知识资产、修订、审核、范围、依赖及操作回执。相比继续向 interview 增加所有运营动作，独立服务能区分“发生过的访谈事实”和“运营人员批准的经营知识”；相比放入营销内容服务，知识证据及内部使用范围无需与渠道发布状态混用。

现有案例报告留在访谈域，可作为只读来源关联；知识域新增的打法/运营案例有独立 ID 和 revision，不能将旧 reportId/assetId 直接当作新业务对象。原 `playbook_card/commonality_report` 表只做存量盘点和来源映射，未验证为空前不删除、不复用其固定置信度规则。

### 2. 通过窄逐字稿契约取得获准快照

在 common 定义 `InterviewTranscriptReadRpcService` 的元数据检查、精确快照读取和当前使用检查；访谈实现只读 provider，知识域实现 `InterviewTranscriptSource` 适配。请求绑定当前可信用户/调用服务、用途、assetId 和期望 hash；沿用现有专用身份传输，不把 token 放入模型参数。sourceRef 包含来源类型、assetId、snapshotHash、数据模式和服务端可核验范围。

正文读取前确认当前访问权、状态及来源 hash，拿到后再次确认快照一致；公开 MCP 的 L2/L3 可见性不自动成为新跨域授权。缺少受信范围适配、来源不可达或被撤回时拒绝正文使用。跨库 SQL 和上传一份“新原稿”均不作为接入方案。

窄读取契约同时增加服务端来源性质：模拟材料为 SIMULATED，未确认性质的历史资产为 UNKNOWN，不能从归档评级推断 REAL。该元数据作为来源描述新增，不改写已冻结的逐字稿正文及 snapshotHash；多来源派生结果只有全部确认真实才为 REAL，含未知为 UNKNOWN，真实/模拟混合为 MIXED。

知识可保存必要引用片段及派生正文，必须列出来源依赖；逐字稿原文仍只由访谈域维护。来源失效后的查询先判依赖，再读派生正文；失效传播通知只优化响应速度，输出前仍复核。

### 3. 单案例运营生命周期与证据质量分开

首期实体包括 KnowledgeCase、Playbook、不可变 KnowledgeRevision、SourceRef、ReviewDecision、OperationReceipt。版本状态为 `DRAFT / REVIEW_PENDING / PUBLISHED / WITHDRAWN`；编辑创建新 revision，提交/审核/撤回使用 expectedVersion、当前操作权限和稳定 requestId，正文、决定与回执同事务。审核要求当前审核权限和明确决定，是否要求编辑与审核由不同人员完成交给受管审核策略；提交者、最后编辑者与审核者均写入审计，不能由模型自行发布。

候选至少包含经营问题、情境、前提、行动、来源所述结果、适用边界及未核实项；原声片段由服务器在对应 snapshot 内逐字核验。L2/L3 仅为来源入库质量门，业务结果标记为 `REPORTED / EVIDENCED / UNVERIFIED` 等独立证据状态。多个案例重复出现可形成有来源的候选归纳，样本量不能直接提升为“已验证有效”。

生成过程使用持久任务身份和输入/规则版本 hash。已完成任务复用原结果，失败或结果未知沿原任务核对；生成完成不等于审核发布。现有访谈报告生成继续按原契约工作，不双写覆盖它。

### 4. 知识发布与个人记忆共享各守边界

知识域根据 Auth 最终提供的可信主体、组织范围及操作权限控制发布和读取；客户端填写 storeId/ownerId 不授予范围。首期只开放已实现并验证的范围能力，缺失能力直接不可用。查询、详情、引用、导出和 Agent 输出都复核当前 published revision、范围和来源依赖。

知识索引是派生能力：首期提供按经营场景、标签、关键词和版本的受权数据库检索；需要语义检索时独立通过 RAG 受管空间和可靠投影接入，首期不新增第二套向量/embedding。知识发布不写入私有偏好，也不自动发布 MemoryDomainExperience；后者继续要求独立治理、非个人归纳和保留授权（LR-90/94/97）。

### 5. 官方 Agent 使用既有接入模式

登记 `knowledge-service / knowledge / knowledge-agent` 的精确服务身份、公钥与 Memory caller 范围，使用 common-a2a/common-skill 及必需 Memory。新增案例提炼、打法审核准备、知识检索技能；审核与发布仍是带当前权限和参数版本的领域动作，不通过纯文字结论推进状态。Supervisor 只动态发现能力，不新增固定领域执行分支。

身份生成器、受管 registry/allowlist 和验证探针随新服务增加精确登记；不复用 interview 私钥或借用其 Memory 范围。Auth 的协议/角色实现由另一会话交付，本 change 只实现消费适配并验证拒绝场景。

## Risks / Trade-offs

- [撤回后旧副本继续被引用] → 依赖记录、输出前复核及失效投影共同约束；保留操作事实但停止正文使用。
- [跨域读取范围尚未接入 Auth] → 先用可信契约夹具开发，真实发布/组织读取在适配验证前关闭，不能用 payload 的工号代替。
- [模拟材料混入真实效果证据] → 来源和派生 revision 继承数据模式，模拟候选保留醒目标记且不兑换真实结果证据。
- [拆分重复生成旧报告] → 新资产与旧 report/source 引用分开，原任务与已完成结果不重放。

## Migration Plan

1. 添加新模块、配置、增量 schema 与初始化清单，保留访谈表和当前 API；核对旧占位表与报告存量，只产出映射清单。
2. 上线窄逐字稿 provider 和知识 source adapter，验证授权/撤回/hash/模拟模式后，接通单案例草稿及审核发布。
3. 先发布知识只读契约，再登记新 Agent/技能和精确 Memory 身份；检验 Memory 不可用时模型和业务工具均零执行。
4. 在模拟材料上验收发布、引用和撤回闭环；之后由独立的门店复盘 change 消费公开契约。
5. 回退停止知识新动作并取消能力发现，保留新 schema、版本及回执；逐字稿与原访谈运行能力继续由原服务承担，不回写/重建旧报告。
