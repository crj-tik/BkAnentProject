# 经验知识运营域拆分设计

## Context

见 proposal.md。当前 Interview 公开 MCP 的逐字稿搜索只返回资产元数据，不返回正文；InterviewAssetMapper.selectPublicAssets 的候选条件包括已发布、L2/L3、脱敏版本、未删除、案例有效、session 归属一致且已归档，InterviewAssetService.isPubliclyEligible 还验证标题/正文凭据形态、正文哈希和来源有效性。InterviewAssetEntity 不含 dataMode。现有 Interview Java、配置和表均由另一会话/既有服务继续维护，本 change 不修改这些部分。

本仓库的知识 Agent 模式使用 common-a2a 的 A2aMemoryHookFactory。A2aMemoryAgentHook 在模型执行前加载当前工作集，在完整 Agent 轮次后复核来源并归档；它会投影当前用户、助手、工具调用参数和工具响应。由此，Knowledge Agent 的工具消息不能携带逐字稿正文或原声摘录，否则 Memory 会保存它们。

## Goals / Non-Goals

**Goals:** 新建独立 Knowledge 微服务和事实库；在原 Interview 服务不改 Java/配置/源表的情况下，只读消费当前已发布脱敏快照；使用现有 Auth 与必需 Memory 协议；将所有新增配置放入一致的模板、容器和 Nacos 清单。

**Non-Goals:** 不搬迁 Interview session/turn/transcript/report；不改 Interview 运行状态机、Java、应用配置或 bk_interview schema；不增加 Interview 授权 API/表；不改 Auth 或 Memory Java 实现；不自动创建 Memory 共享经验；不启用尚无可信映射的 STORE 范围。

### 拆分边界

| 责任事实或入口 | 唯一归属 | 本 change 的边界 |
| --- | --- | --- |
| Interview session、turn、case、transcript asset、原始/脱敏正文、hash/修订、发布状态、报告及报告任务 | interview-service / bk_interview | 保持原样。新 Knowledge 只通过 bk_knowledge 内的受控只读来源投影视图读取当前合格脱敏内容；不修改 Interview Java、配置或源表 |
| 新经营案例、打法、revision、审核、发布范围、来源引用、生成任务、回执和 HTTP 读契约 | knowledge-service / bk_knowledge | 独立新建，不与 Interview 对象共用 ID，不回写 Interview |
| Auth 主体、签名 AuthorizationContext/AuthzFreshness、操作目录及可信业务身份 | auth-service | Knowledge 只验证/消费现有协议；精确操作码及角色授权以协调差异登记，不改 Auth 实现。当前无可信 STORE identity 时门店范围关闭 |
| 必需 Agent 工作集与会话记忆 | memory-service | 复用现有 Memory v2；只在 Nacos 精确登记 knowledge-service / knowledge / knowledge-agent caller，不改 Memory Java，不开启经验发布或共享 grant |
| Supervisor 与 Agent 能力发现 | agent-service | 仅加入 knowledge-agent 的 audience 解析、单项精确 capability 权限和最小动态注册；不加 Graph 专属分支 |
| 知识 wire/API DTO | common/knowledge/** | 只放 Knowledge 序列化契约；不增加 Interview wire provider、Entity、Mapper 或跨域服务依赖 |
| 门店复盘、来源迁移、消费者实现 | Business 或后续 change | 本 change 只冻结 domain-parallel-v1 HTTP 合同，不要求消费者先合入 |

Interview 当前 playbook_card/commonality_report 和历史报告只盘点并输出映射建议；旧表和 ID 留在原域。知识域只建新 ID 和 revision。来源 mode 不由 AUTO/UPLOAD、评级或 PUBLISHED 推断；缺少可信 provenance 的历史来源为 UNKNOWN。报告正文/任务不是本期输入。

## Decisions

### 1. 新增知识运营微服务，不搬迁旧事实

v1 将一个经营案例及其对应打法作为单个 Knowledge 聚合：`knowledge_case.knowledge_id` 是稳定 Knowledge 身份，案例元数据位于 `knowledge_case`，结构化打法/证据位于不可变 `knowledge_revision.body_json`，每次编辑或生成创建新 revision。SourceRef、ReviewDecision、OperationReceipt 和持久生成任务也只在 `bk_knowledge`。本期不再创建第二个 Playbook ID/可变表，避免冻结 v1 的 case/ref/revision 失去一致身份；将来若同一案例拥有多个独立可发布打法，应另行版本化扩展而不是复用 Interview `playbook_card.id`。新表和索引只在 bk_knowledge。新模块加入根 Maven 聚合，Compose 按服务 artifact 构建，旧 Interview 模块和服务定义原样保留。generic Dockerfile 已能打包根模块制品；实现时确认即可，不因为新增服务重复改造镜像入口。

替代方案是把运营编辑继续放入 Interview 或搬迁旧表。本方案将“发生过的访谈事实”和“运营人员审核发布的经营知识”分开，避免双重权威，并使另一并行拆分会话无需改同一 Interview 服务代码。

### 2. 通过 bk_knowledge 内只读视图接入 Interview 来源

当前没有正文 API，因此增加 Knowledge 自有的 InterviewTranscriptSource 数据库适配。为了不改 bk_interview，在 bk_knowledge 建版本化只读视图 knowledge_interview_public_source_v1。该视图从 Interview asset/case/session 联查，只投影来源 ID、快照/内容 hash、内容/脱敏/发布修订、评级、来源类型、关系状态和脱敏 content；不投影 rawContent、映射表或其他敏感列。视图 SQL 复刻当前公开资格条件：asset 为 PUBLISHED、L2/L3、未删除、sanitizationRevision 至少为当前支持版本、case 未删除；无 session 的来源只能是 UPLOAD，有 session 时必须属于相同 case 且为 ARCHIVED。Knowledge adapter 另核验标题/正文凭据形态和 SHA-256(content)=contentHash。当前 Sanitizer.VERSION 为 1；未知版本、列缺失、视图检查失败或版本不支持一律不入模。

knowledge-service 使用第二个专用只读 DataSource 访问该视图。运行账号只获得视图 SELECT，不获 Interview 表权限或写权限；视图采用 SQL SECURITY DEFINER，由受管迁移/DBA owner 创建并维护必要的源表读取权；definer 权限由迁移/DBA 控制。bk_knowledge 主 DataSource 使用独立 Knowledge 应用账号，只获 bk_knowledge 业务表所需的读写权限，不获 bk_interview 表或来源视图权限。只读投影视图 DataSource 使用另一个专用账号，仅获该视图 SELECT；两个账号均不得使用 Compose 共享的 MYSQL_USER=root。SQL 适配按 assetId 做两步读取：先取来源状态/版本元数据，满足准入条件后再取脱敏正文；正文读取绑定当前的 snapshotHash、contentHash、contentRevision、sanitizationRevision 和 publicationRevision。数据库账户与参数由环境变量/密钥注入，Nacos 仅含占位符。该策略保留强制只读边界，代价是 Knowledge 对 Interview 表结构和当前资格规则有显式耦合；视图版本与适配测试用于发现结构漂移，未知状态失败关闭。

PUBLISHED 是现有 Interview 可见性发布动作，本期将它作为唯一来源准入/分享信号，不新增 Interview 用途 grant。来源公开状态本身不授予用户 Knowledge 操作权限：只有已验签 Auth actor、当前 freshness、对应精确 Knowledge 操作和目标资源范围通过后，才能导入/生成/使用来源。Knowledge 记录发起者、assetId、来源快照/修订、用途、任务和回执。来源 PRIVATE/删除/失格时返回 NOT_ACCESSIBLE；当前内容或绑定修订改变时返回 STALE；来源库/视图不可用时返回 UNAVAILABLE。已发布知识的 search/detail/export/Agent 输出前都实时复核，不能依赖缓存或异步失效通知。后续若产品要求区别于公开发布的专用知识用途授权，应另建 Interview API change，不在本 change 暗中实现。

证据定位保存 quoteHash、相对脱敏快照的 offset/locator 与来源引用；Knowledge 生成服务可内部读取和验证短原声证据。Interview 当前没有可信 dataMode，旧资产统一为 UNKNOWN；受控测试输入可显式标 SIMULATED。多来源派生的模式不得比最不可信来源更强，不能将未知或混合样本包装成 REAL。

### 3. Knowledge 生命周期及 Auth 操作边界

Knowledge 领域包含草稿、待审、已发布、已撤回；编辑创建新 revision。写操作使用稳定 requestId、expectedVersion、内容 hash 和持久回执。模型生成与人工审核/发布分离。每个 REST、Agent tool 和下游读请求都从本地验签上下文取 actor，并检查 AuthzFreshness、精确动作和 Knowledge 记录所有权；不得从 payload、模型声明、等级、来源发布状态推断授权。来源 PUBLISHED 只控制来源能否被导入，不扩大 Knowledge 的 own/store 发布读取范围。现有 Auth 仅支持 BROKER 身份且未登记 knowledge 权限；可信 STORE 映射及操作授权到位前，真实 store publish/search/read/check 失败关闭。

Auth/权限协调差异仅包括 Agent 能力操作 agent.workflow.invoke.knowledge 与以下 Knowledge 动作码：knowledge.case.create.own、knowledge.case.edit.own、knowledge.case.submit.own、knowledge.case.generate.own、knowledge.case.review.decide.own、knowledge.case.review.decide.store、knowledge.case.publish.own、knowledge.case.publish.store、knowledge.case.withdraw.own、knowledge.case.withdraw.store、knowledge.case.read.own、knowledge.case.read.store。搜索、详情、batch-read 与 check 按目标资源使用对应 read 操作；own 还要验证 Knowledge 所有权，store 需验证 Auth 可信 STORE identity。不得登记 interview.asset.knowledge-use.* 来源授权操作，因为本 change 没有 Interview 授权 API。Auth catalog、角色策略和可信身份登记由协调 Auth 变更合入，不修改 Auth Java。测试 registry 仅用于本域隔离验收，不代表线上授权已启用。

OWN 案例由独立审核员使用 review.decide.own；非 owner 仅凭此权限可读取 REVIEW_PENDING 的当前候选，不可读其他状态或搜索。owner 不能审核自己的案例，审核批准后仍由 owner 发布。建议 Auth 角色映射为 KNOWLEDGE_AUTHOR（own create/edit/submit/generate/read/publish/withdraw）与 KNOWLEDGE_REVIEWER（review.decide.own）；它们是协调建议而非本 change 已注册角色。当前 Auth 的 businessAdmin 授权主体会获得全部 active catalog 操作码，但仍受 Knowledge owner/store 资源校验约束；roleCode=ADMIN 不等于 businessAdmin。store 操作在可信 STORE identity 签发前不分配。

### 4. knowledge-agent 复用必需 Memory，且不让来源正文进入记忆

Knowledge 官方 Agent 通过 A2aMemoryHookFactory.create("knowledge-service", "knowledge", "knowledge-agent", chatModel) 建立固定 Memory 身份；同一 hook 安装到 ReactAgent hooks 和 OfficialA2aAgentExecutor。保持现有 Memory v2 生命周期：beforeAgent 在模型/工具前加载并校验当前用户/knowledge/knowledge-agent 工作集；afterAgent 在完整轮次后校验被用记忆的来源并归档，归档失败不得释放结果。Memory caller 仅获 service-delivery 和 knowledge: [knowledge-agent] 精确白名单，并使用 Knowledge 自己的 keypair。未登记、错误 owner/context、Memory 不可用或当前工作集失败均拒绝本轮执行，不允许降级旁路。

通用 A2aMemoryAgentHook 会把用户/助手文字、tool-call 参数和 tool response 纳入 Memory 事件。因此 Knowledge Agent 的消息合同只允许传 assetId、revision、状态、任务 ID 和受限的派生摘要；逐字稿正文/原声摘录在服务内部完成读取与生成，不作为 tool response 交给 ReactAgent。Agent 工具不得接收正文输入，不得在 assistant 输出或工具参数中回显原声正文；需要查看精确证据的审核者通过单独的授权 Knowledge REST/UI 路径访问，不进入 Agent Memory 对话。对 Memory 请求/归档做哨兵文本测试，逐字稿 body、quote、rawContent 和敏感映射均不得出现在消息、工具参数或响应载荷中。Memory 仅用于私人对话上下文，不因知识发布自动签发 Memory share grant、MemoryDomainExperience 或经验学习权限。

该隔离约束比简单“使用 Memory hook”更严格，因为当前通用 hook 会保存完整工具参数和响应。若业务坚持在 Agent 对话中传递逐字稿正文或完整原声片段，必须先单独设计带字段级脱敏/拒存的 Memory 投影扩展，本期不允许该数据路径。

### 5. 配置同步矩阵

新模块配置必须按同一服务清单同步，密钥只放秘密文件或密钥管理器：

| 配置面 | 本 change 新增或调整 | 保留/不调整 |
| --- | --- | --- |
| .env.example 与已有本地 .env | 增加 MYSQL_DB_KNOWLEDGE=bk_knowledge、KNOWLEDGE_MYSQL_USER、KNOWLEDGE_MYSQL_PASSWORD、KNOWLEDGE_SOURCE_DB_USER、KNOWLEDGE_SOURCE_DB_PASSWORD、KNOWLEDGE_SERVICE_BASE_URL、KNOWLEDGE_MEMORY_V2_COMPRESSION_ENABLED。若部署已有本地 .env，只同步键名/安全默认值并保留其实际秘密值；不提交 .env。 | 当前 worktree 不存在本地 .env；若部署目录已有 .env，只同步键名并保留实际秘密值，不得提交或生成伪造口令。Interview 的 ticket、Memory、数据库和 A2A 变量保持原值/名称 |
| 公钥与私钥模板 | 为 Knowledge 生成独立服务身份；公钥变量 KNOWLEDGE_SERVICE_IDENTITY_PUBLIC_KEY 加入现有生成的 public-keys.env；私钥独立挂载 .secrets/service-identities/knowledge-service/private-key.pem。 | 不放入 .env.example、Nacos 或版本库，不复用 Interview/Agent/Memory key |
| knowledge-service application.yml | 新增端口 9015、Knowledge 主库和只读视图双 DataSource、Nacos import、Auth key、A2A 身份和必需 Memory 属性模板 | 运行秘密由 Compose/secret 注入 |
| nacos/knowledge-service.yaml | 新增服务运行配置、主库/只读视图 URL 占位符、来源视图版本、Dubbo Memory registry、服务就绪依赖、必需 Memory 属性与可选压缩配置；不存秘密 | 只在 NACOS_CONFIG_DATA_IDS 显式选中后发布 |
| docker-compose.yml | 新增 knowledge-service，指定制品与模块 build、独立私钥挂载、Knowledge 主库非 root 凭据和只读 view 凭据、端口/服务地址、MySQL/Nacos、Dubbo 注册地址、A2A_PUBLIC_BASE_URL 和 Memory 就绪依赖；添加 bk_knowledge 到可选只读 MySQL MCP 数据库白名单 | Interview service block 不改 |
| nacos/agent-service.yaml | 增加 a2a:knowledge-agent → agent.workflow.invoke.knowledge 及 knowledge-agent 动态注册元数据 | 不添加 Supervisor Graph 分支 |
| nacos/gateway.yaml | 增加 /knowledge/** → knowledge-service 路由及 knowledge-service audience 对应关系 | 既有路由与默认授权开关保持原样 |
| nacos/memory-service.yaml | 新增 knowledge-service trusted caller、公钥 keyId 和 knowledge: [knowledge-agent]；不授予 experience publisher 或共享范围 | Memory Java 与全局 Memory 功能开关不变 |
| Nacos data ID 文档/发布模板 | 将 knowledge-service.yaml 纳入可选择的受管 data ID 清单，部署时明确选择 | NACOS_CONFIG_DATA_IDS 默认仍为空，不自动上传或覆盖运行配置 |
| root pom.xml、MySQL 初始化和说明 | 聚合 knowledge-service；bk_knowledge 建表与只读视图作为单独版本化迁移/check，按 Interview 现有 schema 顺序确保源表已存在；为 Knowledge 主账号仅授予本库业务表读写权限，为视图账号仅授予只读 SELECT；同步 MCP 只读库数量说明 | 不修改 Interview migration/hash/check；generic Dockerfile若无需改则保持不变 |

关于废弃项：本 change 不废除任何 Interview 配置，也不移走它仍拥有的功能。没有已证明只属于旧知识职责且已无引用的配置键。检查发现的新变量/废弃项必须同步更新 .env.example、Compose、Spring 模板、Nacos 占位符、配置 data ID 文档与使用说明；秘密值只由部署环境提供。

Compose 中的 Memory/知识 readiness 仅描述依赖连通，不代表真实 Auth 操作登记、Nacos 配置已发布、Memory public key 已生成或 STORE 映射已验收。部署者本地 .env 与生成公钥文件分开维护；变更 Compose 环境值后需按既有容器重建流程生效。

### 6. Agent 注册和冻结消费契约

当前 OfficialA2aAgentClient.audienceFor 使用 agentId switch；本 change 增加 knowledge-agent → knowledge-service。agent-service 的 capability map 增加精确的 a2a:knowledge-agent → agent.workflow.invoke.knowledge，Nacos agent.distributed.agents 增加最小 serviceId/baseUrl/Agent Card/A2A path/domain/skills。Supervisor 继续动态发现，不新增固定业务执行分支。

对下游只提供 domain-parallel-v1 的 published/search、published/batch-read、published/check 三个 POST 接口，路径、request/response 包络和状态以 docs/business-domain-change-roadmap.md 为准。check status-only；所有返回均依赖当前 Auth scope、published revision 与 Interview view 状态，不要求下游 change 先合并。

### 7. 并行会话与部署边界

本 change 拥有新 knowledge-service、bk_knowledge、Knowledge 只读来源视图/适配器、Knowledge DTO、Agent 映射和上述配置差异。它不拥有 Interview Java/config/源表，也不修改 Auth/Memory Java 或其它业务域。当前并行总览的知识范围行仍记有 Interview 窄来源适配和 common/interview/transcript；需由协调会话在实施前同步为本设计的 Knowledge 只读视图边界，冻结 HTTP 契约不变。共享 root pom、docker-compose、.env.example、Nacos 模板、MySQL manifest/MCP allowlist 的改动需保持为本 change 的显式小差异；与另一个会话发生相同共享文件变更时按键/服务块逐段合并，不覆盖对方服务配置。

来源测试在 Knowledge DB/source-view fixture 隔离库进行，不要求共享数据库写入。MySQL 初始化迁移前向追加，不改已应用 SQL/check/hash。Agent/Memory/Auth 测试使用本 change 的独立服务身份夹具；真实 key、角色授权、Nacos 发布及 STORE 映射单独交接。下游 HTTP 消费者可用本域固定契约和独立测试夹具验证，不拉取并行模块的未合入制品。

## Risks / Trade-offs

- [Interview 表结构或公开资格规则变化导致镜像视图漂移] → 视图版本化并锁定当前列/筛选；Knowledge 集成检查与 Interview 当前 mapper/service 规则对照，未知版本或差异时关闭来源读取。
- [source DB 账户读取越界] → Knowledge 主账号只访问本库业务表，来源账号只 SELECT bk_knowledge 受限视图；视图 definer 才有源表读取权限且不暴露 rawContent，Compose 应用容器不注入 root 凭据。
- [PUBLISHED 不支持比公开发布更细的 Knowledge 用途授权] → 本期只允许从当前公开合格来源派生，并对每次调用单独执行 Knowledge Auth 操作；专用用途授权另立 Interview API change。
- [撤回与请求并发] → 每次正文读取和最终输出都复核 publicationRevision；来源状态未知时返回 UNAVAILABLE，禁止回退到缓存。
- [Memory hook 会保存工具参数和响应] → Agent/tool 消息只传来源 ID、任务状态和受限派生摘要；正文与精确原声留在 Knowledge 内部工作流及授权审核界面，并用 sentinel 测试验证不入 Memory。
- [Auth 缺少 Knowledge 操作或 STORE 身份] → 提交精确操作/角色/身份登记差异；真实调用在登记前失败关闭，不用 payload ID、业务管理员角色或测试 key 代替。
- [配置在 .env、Nacos 与 Compose 间漂移] → 实施任务维护逐项矩阵并扫描所有引用；Nacos data ID 显式选择，秘密值不写入模板/仓库；本 change 无待删除 Interview 键。

## Migration Plan

以下为合并后的部署步骤。本 change 只交付代码、迁移/视图、配置片段、校验结果和 runbook，不直接操作共享数据库、Docker 或 Nacos 运行环境。

1. 增加 knowledge-service、bk_knowledge 主 schema 与 Interview 当前公开来源投影视图；Knowledge 主账号仅写本库业务表，来源只读账号仅 SELECT 视图，definer/DBA 负责受限源表读取权；应用容器不注入 root 凭据，不应用 Interview migration。
2. 部署者在本地 .env/密钥管理器配置 Knowledge DB 与独立只读视图账号、Knowledge 服务私钥/公钥；不提交实际秘密。核对 .env.example 与 Compose 变量齐全。
3. 按审核后的 data ID 显式发布 knowledge-service、agent-service、gateway 和 memory-service Nacos 配置；Memory 配置只登记精确 caller/domain/agent 和公钥。
4. 先启动 Knowledge 新服务并验证数据库视图、必需 Memory、Agent 动态发现和 Auth 拒绝/允许路径。Auth 操作登记、Memory 公钥、Nacos 配置未就绪时相应真实入口保持关闭。
5. Gateway 发布 /knowledge 路由和能力映射，验收冻结 HTTP 契约与来源撤回/过期/不可用场景。回滚可停止 Knowledge 并撤掉 Gateway/Agent 注册；保留已应用 Knowledge schema/hash，不删除 Interview 或 Memory 数据。视图/Knowledge 版本迁移只前向应用。

## Open Questions

无。本期来源准入、只读数据库/视图方案、Memory 消息限制、Auth 操作交接和共享配置边界已在 proposal/spec/design/tasks 中固定。
