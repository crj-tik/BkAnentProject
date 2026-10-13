# 独立 RAG 逐空间迁移清单（源码基线，非运行盘点）

## 证据边界

本清单对应 `extract-isolated-rag-service`。迁移前源码为 `74c8d92^`（`2a3fa12`），首次集中迁移为 `74c8d92`，本轮检查基线为 `365f8a1`。历史源码引用可用 `git show 74c8d92^:<路径>` 复核；已删除文件不是当前运行实现。

下面的旧别名、模板字段和逻辑 ID 均为 checked-in 源码事实，**不是现存 ES/Milvus schema、实际生效配置或数据数量的证明**。未读取 `.env`、未查询真实后端、未执行 Docker/Nacos/真实 SQL、未导出或发送语料。新物理名称只是模板声明，不能据此认定已 provision、回填或切流。本清单补齐 tasks 1.2/1.3/1.4 的本地证据，不将这些含实际环境要求的任务勾选完成。

## 空间映射摘要

| 逻辑空间 | 旧 Milvus 别名 | 新 ES 模板名称 | 新 Milvus 模板 database.collection |
|---|---|---|---|
| `listing.knowledge` | `listing_knowledge` | `rag_listing_knowledge_v1` | `default.rag_listing_knowledge_v1` |
| `memory.preference` | `user_preference_knowledge` | `rag_memory_preference_v1` | `default.rag_memory_preference_v1` |
| `memory.constraint` | `system_constraint_knowledge` | `rag_memory_constraint_v1` | `default.rag_memory_constraint_v1` |
| `supervisor.knowledge` | `agent_knowledge` | 未注册 | 未注册 |

不同空间不能共用索引或 database/collection；物理分开不赋予访问权。前三空间的模板角色为空、worker/dispatcher 默认关闭，通用知识未注册且默认禁用。

## 1. listing.knowledge

### 已确认的本地事实

- 旧 ES 索引模板为 `listing_info`，ES 使用业务房源 ID；旧 Milvus 逻辑 ID 为 `listing:{listingId}`，source type 为 `LISTING`，source ID 为十进制房源 ID。两种旧键不能直接视为同一物理 ID。
- SQL 真源是 `bk_listing.listing_info`。新资源授权真源为 `listing_rag_grant`，必须有当前认证 actor 的有效 `SEARCH` grant；不能推断 `brokerId == auth userId`，无 grant 默认拒绝。
- 新逻辑 ID 保留 `listing:{id}`；模板 schema 为 `1`、policy 为 `listing-v1`。新 metadata 同时携带逻辑身份、source、revision、schema 和 contentHash，授权字段与业务正文来自同一完整投影。
- 创建、更新、删除、状态、资产绑定、OCR、核验及 grant 变更均走领域事务 revision/outbox。新正常 writer 不再调用旧 agent 索引 RPC。
- 消费者包括领域 REST/facade/RPC、旧 agent 房源 REST 包装及 `searchListingsByKeyword` 工具；后者默认混合召回加语义重排，不在房源进程执行 ES。
- 回填在领域按业务 ID 分页扫描源记录与持久 revision 身份的并集，锁后读取当前完整快照，已删除身份仍生成 tombstone。回填 `QUEUED` receipt 不是双后端 `APPLIED` 水位。

### 切流前缺失证据

实际旧索引/集合及 schema、旧 ES 写入完整性、PK 类型/长度/映射、模型维度、源/索引身份与 hash 对账、有效 grant 种子、删除水位均未在线确认。旧 ES 写入曾默认关闭，不能用旧 ES 数量作为 SQL 完整性基准。真实房源 Agent 跨 A2A 工具调用另受 KI-59 阻塞。

### 可复核源码

- 历史：`listing-master-service/.../search/ListingKnowledgeDocumentAssembler.java`、`.../service/impl/ListingSearchServiceImpl.java`、`nacos/listing-master-service.yaml`，均以 `74c8d92^` 复核。
- 当前：`common/.../rag/RagDocumentIds.java`、`listing-master-service/.../rag/ListingRagGrantService.java`、`ListingRagOutbox.java`、`ListingRagBackfillService.java`、`.../service/impl/ListingManagementServiceImpl.java`、`.../tool/ListingTools.java`。
- 迁移：`sql/migrations/20261012_listing_rag.sql`；映射：`nacos/rag-service.yaml`。

## 2. memory.preference

### 已确认的本地事实

- 旧逻辑 ID 采用 `userId + "_" + preferenceKey`，旧 metadata 含 `user_id`、`preference_key`、`preference_value` 和 category。此拼接格式并非通用无歧义复合键；**不能用下划线拆分推断旧身份**。
- SQL 真源为 `bk_memory.user_preference_memory`，业务唯一身份是 `(user_id, preference_key)`；新逻辑 ID 为 `preference:{Base64URL(userId UTF-8)}:{Base64URL(key UTF-8)}`，复核 SQL/可信 metadata 后才能关联旧键。
- 偏好 scope 强制 owner 为真实 principal.userId；SERVICE writer 不能搜索。confidence < `0.1` 为索引失效，<= `0` 的物理删除也保留投影 tombstone；重建不归零 revision。
- upsert/decay 在同一事务锁下重新读取 SQL 已持久化 decimal/time 表示，然后生成完整投影与 hash。新最终排序可采用语义相关度 × 持久 confidence × 可选半衰期因子，阈值只能用于注册过的语义尺度。
- 新搜索候选公共 metadata 为 `preferenceKey/category/confidence/observationCount/lastObservedAt/active`；userId/ownerId 留在索引用于授权，不作为候选公共 metadata。独立的已授权业务 record 响应不等同于索引 metadata。
- 消费者为 memory REST/RPC 与 agent 受权记忆纯包装。旧 collector/retriever 无业务调用并已删除；不能据此宣称旧集合为空。

### 切流前缺失证据

实际旧 PK 与冲突实例、owner metadata 完整性、SQL/旧索引时间表示、实际模型/语义版本、回填及物理删除总量未核验。MySQL DATETIME/DECIMAL 与 H2 的测试结果不是等价证明。只有真实 rerank profile 已验证后，才可在 `memory.rag.semantic-scoring-versions` 注册其确切版本；空允许集保持失败关闭，不填测试版本。

### 可复核源码

- 历史：`agent-service/.../service/UserPreferenceCollector.java`，以 `74c8d92^` 复核。
- 当前：`common/.../rag/RagDocumentIds.java`、`memory-service/.../service/UserPreferenceMemoryService.java`、`MemoryRagProjectionService.java`、`MemoryRagSearchFacade.java`、`MemoryRagSecurity.java`、`MemoryRagBackfillService.java`。
- 迁移：`sql/migrations/20261012_memory_rag.sql`；映射：`nacos/rag-service.yaml`。

## 3. memory.constraint

### 已确认的本地事实

- 旧别名 `system_constraint_knowledge`，逻辑 ID 为原始 constraint key；metadata 为 `constraint_key/category/tags`。历史 agent bootstrap 分开写 SQL 与 Milvus。
- SQL 真源为 `bk_memory.system_constraint_memory`，业务身份为 constraint_key；新逻辑 ID 是 `constraint:{Base64URL(key UTF-8)}`。
- 普通读取需明确约束读取角色且 active=true；管理需领域显式管理角色。不能虚构 owner 或把服务 operation grant 当用户管理授权。
- 新 upsert/停用/重新激活均生成领域事件；bootstrap 仅 memory 执行，遵守开关、只补缺，不覆盖人工正文或恢复已停用数据。
- 公共 candidate metadata 为 `constraintKey/category/tags/active`。memory REST/RPC 与 agent 受权纯包装消费；回填包括已保留的删除身份。

### 切流前缺失证据

真实历史缺失/删除身份、seed 与人工数据冲突、启停状态、PK/schema、权限种子和双后端应用水位未确认。删除身份未被历史机制持久化的旧记录不能凭空恢复为已知 tombstone；必须以受权历史/快照证据补齐。

### 可复核源码

- 历史：`agent-service/.../service/SystemConstraintBootstrapRunner.java`，以 `74c8d92^` 复核。
- 当前：`memory-service/.../service/SystemConstraintMemoryService.java`、`SystemConstraintBootstrapRunner.java`、`MemoryRagSecurity.java`、`MemoryRagBackfillService.java`；`common/.../rag/RagDocumentIds.java`。
- 迁移：`sql/migrations/20261012_memory_rag.sql`；映射：`nacos/rag-service.yaml`。

## 4. supervisor.knowledge

### 已确认的本地事实

旧 `agent_knowledge` 通用内部 writer 接受调用方 documentId，以 `memory` source type 和同值 source ID 生成文档；不存在已证明的统一语料身份或权威 SQL 真源。当前保留通用旧搜索 REST、`local:milvusKnowledgeSearch` 稳定能力与受管 alias，但没有物理注册、可信 owner policy 或授权导出/import 输入。默认禁用是安全边界，不是迁移完成。

### 激活前必要输入

必须有获准语料来源/导出目标、完整可信逻辑身份与旧 PK 映射、owner/read policy、schema/模型证据、revision-bearing 导入事实和删除水位。RAG 的 inspect/provision/replay 只管理注册空间或已接受事件，不能生成缺失语料。未提供这些证据时不创建、回填或公开该空间。

### 可复核源码

历史 `agent-service/.../milvus/memory/AgentMemoryMilvusService.java`（`74c8d92^`）；当前 `agent-service/.../rag/AgentRagCompatibility.java`、`.../milvus/memory/AgentMemoryMilvusService.java`、`.../tool/AgentMilvusTool.java`、`nacos/agent-service.yaml` 和 `nacos/rag-service.yaml`。

## 5. 共享 schema 与物理身份

旧 Spring AI store 把逻辑 ID 放入 `Document.id`，也将 documentId/source_type/source_id 放入 metadata。Spring AI 1.1.2 默认 schema 的 PK 最大 36 字符以及手工 builder 不执行 afterPropertiesSet，是库/源码事实，不代表旧 live collection 确实使用该 schema。

新模板字段为 `doc_id/content/metadata/embedding`，source 字段在 metadata；配置声明 1024 维、COSINE/IVF_FLAT、embedding 模型 `baai-m3-1b-base-v1-embedding-20240619`。新物理 ID 精确算法为：

```java
UUID.nameUUIDFromBytes(("rag-physical-id-v1\u0000" + logicalId).getBytes(StandardCharsets.UTF_8))
```

该 UUID 只用于新空间 Spring AI delete/add，逻辑 ID 保留于 metadata。**它不能反推旧 PK，delete/add 也不是原子 upsert。** store 缓存绑定不可变 space/schema/model/fields/dimension 身份，实际 schema 只读核验，provision 显式授权；不同 schema 不能复用同一物理集合。旧映射未知时不得猜测键或删除记录。

新 NUMBER 严格 double round-trip，INSTANT 固定 UTC 九位小数，ES date_nanos；不能据此声称兼容未知旧 ISO/SQL 表示。真实两路过滤等价另须验收。参见 LR-32/35、KI-58/60、`rag-service/.../storage/ProjectionCodec.java`、`SpringAiMilvusDocumentStore.java`、`ProjectionFilters.java`。

## 6. 兼容接口与事件证明

保留的六组 REST 方法/路径：

- GET `/agent/vector-store/milvus/search`
- GET `/agent/mcp/milvus/search`
- POST `/agent/vector-store/milvus/collections/init`
- POST `/agent/mcp/milvus/collections/init`
- POST `/agent/rag/listings/index`
- POST `/agent/rag/listings/query`

初始化只允许已登记别名、匹配维度和管理身份；旧 collectionName 不提供物理寻址权。旧房源默认 20+20/最终10，旧知识 HTTP 最终5，工具 topK 保持合法有界池。历史 Milvus DTO、chat/checkpoint只读和 recordMilvus/recordTool 包装保留，不能因此保留后端执行底座。

`AgentRpcService.syncListingKnowledge(Long)` 与 `deleteListingKnowledge(Long)` 没有原调用认证、revision/hash 或排空事实参数。当前有效调用只返回 `LEGACY_RECONCILIATION_REQUIRED`，没有成功纯代理路径。独立 signed `RagListingReconcileRequest` 可绑定 expected revision/hash/intent，但它不能凭空为 ID-only 入站补齐可信证据。必须先确认获准的证明输入路径和真实停写/排空事实，不能由 agent 读取当前状态后自签名认定旧事务成功。task 6.7 保持阻塞，不以 boolean reconciliation 开关代替排空证明。

## 7. 运行目标与水位证据清单（全部未执行）

1. 获准环境、具体运维操作范围、项目资源归属、数据目的地；apply 不是 Docker/Nacos/共享 SQL/切流授权。
2. 实际 Nacos server、namespace **ID**、group、选中 data IDs 及当前版本备份/应用生效值。相关配置为 rag/auth/listing/memory，以及需要升级时的 agent；不依赖无人导入的 mysql-common。
3. 独立服务 key 和 auth response key、匹配 keyId、至少32 UTF-8字节 secrets、真实 active principal/role、域读/管理角色和 listing 资源 grant。密钥值不写入清单。
4. 三迁移明确 `USE bk_rag/bk_listing/bk_memory`，自定义 MYSQL_DB 变量不会重定向这些 SQL；真实 MySQL8.4 的首次/重复执行、最小权限、备份和 payload/tombstone保留窗口。memory bootstrap 默认可写 SQL，dispatcher 禁用不等于启动无写入。
5. 模板 HTTP9018 loopback-only、Dubbo20898 container-network、独立 `bk-anent-rag:dev`、`rag-service` 模块，仅为 checked-in候选。需实际端口、不可变 image digest/config 快照、目标 JRE DNS 模块和双方 RPC 发现/验签证据。
6. 获准合规 ES/embedding/rerank 目标、实际 provider/model/version/dimension/scale/scoringVersion、memory 接受版本。私网 ES/host.docker.internal 不放行，不向未授权第三方传语料。
7. 逐空间旧 PK/schema/逻辑身份、来源数/hash/权限/删除对账、新完整投影和双后端应用水位。回填游标或 `QUEUED` 不等于 `APPLIED`；inspect capability 证据60秒过期，不代替数据完整性。
8. 真实 stop-write/drain、增量追平、消费者切流及受控 worker/dispatcher启用；RAG停机反例、兼容发布周期、事件水位差异重放和回滚。不删除 index/collection/volume。
9. KI-59 A2A 专用凭据 transport 支持仍待确认；仅配 secrets/部署目标不能修复此链，不暗改SDK/反射或增加第三客户端。

基础 readiness 仅证明认证配置、两张控制表可查询和至少三个本地 RPC provider导出；不能据此推断 Nacos实际发现、backend可用、schema/模型正确或回填完成。KI-37/38/39/42/48/50/51/53 的受影响路径门禁参见 `docs/linux-deployment-init.md`，未扩张为全仓修复。对应本地测试与构建见 [isolated-rag-local-verification.md](isolated-rag-local-verification.md)。
