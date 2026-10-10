# Tasks: 按域拆分 RAG 能力

依赖顺序：Phase 1（组 1-3）先落地房源域，Phase 2（组 4-6）后动记忆域。每组完成即编译 + 相关单测验证；组 3、6 含部署与配置切换。

本次修订撤回裸 SDK、embedding HTTP 客户端、双实现测试和 optional/profile 打包任务；统一复用原 Spring AI 实现。design.md 的 Review Constraints 尚未关闭，当前任务清单仍需补齐权限、一致性、部署及真实入口验收后才能进入实施。

## 1. common-milvus 共享模块（Phase 1 起点）

- [ ] 1.1 创建 `common-milvus` Maven 模块（包 `com.bkanent.common.milvus`），纳入根 pom `<modules>`，对齐 `common-skill` 的打包与命名约定
- [ ] 1.2 将 `milvus/core` 接口、模型及现有 `SpringAiMilvusDocumentStore`、`DefaultMilvusEmbeddingService` 一起迁入共享模块；抽取 `MilvusConnectionProperties` 和实际使用的 `AgentMilvusProperties` 配置；使用普通 Maven 依赖，不添加 optional/profile 双实现设计或适配器模块
- [ ] 1.3 为原实现抽取新增迁移回归测试：覆盖集合初始化、upsert、检索、删除、完整结果元数据；验证既有 schema、逻辑文档 ID、source-id、embedding 模型和维度兼容，不建立第二实现或双实现测试占位
- [ ] 1.4 agent-service 改为依赖 common-milvus，删除本地 `milvus/core` 包，全量编译 + 既有单测回归（此步无行为变化）

## 2. 房源 RAG 迁入 listing-master-service

- [ ] 2.1 listing-master-service 引入 common-milvus 与 `spring-ai-starter-vector-store-milvus` 依赖，装配 Spring AI 实现与 `MilvusConnectionProperties`
- [ ] 2.2 迁移 `ListingMilvusService`、`BgeRerankService`、`ListingRerankService`、`ListingStructuredFilter`、`ListingRecallCandidate`、`model/rag` DTO 到 `com.bkanent.listing.rag` 包；`ListingKeywordRecallService` 重写为进程内直调本地 ES 检索 service（去掉 Dubbo `ListingMasterRpcService` 自调用分支）
- [ ] 2.3 迁移 `BgeRerankServiceTest` 并补双路召回合并、单路命中、重排降级三个 spec 场景的单元测试
- [ ] 2.4 新增 listing-master REST 端点 `/listing/rag/index`、`/listing/rag/query`，权限校验复用 auth-service RPC（对齐 `agent.rag.listing.*` 权限码语义），迁移 `AgentControllerQueryTest` 中 listing RAG 用例
- [ ] 2.5 `ListingManagementServiceImpl` 的 `syncListingKnowledge` 调用改为进程内新 service；common 模块 `AgentRpcService` 删除 `syncListingKnowledge`/`deleteListingKnowledge` 方法，`AgentRpcServiceImpl` 同步删除实现

## 3. Phase 1 配置切换与部署

- [ ] 3.1 `nacos/listing-master-service.yaml` 增加 `milvus.*`（集合字段/索引参数）与 `rag.*` 配置段，`required-dependencies` 增加 `milvus`
- [ ] 3.2 `nacos/agent-service.yaml` 移除 `rag.*` 段、裁剪 `milvus.*`（Phase 2 完成前 memory 集合配置暂留）
- [ ] 3.3 `nacos/gateway.yaml` 增加 listing-master RAG 路由；`/agent/rag/listings/**` 加 RewritePath 过渡转发并标注移除计划
- [ ] 3.4 端到端验证：房源变更触发进程内索引 → 网关新路径检索命中；旧路径转发命中；agent-service 无 listing RAG 端点；按 AGENTS.md 规则补 `docs/logic-rationale.md` 条目（域归属规则、D3 环解除）并中文 commit 推送 dev-fix

## 4. memory-service 集成记忆 RAG（Phase 2 起点）

- [ ] 4.1 memory-service 引入 common-milvus，按 agent-service 原运行路径装配现有 Spring AI Milvus 实现与 embedding 接入（D2）；迁移必要的 Nacos/本地 profile 配置并验证 bean 装配、模型与集合维度一致，不新写裸 SDK 或 embedding HTTP 客户端
- [ ] 4.2 迁移 `AgentMemoryMilvusService`（含 `user_preference_knowledge`/`system_constraint_knowledge`/默认集合逻辑）与 `MemoryCollectionInitializer` 到 memory-service
- [ ] 4.3 memory-service 新增内部 REST 端点（向量检索 + upsert），补齐 spec 场景单测：语义检索、topK 缺省、约束更新后可检
- [ ] 4.4 迁移 `SystemConstraintBootstrapRunner` 到 memory-service（`upsertSystemConstraint` 变进程内直调），验证双写失败仅告警不阻塞启动；`required-dependencies` 增加 `milvus`，验证 Milvus 故障时 MySQL 半边可用

## 5. agent-service 瘦身为薄代理

- [ ] 5.1 `AgentMilvusTool.searchKnowledge` 底层改为 REST 调用 memory-service 检索端点（超时 + 有限重试），新增对应配置类（仿 `MemoryServiceProperties`）；保留 `AgentToolContextHolder` 门控、topK、审计记录
- [ ] 5.2 补工具远程化单测：返回格式与迁移前一致、会话禁用不发起远程调用、远端不可用降级提示
- [ ] 5.3 删除 agent-service 的 `milvus/memory`、`milvus/listing` 残留、`AgentController` 的 `/agent/rag/memory/**` 端点、`AgentChatProperties` 中 memory 工具不再引用的配置项；删除死代码 `UserPreferenceCollector`/`UserPreferenceRetriever`
- [ ] 5.4 `nacos/agent-service.yaml` 移除剩余 `milvus.*` 段，`required-dependencies` 移除 `milvus`；全量编译 + `SupervisorToolLoopGraphTest`、`SupervisorCapabilityCatalogTest` 等编排回归套件必须绿

## 6. Phase 2 验证与收尾

- [ ] 6.1 端到端验证：supervisor 会话调用 `milvusKnowledgeSearch` 返回 memory-service 检索结果；会话禁用开关生效；停 memory-service 后工具返回不可用提示且会话不中断
- [ ] 6.2 验证 agent-service 在 Milvus 实例不可达时正常启动与编排（就绪链缩短生效）
- [ ] 6.3 `nacos/gateway.yaml` 移除 `/agent/rag/listings/**` 过渡转发（确认无外部消费后）
- [ ] 6.4 按 AGENTS.md 规则补 `docs/logic-rationale.md` 条目（D2 复用原 Spring AI 实现、业务规则归属与存储运行位置分离、域访问边界、D4 工具代理边界），中文 commit 推送 dev-fix
