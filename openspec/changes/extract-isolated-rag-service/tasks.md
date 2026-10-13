## 1. 基线盘点与实施前置检查

- [x] 1.1 重新读取 LR/KI、主规格及本设计，核对真实源码/消费者清单；确认旧 split-rag-by-domain 不实施，不归档或覆盖其历史工件。
- [ ] 1.2 盘点旧 REST/RPC/工具/技能/审计 JSON、现有集合与 ES 索引、文档 ID/PK/schema、实际模型维度及旧 ES 写入完整性，形成逐空间迁移清单。
  - 本地源码/历史模板逐空间清单已建立：`docs/acceptance/isolated-rag-migration-inventory.md`。真实 PK/schema/模型与数量/hash/权限/删除水位未盘点，不据本地名称完成本项。
- [ ] 1.3 核对各知识空间的真源与权限依据；明确 listing 资源授权签发路径、memory owner/管理基线与通用知识归属，未知归属数据隔离不激活。
- [ ] 1.4 检查实际 namespace/group/data ID、生效配置、端口清单、镜像/config 快照与依赖树；记录 KI-37/38/39/42/48/50/51/53 对受影响路径的门禁，不扩张为全仓修复。
- [x] 1.5 识别所有 RAG HTTP transport 与 DNS/连接扩展点，记录合规真实目标缺口；内网 ES/host.docker.internal 不放行、不擅自替换或公开，未满足时阻塞真实联调/切流但可继续非联网实现。

## 2. 独立模块与纯契约

- [x] 2.1 创建 rag-service Java 17 Spring Boot 可执行模块并纳入根 reactor，使用根管理版本及独立应用名，不建立 common-milvus 或多实现 profile。
- [x] 2.2 在 common 增加版本化 search/submit/getStatus/inspect/provision/replay RPC 与纯 DTO，严格定义请求字段、结果状态及错误码，不泄漏客户端或服务 JAR 依赖。
- [x] 2.3 实现 v1 数量/模式/策略/字段校验、类型化过滤模型、响应 scoreKind/scoringVersion/degraded 元数据，覆盖未知字段/原生脚本/非法容量关系单测。
- [x] 2.4 实现受管空间注册校验与管理权限入口，禁止不同空间共用物理索引/集合、任意目标寻址或 search 自动初始化；增加注册和跨空间测试。

## 3. 可信身份与空间授权

- [x] 3.1 实现按服务独立密钥的请求绑定签名 envelope 校验，覆盖 audience/operation/hash/expiry/key rotation，凭据仅来自环境或秘密管理，不进入模型/日志/快照。
- [x] 3.2 复用现有access token解析，在auth/common新增最小resolveRagPrincipal返回真实userId/roleCode/活跃事实，保持原principal结构；校验RAG→auth服务身份并接线专用凭据上下文、到期/撤销/账号失效失败关闭，增加伪造gateway/userId/serviceName反例。
- [x] 3.3 在房源域建立最小listing_rag_grant真源及受管管理入口，默认无授权拒绝，不推断brokerId=userId；接线房源/记忆scope签发与ownerDomain权限、actor/space/request/policy/expiry绑定，RAG校验交集，不依赖宽松hasPermission。
- [x] 3.4 实现强制 owner/resource/active 条件及双方过滤等价校验，覆盖 scope 超上限、越域、跨用户、普通consumer伪造别域授权、后台写服务越权搜索。
- [ ] 3.5 接线 agent/房源/记忆的同步与异步读取凭据生命周期；续接过期明确要求重新认证，不把长期 access token 落 checkpoint，不以 userId 换发身份。

## 4. 迁移检索执行与出站安全

- [ ] 4.1 将现有 SpringAiMilvusDocumentStore、实际 embedding 接入及必需模型/连接/schema 配置迁入 RAG，保持原文档 metadata/逻辑 ID 映射；不重写裸 SDK 或 HTTP embedding 客户端。
- [ ] 4.2 调整按空间 schema 身份缓存的 VectorStore 实例，校验 database/collection/field/model/维度不混用；验证初始化、旧文档读/更新/删除及维度错误。
- [x] 4.3 从房源迁移 ES 索引/关键词召回执行到 RAG，使用统一领域投影、显式 mapping 和类型化过滤，去除房源 Mapper/RPC 回调及 ES→SQL 召回 fallback。
- [x] 4.4 实现两路召回、文档去重、版本化 RRF/同分稳定顺序与有界候选池；增加异量纲分数、重复候选、单路开关及数量不足测试。
- [x] 4.5 迁移现有 BGE native/DashScope 重排协议并校验候选完整性、下标/分数，接线明确评分 profile/logit或bounded转换，覆盖 scoreKind 与关闭重排语义。
- [x] 4.6 在 ES/Spring AI embedding/重排真实 HTTP transport 接入每次连接的 host/DNS 地址安全守卫，拒绝私网/环回/保留/映射地址，禁自动重定向，覆盖 DNS 换址、重连、重试与配置刷新；不能仅启动校验或增加可信配置例外。
- [x] 4.7 实现 STRICT/ALLOW_DEGRADED、阶段错误、整体 deadline、有界重试/并发/文本预算；覆盖一路/两路/重排失败与安全拒绝不可降级、不把失败当 NO_MATCH。

## 5. 领域事务事件与 RAG 应用账本

- [x] 5.1 为房源域新增真源版本/事务 outbox，覆盖创建、更新、删除、状态、资产绑定及所有改变语料/权限的活跃入口；外部发送发生在提交后，回滚零事件。
- [x] 5.2 为记忆偏好/约束新增版本与 outbox，覆盖 upsert、衰减、阈值失效、物理删除、停用与重新激活，使用无歧义稳定身份。
- [ ] 5.3 新增 bk_rag 控制数据库迁移及事件/文档状态，明确目标库、敏感payload最小权限/保留期，验证当前 MySQL 语法及重复执行，不修改无关历史迁移。
- [x] 5.4 实现提交持久化确认、事件状态查询、revision/hash 幂等/冲突/过期与 tombstone规则，发送超时可用相同eventId核对并重试。
- [x] 5.5 实现同文档串行worker、两路应用状态、有界重试/告警/授权 replay与实际hash核对补偿，覆盖并发、崩溃、网络迟到及部分成功，不承诺跨后端原子写。
- [x] 5.6 在融合/重排前依据最新账本剔除旧版本、部分应用与删除候选，禁止不同版本合并，测试账本不可用失败关闭及迟到旧向量只导致漏检不暴露旧正文。

## 6. 领域消费、Bootstrap 与兼容包装

- [x] 6.1 实现房源授权检索 facade：请求 RAG、按 ID 批量读取最新详情并重验权限/状态/条件、映射旧 match/context；最多一次有界扩大候选，不反向从 RAG 读业务。
- [ ] 6.2 将 searchListingsByKeyword 保留为领域包装的默认混合检索工具，更新 description/listing-search 技能并验证真实房源 Agent 调用；SQL 列表/详情与营销独立搜索不变。
- [ ] 6.3 实现记忆检索消费与真源可用性/最终排序分层，注册实际评分元数据；覆盖相关度尺度、阈值、时间硬过滤与软衰减区别、候选池不提前裁成最终topK，不强制示例权重或新评分 DSL。
  - 本地统一评分时刻、两池去重/再次真源复核、累计预算、返回metadata白名单和评分/时间/RPC反例已补齐；实际rerank profile及memory允许scoringVersion尚未验证注册，本项未完成。
- [x] 6.4 将约束 bootstrap/YAML资源迁入 memory，遵守启用开关、仅补缺、人工修改/停用不覆盖；所有更新生成领域事件，agent 不再重复执行初始化或双写。
- [x] 6.5 将 milvusKnowledgeSearch 改为远程代理，保留能力身份/格式化/recordMilvus及工具审计；在生产Graph接线真实知识禁用和授权空间，不固定true或允许模型物理寻址。
- [x] 6.6 保留真实六组旧REST方法/路径/成功字段，接线权限/别名/schema维度及房源facade；实现旧数量映射20+20/最终10、工具topK候选池，覆盖新旧默认、显式边界及非法关系，不静默改显式数量。
- [ ] 6.7 实现旧Agent索引RPC的停写排空后有限纯代理，依据已确认真源/revision生成事件；未提交/回滚/删除次序不明返回LEGACY_RECONCILIATION_REQUIRED，覆盖旧删除不误删较新状态，不创建无限待处理队列。
  - 领域signed reconcile已补当前完整投影hash证明和正向/拒绝/提交后派发失败回归；旧ID-only入口仍无可信认证与证明输入路径，不能自签名补造，保持失败关闭。见KI-74/75；真实排空及条件成功代理未完成。
- [x] 6.8 区分确定只读失败/权限拒绝/参数错/结果未知，验证旧 chat/milvusResults/checkpoint只读反序列化、已完成调用不重发；如新增transition，按KI-2逐调用点增加测试。

## 7. 配置、部署与能力诊断

- [ ] 7.1 新增 rag-service Nacos及应用配置，独立bk_rag/RPC/空间/后端/安全/评分策略；验证数据ID、namespace/group与实际生效一致，不依赖无人导入mysql-common。
- [ ] 7.2 在首次消费者切流版本同步迁走/删除来源执行类、bean、初始化及执行依赖，不能只关配置或删starter留源码；拆分Compose环境锚点，后端配置仅归RAG，保留chat与营销业务配置。
- [ ] 7.3 沿用项目Dockerfile模块构建新增独立bk-anent-rag镜像/JAR、受管端口与RPC发现，明确memory Dubbo接线；不混用外部reranker镜像规则或操作其容器。
- [x] 7.4 实现基础readiness与每空间/检索阶段能力诊断、schema/模型维度/重排协议与安全gate；消费者不以RAG后端作为全局硬启动/就绪依赖。
- [x] 7.5 更新仅受影响服务的启动/诊断/迁移清单，记录配置/镜像水位及默认混合路径真实验收命令，失败保留非零结果，不以TCP/running或strict validate证明完成。

## 8. 回填、切流、清理与验证

- [ ] 8.1 实现受权backfill：在领域事务中一致读取/锁定revision和完整快照，经相同outbox发布，水位含持久删除身份；测试回填与更新/删除交错收敛，通用知识先整理归属，未知语料不公开。
  - memory原POST改为默认100/最大500的分阶段keyset单页，返回cursor/nextCursor/scanned/QUEUED receipts，覆盖持久删除与更新交错；nextCursor=null不是双后端APPLIED或跨页原子快照。通用知识归属、真实回填和水位未验收。
- [ ] 8.2 在经授权且URL合规的真实目标验证新索引/集合、旧身份映射、两路过滤等价、删除/更新/partial写补偿和默认重排；缺目标时标阻塞，不用单路或stub替代完整验收。
- [ ] 8.3 执行短暂停写、旧writer/在途RPC排空、增量追平、配置/镜像备份及消费者切流；仅全部安全/身份/数据/能力gate通过才恢复写入。
- [ ] 8.4 验证RAG停机时领域结构化功能与无知识Supervisor仍可用；验证显式降级标记、旧接口/技能、跨域跨用户反例及历史审计恢复。
- [ ] 8.5 首次切流已无旧执行底座；完成一个兼容发布周期并确认消费者升级后再删除旧索引RPC签名/引用及残余无调用代码，确认偏好collector/retriever已清理，保留允许的纯包装。
- [x] 8.6 核查有效依赖/配置与源码：agent/listing/memory无本次ES/Milvus/embedding/rerank执行依赖和重复bootstrap，其他领域业务依赖不误删。
- [x] 8.7 执行受影响模块及上游的Maven compile/test/package，再执行全量既有编排/认证/领域回归；验证可执行JAR，逐项记录真实后端未执行/失败项。
- [ ] 8.8 演练按事件水位恢复新兼容版本；若需旧版本先重放差异且符合最新URL策略，不仅改路由；保留全部索引/集合/卷，不自动删除、prune或公开服务。
- [x] 8.9 将新增设计根因与确认缺陷更新到LR/KI并关联验收记录，在各已验证实施提交按项目规则中文commit及push；所有任务真实完成后才归档本变更，不以规划完成勾选实施任务。
