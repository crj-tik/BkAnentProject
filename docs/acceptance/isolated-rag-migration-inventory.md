# 独立 RAG 逐空间迁移清单（历史源码、实际初始化与待验证水位）

## 证据边界与当前口径

本清单对应 `extract-isolated-rag-service`，分支 `dev-fix`，环境盘点交接基线 HEAD `42a55bf`；主会最新任务进度交接为 **27/49**：6.7按原任务边界以全量生产零旧RPC/proof、provider死注入清除、正常四Agent方法/index/backfill/outbox/physical worker保留及clean compile通过闭合；1.4由主会核对安全运维库存/配置/脚本及Nacos/model证据后闭合。完整test 8.7仍open，1.2仍由主会按完整证据核对，不据本文补勾。计数由主会维护，不由本文重新计数或勾选。主会交接本轮 **22模块 clean compile SUCCESS**，日志 `D:/project/rag-current-reactor-clean-compile.log`；首轮current test的common-a2a撤销event fixture空message失败保留。随后fixed轮RAG279/auth60通过，但A2A redirect fixture、memory mock和customer两个fixture仍失败，下游模块skipped；主会已修夹具、A2A focused通过，当前final reactor test正在运行、尚无结果，不报完整test/package/deploy成功。迁移前源码 `74c8d92^`（`2a3fa12`）、首次集中迁移 `74c8d92`、前序盘点 `365f8a1` 与 `ee4c9b9` 的本地验证都是历史证据，不证明当前版本已测试/部署。

本次恢复任务仅编辑本清单与 `isolated-rag-unblocking-steps.md`，`isolated-rag-local-verification.md` 由主会独占，不修改。此前已先读取三现有文档及 `docs/logic-rationale.md` / `docs/known-issues.md`；不修改 LR/KI/tasks/spec/code/config，不宽泛查实现或测试，不执行 Maven、Docker、SQL、网络、commit/push。下文运行摘要区分 Docker/主会交接与已读取 safe JSON；精确证据路径列于第7节。本编辑不自行重算原始数据/备份 checksum，不读取 `runtime.env`、`.env`、root/private backup 或 inspect/env/dump 私密内容，不披露秘密、备份 payload 或未经批准正文。

个人开发环境、本地及本项目容器变更已授权；公司 embedding/rerank/LLM 为预定目的地，不再要求新脱敏项目或重复批准。ADMIN 房源业务广读、BROKER 状态限制、MANAGER 默认拒绝、个人记忆 ADMIN 亦 owner-only、失效用户 token 无效、最小 A2A SDK transport/auth beacon、删除无消费者旧 RPC 和 signed listing legacy reconcile 均已明确。旧 LR/KI 的待授权/兼容代理/至少一旧兼容周期表述保留历史，不作为本轮新阻塞；权威清单/规格同步及任务勾选由主会处理，本编辑不改其状态或重算任务进度。

## 空间映射摘要

| 逻辑空间 | 历史 Milvus 别名 | 新 ES 模板名称 | 新 Milvus 模板 database.collection |
|---|---|---|---|
| `listing.knowledge` | `listing_knowledge` | `rag_listing_knowledge_v1` | `default.rag_listing_knowledge_v1` |
| `memory.preference` | `user_preference_knowledge` | `rag_memory_preference_v1` | `default.rag_memory_preference_v1` |
| `memory.constraint` | `system_constraint_knowledge` | `rag_memory_constraint_v1` | `default.rag_memory_constraint_v1` |
| `supervisor.knowledge` | `agent_knowledge` | 未注册 | 未注册 |

这些物理名称是模板/历史源码映射，不是 live schema、模型、索引数量、provision、回填或切流事实。不同空间不共用索引/集合；物理分开不赋予读取权。未知 supervisor 保持未注册/未激活，不新建行业语料；营销独立 ES 不在本次提取范围。

## 1. listing.knowledge

### 历史身份与领域真源

- 历史 ES 索引模板 `listing_info`，使用业务房源 ID；历史 Milvus 逻辑 ID `listing:{listingId}`，source type `LISTING`，source ID 十进制房源 ID。不能直接将两种旧键视为相同物理 ID。
- SQL 真源为 `bk_listing.listing_info`，新逻辑 ID 保留 `listing:{id}`。资源 grant 真源为 `listing_rag_grant`；不推断 `brokerId == auth userId`。
- 原模板 schema `1`、policy `listing-v1` 属历史声明；本轮房源 policy 为 **`listing-v2`**，公共领域 scope 为 **`rag.scope.v2`** 并增加 `businessWideRead`。是否生效需当前测试与运行配置验收。
- 完整投影同时包含身份/source/revision/schema/contentHash、授权和业务正文；创建、更新、删除、状态、资产绑定、OCR、核验和授权变更保留领域事务 revision/outbox 路径，正常 writer 不恢复旧 Agent 索引 RPC。
- 消费者保留领域 REST/facade/RPC、Agent 房源 REST 包装和 `searchListingsByKeyword`；默认混合召回加 rerank 不在房源进程执行 ES，所有实际消费者无旧后端 fallback。

### 本轮角色/scope 验收口径

ADMIN 读取所有未删除房源，含非 `ON_SALE`；BROKER 只读取 `ON_SALE`；MANAGER 不默认准入。不能再把这两类已授权默认角色统一要求逐条 grant，也不能因 ADMIN 广读而放行已删除房源。显式资源授权规则和领域最终 SQL/revision/hash/状态复核须与当前 policy 自洽；USER 是 READ 的唯一合法身份，SERVICE READ 在 common signer 拒绝，领域不可达 SERVICE public 分支删除。交接中的源码修订尚待当前测试，不等于 live 授权已验证。

### 已测 SQL 与切流前缺口

原 MySQL upgrade 后 listing 仍 0，独立 fresh listing 亦 0。最新旧后端实测：ES `listing_info` count=0、mapping=`{}`，旧 UUID 已记录；Milvus REST `default` 集合 `[]`，etcd 精确 catalog 为1库/0集合/0partition、实体0。**这些只证明该目标/快照时点没有旧实体或可用集合 schema，不代表新索引/schema 已 provision 或检索能力通过。**旧 PK/model/hash 因无集合/实体而 absent，不再写成等待盘点或权限授权的阻塞，也不补造映射/维度/hash。新 schema、模型维度、默认召回、删除/授权及应用水位仍待实际运行；旧 ES 写入曾默认关闭，不以旧空索引反证历史写入完整性。

回填保留源记录与持久 revision 身份并集、有界业务 ID 分页、锁后当前完整快照和物理删除身份 tombstone；`QUEUED` 不是双后端 APPLIED。Explore 全量清理复核已确认旧 RPC/proof/collector 删除，正常 index/backfill/outbox 与 worker 物理 hash reconcile 保留；该源码清理结论不等于当前编译/测试或真实房源 Agent A2A transport/auth 生命周期通过，不再等待 SDK 授权。

### 路径锚点（不冒充本轮源码复核）

历史 `listing-master-service/.../search/ListingKnowledgeDocumentAssembler.java`、`.../service/impl/ListingSearchServiceImpl.java`、`nacos/listing-master-service.yaml` 以 `74c8d92^` 查阅；领域锚点 `common/.../rag/RagDocumentIds.java`、`listing-master-service/.../rag/ListingRagGrantService.java`、`ListingRagOutbox.java`、`ListingRagBackfillService.java`、`.../service/impl/ListingManagementServiceImpl.java`、`.../tool/ListingTools.java`。迁移为 `sql/migrations/20261012_listing_rag.sql` → 固定 `bk_listing`；映射见 `nacos/rag-service.yaml`。本轮未读取实现文件，不提供可能漂移的精确行号。

## 2. memory.preference 与所有个人记忆入口

### 身份与投影历史

- 历史 ID 为 `userId + "_" + preferenceKey`，metadata 含 `user_id/preference_key/preference_value/category`；不按下划线拆分猜 owner。
- SQL 真源 `bk_memory.user_preference_memory`，业务身份 `(user_id, preference_key)`；新 ID 为 `preference:{Base64URL(userId UTF-8)}:{Base64URL(key UTF-8)}`。关联旧键必须基于 SQL/可信 metadata。
- owner 绑定真实 principal.userId；confidence < `0.1` 的索引失效、<= `0` 的物理删除仍保留 projection tombstone，revision 不归零。upsert/decay 同事务锁下重读持久 decimal/time 再投影；最终相关度/confidence/时间衰减排序、统一评分 asOf、合池预算和真源复核按既有路径保留。
- candidate 公共 metadata 仅 `preferenceKey/category/confidence/observationCount/lastObservedAt/active`；userId/ownerId 用于索引授权，不作为候选公共 metadata。独立已授权业务 record 响应不等于索引 metadata。
- memory REST/RPC 和 Agent 纯包装保留；历史 collector/retriever 无消费者已删除不说明旧集合为空。

### 本轮 owner-only 边界

所有个人记忆读、写、回填均 owner-only，**ADMIN 也不例外**。不能只检查 body owner 是本人而忽略已有 session/record 的 owner；不能借管理角色、SERVICE writer、旧全 owner 回填实现或 public SERVICE 分支越权。READ 仅真实 USER，失效用户 token 无效，所有实际消费者无 fallback。

主会纠正的 KI-79 实际缺口是个人记忆 query、missing-session/final reauth 门禁，不是已证明的 session owner takeover：现有 service 已保持 owner immutable 且 `session_id` UNIQUE，未证明可覆盖 other-owner session。相关修复及当前反例仍待成功test，不把早期takeover推测保留为已确认缺陷。

### 已测 SQL 与剩余证据

upgrade 后 preference 0、shared memory 3；fresh preference/shared memory 均 0。fresh 的 memory lock 1 是初始化行数，不是业务回填。旧 Milvus 实测 REST default 集合空且 etcd catalog 1库/0集合/0partition、实体0，旧 PK/model/hash absent；该时点不存在旧实体可核对 owner/冲突/旧向量 hash，不再作为缺盘点权限或待补旧映射的阻塞。SQL 的持久个人/共享记录仍按真实 owner 和授权处理，空旧集合不能豁免它们。新模型/语义版本、SQL时间/decimal到当前投影的表示、session/record 全入口 owner 反例、回填及物理删除结果仍待验证。真实 MySQL 初始化和隔离 restore 计数匹配不证明应用层 hash、owner 权限或双后端过滤等价。

真实 rerank profile/尺度/scoringVersion 通过后才登记 `memory.rag.semantic-scoring-versions`；空集失败关闭，不凭模型名或测试版本替代。历史 memory `p:0` 分阶段单页 cursor/nextCursor/receipts 只作为旧接口背景，本轮 owner-aware 范围和当前接口由 freeze 后实现/测试确认，nextCursor=null 从不等于 APPLIED。

路径锚点为历史 `agent-service/.../service/UserPreferenceCollector.java`（`74c8d92^`），领域 `common/.../rag/RagDocumentIds.java`、`memory-service/.../service/UserPreferenceMemoryService.java`、`MemoryRagProjectionService.java`、`MemoryRagSearchFacade.java`、`MemoryRagSecurity.java`、`MemoryRagBackfillService.java`；`sql/migrations/20261012_memory_rag.sql` → 固定 `bk_memory`。

## 3. memory.constraint

历史别名 `system_constraint_knowledge`，原 ID 为 constraint key，metadata 为 `constraint_key/category/tags`；历史 Agent bootstrap 曾分别写 SQL/Milvus。真源为 `bk_memory.system_constraint_memory`，新 ID `constraint:{Base64URL(key UTF-8)}`。

共享/系统约束读取与管理按显式角色/权限和 active 规则，READ 同样仅真实 USER；不虚构 owner，不将 SERVICE operation grant 当用户管理权。个人记忆 owner-only 不自动扩张成系统约束 owner 模型。新 upsert/停用/恢复保留领域事件；bootstrap 只在 memory、遵守开关、只补缺，不覆盖人工正文或恢复已停用记录。公共 candidate metadata 为 `constraintKey/category/tags/active`；回填包含已保留删除身份。

upgrade 保留 constraints 17，fresh constraints 0，MySQL 隔离 restore 与原计数匹配；这些是不同环境事实，不相互覆盖。旧 Milvus 实测无集合/partition/实体，旧 PK/model/hash absent，不待猜测旧 schema 或新增旧语料。17条SQL约束及其 seed/人工状态、当前权限、新投影和双后端应用水位仍须验证。旧机制未持久化的删除身份不能凭空恢复为已知 tombstone；空旧 catalog 不证明历史删除已完整持久化。

路径锚点为历史 `agent-service/.../service/SystemConstraintBootstrapRunner.java`（`74c8d92^`），领域 `memory-service/.../service/SystemConstraintMemoryService.java`、`SystemConstraintBootstrapRunner.java`、`MemoryRagSecurity.java`、`MemoryRagBackfillService.java`，以及 `common/.../rag/RagDocumentIds.java`；迁移同 `20261012_memory_rag.sql`。

## 4. supervisor.knowledge

历史 `agent_knowledge` writer 接受调用方 documentId，以 `memory` source type 和同值 source ID 生成文档；无已证明的统一身份/权威 SQL 真源。保留通用搜索 REST、`local:milvusKnowledgeSearch` 稳定包装和受管 alias，不恢复后端执行底座。

本轮明确继续未注册/未激活，未知语料不公开、**不要求新建行业语料**，不是等待新批准的实施阻塞，也不是该空间已迁移。未来若另有明确范围才需可信来源/身份/PK、owner/read policy、schema/model、revision-bearing 导入和删除事实；现有 inspect/provision/replay 不生成缺失语料。

历史路径 `agent-service/.../milvus/memory/AgentMemoryMilvusService.java`（`74c8d92^`）；包装锚点 `agent-service/.../rag/AgentRagCompatibility.java`、`.../milvus/memory/AgentMemoryMilvusService.java`、`.../tool/AgentMilvusTool.java`、`nacos/agent-service.yaml` / `nacos/rag-service.yaml`。

## 5. 共享 schema、物理身份与 HTTP 目的地

历史 Spring AI store 将逻辑 ID 放在 Document.id，同时记录 documentId/source_type/source_id metadata；Spring AI 1.1.2 默认 PK 最大 36 字符、手工 builder 不等于 afterPropertiesSet，是历史库/源码事实，不证明 live collection schema。

新模板字段 `doc_id/content/metadata/embedding`，source 在 metadata；1024 维、COSINE/IVF_FLAT、embedding 模型 `baai-m3-1b-base-v1-embedding-20240619` 是模板声明，不是测量。历史已记录新物理 ID 精确算法：

```java
UUID.nameUUIDFromBytes(("rag-physical-id-v1\u0000" + logicalId).getBytes(StandardCharsets.UTF_8))
```

只用于新空间 Spring AI delete/add，逻辑 ID 留在 metadata；不能反推旧 PK，delete/add 不称原子 upsert。store 绑定不可变 space/schema/model/fields/dimension；schema 只读核验，provision 显式受权，不跨 schema 复用集合。新 NUMBER 严格 double round-trip、INSTANT 固定 UTC 九位小数/ES date_nanos，不自动兼容未知旧 SQL/ISO 表示。参见 LR-32/35、KI-58/60 和历史 `ProjectionCodec` / `SpringAiMilvusDocumentStore` / `ProjectionFilters`。

本轮 HTTP 只通过 `mode` / `allowed-hosts` / `internal-networks`：PUBLIC_ONLY 默认；精确公开 host `open-chatgpt.ke.com`；MANAGED_INTERNAL 对精确 host 配 RFC1918/ULA 网段，项目 Docker `172.24.0.0/16`。`host.docker.internal` 容器映射 `192.168.65.254/32`、Windows `10.117.102.54/32` 必须按执行位置不同 map。全部 raw DNS/实际地址绑定/重连/TLS 校验及 proxy/redirect deny 保留，HTTP 不覆盖 Milvus gRPC/Dubbo。授权目的地/配置不等于 live 网络与默认 backend/rerank 已验证。

## 6. 保留接口与无消费者遗留清理

保留的六组 REST 包装历史路径：

- GET `/agent/vector-store/milvus/search`
- GET `/agent/mcp/milvus/search`
- POST `/agent/vector-store/milvus/collections/init`
- POST `/agent/mcp/milvus/collections/init`
- POST `/agent/rag/listings/index`
- POST `/agent/rag/listings/query`

初始化仅受管 alias/匹配维度/管理身份，旧 collectionName 不赋予物理寻址权。历史默认房源 20+20/最终10、知识 HTTP 最终5、工具 topK 有界池，以及历史 DTO/chat/checkpoint 只读/recordMilvus/recordTool 包装继续作为回归要求，当前版本待验证；不因名字叫 Milvus 而保留执行底座。

Explore 全量复核已确认无消费者 `AgentRpcService.syncListingKnowledge(Long)` / `deleteListingKnowledge(Long)`、signed listing legacy reconcile/proof 与旧 collector 删除；其他 Agent 四个方法及正常 REST/工具/业务 READ、normal index/backfill/outbox 和 physical worker 保留，营销独立 ES 不删除。历史 `LEGACY_RECONCILIATION_REQUIRED`/expected revision/hash 条件代理与 KI-75 的可信旧入口要求不再作为本轮待建设路径，**不建旧兼容周期**。清理已确认是源码静态证据，不再写“是否删除未核对”；当前 tests/package/deploy 和实际生命周期/物理水位仍未通过，不能以删除结论代替回归。

A2A patch模块 `a2a-sdk-transport-patch` / `0.2.5.Beta2-bk-transport1`采用原binary unpack，patch compile官方 **`A2AClient` + `JdkA2AHttpClient` 两个source**，不是单source；不改`.m2`、不反射、不第三JSON-RPC。`rag.credential.v2`兼token/beacon。真实SDK transport/auth生命周期仍待当前test/运行验证，不再请求范围批准。

## 7. 初始化、备份与配置发布实际证据

### 原环境及 upgrade（Docker 代理交接）

- MySQL `8.4.11` 四库受限 dump `168764` bytes；Nacos 十二原 config 在 `BK-AGENT`/`public` 各六，原均无 rag。
- Nacos `docker commit` pause snapshot + `docker save` archive `293634560` bytes，有archive checksum；是 **crash-consistent，不是cold Derby**。最新隔离Derby crash recovery及十二原config hash读回匹配已证（下节安全JSON），不以此证明cold备份或应用rollback。
- 原 Nacos `978571137a06`、MySQL `325cc757feb7` 未重建，根 `.env` 未改。安全备份 `D:/project/rag-dev-backups/42a55bf-20261013-init` 含秘密文件，只引用非秘密摘要，不读取私密内容。
- 版本初始化 `v20261013.1` 固定 fresh 九步/upgrade 六步、固定 DB/SQL 映射、LF SHA-256、shared runner 和 check。首次 upgrade checksum 因 CRLF 未归一在 DDL 前拒绝；修正 LF 后实际六步运行两次均 Verified，不抹去第一次失败。
- 原计数保持 listing 0/preference 0/constraints 17/shared memory 3/users 2/roles 3/workflows 3/checkpoints 46。
- `rag_runtime` 仅两张 `bk_rag` 控制表 SELECT/INSERT/UPDATE、无 DDL；跨 `bk_auth` SELECT 实测 `1142`。两控制表权限验证不是全业务权限验收。

RAG 增量三 SQL 固定目标仍为 `20261012_rag_control.sql` → `bk_rag`、`20261012_listing_rag.sql` → `bk_listing`、`20261012_memory_rag.sql` → `bk_memory`；已改为版本 manifest/shared runner 的 fresh/upgrade 实测口径，不再保留“Compose 首启没迁移、真实 SQL 全未执行”的现行声明。其他六步/九步文件明细由实施方 manifest 证据维护，本编辑未读取配置，不猜映射。SQL 外部值使用参数绑定、标识符固定，DDL 账号与 runtime 分离；payload/tombstone 保留与清理不凭初始化成功推断，当前不删数据。

### 独立 fresh（允许 JSON 已读取）

`D:/project/rag-dev-backups/42a55bf-20261013-init/fresh-init-verification.json` 记录：

| 项目 | 值 |
|---|---|
| Compose / 容器 | `bk-anent-dev-initcheck` / `bk-anent-dev-initcheck-mysql-1` |
| 新空卷（保留） | `bk-anent-dev-initcheck_fresh-data` |
| manifest / 首启 | `v20261013.1` / healthy |
| 后续 replay | fresh、fresh、upgrade |
| counts_unchanged | true |
| auth users / roles | 2 / 3 |
| contract template | 1 |
| settlement rules / tier | 2 / 1 |
| memory lock | 1 |
| listing/preference/constraint/shared memory | 各 0 |
| rag document states / index events | 0 / 0 |
| history facts：auth/interview/agent/rag/listing/memory | 1/2/3/1/1/1，合计9 |
| active_admin_broker / auth_other_user | 2 / 0 |

Docker 代理确认首启九步、fresh 重跑两次各九步、upgrade 六步全部 check。JSON 本身没有各步原始 SQL 输出/checksum 值；此处不冒充独立运行或重算校验。fresh 和原环境计数不同是环境不同，不代表原 constraints/shared memory 被清空。

### 最终 Nacos 七配置（safe JSON 已读，历史异步观察保留）

`D:/project/rag-dev-backups/42a55bf-20261013-init/nacos-publication-verification.json` 确认 namespace ID `BK-AGENT`、group `DEFAULT_GROUP`，七 data ID 为 `auth-service.yaml`、`rag-service.yaml`、`listing-master-service.yaml`、`memory-service.yaml`、`agent-service.yaml`、`customer-service.yaml`、`marketing-content-service.yaml`；`authenticated_login=true`、`readback_equal=true`、`a2a_domains=11`，逐 data ID 的 SHA-256 保存在该安全 JSON。**最终七配置认证发布/readback 与11域 A2A 信任一致已完成**，不再列“11域扩展版待再次读回”或“尚未publish”为阻塞。Docker交接原Nacos未重建。

历史仍保留：`bkagent` 登录HTTP200、顶层accessToken未回显；早期rag POST true立即GET404为异步可见性观察，最终通过限定deadline readback retry核对，不以POST true替代读回。后续如配置版本再变化，需按同样限定deadline逐项读回，但不能把这项维护规则说成当前最终版本未验收。

A2A env 保持 `{DOMAIN}_A2A_KEY_ID` / `{DOMAIN}_A2A_SIGNING_SECRET`；response 保持 `AUTH_RAG_RPC_RESPONSE_KEY_ID` / `AUTH_RAG_RPC_RESPONSE_SECRET`，不改新 AUTH 前缀。其他六 app YAML 扩展保留九域真实 guard 能力，不改为全拒；六服务未部署。配置中心读回不代表应用实际加载/生效。只七data ID，不整目录发布、不依赖无人导入mysql-common、不输出secrets。

### 最终安全运维库存、配置与模型预检

本轮已读三个指定安全证据，不搜索其他backup，不读取containers-before/full.env/raw dump；JSON提供非秘密库存/校验摘要，不扩大旧secret的读取或使用权限。

- `D:/project/rag-dev-backups/42a55bf-20261013-init/operations-inventory-safe.json`：当前data ID总数为 **BK-AGENT 17 / public 21 / bk-acceptance-smoke 5**，原基线16/21/5；七选中发布中新增rag，不能把“选中十二原config备份”当总库存十二。原MySQL/Nacos及四backend image本地存在；旧auth image本地存在但容器exited；旧listing/memory/agent容器exited，所记录共同image ID `sha256:a6e760985207af69747d3306bac1e714bfeab829ff6d6b21c90cfcfb6c38264d` **本地缺失、repo_digests=[]**。该旧ID/可变dev标签不是可重建或已备份的rollback image；保留原实例不等于重建恢复已有保证。JSON中applications_started/rag_image_created=false、applications_effective_not_verified=true，不视库存通过为应用部署。原dump、Nacos archive及四只读卷archive的bytes/SHA-256元数据见此JSON，未读取payload或自行重算。
- `D:/project/rag-dev-backups/42a55bf-20261013-init/configuration-validation.json`：严格PyYAML与显式duplicate检测，application_count16/owned_yaml_count9，记录逐文件SHA-256；compose runtime valid、empty example fails closed、bash/LF、publisher AST、manifest checksum和diff check通过，application_build_or_start=false。它是静态/脚本配置证据，不证明应用guard已实际请求或加载Nacos。
- `D:/project/rag-dev-backups/42a55bf-20261013-init/model-preflight-verification.json`：仅synthetic text、**through_application_guard=false**。embedding旁路请求 `https://open-chatgpt.ke.com/v1/embeddings` 成功，configured/response model均 `baai-m3-1b-base-v1-embedding-20240619`，实际dimension1024/all_finite=true；这是目的地/模型旁路预检，不证明当前应用HTTP安全guard/新schema或默认双路通过。rerank在配置目标 `http://host.docker.internal:18119/rerank` 返回 **HTTP401**，http_success=false、profile_not_activated=true；保留实际失败并维持profile关闭，不猜评分尺度/scoringVersion、不把旁路embedding成功写成rerank或默认路径已验收。后续需用获准凭据来源解决目标认证，不输出或扩大旧secret权限。

主会已按这些安全库存/配置/脚本和Nacos/model证据闭合任务1.4，当前27/49；1.2及完整运行门禁仍由主会核对，不据局部预检新增完成。

### 旧后端只读盘点与安全备份

Docker交接四旧后端先停止，卷只读备份后恢复原容器healthy；没有重建或清空原卷。`D:/project/rag-dev-backups/42a55bf-20261013-init/backend-inventory-verification.json` 已读：

| 目标 | 时点实测 | 身份/限制 |
|---|---|---|
| ES | health green、total_documents=0；`listing_info` 和 `marketing_content` 各count0、mapping `{}` | 旧UUID分别`SB7OICsnTEi5TiYYxIFzYA` / `8XOtbKnuRRmXRQhr2R4GmA`；各空mapping SHA-256为`44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a`，不是新RAG schema；legacy model/PK为null |
| Milvus/etcd | REST default集合`[]`；精确catalog database1/collection0/partition0、实体0、identity_hash_inventory空 | 固定catalog key `by-dev/meta/root-coord/database/db-info/1`；PK/dimension/model/metadata因无集合不适用，不猜旧实体hash |

上述空catalog已确认，不再等待盘点权限或把旧PK/model/hash缺失写成可补的旧数据阻塞。它不免除新schema/provision/embedding/rerank与应用双路水位门禁。营销独立ES及其旧UUID保留，count0不是删除营销能力的理由。

### MySQL 隔离恢复与脚本真实门禁

`D:/project/rag-dev-backups/42a55bf-20261013-init/mysql-restore-verification.json` 已读：由受限备份恢复到隔离容器 `bk-anent-dev-restorecheck-mysql-1` 和独立卷 `bk-anent-dev-restorecheck_restore-data`，`isolated/restored/original_counts_match=true`；listing0/preference0/active_constraint17/shared_memory3/auth_users2/auth_roles3/async_workflows3/checkpoints46与原计数匹配。本编辑不读取该JSON引用的SQL payload。

`D:/project/rag-dev-backups/42a55bf-20261013-init/nacos-restore-verification.json` 已读：internal隔离容器 `bk-anent-dev-nacos-restorecheck-internal` 在 `bk-anent-dev-nacos-restorecheck_isolated` 网络ready，effective_published_ports=[]、mounts=[]；Derby crash recovery verified，`BK-AGENT`/`public`各六份共十二原config SHA-256读回匹配，verified_count=12、original_instance_untouched=true、cold_consistent_backup=false。仅按实际无有效published ports判断隔离，不将继承的旧HostConfig绑定当真实端口发布。**MySQL隔离restore与Nacos paused snapshot隔离crash recovery已证，不是graceful cold备份，更不是8.8事件/config/应用rollback**；该运行门禁不据备份恢复关闭。

`D:/project/rag-dev-backups/42a55bf-20261013-init/script-gate-verification.json` 已读：bash syntax、all_shell_lf、compose_config和fresh/upgrade checksum dryruns为true；真实exit codes如下。

| 脚本场景 | exit code | 能证明/不能证明 |
|---|---:|---|
| 仅live mysql+nacos | 0 | 只验证选中基础组件，不是当前app能力 |
| missing rag / with-rag不能省rag | 1 / 1 | 缺预期rag失败关闭，rag未部署不是成功 |
| stopped auth | 1 | 已停止预期app不会被误报健康 |
| wrong host | 124 | 错host有界超时；不是远端能力通过 |
| live localhost MySQL | 0 | 指定本地MySQL检查通过，不代替其他host/应用 |

不把这些脚本/基础容器检查扩大为当前应用、RPC发现、backend检索、默认双路或outage通过；当前真实应用验证仍未执行成功。

## 8. 仍未完成的运行证据

1. 当前test失败修复后的成功回归、package、当前依赖/JAR hash和USER-only READ/owner-only/失效 token/SDK transport/auth beacon/managed HTTP反例。22模块clean compile已成功；Explore静态旧RPC/proof/collector清理已确认，不将这两项重复列为缺证。
2. 最终七配置/11域trust发布读回已证；运行应用实际加载、生效值及真实跨服务凭据仍待验证。
3. 新images/digest、限定服务部署、JRE DNS模块、真实RPC导出/发现/验签。脚本基础检查和容器healthy不是backend能力验收。
4. embedding旁路model一致/1024finite已测但未经应用guard；rerank预检401、profile未启用，仍待解决认证并验证协议/scale/scoringVersion及经实际应用guard的默认双路。新ES/Milvus schema/provision、逻辑/物理身份/owner/删除对账仍待验证；旧ES空mapping/Milvus空catalog已盘点，无旧实体的PK/model/hash为absent，不补造未知旧语料。旧listing/memory/agent imageID本地缺失，不当可重建rollback镜像。
5. owner-aware 回填、最新 revision/hash/schema/tombstone、两路 APPLIED 与实际物理投影/删除水位、默认双路融合/rerank、领域最终排序与越权/迟到写/partial 补偿。
6. stop-write/drain、增量追平、真实消费者切流、知识关闭零调用、RAG outage的正常业务可用性及8.8事件/config/应用rollback；MySQL隔离restore、Nacos paused snapshot隔离crash recovery/十二原config匹配已通过，不重复列为未执行，也不称cold备份或代替应用回滚。无旧兼容周期要求，但不免除这些水位门禁；保留index/collection/volume，不prune/down-v。

历史683 tests/JAR hash 只留在[本地验收记录](isolated-rag-local-verification.md)，不能充当当前源码通过证据。已授权执行顺序见[执行与剩余门禁](isolated-rag-unblocking-steps.md)。不将本次初始化/七配置发布扩大为默认检索、切流或完整安全审计通过，不由文档自行勾 tasks。
