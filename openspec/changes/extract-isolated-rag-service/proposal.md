## Why

RAG 执行目前分散在 agent-service（Spring AI Milvus、embedding、候选合并和重排）与 listing-master-service（ES 关键词索引及召回），记忆的结构化生命周期与向量更新也分离。需要将这条检索链整体抽成唯一的 rag-service，使业务域与 SubAgent 通过受控契约消费统一检索能力，同时以独立 ES 索引和 Milvus 集合隔离知识内容，避免新增服务后旧实现仍在多处运行。

本变更替代 `split-rag-by-domain` 的架构方向；旧 change 仅作为历史规划保留，不实施其 common-milvus 分发和域内 RAG 路线。本次只生成规划工件，不实施应用代码、不上线、不归档或改写旧 change。

## What Changes

- 新建独立 Maven/Spring Boot `rag-service`，迁入并复用现有 Spring AI Milvus 存储封装、embedding 接入、ES 客户端和语义重排协议；不重写裸 Milvus SDK，不新建 embedding HTTP 客户端，不维护多套存储实现。
- 将 ES 关键词召回、Milvus 向量召回、融合、去重和通用语义重排集中执行，默认混合召回加重排。业务域只提交授权范围、结构化条件、候选数量和受支持策略/开关，领域最终排序仍留域内。
- 为每个获准知识空间配置独立 ES 索引与 Milvus 集合；不同域或需隔离的 SubAgent 不共用物理存储。逻辑空间映射和跨空间授权由服务端控制，metadata 仅承担空间内过滤，不能代替物理隔离或授权。
- 定义有版本的 Dubbo 检索、索引变更、状态与管理契约，验证调用服务和最终用户身份。拒绝任意集合名、索引名、查询表达式、脚本或后端 URL。
- 房源域和记忆域保留业务数据库、语料生成、授权决定、生命周期和最终排序；通过事务 outbox 提交完整版本化文档或删除事件。rag-service 统一维护两类索引、持久化应用状态、补偿失败及提供回放/重建能力，消除房源→agent→房源的索引回调环。
- 将房源 Agent 的现有关键词工具改为领域包装的新混合检索消费者，同步技能描述；agent-service 保留 `milvusKnowledgeSearch` 的稳定工具身份、会话门控、结果记录和审计，仅代理知识检索，不执行后端 RAG。
- 保留真实旧 REST 路径及响应字段一个兼容发布周期，由受鉴权的代理调用新领域入口/RAG；旧索引 RPC 分阶段退役。**BREAKING**：任意物理集合选择不再受支持；工具关键词搜索默认语义升级为混合检索；旧索引 RPC 在所有消费者升级后的下一清理发布删除。
- 配置、后端凭据、启动初始化和 RAG 依赖健康检查迁入新服务，移除 agent/listing 的旧后端依赖；RAG 故障仅使相关检索能力不可用，不将所有域的结构化操作或 Supervisor 编排判为不就绪。
- 对 rag-service 发出的 HTTP/HTTPS 请求执行 host 和解析地址校验，拒绝 localhost、环回、私有和保留地址，不为部署配置设置豁免。现有内网 ES 和本机 reranker 地址不能直接沿用为合规目标，必须在切流前提供符合约束且经过验证的部署目标。

## Capabilities

### New Capabilities

- `isolated-rag-retrieval`: 独立空间存储映射、服务/用户授权、结构化过滤、默认双路召回、融合重排、候选及故障契约。
- `rag-index-lifecycle`: 完整文档投影、版本/幂等、事务 outbox、双索引补偿、记忆生命周期、旧数据重建与切换回滚。
- `domain-rag-consumption`: 房源/记忆消费者职责、领域最终排序、Supervisor 工具代理和真实旧接口兼容及来源实现清理。
- `rag-service-runtime`: 可执行服务与 RPC 接线、配置/凭据归属、出站 URL 安全、能力健康、迁移验证及部署门禁。

### Modified Capabilities

无。现有 `supervisor-tool-orchestration` 的模型选择权、调用治理、账本恢复与历史 checkpoint 只读边界保持不变；`authentication-baseline`、`configuration-hygiene`、`runtime-bootstrap`、`service-packaging` 的现有要求继续适用。本变更的新 RAG 特定约束由上述新增能力表达，不改写其一般规则。

## Impact

- 代码模块：新增 rag-service；修改 common 的纯 DTO/RPC 契约、auth-service 的最小 RAG principal 事实接口、agent-service、listing-master-service、memory-service；根 Maven reactor 聚合新模块。rag-service 独占本次 RAG 的 ES/Milvus/embedding/rerank 执行依赖。记忆域需明确接线 Dubbo，不能以 common 的传递依赖视为已接通。
- 入口：旧 `/agent/vector-store/milvus/search`、`/agent/mcp/milvus/search`、两条集合初始化路径、`/agent/rag/listings/index`、`/agent/rag/listings/query`；`AgentRpcService.syncListingKnowledge/deleteListingKnowledge`；本地知识工具、房源工具/技能及 chat 审计 DTO。
- 数据：独立空间新索引/集合、房源与记忆域 outbox、rag-service 应用状态账本（独立 bk_rag 库）；保留旧数据不删除，通过盘点、backfill 和增量追平验证，不承诺数据零迁移。
- 配置与运维：新增 Nacos data ID，检查实际 namespace/group 导入链；修改受影响服务的启动、环境覆盖、Compose 和打包配置；切流前验证 URL 合规、身份链、真实能力健康与数据完整性。不得通过关闭双路/重排把未满足门禁伪装为默认路径验收成功。
- 范围限制：营销现有独立 ES 业务搜索未接入此次 RAG，也不消费房源索引，本次不搬迁其实现；它不能参与本次混合召回链。未来任何域接入 RAG 必须使用新服务，不再复制检索底座。不建设 KI-13 行业语料，不新增通用业务评分 DSL，不改访谈状态机或全项目认证模型。新发现缺陷与非显然决策在实施提交中按项目清单规则维护。
