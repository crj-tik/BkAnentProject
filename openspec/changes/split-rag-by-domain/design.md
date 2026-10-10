# Design: 按域拆分 RAG 能力

## Context

当前 RAG 全部实现寄生在 agent-service：`milvus/core`（约 700 行 Spring AI 封装）、`milvus/listing`（双路召回+重排）、`milvus/memory`（记忆向量）、`model/rag` DTO、REST 端点与 `AgentRpcService` 的索引同步方法。supervisor 对 RAG 的唯一直连是本地工具 `AgentMilvusTool`；房源 RAG 与记忆 RAG 的数据归属分别在 listing-master-service 与 memory-service（记忆域存在 MySQL/Milvus 双写脑裂，见 `SystemConstraintBootstrapRunner`）。共享设施：同实例 MySQL 分库（bk_listing/bk_memory/bk_agent）、Milvus 使用不同集合组织内容、KE 网关 embedding（baai-m3-1b，1024 维）、本机 bge-reranker 容器（18119）。集合命名本身不构成授权隔离。仓库有 `common-skill` 共享模块先例（独立 Maven 模块 + 自动装配）。memory-service 当前直接使用 MySQL/MyBatis + REST，迁移时正常引入原存储路径需要的 Spring AI 依赖；listing-master-service 已具备 Spring AI（deepseek chat + A2A）。现有技术栈差异不是重写存储实现的理由。

## Goals / Non-Goals

**Goals:**
- 域归属原则落地：向量的归属跟着数据归属走，每个域同时拥有自己数据的结构化半边与向量半边
- agent-service 就绪链移除 `milvus`，RAG 故障不再阻塞 supervisor 编排
- 基础设施（向量存储抽象、embedding 客户端）下沉 common-milvus，新域（如 KI-13 行业大脑）可按此模式集成
- Dubbo 双向环删除，Milvus 集合与数据零迁移（集合名、维度不变）

**Non-Goals:**
- 不建独立 rag-service 中台（当前只有一个真实业务域 + 记忆域，独立服务对这个体量偏重；若未来多域共享需求成型再评估）
- 不改 Milvus 部署拓扑、不引入多 database 强隔离（留作升级路径）
- 不迁移 memory-service 的 MySQL 结构化半边，不动其 REST API 契约（`MemoryStoreClient` 接口不变）
- 不实现 KI-13 行业大脑，仅保证其未来可接入
- 首期不删除 `ListingMasterRpcService` 的 `getListingKnowledgeDocument` 等回调方法（环虽解除，保守保留，避免与其他进行中变更互相牵连）

## Decisions

### D1: common-milvus 以普通 Maven 模块共享现有实现

仿 `common-skill`：根 pom 聚合新模块，将现有 `milvus/core` 模型、接口（`MilvusDocumentStore`、`MilvusEmbeddingService`、request/result record）及 `SpringAiMilvusDocumentStore`、`DefaultMilvusEmbeddingService` 一起迁入。共享模块使用普通、明确的 Maven 依赖声明，不使用 optional/profile 构造多种实现或同坐标不同内容的 jar，也不新增适配器模块。

抽取实际使用的连接和集合配置，包括 `MilvusConnectionProperties` 及 `AgentMilvusProperties` 中的字段映射、索引参数、维度配置；域集合的选择仍由宿主配置。迁移不改变实际向量写入和查询路径，也不为了让两个既有接口“统一”而重写 embedding 流程。

**备选**：为本次迁移维护双实现、按 profile 切换实现或新增适配器 → 不是迁移需要，增加实现与兼容成本，撤回。

### D2: memory-service 复用原 Spring AI Milvus 与 embedding 接入

memory-service 通过 common-milvus 复用 agent-service 的现有 Spring AI Milvus 存储封装及 embedding 接入，按现有运行路径装配所需依赖与配置。不重写裸 Milvus SDK 存储，不新增直调 `/embeddings` 的 HTTP 客户端，不建立双实现一致性测试。listing-master-service 也复用同一实现，而不是各自维护存储代码。

Spring AI 在这里承担向量存储、embedding 接入和检索的基础设施职责，并不定义整个 RAG 业务流程。房源候选合并、业务过滤和重排策略由房源域负责，记忆归属及生命周期由 memory-service 负责。业务规则归属与底层实现运行位置是两个独立决策；本次选定两个宿主复用同一封装，不据此推出“每个域必须自建一套存储实现”。

**备选**：以 memory-service 目前未显式使用 Spring AI 为由重写底层 → 原方案过度设计，撤回；权限、一致性、旧数据兼容和部署问题分别处理，不作为重写底层的理由。

### D3: 房源 RAG 整体迁入 listing-master-service，索引同步改进程内

`ListingMilvusService` 及其依赖（`ListingKeywordRecallService` 改进程内直调本地 service、`BgeRerankService`、`ListingStructuredFilter`、`model/rag` DTO 迁入 `com.bkanent.listing.rag` 包）、`AgentController` 的 listing RAG 端点迁为 listing-master 的 `/listing/rag/**`。listing-master 已有 Spring AI，直接复用 D1 的 Spring AI 实现。`AgentRpcService` 删除 `syncListingKnowledge`/`deleteListingKnowledge`（全仓唯一消费方 listing-master 同分支改造，`ListingManagementServiceImpl` 改调进程内新 service）。

**备选**：保留 Dubbo 索引同步、只迁检索 → 半个域两处安家，环只解一半，弃。

### D4: AgentMilvusTool 改远程代理，会话门控留在 agent-service

`AgentMilvusTool` 包装层不动（`AgentToolContextHolder` 门控、topK、`recordMilvus`/`recordTool` 审计记录都在本地），底层 `searchKnowledge` 换成对 memory-service 新检索端点的 REST 调用（复用 `RestClient.Builder` + `MemoryServiceProperties.baseUrl` 同一模式的配置类）。memory-service 新增两个内部 REST 端点（检索 + upsert），鉴权复用服务间调用既有模式（内网直连，网关不暴露）。

**备选**：工具整体迁 memory-service 并以 A2A 能力暴露 → 改变 supervisor 工具注册方式与权限模型（local → a2a），影响面远超需要，弃。

### D5: 网关旧路径兼容转发，一个版本后移除

`nacos/gateway.yaml` 新增 `/listing/rag/**` 路由；`/agent/rag/listings/**` 旧路径在网关加 RewritePath 转发到 listing-master 新端点，标注过渡期；`/agent/rag/memory/**` 因全仓无外部消费方（仅网关权限模型引用），直接移除不做兼容。

### D6: 配置随域迁移，agent-service 就绪链收短

`agent-service.yaml` 的 `milvus.*` 段拆给两个宿主（各自裁剪子集：listing 拿集合字段+索引参数，memory 拿 default-collection），`rag.*` 段整体迁 `listing-master-service.yaml`；memory 的 rerank 无关项不迁。`required-dependencies` 中 agent-service 移除 `milvus`，listing-master 增加 `milvus`，memory-service 增加 `milvus`（MySQL 半边不受影响的降级语义见 memory-rag spec）。

### D7: SystemConstraintBootstrapRunner 随记忆域迁移

约束引导（读 `system-constraints.yml` → 双写 MySQL + Milvus）整体迁 memory-service，`MemoryStoreClient.upsertSystemConstraint` 的跨进程调用变进程内直调。双写失败仅告警不阻塞启动的既有行为保持（对应 memory-rag spec 场景）。

### D8: 死代码直接删除

`UserPreferenceCollector`/`UserPreferenceRetriever` 无任何调用方（已全仓核实），随本次迁移删除，不做迁移。`AgentChatProperties.defaultTopK` 被工具引用的部分保留在 agent-service。

## Risks / Trade-offs

- [共用实例及凭据时，分集合只能组织数据，不能代替域/用户授权] → 服务端集合访问范围、可信 actor 与用户过滤仍须单独设计；不以“社区版无 ACL”作为依据，也不以换 SDK 或重写存储代替访问控制
- [索引同步路径切换期间可能丢事件] → Milvus 集合不重建，切换窗口内房源变更极少（开发期）；部署顺序先 listing-master 新代码生效、后下线 agent-service 旧端点，重放近期变更的索引 upsert 作为兜底
- [memory-service 检索端点成为 supervisor 知识工具的新单点] → 工具返回明确不可用提示、会话其余能力不受影响（spec 已要求）；远端调用设超时与有限重试
- [原实现抽取时改变既有 schema、metadata/文档 ID 映射或 embedding 配置] → 复用现有 Spring AI 存储路径，以迁移回归验证旧文档可读、可更新、可删除及模型/维度一致；不以重写底层解决兼容问题
- [`/agent/rag/listings/**` 旧路径外部脚本断链] → D5 网关过渡转发兜底
- [agent-service 瘦身过程中误删仍被 supervisor 引用的类] → tasks 中安排编译 + 全量既有单测门禁（`SupervisorToolLoopGraphTest` 等编排回归套件必须绿）

## Migration Plan

按 Phase 1 → Phase 2 两期，各自独立可部署、可回滚：

**Phase 1（common-milvus + 房源 RAG）**
1. 建 common-milvus 模块，迁 `milvus/core`，agent-service 改依赖 common-milvus（此步无行为变化，全量回归）
2. listing-master 引 common-milvus + Spring AI vector store 依赖，迁入 listing RAG 代码与新端点，`AgentRpcService` 契约方法删除、`ListingManagementServiceImpl` 改进程内调用
3. 网关加路由与旧路径转发；Nacos 配置迁移
4. 回滚：还原 agent-service 端点与 Dubbo 方法、网关路由指回即可；Milvus 数据无变化

**Phase 2（记忆 RAG + 工具代理化）**
1. memory-service 引 common-milvus，装配原有 Spring AI Milvus 与 embedding 接入，新增检索/upsert 端点与约束引导迁移
2. agent-service `AgentMilvusTool` 改远程代理，删 `milvus/memory` 与 RAG 端点、死代码；就绪依赖移除 `milvus`
3. 回滚：agent-service 恢复本地实现（Milvus 客户端依赖回加）；memory-service 端点保留无害

## Review Constraints — 实施前仍需解决

本次修订仅撤回存储双实现的过度设计，并补齐原实现迁移回归要求，不表示上轮审查的其他阻塞项已经解决。以下问题仍须同步修订 specs 与 tasks 后才能实施：

- **权限与隔离**：内部 REST 必须明确认证身份和授权；集合由服务端控制访问范围，用户记忆按可信用户过滤，不能将内网可达或集合命名视为鉴权。
- **数据一致性**：房源索引的提交时机、失败补偿与更新顺序，以及约束/偏好所有活跃写入口的更新、停用、删除和衰减同步仍需定义；迁到同一进程不提供跨存储事务。
- **部署与故障语义**：memory 的必需 readiness 与结构化降级要求需协调，Compose 启动依赖、环境覆盖、Nacos 及本地 profile 配置需一起核对。
- **准确入口与验收**：实际 memory 检索/初始化旧路径、生产 Graph 中的禁用开关、房源 Agent 是否使用新检索能力及重排失败契约仍需明确。不能将目前的工件格式校验通过视为这些问题已关闭。

这些事项的处理不要求重写 Spring AI 存储或 embedding 客户端。旧数据兼容应以原实现的迁移回归验证为基础。

## Open Questions

- bge-reranker 容器（本机 18119）目前仅 listing 消费，未来若 memory 或其他域也需要重排，是否将其配置也下沉 common-milvus → 可后议，不影响本次结构
- `ListingMasterRpcService.getListingKnowledgeDocument` 在环解除后何时删除 → Phase 1 落地后单独评估（Non-Goal 已声明首期保留）
