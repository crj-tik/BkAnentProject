# 按域拆分 RAG 能力（split-rag-by-domain）

## Why

RAG 能力（embedding、Milvus 向量存储、双路召回、重排）目前全部寄生在 agent-service 进程内，造成三个结构性问题：**（1）域归属错位**——房源 RAG 的数据、语料、索引触发事件全在 listing-master-service，记忆 RAG 的结构化半边（约束/偏好表）全在 memory-service，但两者的向量半边都留在 agent-service，形成"同一域两个进程"的脑裂；**（2）现存的 Dubbo 双向环**——listing-master 变更房源时调 agent-service 建索引，agent-service 的召回流程又反调 listing-master 取详情，两路跨进程往返；**（3）启动依赖过重**——agent-service 就绪链声明了 milvus，RAG 故障会阻塞 supervisor 编排服务启动。

拆分后各域可独立演进 RAG 能力（如 interview-service 行业大脑 KI-13 未来接入），共享底座下沉为 common 模块，新域按此模式集成。

## What Changes

- **新建 `common-milvus` 共享模块**：将 agent-service 的 `milvus/core`（`MilvusDocumentStore` 抽象、现有 Spring AI Milvus 存储实现、embedding 接入及连接/集合配置）一起下沉为普通 Maven 共享模块，仿 `common-skill` 先例，供多服务复用。不重写裸 SDK 或 embedding HTTP 客户端，不引入 optional/profile 双实现打包或新适配器。
- **房源 RAG 迁入 listing-master-service**：`ListingMilvusService`（双路召回+重排）、`model/rag` DTO、`BgeRerankService`、`ListingStructuredFilter` 迁入 listing-master；ES BM25 关键词召回与向量召回在进程内团聚；`AgentRpcService.syncListingKnowledge/deleteListingKnowledge` 的 Dubbo 环删除，改为进程内直调。**BREAKING**：`/agent/rag/listings/**` REST 端点从 agent-service 移除，网关改为路由到 listing-master 的新端点（旧路径在网关保留兼容转发的过渡期方案见 design.md）。
- **记忆 RAG 迁入 memory-service**：`AgentMemoryMilvusService`、`SystemConstraintBootstrapRunner`（约束双写逻辑收拢）迁入 memory-service；记忆域（系统约束、用户偏好的 MySQL 半边 + Milvus 半边）在同进程内闭合。**BREAKING**：`/agent/rag/memory/**` REST 端点从 agent-service 移除。
- **agent-service 瘦身为薄代理**：`AgentMilvusTool`（supervisor 的 `milvusKnowledgeSearch` 工具）保留在 agent-service，会话门控（`allowKnowledgeSearch`、topK）留在本地，底层检索改为远程调用 memory-service；agent-service 的 Nacos `required-dependencies` 移除 `milvus`。
- **死代码处理**：`UserPreferenceCollector`/`UserPreferenceRetriever`（无任何调用方）在迁移中删除。

## Capabilities

### New Capabilities

- `common-milvus`: 多服务共享的 Milvus 向量存储与 embedding 基础库——`MilvusDocumentStore` 抽象、连接管理、集合初始化、embedding 客户端封装，作为各域集成 RAG 的基础设施契约。
- `listing-rag`: 房源域自有 RAG——基于 ES BM25 关键词召回与 Milvus 向量召回的双路召回、合并、bge 重排、结构化过滤，以及房源变更时的索引同步（进程内触发）。
- `memory-rag`: 记忆域自有向量检索——系统约束/用户偏好的向量 upsert 与语义检索，与 memory-service 的 MySQL 结构化半边同进程闭合，记忆域数据（MySQL 表 + Milvus 集合）归属 memory-service。

### Modified Capabilities

- `supervisor-tool-orchestration`: supervisor 本地工具 `milvusKnowledgeSearch` 的底层实现从进程内 Milvus 直连改为对 memory-service 的远程调用；工具注册、权限门控（`agent.chat.use`）、会话级开关行为不变。

## Impact

- **代码**：agent-service 删除 `milvus/listing`、`milvus/memory`、`model/rag`、`AgentController` RAG 端点、`AgentRpcServiceImpl` 索引方法（约 1440 行迁出）；listing-master-service、memory-service 各新增对应包；新建 `common-milvus` 模块并纳入根 pom 聚合。
- **RPC 契约（common 模块）**：`AgentRpcService` 删除两个索引同步方法（**BREAKING**，全仓仅 listing-master 一个消费方，同分支内同步改造）；`ListingMasterRpcService` 中 `getListingKnowledgeDocument` 等 agent-service 回调方法在环解除后可评估删除（保守起见首期保留）。
- **配置（Nacos）**：`nacos/agent-service.yaml` 的 `milvus.*`、`rag.*` 段迁出至 `nacos/listing-master-service.yaml` 与 `nacos/memory-service.yaml`（各自裁剪所需子集）；agent-service 增加 memory-service 检索端点地址（复用既有 `MemoryServiceProperties` 模式）；agent-service 就绪依赖移除 `milvus`。
- **网关**：`nacos/gateway.yaml` 新增 listing-master RAG 路由；`/agent/rag/**` 旧路径处理策略见 design.md。
- **外部系统**：MySQL（分库既有，零改动）、Milvus（集合与数据不动）、ES（仅 listing-master 使用，不动）、KE 网关 embedding（listing/master 与 memory 各自配置）、bge-reranker 容器（仅 listing 消费）。
- **测试**：`BgeRerankServiceTest`、`AgentControllerQueryTest` 中 RAG 相关用例随迁/改写；新增 memory-service 远程检索的 agent-service 侧降级单测。
- **文档**：按 AGENTS.md 要求，设计决策（业务域归属与底层存储运行位置分离、复用原 Spring AI 存储与 embedding 接入、域访问边界）追加 `docs/logic-rationale.md` 条目；审查发现的既有问题在实施修复前查重并维护 KI 清单。
- **无交叉**：与进行中的 `migrate-supervisor-graph-approval-routing` 变更无文件/逻辑交叉（已核对其 artifacts 无 RAG/Milvus 内容）。
