## Why

RAG 执行原先分散在 agent-service（Spring AI Milvus、embedding、候选合并和重排）与 listing-master-service（ES 关键词索引及召回），记忆的结构化生命周期与向量更新也分离。需要将这条检索链整体抽成唯一的 rag-service，使业务域与 SubAgent 通过受控契约消费统一检索能力，同时以独立 ES 索引和 Milvus 集合隔离知识内容，避免新增服务后旧实现仍在多处运行。

本变更替代 `split-rag-by-domain` 的架构方向；旧 change 仅作为历史规划保留，不实施其 common-milvus 分发和域内 RAG 路线。当前实施已部分完成但未完成真实配置、数据与切流验收。既有 7 个规划工件已完成修订，当前进入获授权的 apply 实施、初始化和限定开发部署验证；维护 docs/LR/KI，并在当前增量验证后提交推送。完整完成仍以任务门禁的实际证据为准。

## What Changes

- 新建独立 Maven/Spring Boot `rag-service`，迁入并复用现有 Spring AI Milvus 存储封装、embedding 接入、ES 客户端和语义重排协议；不重写裸 Milvus SDK，不新建 embedding HTTP 客户端，不维护多套存储实现。
- 将 ES 关键词召回、Milvus 向量召回、融合、去重和通用语义重排集中执行，默认有界混合召回加重排。业务域只提交授权范围、结构化条件、候选数量和受支持策略/开关，领域真源、最终权限/状态检查和最终排序仍留域内。
- 为每个获准知识空间配置独立 ES 索引与 Milvus 集合；逻辑映射和跨空间授权由服务端控制，metadata 不能代替物理隔离或授权。未知 Supervisor 语料继续隔离，不能默认公开或自动激活。
- 定义有版本的 Dubbo 检索、索引变更、状态与管理契约，验证调用服务和最终用户身份。仅 ADMIN 与普通成员两类业务身份（当前实际 roleCode 为 ADMIN/BROKER）；MANAGER 是遗留角色，不默认视为 ADMIN。活跃 ADMIN 可全读未删除业务房源（含非 ON_SALE），普通成员 BROKER 只读 ON_SALE；使用显式 business-wide scope 与角色/activeOnly 独立复核，不建设资源 grant 规范、不用空资源集或通配表达全读。个人偏好绝对 self-only，ADMIN 亦无跨用户例外。停用、删除、离职或角色失效用户在所有相关入口禁止操作。
- 接通 A2A 专用认证：使用可验证信标绑定真实身份、请求及受支持 SDK transport；专用凭据上下文不能进入模型 metadata、工具参数、日志或 checkpoint。必要最小 SDK 升级/修补或其受支持 transport 接线属于 apply 授权范围，不能以普通 HttpClient 能力推定真实 A2A 已支持。
- 房源域和记忆域通过事务 outbox 提交完整版本化文档或删除事件；rag-service 统一维护双索引、独立 bk_rag 应用账本、补偿、回放和重建，消除房源→agent→房源索引回调环。
- 房源关键词工具改为默认混合检索的领域包装并同步技能；`milvusKnowledgeSearch` 保留稳定工具身份、会话门控、结果记录和审计，只代理知识检索。
- 真实旧 REST 路径及成功字段保留受鉴权纯代理兼容，退役另行公告。**BREAKING**：任意物理目标选择不再支持；关键词工具默认语义升级为混合加重排；全量调用方盘点确认无旧消费者后，直接删除 `AgentRpcService.syncListingKnowledge/deleteListingKnowledge` 签名、实现及残余引用，并删除无消费者的 signed listing legacy reconcile/证明路径（保留正常 index/backfill/outbox 及 worker 实际 hash 补偿），不建立条件成功或永久失败的旧 RPC 兼容路径，不等待虚构兼容周期。
- HTTP 出站默认 `PUBLIC_ONLY`；内网须显式启用 `MANAGED_INTERNAL`，使用精确 host allowlist 与逐 host 对应 CIDR（建议 `rag.http.internal-networks` map），允许获准受管 Docker 单标签/host.docker.internal 和 RFC1918/IPv6 ULA。每次连接校验全部原始 A/AAAA 并固定已校验地址；环回/localhost、链接本地、映射、组播和其他保留地址始终禁止。禁止通配、0/0、任意 URL 覆盖、代理和自动重定向；未明确配置失败关闭，不是所有内网一律禁用或可信配置无限豁免。
- 个人开发环境允许在后续 apply 修改本地/本项目容器与配置；公司内网 embedding/rerank/LLM 暂不开展新的语料脱敏项目，但凭据仍不得硬编码、打印、进入模型或 checkpoint，不影响访谈既有 LR-6 脱敏逻辑。不得擅自改用第三方或公开语料/服务。
- 无生产真实业务，采用一次有界开发发布窗口，不搭建无消费者的旧 RPC 过渡发布。保留种子开发数据、历史 workflow/checkpoint 及旧存储；初始化必须版本化、可重入、与最新代码同步，覆盖当前缺失数据库/表、Compose 首启迁移和 Nacos 管理认证/实际 namespace 生效链。

## Capabilities

### New Capabilities

- `isolated-rag-retrieval`: 独立空间映射、服务/有效用户授权、ADMIN/成员与绝对 self-only、有界默认双路融合重排及故障契约。
- `rag-index-lifecycle`: 完整文档、版本/幂等、事务 outbox、双索引补偿、记忆生命周期、保种子版本初始化、重建与水位回滚。
- `domain-rag-consumption`: 域最终检查/排序、Supervisor 工具代理、A2A 专用身份链、真实 REST 兼容和无调用旧 RPC 直接清理。
- `rag-service-runtime`: 独立服务、配置/凭据归属、HTTP 双模式地址安全、能力健康、版本初始化及开发发布门禁。

### Modified Capabilities

无。`supervisor-tool-orchestration` 的模型选择权、唯一通用 Graph 执行、账本恢复与历史 checkpoint 只读边界保持不变；既有认证、配置卫生、运行初始化、打包及访谈脱敏要求继续适用。本变更不改写一般规则，不新增业务步骤调度器或全项目 RBAC/脱敏工程。

## Impact

- 模块：rag-service、common 纯 DTO/RPC、auth-service 最小 RAG principal 事实接口、agent/listing/memory 和必要 common-a2a/真实 SDK transport；根 reactor 聚合。RAG 独占本次后端执行依赖，记忆域明确 Dubbo 接线。
- 入口：六组真实旧 REST、知识/房源工具及技能、chat 审计 DTO、A2A 同步/流式/任务查询取消/异步续接身份链；删除无消费者的旧 Agent 索引 RPC，不牵连其他 AgentRpcService 方法。
- 已报告实证基线：MySQL 8.4.11，bk_rag 不存在，listing_rag 与 memory_rag 各三表缺失；listing_info=0、user_preference_memory=0，但 system_constraint_memory=17、session_shared_memory=3；auth 两个活跃 ADMIN/BROKER 用户，三角色含遗留 MANAGER；agent_async_workflow=3、checkpoint=46，最新三 orchestration 表及 run_control 结构缺失。不能称为全部空库或删除重建。
- 后端/Nacos的原始基线：旧 ES/Milvus 停止且无发布端口；Nacos BK-AGENT16/public21，旧容器空namespace实际public，无持久mount。当前已在受限备份后启动原后端，旧ES两个索引均0/mapping空，Milvus精确catalog仅default库且0集合/分区。七选中模板在十一域A2A扩展后认证发布/读回一致，原Nacos未重建。版本manifest首启/升级实测通过；这些不证明当前应用读取、模型schema或默认检索能力，实证详见验收记录。
- 版本初始化：限定补齐本次 RAG 与 agent 最新编排必要结构，明确目标库、版本/校验、重复运行和首启/已有数据升级，保留种子与历史。任务 5.3/7.1/7.5 承接验收；不顺带修复全仓历史 SQL。
- 范围：营销独立 ES 业务搜索未接入本次 RAG，保留其实现；不建设 KI-13 行业语料，不改访谈状态机。LR/KI 中与本轮新授权冲突的旧 HTTP/A2A/兼容假设由本规划明确替代，当前 apply 同步维护清单，修复状态与已验证实施提交关联，不将授权或源码当作运行完成。
