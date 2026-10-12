## Context

动机与范围见 [proposal.md](proposal.md)。本设计是 `extract-isolated-rag-service` 的规划，不是已实施或已部署的事实；替代旧 `split-rag-by-domain`，不修改其历史工件。

当前与迁移直接相关的事实：

- `agent-service/.../milvus/core/impl/SpringAiMilvusDocumentStore.java` 已通过 Spring AI `MilvusVectorStore` 与 `EmbeddingModel` 写入/检索；独立 `MilvusVectorDocument.vector` 不被该路径消费。`DefaultMilvusEmbeddingService` 的截断不代表实际存储路径统一截断。迁移复用实际路径，不重写裸 SDK 或 embedding HTTP。
- `agent-service/.../milvus/listing/ListingMilvusService.java` 通过 RPC 从房源 ES 召回，再从 Milvus 召回，按房源 ID 合并后交给 `BgeRerankService`；现状没有显式融合算法。关键词命中及向量命中还反查房源域文档/详情。房源事务在提交前触发 ES 和 agent RPC，agent 再回调房源，存在提交前读取和失败丢同步的问题。
- `listing-master-service/.../service/impl/ListingSearchServiceImpl.java` 持有 `ElasticsearchOperations`，历史 ES 写入默认关闭；房源 Agent 的 `searchListingsByKeyword` 工具和技能仍是活跃消费者。现有 SQL 列表/详情不是该 RAG ES 路径；营销 `marketing_content` 的独立业务 ES 搜索不纳入本次，不能误删其配置或声称全仓没有其他 ES 客户端。
- 记忆 MySQL 在 memory-service，向量初始化、系统约束 bootstrap 与偏好相关旧类在 agent。偏好 upsert/decay、约束 upsert/active 更新没有完整向量同步。偏好 collector/retriever 当前无业务调用方。约束 bootstrap 属性存在但旧 runner 未遵守，不应照搬重启覆盖人工数据的行为。
- 旧集合配置为 `agent_knowledge`、`listing_knowledge`，偏好/约束使用 `user_preference_knowledge`、`system_constraint_knowledge`；向量配置为 1024 维、COSINE、IVF_FLAT。真实 schema/现存数据未在线核验；逻辑 ID、source_type、source_id 实际位于 metadata，迁移不可假设为顶层 schema。房源 ID 是 `listing:{id}`，旧偏好 ID 的下划线拼接不保证无歧义。
- 根声明 Java 17、Spring Boot 3.5.0、Spring AI 1.1.2、Alibaba 1.1.2.3、Dubbo 3.2.15，实施时以有效依赖树为准。现有网关注入的固定标记和普通 userId 不形成服务认证；`hasPermission` 仅验证账号/角色活跃，不能作为 RAG 空间授权的充分条件。
- 当前 `host.docker.internal:18119` 重排与容器/私网 ES HTTP 不符合本次出站 URL 约束。只做 TCP 的公共 readiness 不能证明模型、schema、过滤与重排协议可用。

关联约束：LR-17/18/19（模型选择、Graph 唯一执行、稳定能力身份）、LR-22/23/29（事实恢复与确定失败）；KI-2（真实状态转换调用点单测）、KI-13（行业语料未建）、KI-37/38/39/42（迁移/启动验收）、KI-48/49/50/51/53（依赖/配置误配）。本次实施不顺带修复所有存量债务。

## Goals / Non-Goals

**Goals:**

- 使整个 RAG 执行链只有一个运行位置，域通过有版本、有界契约消费，默认 ES + Milvus + 融合 + 重排。
- 将物理隔离、空间授权和空间内过滤分开实现；服务端映射目标，任何调用不能自行创建/挑选后端。
- 复用已有存储封装，明确迁移新增的融合、身份、双索引生命周期与行为变化，不把它们包装成无行为变化的搬迁。
- 用可靠事件与应用状态账本实现最终一致性和可回放；不承诺 MySQL/ES/Milvus 原子事务或网络 exactly-once。
- 保留工具/端点/审计包装与领域最终校验，清理源实现和配置，隔离 RAG 故障的影响。

**Non-Goals:**

- 不新建 common-milvus 分发底层实现，不增加为了双实现而存在的适配器、profile/optional 多实现或新的 embedding HTTP 客户端。
- 不迁移业务数据库，不把所有 Elasticsearch 业务搜索自动定义为 RAG；营销现有业务搜索保持原状，未来接入本服务时另作明确变更。
- 不默认每用户一个集合；用户隔离在独立知识空间内使用强制授权条件，不把用户数直接转成集合数。
- 不建设新的 episodic/行业知识采集流程，不激活偏好死代码，不实现任意评分 DSL、Milvus 原生 decay ranker或新 chunking pipeline。
- 不新增 Supervisor 业务节点、技能步骤调度器、A2A RAG Agent 或旧 Graph 恢复路径，不进行全项目 RBAC 重构。
- 不上线、不改现有容器/配置中心、不公开语料或删除旧数据；当前阶段只生成规划。

## Decisions

### D1: 独立服务承载现有实现，不通过共享库分散执行

新增 `rag-service` Maven 子模块，普通 Spring Boot 可执行 JAR，package `com.bkanent.rag`。将现有 Spring AI 存储接口、模型、实现及必需配置迁入服务；既有 BGE native/DashScope 两种重排响应处理迁入并补充协议校验。ES 的 `ElasticsearchOperations` 执行逻辑从房源服务迁入，去除业务 Mapper 依赖，改以领域提交的检索投影写入/返回。

common 仅新增纯 Java RAG DTO 和 RPC 契约；不让消费者依赖服务 JAR 或后端客户端。原 Milvus 审计 JSON 字段保留，序列化位置与运行位置分开决定。首次消费者切流版本必须同步迁走/删除原执行类、Spring bean、初始化与对应依赖，不能删除starter却保留直接import它的执行源码；下一清理发布只延后退役旧RPC签名及不执行后端的包装。agent 的 chat/Graph/A2A 依赖不因 embedding 迁出而随意改装配；确认 OpenAI starter 是否还有 chat 用途后再删除，不只按包名判断。

保持 1024 维及现有 embedding 模型配置；实际模型维度要验收。集合实例缓存以不可变空间 schema 身份（database+collection+schemaVersion+modelVersion）为键，禁止同一物理集合被不同维度/字段配置重用。Spring AI 未暴露的行为先核对当前版本；本设计不假设 SDK 原生 decay ranker 已可用。

**替代方案**：common-milvus 下沉给每个域仍会产生多个执行位置，不符合用户决定；裸 SDK/HTTP 重写增加兼容风险而无必要，均不采用。

### D2: 知识空间注册是一对独立索引/集合，不是任意集合接口

部署管理的 `rag.spaces` 注册包含 spaceId、ownerDomain、允许 callerService/operation、ES 物理索引、Milvus database/collection、schemaVersion、embeddingModelVersion、过滤/返回字段、数量上限与评分 profile。初始化走独立管理操作，不由 search/upsert 对未知空间自动创建。

首期明确空间：

| 逻辑 spaceId | 语料/所有者 | 物理边界策略 |
|---|---|---|
| listing.knowledge | 房源知识 / listing-master | 独立 listing ES 索引与 Milvus 集合 |
| memory.preference | 用户偏好 / memory | 独立偏好索引与集合，强制用户过滤 |
| memory.constraint | 系统约束 / memory | 独立约束索引与集合，仅允许已定义读取/管理权限 |
| supervisor.knowledge | 已盘点通用知识 / agent 管理入口 | 独立通用索引与集合，归属未整理不可激活 |

这些标识是新契约，不代表现有索引已创建；物理名称在受管迁移清单确认，例如采用 `rag_listing_knowledge_v1` 等稳定名称，不由用户输入生成。不同域、独立持有语料的不同 SubAgent 不得映射同一对物理目标。同域多个消费者共享**同一授权知识空间**需要显式注册授权，不因消费者名称不同自动复制业务语料；若其语料需要隔离则创建新空间及独立物理目标。注册校验禁止两个不同空间指向同一物理集合或索引。

同空间内可用 tenantId（仅已有真源支持时）、ownerId、visibility、allowedResourceIds、active 等条件；不能捏造项目尚未存在的组织体系。先按可信 userId 和领域资源授权落地，租户字段无真实来源则不开放。

**替代方案**：单集合/索引靠 source_type 标签隔离违背用户要求；每用户一集合会无必要扩大管理规模，均不采用。

### D3: 用 Dubbo 提供纯服务契约，双重身份与授权失败关闭

common 新增 `RagRetrievalRpcService.search`、`RagIndexRpcService.submit/getStatus`、`RagSpaceAdminRpcService.inspect/provision/replay`。领域入口负责真源权限及最终 DTO，RAG 不需要独立公网搜索 REST；保留旧 HTTP 入口在 agent 的兼容包装。memory 明确添加并装配 Dubbo consumer/provider（必要领域投影桥），不是依赖 common 的传递类路径。

内部服务认证采用按服务独立的 HMAC-SHA256 密钥，RAG 持有受管 keyId→callerService/允许 domain/operation 映射，密钥仅在秘密管理/环境提供。认证 envelope 签名覆盖协议版本、audience=rag-service、callerService、operation、requestId、规范请求哈希、actorMode、有效期限（最长 60 秒）和凭据摘要；RAG 验证签名、时间窗、请求绑定与允许操作。身份来自 key 映射，不来自 Dubbo application name、gateway 字符串或模型。幂等索引允许签名重新生成但事件 ID 不变；重复检索只读允许受同一有界策略执行，不以 nonce 承诺索引 exactly-once。

用户检索另携带现有 access token，作为 RPC 的专用凭据上下文。现有 `AuthenticatedPrincipal` 只有userId，不能据此获得角色事实；common/auth新增仅RAG需要的 `resolveRagPrincipal` 契约，返回当前userId、真实roleCode及角色活跃事实，复用现有token解析与auth表读取，不改变原 `resolvePrincipal` 返回结构或扩张为通用RBAC重构。RAG验证该结果并核对签名envelope的actorId；RAG→auth RPC同样校验受管服务签名，伪造认证响应不得被接受。网关/调用入口从已验证 Authorization 得到凭据，模型、技能参数、普通 structuredContext 不能提供或覆盖它。凭据不进入工具 schema、日志、审计快照/checkpoint。异步/续接每次检索仍需有效认证；已过期时明确返回 AUTHENTICATION_EXPIRED，原 owner 重新认证后才可续接，不永久存储 token 或凭 userId 自动换发。后台索引可 actorMode=SERVICE，只获本域写/状态权限，不得转为用户搜索。

RAG 专属权限依据受管空间 policy 中的 caller 操作、活跃 principal 的角色读取/管理基线与领域授权 scope 三者交集；**不以当前宽松 hasPermission 返回 true 作为空间授权**。房源域作为 listing.knowledge 的授权权威。现有brokerId来自业务请求，未证明与认证userId相等，因此新增仅用于本域的 `listing_rag_grant` 真源授权表（actorUserId、resourceId、operation、active、version），由受管管理角色维护；域按已验证principal和该表交集生成有界签名 `allowedResourceIds`，默认无grant即拒绝。ADMIN管理基线及其他允许角色由受管policy明确列出，首次切流由部署方核对并配置授权种子，不用brokerId/userId相等推断或自动向全部活跃账号授权。该表不改变原SQL列表API或全系统认证，只闭合新RAG资源读取边界；所有grant变更进入投影/outbox及scope版本核对。记忆域为自身空间授权权威，用户偏好强制 ownerId=principal.userId，约束仅明确平台读取基线/管理角色开放。agent 直接读取 supervisor.knowledge 只能使用其空间已定义策略。

授权 scope 绑定 spaceId、actorId、operation、requestHash、policyVersion、expiry，签发权仅归该空间注册的 ownerDomain key；普通消费者不能自己签发别域 scope。范围无法由既有真源证明时拒绝，不用“所有活跃账号可读”弥补业务权限缺口。v1 对 allowedResourceIds 设有界长度（默认 5000）；无法在此范围表达的权限请求明确 RESOURCE_SCOPE_TOO_LARGE，不去除过滤或引入脚本。本次仅实现 RAG 必需的域授权接线，不扩张为全系统认证重构。

**替代方案**：裸内网直连、userId/headers 或只用服务发现不可信；复制用户签名密钥到 RAG不必要；本次新增通用授权平台/身份 DSL 超范围。选择现有用户认证 RPC加最小 RAG 服务认证与领域 scope。

### D4: v1 检索契约明确策略、数量、过滤和响应

示例仅展示业务 DTO；认证 envelope/access token 不属于模型可填的 JSON：

```json
{
  "contractVersion": 1,
  "requestId": "server-generated-id",
  "spaceId": "memory.preference",
  "query": "用户对部署方式的要求",
  "filters": [
    {"field": "category", "op": "in", "value": ["deployment"]}
  ],
  "recall": {"hybrid": true, "keywordCandidates": 100, "vectorCandidates": 100},
  "fusion": {"strategy": "RRF", "rrfK": 60, "keywordWeight": 1.0, "vectorWeight": 1.0},
  "semanticRerank": true,
  "rerankCandidates": 100,
  "candidateLimit": 50,
  "failurePolicy": "STRICT"
}
```

默认值和关系（均是设计默认，可由受管空间 policy 在同一范围内收紧，不是性能承诺）：

| 参数 | v1 默认 / 有效范围 |
|---|---|
| query | 非空，最多 8192 字符；超限拒绝，不静默截断 |
| hybrid | true；false 时必须提供 singleRecall=KEYWORD 或 VECTOR |
| keywordCandidates / vectorCandidates | 启用路默认各 100，1–500；未启用路不得提供有效数量 |
| fusion.strategy | 仅 RRF；未知策略拒绝 |
| rrfK / 各路 weight | 默认 60（1–200）；默认 1（0.1–10，有限数） |
| rerankCandidates | 重排开启时默认 100，1–500，且 >= candidateLimit；关闭时该参数不生效且显式提供则报错 |
| candidateLimit | 默认 50，1–200；开启路数量之和必须 >= rerankCandidates（重排开）或 candidateLimit（重排关） |
| semanticRerank | true；允许显式 false |
| failurePolicy | STRICT；显式 ALLOW_DEGRADED |

数量约束是请求的容量检查，不保证独立结果足量（两路可能重复或被过滤）。请求字段枚举严格，非法关系报 INVALID_ARGUMENT，不将缺省 candidateLimit 擅自变成用户最终 topK。旧工具 topK 的 1–200 范围在消费层验证，超限明确拒绝。

过滤为类型化条件数组，全部 AND，`in` 表达字段内部 OR；v1 不开放任意 OR/NOT、nativeFilterExpression、SQL/ES/Milvus DSL或执行脚本。类型化 AST 经现有后端 API生成查询，不用外部原生字符串拼接。字段/算子注册：

| 空间 | 可由域提供的过滤 / 必需返回元数据 |
|---|---|
| listing.knowledge | region:contains_ci；layout:eq/in；area、totalPrice:gte/lte；status:eq/in；verified:eq。返回 listingId/title/address/layout/area/totalPrice/status/verified及权限字段 |
| memory.preference | category/preferenceKey:eq/in；lastObservedAt:gte/lte；confidence:gte/lte。返回 userId/preferenceKey/category/confidence/lastObservedAt/observationCount |
| memory.constraint | category/constraintKey:eq/in；active:eq（普通搜索强制 true）。返回 constraintKey/category/tags/active |
| supervisor.knowledge | 只开放实际盘点 schema 中已落库字段；未注册字段拒绝 |

occurredAt、memoryType、importance 仅在未来已有真源的记忆空间中注册并同步到两路后开放，不能假装现有偏好表有 episodic 字段。v1 为未来真实字段保留同一 eq/in/gte/lte 机制，不实现不存在的数据采集。时间统一 UTC，偏好使用 lastObservedAt 而非 ingestion 时间。房源 region 保持当前地址包含的语义，不声称已拥有行政区编码；contains_ci 两路语义必须一致，非 ASCII 与转义用测试验证，若当前 Spring AI 过滤无法直接表达则先用**经过鉴权的有界候选批次**同义过滤补足，不把不支持字段当作已应用。新文档需有与关键词相关的真实属性，ownerName 等个人信息不默认加入正文/重排文本，旧检索隐私字段变化在兼容公告说明。

响应包含 contractVersion/requestId/spaceId/strategyVersion、requestedMode/effectiveMode、degraded/failedStages、候选及各阶段统计（不泄露跨权限命中计数）。候选包含 documentId/sourceType/sourceId/content/projectionVersion/schemaVersion、sources、keywordScore/vectorScore/fusionScore、rawRerankScore/relevanceScore/scoreKind/scoringVersion 和注册的 metadata。仅候选统计，不返回未经授权文档/索引地址。

**替代方案**：仅“传配置”不可验收；任意字段/算子/脚本造成执行与权限失控；固定返回 5 条不够领域排序。选择有限且可扩展的 v1。

### D5: RRF 融合与语义评分分层，不混加异量纲分数

两路按空间内 documentId 去重，先分别形成有序列表。RRF：`fusionScore = Σ(weight_i / (rrfK + rank_i))`，rank 从 1 起。相同分数按 documentId 稳定排序。保留 BM25 与 vector 原始分数，但不当作可直接相加的相关度。

重排开启时取融合前 rerankCandidates 条的**已授权有效同版本**正文，一次调用既有重排协议，请求要求覆盖全部输入候选（DashScope top_n=实际输入数）；校验索引唯一、范围、完整性及分数有限。排序再按 candidateLimit 返回；空输入不调用重排。

scoring profile 与提供方/模型版本绑定：提供方声明输出 logits 时用 sigmoid，声明 bounded_0_1 时原值并校验范围；部署必须以实际响应/协议验收选择 profile，未声明或不符则 SCORING_PROFILE_MISMATCH，不猜测 BGE native 返回哪一种。语义候选 `scoreKind=SEMANTIC_NORMALIZED`、relevanceScore在 [0,1]，这是排序相关度，不是统计概率，也不承诺不同模型版本同量纲。

关闭/降级重排时 `scoreKind=RANK_FUSION`（单路为 RANK_SINGLE），使用各路 rank 的 RRF 分数除以本次启用权重在 rank=1 的理论上界生成 [0,1] 排序指标；该数不是语义相关度。同一请求可能因去重与过滤有不足候选，应据实返回。域的语义阈值策略只对已验证 SEMANTIC_NORMALIZED 版本应用；默认最终评分不把 rank 分数当语义分数。

**替代方案**：原始 BM25+cosine 相加不合理；未检查分数便套用户示例权重会错用尺度；引入全套可编程 final_scoring 不在本期。

### D6: 领域保留最终排序与真源校验

房源域调用 RAG 后按 ID 批量读取最新详情，重新检查权限/状态/原结构化条件，再映射旧 `ListingRagMatch`、拼接上下文；RAG 不反查域。权限 scope 在检索时由领域真源签发并先于重排过滤；最终校验解决事件传输窗口内的业务更新与撤权，仍不承诺跨库瞬时快照。

记忆域先确定可信 owner/范围与类型，再调用对应空间；当前偏好/约束可用性继续以真源 confidence/active 规则核验。时间衰减、importance 或商业排序作为独立领域函数接入，输出确定性策略版本；本期不强制用户示例的 0.8/0.2 或 30 天，也不添加尚无真源的 importance。若采用半衰期，使用 `2^(-ageDays/halfLifeDays)`，相关度阈值先执行且仅适用明确语义尺度；偏好时间采用 lastObservedAt。通用 RAG 返回足量元数据，域最终 topK不等于 candidateLimit。

真源校验淘汰候选时最多再扩大一次候选池（不超过空间上限及请求总预算），不能无限补取或调用域内 ES/Milvus。剩余不足如实返回；连续补取可能重复，域按 documentId 去重。

**替代方案**：把领域 decay塞进通用 reranker混淆职责；RAG 先选最终 5 条会使域无法恢复被裁掉候选；全部最新业务查询搬 RAG会产生数据库耦合，均不采用。

### D7: 领域 outbox + RAG 独立应用账本，双索引最终一致

房源与记忆域分别新增事务 outbox及稳定文档投影 revision（业务表没有适合版本时使用领域 `rag_projection_revision` 表，主键稳定业务身份）。真源变更与 revision/outbox 在同一事务中完成；outbox保存完整投影，事务提交后 dispatcher经 Dubbo发送，不在事务内远程取文档。不强制增加消息中间件，利用现有数据库与有界轮询，避免为迁移新增 broker 依赖。

事件含 eventId、spaceId、documentId、revision、schemaVersion、UPSERT/DELETE、contentHash、正文/类型化属性、createdAt。同一revision只能一种规范payload；相同revision不同hash冲突；删除保存 tombstone。房源稳定 ID继续 `listing:{id}`，偏好用有长度边界的规范复合身份编码（例如两个 UTF-8 段各自 Base64URL，带类型前缀），定义旧下划线 ID映射与迁移去重；约束 key采用相同有边界规范规则。v1不自动 chunk，长文档按空间/模型限制明确拒绝且留失败事件，不统一套旧未生效截断。

RAG 独立 `bk_rag` 数据库只存**索引控制事实**，不成为业务真源：`rag_index_event`保存规范payload与事件状态（为崩溃重放需要，敏感文本受最小权限/保留策略保护），`rag_document_state`保存 latestAcceptedRevision/hash/tombstone及每路 appliedRevision/失败状态。submit只在事件持久化后返回 ACCEPTED；getStatus区分 ACCEPTED/APPLYING/APPLIED/RETRY_PENDING/CONFLICT/STALE，不能把RPC返回当双写完成。该数据库是实现可靠补偿的最小新增运行依赖，不是无状态服务误配。

worker 使用数据库文档行锁/事务串行化同一文档的外部应用，保持已接受版本单调；锁内调用有界超时，崩溃连接释放后重放同一版本。数据库连接丢失会中断工作；可能已发送的旧网络请求由状态账本校验与最新版本补偿，不能承诺跨后端原子提交。关键词可用后端版本控制，向量路径继续现有Spring AI；不为向量 CAS重写裸SDK。

检索在融合/重排前通过RAG账本核验候选revision==最新accepted且两路applied该版本、非tombstone；部分/旧版本均剔除。后端记录与账本错位不暴露正文，通过补偿修复。即使迟到旧向量写覆盖新文档，只会导致暂时漏检而非将旧正文当新版本；定期核对实际文档revision/hash并重放最新版。对检索有严格不可见要求的删除在accepted时即时隐藏；事件尚未接收窗口仍由领域最终检查兜底。

两路共享同一内容与属性版本。失败最多自动重试5次，指数退避（1/2/4/8/16秒加抖动），耗尽保留RETRY_PENDING告警，由受权replay触发新一轮；outbox发送失败也保留可核对状态，按事件身份重试。限制worker并发/每batch，保护业务连接池；账本不可用则检索/索引失败关闭，不退回裸后端读。删除tombstone至少保留整个迁移/回放窗口，不能清除后复活旧版本。

**替代方案**：只用afterCommit监听在崩溃时丢事件；ES成功+Milvus失败只log无法补偿；RocketMQ不能替代真源事务事件持久化。选择数据库outbox与小型控制账本，不许诺exactly-once。

### D8: 记忆索引事件归入口所有者，bootstrap仅在memory执行

将 `SystemConstraintBootstrapRunner` 的语料/初始化职责迁至 memory-service，随同 `system-constraints.yml`资源；显式读取启用开关，默认启用仅补缺，人工改过或active=false不覆盖。所有结构化 upsert/停用/重新激活均写投影事件，不由agent再单独双写。

偏好 upsert、confidence decay导致更新或删除均与outbox同事务。confidence <0.1按域既有可检索规则生成失效/删除投影，仍可保留SQL记录；<=0物理删除同时生成tombstone。现有无调用 `UserPreferenceCollector/Retriever` 在全仓复核后删除，不为迁移把它们重新激活。会话记忆、artifact、handoff不自动全部索引；未来接入须提供真源、schema与权限。

**替代方案**：只搬bootstrap仍遗漏活跃写入口；让RAG调SQL判断记忆生命周期又把领域权责搬走，均不采用。

### D9: 兼容包装与最终清理分阶段，失败类型不混淆

兼容路径和协议：

| 原入口 | 首次切流后的行为 |
|---|---|
| GET /agent/vector-store/milvus/search，/agent/mcp/milvus/search | 保留权限入口，调用默认获准通用知识空间；旧响应集合字段是只读兼容展示，不是下一请求寻址权 |
| POST /agent/vector-store/milvus/collections/init，/agent/mcp/milvus/collections/init | 显式管理权限 + 旧别名映射 + schema维度校验后调用管理RPC；不接受任意物理创建 |
| POST /agent/rag/listings/query | 调房源领域检索facade，再由房源域调用RAG及真源详情映射，保留旧match/context响应 |
| POST /agent/rag/listings/index | 调房源领域授权投影入口，生成可靠事件；返回保留成功字段但附状态，明确接收不等于两路完成 |
| AgentRpcService.syncListingKnowledge/deleteListingKnowledge | 过渡代理到领域持久化事件入口；新房源writer不再调用这些方法 |
| local:milvusKnowledgeSearch | 保留名称、query、会话topK、recordMilvus/recordTool，改走授权域/空间RPC |
| 房源searchListingsByKeyword工具 | 名称兼容，语义升级为默认混合+重排，修改description及listing-search技能，不留房源ES执行 |

旧索引RPC本来没有完整payload且可能发生在事务提交前；兼容只服务停写、排空后可核对的遗留调用/重建，不承诺旧writer与新writer长期并行可靠。调用到达时先验证服务、稳定ID与当前真源：UPSERT仅在已确认提交且不存在删除事实时由领域事务生成完整投影事件；DELETE仅在当前真源已不存在/已删除且存在持久revision身份时生成新tombstone；若当前真源仍存在或已有较新更新，不应用旧删除。原事务回滚、提交结果不明、删除身份不可核实或次序不明时返回 LEGACY_RECONCILIATION_REQUIRED，不自动排队无限等待或确认成功，记录待人工/受权重新核对事实。部署切换前短暂停止房源写入口、排空旧实例/在途调用并追平事件，使正常新写不依赖上述限制；删除旧RPC在下一清理发布、全消费者升级后执行。

旧数量兼容转换独立于新RPC缺省值：旧房源topK缺省取当前受管rerankTopK（迁移基线10），两路缺省取当前受管keywordTopK/vectorTopK（基线各20）；candidateLimit=旧最终topK，rerankCandidates=两路请求数之和（默认40，若超过服务重排上限则为500且须>=candidateLimit），保留显式每路数量不自动改写。不满足最终topK<=候选容量/200上限时明确INVALID_ARGUMENT。旧知识工具topK缺省沿用AgentChatProperties，candidateLimit=topK，rerankCandidates=max(topK,100)，未显式每路数时各至少max(topK,100)，均须在空间上限内；旧HTTP通用搜索保持最终5条，不使用新默认50。旧字段collectionName只做别名授权转换，不透传物理目标。增加默认20+20/10、显式100/200边界及非法关系测试。

旧REST至少保留一个兼容发布周期，退役需单独明确公告；本变更最终仍可保留纯代理，但源RAG执行实现不可保留。旧collectionName仅映射固定已盘点别名；Graph context转换为逻辑spaceId且做权限检查。MCP REST别名不是新增MCP公开面，不借LR-8放开用户记忆。

错误明确区分 AUTHENTICATION_FAILED/EXPIRED、FORBIDDEN、INVALID_ARGUMENT、ENDPOINT_POLICY_DENIED、DEPENDENCY_UNAVAILABLE/TIMEOUT、RERANK_PROTOCOL_ERROR、SCORING_PROFILE_MISMATCH、INDEX_CONFLICT。旧 `guarded` 不再把所有 IllegalStateException映射为权限拒绝。本地工具对**确定只读失败**返回结构化失败事实及模型可见简短错误，由通用调用层落已完成失败结果；不误走副作用OUTCOME_UNKNOWN，不新增业务Graph拓扑。已完成账本/历史Milvus DTO JSON兼容，知识禁用在真实生产入口生效，不固定true。新增transition调用点如不可避免，按KI-2逐真实调用点加单测。

**替代方案**：直接删真实旧路径会断脚本；重写gateway到未经领域检查的RAG会绕过权限/最新详情；长期fallback保留第二实现违背抽离目的。

### D10: 明确严格故障政策、超时及预算

默认STRICT：ES或vector任一路失败，或要求的重排失败，整个检索明确失败。只有显式ALLOW_DEGRADED允许单存活路或无重排融合顺序；响应必须说明failedStages/effectiveMode/scoreKind，域可据此决定是否采用。两路皆失败、认证/权限/URL安全/空间准备/应用账本失败不能降级。NO_MATCH是成功空候选，与不可用区分。

设计默认：查询整体20秒，每路5秒、重排8秒，阶段期限不超过剩余总预算；只读后端瞬时失败最多重试1次且仍受同一deadline，认证/参数/安全拒绝不重试。候选/重排文本字节预算、worker并发由受管配置限制，真实延迟压测后只在有界范围调整，不声称这些数值已证明SLO。native/DashScope缺候选、越界、重复、非有限分数全部协议错误，不能空列表掩盖。

**替代方案**：继承ES→SQL fallback而vector硬失败使混合模式含糊；自动任意降级无法让域判断相关度质量，均不采用。

### D11: 出站URL安全不可因内网默认值豁免

rag-service的ES HTTP transport、Spring AI embedding实际HTTP transport、重排RestClient以及任何HTTP重定向/URI连接分支统一遵守：仅http/https；校验host及全部解析A/AAAA结果；拒绝localhost、环回、私有、保留、IPv4映射IPv6、未明确允许的特殊范围；userinfo/畸形authority拒绝。禁用自动重定向，重定向若未来开放也要重新同等校验。每次新连接用经过校验的目标地址并保持原host的TLS/SNI验证，不由客户端再次不受控解析，避免DNS检查/使用脱节；连接池重连、重试、配置刷新也走守卫。

需要在现有客户端真实transport扩展点接入安全守卫，不是新增业务存储适配器或HTTP embedding实现。当前版本如果无法在不重写底座的情况下保证连接目标，先标为实施阻塞并使用该客户端受支持的连接/DNS扩展，不能弱化为仅启动时字符串检查。Milvus host/port的gRPC与业务Dubbo不是本设计的HTTP URL请求，但若其URI分支实际发HTTP则同样验证，不能用`http://`字符串或“SDK调用”臆断豁免。内部管理健康检查使用本进程状态/JMX/进程命令，避免新增违反约束的服务端HTTP探测。

现有私网ES与host.docker.internal重排**不能原样作为合规部署目标**。实施可完成非联网单测与契约stub，但真实默认路径验收/切流须部署方提供满足约束且有授权的目标；本设计不擅自公开本机服务、替换模型/协议、传语料到第三方或修改网络。安全拒绝不接受ALLOW_DEGRADED绕过，缺合规目标属于明确部署门禁，不是方案未选架构。

**替代方案**：Nacos/env/local“可信配置例外”违反用户约束；手写embedding客户端不是解决地址校验的必要手段；让房源继续执行内网ES规避约束且违背集中边界，均不采用。

### D12: 配置、依赖与能力健康分开归属

新增 `nacos/rag-service.yaml`，独立bk_rag数据源、RPC、空间、ES/Milvus/embedding/rerank配置及超时策略。配置实效通过实际namespace/group/dataID核对，不把无人导入的mysql-common当共享配置依据。bk_rag迁移SQL明确目标库，支持当前MySQL基线并验证重复执行；不借此次修复所有历史SQL。

运行端口候选 HTTP管理9018、Dubbo20898（不启用公网搜索），实施先核对全端口清单与实际占用，冲突可在部署配置选择空闲端口，不改变契约。QoS默认关闭。镜像采用独立 `bk-anent-rag:dev`，沿用Dockerfile模块构建 `MAVEN_MODULES=rag-service`及可执行SERVICE_ARTIFACT；新服务不继承包含不相关DB/凭据的全量环境锚点。仅RAG获得相关执行配置，营销保留自身业务ES配置而非借RAG变量。agent移除Milvus直接depends_on/readiness，listing移除本次ES初始化/必需readiness，memory新增RPC消费而不引后端starter。

RAG基础readiness要求有效配置、认证接线、控制数据库与RPC provider；检索能力分别诊断ES索引、Milvus集合/schema、实际embedding维度、重排协议/scoringProfile。默认hybrid+rerank能力须全阶段健康；基础进程可供状态/补偿管理，不以TCPUP替代能力UP。未授权/未回填空间单独NOT_READY，不阻断无关空间管理，但相关搜索必须失败。

消费者启动可声明RAG已启动/可发现，但**不能通过service_healthy硬依赖RAG后端**使结构化域/Supervisor全局不可启动。RPC reference check=false只是发现/启动策略，不是认证。消费者基础readiness不包含rag；能力诊断公开rag.available及错误原因。RAG停机的相关调用失败，其他模型/SQL流程继续。

**替代方案**：给每个域加Milvus/ES必需readiness重造强耦合；把所有后端只列为可选又误报默认能力健康；复制共享compose锚点泄漏依赖，均不采用。

## Risks / Trade-offs

- [独立服务增加一次RPC及索引控制库依赖] → 控制预算/并发、保持domain只批量取详情、监控阶段耗时；可靠补偿需要持久事实，不宣称仍是纯无状态迁移。
- [现有URL目标与安全政策冲突，当前环境不能直接联调] → 明确部署gate，保留不发网契约验证，未具合规目标不切流，不改安全要求或自动发布语料。
- [服务HMAC权威scope由领域代码签发，错误授权仍可造成越权] → 每空间限定签发服务/操作、RAG principal复核与强制owner、真源scope路径测试；固定gateway标记和普通userId不能获得签发权。
- [双索引应用并非原子，迟到旧向量写可能暂时漏检] → 最新revision/两路完成账本过滤、同文档串行写、定期实际hash核对及补偿；不暴露旧正文，不承诺瞬时全量可见。
- [迁移中旧ES不完整、旧知识owner缺失、旧偏好ID碰撞] → 从真源backfill，未知归属隔离整理，规范身份映射核对；不宣称零迁移或默认公开。
- [严格默认故障比旧ES→SQL兜底更显式] → 文档与响应公告，允许受控显式降级，保持独立SQL业务入口；不偷换混合搜索模式。
- [候选扩展增加embedding/重排成本] → 有界数量、两次域查询上限、限流与整体deadline；回填分批且不挤占在线检索资源。
- [Bootstrap迁移改变重启覆盖行为] → 明确仅补缺/开关、停用与人工修改回归，不把修正当静默搬迁。
- [旧provider/consumer不同步发布会断索引同步] → 停写短窗、过渡纯代理、事件水位追平后再清理；保留配置和镜像快照，不能提前删RPC。
- [当前依赖/启动脚本债务影响验收] → 仅对新RAG与受影响服务增加真实生效/成功门禁，引用已知KI并如实记录未修范围。

## Migration Plan

### Phase 0 — 冻结契约与盘点

1. 复核当前主规格与LR/KI，记录新设计根因及发现的缺陷；本次规划不表示缺陷已修。
2. 盘点旧接口/consumer、物理schema/文档身份、embedding真实维度、语料权属、旧ES完整性、资源权限scope、有效namespace/config、镜像/端口清单。
3. 建立逐空间迁移清单和合规HTTP目标门禁；未知通用知识归属或目标不合规的空间保持禁用。

### Phase 1 — 新底座与可靠事件，尚不切流

1. 建rag-service/common RPC契约、服务/用户/域scope认证、空间注册、bk_rag账本，迁入SpringAI/ES/重排实现及出站守卫；先通过非联网单测/容器装配契约。
2. 房源和记忆域接入revision/outbox，覆盖所有活跃变更；迁移memory bootstrap唯一职责与资源/开关。dispatcher先停用或发送仅向未激活新空间，不与旧执行路径形成长期双写架构。
3. 在获得合规真实目标后初始化新独立索引/集合；backfill在领域事务中锁定投影revision并一致读取完整真源快照，经相同outbox发送，不给旧正文拼配较新revision。扫描期间的更新/删除通过持久revision/tombstone收敛，核对水位包含已删除身份；增加回填与更新/删除交错测试。通用知识受管导出整理，禁止调用方指定物理目标。
4. 记录快照水位，校验文档数量/身份/hash、授权过滤、更新/删除及模型schema；先完成新空间数据，不复用旧索引不完整性的假设。

### Phase 2 — 有界切流发布

1. 备份当前镜像digest/config/空间映射与事件水位；短暂停止受影响房源/记忆写入，排空旧writer及在途旧RPC，追平增量。
2. 部署消费者纯代理和领域检索入口，切换新writer到outbox→RAG；移除agent/listing本地执行配置/依赖/启动初始化，使新rag-service成为唯一执行者。
3. 升级房源工具/技能语义，验证知识禁用、真实旧REST/权限与historyDTO；默认模式真实ES+Milvus+rerank烟测，跨空间/跨用户反例、一路故障与RAG停机反例必须通过。
4. 新配置/数据生效与默认能力gate全部通过才恢复写入口；严格检查实际namespace，不执行全项目配置目录覆盖或数据卷删除。

### Phase 3 — 清理发布

首次切流发布已经同步迁走/删除旧执行包、bean、重复bootstrap及对应执行依赖；至少经过一个兼容发布周期且所有消费者升级后，再删除旧Agent索引RPC签名/引用及残余无执行职责代码，允许旧REST/工具纯包装继续存在。检查源码与有效依赖/配置没有本次后端fallback。旧数据/集合自动删除不属于本任务，后续需单独授权。

### Rollback

- 切流前失败：新空间不激活，旧数据/部署不动，停用新dispatcher并保留事件，修复后按水位重放。
- 切流后失败：暂停写入口、保留新outbox及bk_rag状态，记录差异水位；优先回滚新RAG/消费者到兼容的新契约版本而非恢复被清理的旧架构。
- 如必须退回旧执行版本，先以真源/事件重建或重放迁移窗口变更到旧索引/集合，并完成授权/删除一致核对再切路由；只恢复镜像或路由不足以数据回滚。最新URL约束仍适用，若旧版本不合规则不得恢复该HTTP执行，保持检索不可用而保留结构化功能。
- 不执行down -v、prune或drop index/collection，不操作外部reranker项目；配置更新仅针对本项目已确认目标。

## Open Questions

无影响架构或契约的未决项。实际合规目标、空闲端口、物理名称/旧schema清单、重排提供方评分profile和真实负载参数属于实施前必须验证的环境值，已进入任务与切流gate；未验证时如实阻塞对应验收，不自行豁免或宣布上线。
