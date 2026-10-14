## 1. 基线盘点与实施前置检查

已结束规划修订并进入授权 apply，执行源码、初始化、限定配置与开发运行验证，维护 docs/LR/KI 并在验证后中文提交推送。保留49个稳定编号，不增删/复用编号；旧验证仅证明旧要求，新增要求不得自动勾选。此前重开原[x]：1.5、3.2、3.3、3.4、4.6、7.5、8.7、8.9，使基线为24/49；仅按当前完整证据继续勾选，不以源码、授权或历史测试代替新验收。

- [x] 1.1 重新读取 LR/KI、主规格及设计，确认旧 split-rag-by-domain 不实施、不覆盖历史；当前已读取并同步维护 LR/KI，未改旧 change 的历史工件。
- [ ] 1.2 盘点旧 REST/RPC/工具/技能/审计、真实 ES/Milvus schema/PK/模型维度/身份/hash/水位。旧后端已备份后启动：ES两个旧索引均0且mapping空，Milvus REST与精确catalog仅default库、0集合/分区/实体；不存在旧PK/model/schema可补造。6.7已全量核对无旧Agent索引RPC调用方并清理；新模型维度/投影身份和应用水位仍需实测，不据模板完成。
- [ ] 1.3 按最小业务基线核对 ADMIN唯一管理员、BROKER普通成员、MANAGER不默认准入；ADMIN全未删除房源含非ON_SALE，BROKER仅ON_SALE；偏好包括ADMIN绝对self-only，未知Supervisor语料隔离，不再要求资源grant规范/授权种子。
- [x] 1.4 已读取operations-inventory-safe/configuration-validation/Nacos恢复证据，核对DEFAULT_GROUP原BK-AGENT16/public21/acceptance5、现BK17/public21/5，旧空namespace实际public；七selected十一域key扩展后认证发布/逐hash读回一致。实际端口/旧imageID/digest/原volume与受限archive checksum已记录，无mount原Nacos未重建；隔离internal实例12原config恢复匹配。旧listing/memory/agent imageID本地缺失明确非可重建rollback；应用生效另验，不全目录覆盖。
- [ ] 1.5 重验所有HTTP真实transport与新双模式配置/目标：仅mode/allowed-hosts/internal-networks逐host map；项目网172.24.0.0/16，host.docker.internal容器192.168.65.254/Windows10.117.102.54分别核对，公司公开HTTPS host走公开分支。旧一律禁内网验证不证明新要求完成。

## 2. 独立模块与纯契约

- [x] 2.1 创建rag-service Java17可执行模块并纳入reactor，不建立common-milvus或多实现profile。
- [x] 2.2 common增加版本化search/submit/getStatus/inspect/provision/replay纯RPC/DTO，定义状态/错误，不泄漏服务JAR或后端客户端。
- [x] 2.3 v1数量/模式/策略/类型化字段校验及scoreKind/scoringVersion/degraded元数据，覆盖非法关系/未知字段/脚本。
- [x] 2.4 受管空间注册/管理入口，禁止跨空间共享物理目标、任意寻址或search自动初始化。

## 3. 可信身份与空间授权

- [x] 3.1 按服务独立密钥的请求绑定签名envelope，覆盖audience/operation/hash/expiry/rotation，凭据只来自环境/秘密管理，不进模型/日志/快照。
- [ ] 3.2 复用auth最小signed principal并重验当前账号/角色事实：停用/删除/离职/角色失效在全部相关REST/RPC/A2A/工具/管理/异步续接入口禁操作，未过期token不豁免，MANAGER不默认ADMIN；补伪造及入口/最终输出失效反例。旧局部认证测试非新全入口完成证据。
- [ ] 3.3 改房源scope为显式businessWide及activeOnly语义：ADMIN未删除全读，BROKER仅ON_SALE，RAG独立按当前signed role核对；移除资源grant必需/空集即全读假设，不用资源通配，不扩张授权DSL；保留ownerDomain/actor/space/request/policy/expiry绑定。
- [ ] 3.4 重验两路强制owner/未删除/active条件等价；个人偏好读写及用户管理/回填响应绝对self-only（ADMIN亦不得跨用户），跨域/伪scope/后台写身份不能获得用户搜索/管理权。
- [ ] 3.5 接通A2A专用可验auth信标和真实SDK受支持transport（必要最小升级/修补已授权），覆盖同步/流式/任务查询取消/异步/审批续接；专用上下文不进模型metadata/工具参数/RunnableConfig/checkpoint，进程内租约过期/重启须原owner重新认证，不以userId换发。

## 4. 迁移检索执行与出站安全

- [ ] 4.1 将现有 SpringAiMilvusDocumentStore、实际 embedding 接入及必需模型/连接/schema 配置迁入 RAG，保持原文档 metadata/逻辑 ID 映射；不重写裸 SDK 或 HTTP embedding 客户端。
- [ ] 4.2 调整按空间 schema 身份缓存的 VectorStore 实例，校验 database/collection/field/model/维度不混用；验证初始化、旧文档读/更新/删除及维度错误。
- [x] 4.3 从房源迁移 ES 索引/关键词召回执行到 RAG，使用统一领域投影、显式 mapping 和类型化过滤，去除房源 Mapper/RPC 回调及 ES→SQL 召回 fallback。
- [x] 4.4 实现两路召回、文档去重、版本化 RRF/同分稳定顺序与有界候选池；增加异量纲分数、重复候选、单路开关及数量不足测试。
- [x] 4.5 迁移现有 BGE native/DashScope 重排协议并校验候选完整性、下标/分数，接线明确评分 profile/logit或bounded转换，覆盖 scoreKind 与关闭重排语义。追加外部 Reranker-BGE-V2-M3 治理协议适配（governed，/api/v1/rerank）：请求 model/query/passages + raw_scores + truncate=false，每次响应钉扎 alias/revision/scoring_version/artifact_digest 与 score_semantics，漂移即 SCORING_PROFILE_MISMATCH、意外截断即 RERANK_PROTOCOL_ERROR、400/413 映射不可重试 INVALID_ARGUMENT；三空间登记实测 LOGIT profile 锚点，endpoint/CIDR/env 接线完成，协议与模板单测通过（rag-service 288 项）。真实容器联调与默认重排能力验收未执行，见 8.2。
- [ ] 4.6 改真实 HTTP transport 为 PUBLIC_ONLY 默认/MANAGED_INTERNAL 显式；精确 host allowlist+该 host 对应 RFC1918/ULA CIDR，每连接校验全部原始 A/AAAA 并固定地址/TLS；允许明确受管 Docker 单标签/host.docker.internal，禁 localhost/环回/链接本地/映射/组播/保留、通配/0/0、代理/自动重定向/任意 URL 覆盖。补 host 跨网、缺配置、DNS 混答/换址/重连/重试/刷新及两部署形态反例。
- [x] 4.7 实现 STRICT/ALLOW_DEGRADED、阶段错误、整体 deadline、有界重试/并发/文本预算；覆盖一路/两路/重排失败与安全拒绝不可降级、不把失败当 NO_MATCH。

## 5. 领域事务事件与 RAG 应用账本

- [x] 5.1 房源revision/outbox覆盖活跃创建更新删除状态资产等投影变更，提交后派发、回滚零事件；新角色scope回归另在3.3验收，不依旧grant基线。
- [x] 5.2 记忆偏好/约束revision/outbox覆盖upsert/衰减/阈值/删除/停用重激活，无歧义稳定身份。
- [x] 5.3 版本化bk_rag控制库及领域初始化，明确目标库/版本校验/敏感payload最小权限保留期；接入三份20261012 SQL，MySQL8.4.11新库/已有种子升级/重复执行实测。限定补齐agent三orchestration表和run_control必要列，不全仓重放历史SQL。v20261013.1 六步现存升级及重复实测通过；独立空新卷九步首启、两次fresh重跑/一次upgrade通过，SQL/check LF hash及history校验。runtime仅两控制表SELECT/INSERT/UPDATE，跨bk_auth查询1142；现存17约束/3共享记忆/2用户/3workflow/46checkpoint不变，payload/tombstone保留规则不清数据。
- [x] 5.4 持久化确认/getStatus及revision/hash幂等冲突过期/tombstone，超时按同eventId核对。
- [x] 5.5 同文档串行worker、双路状态/重试/告警/授权replay及实际hash核对补偿；此worker职责不得被旧signed listing legacy reconcile清理误删。
- [x] 5.6 融合/重排前按最新账本剔除旧/部分/删除，账本故障失败关闭，迟到旧向量只漏检不暴露旧正文。

## 6. 领域消费、Bootstrap 与兼容包装

- [x] 6.1 房源facade请求RAG、批量最新真源检查及旧match/context，至多一次有界扩池；新ADMIN/BROKER规则待3.3/3.4回归，不据旧grant测试宣称完成。
- [ ] 6.2 searchListingsByKeyword领域默认混合工具/description/技能，真实房源Agent凭3.5专用A2A认证调用；SQL详情与营销独立搜索不变。
- [ ] 6.3 记忆消费/真源有效性/领域最终排序，验证真实rerank profile及scoringVersion注册，保持统一asOf/两池去重/累计预算/metadata白名单；不强制示例权重或新评分DSL。
- [x] 6.4 bootstrap/YAML唯一迁memory，开关/仅补缺/人工及停用不覆盖，所有更新事件化。
- [x] 6.5 milvusKnowledgeSearch纯代理，保留能力/recordMilvus/审计/知识开关，不允许模型物理寻址。
- [x] 6.6 六组真实旧REST方法路径成功字段纯代理兼容及旧数量20+20/最终10/工具topK，维度和别名检查；新增全入口身份/个人偏好要求另待3.2/3.4验收。
- [x] 6.7 全量源码搜索核对：旧syncListingKnowledge/deleteListingKnowledge生产声明/实现/调用零命中，仅反射否定测试；无消费者signed listing legacy reconcile/证明DTO/配置/错误码/测试清除，provider遗留死注入同步删除。不建旧兼容路径；其他AgentRpcService四方法、正常index/backfill/outbox和worker实际双路hash补偿保留。当前统一编译已通过，完整回归另由8.7验收。
- [x] 6.8 确定只读失败/权限/参数/未知分离，historyDTO/checkpoint只读、已完成不重发；如新增transition按KI-2逐点单测。

## 7. 配置、部署与能力诊断

- [ ] 7.1 新RAG应用/Nacos配置和三项HTTP策略，实际namespace/group/dataID/角色/密钥/评分生效；config-init接Nacos管理认证，版本初始化与首启链/现存升级一致，敏感值不打印；无持久mount先受管导出，不盲重建。
- [x] 7.2 来源执行源码/bean/初始化/依赖已实质迁出（历史ee4c9b9核验），Compose拆后端归属，chat/营销保留；不代替7.1/7.3/8.3真实生效部署。
- [x] 7.3 仓库Dockerfile支持MAVEN_MODULES／SERVICE_ARTIFACT，Compose独立bk-anent-rag:dev／rag-service构建及JAR选择、HTTP9018、应用Dubbo20898和Memory20891齐备；config-q及指令／shell语法通过。受限release七个不可变source/JAR绑定镜像实际部署，RAG双协议／Memory RPC已注册，外部reranker未改。标准Dockerfile全Maven rebuild未执行，标准双网络实际注册地址另须7.1／8.3核对；不沿用stage.20到标准实例，不以本项代替8.2默认能力。安全证据task73-repository-delivery-summary-safe.json及dns-protocol-health-discovery-safe.json。
- [x] 7.4 基础readiness/每空间能力诊断、schema/维度/profile及安全gate，消费者不以RAG后端全局硬依赖；新双模式真实验收另待4.6/8.2。
- [ ] 7.5 重验受影响启动/诊断/版本初始化链：Compose首启三RAG SQL及agent当前结构齐备，已有数据升级+重复执行不丢17约束/3共享记忆/2auth用户/3workflow/46checkpoint；记录镜像/config/版本/事件水位，默认混合真实验收失败保留非零。旧脚本/清单验证不证明新增初始化验收。

## 8. 回填、切流、清理与验证

- [x] 8.1 受权有界backfill（默认100/最大500单页）、锁内完整快照/outbox及持久删除身份、更新删除交错测试；未知supervisor未激活，游标终止不等于两路APPLIED。真实数据/权限水位待1.2/8.2；新用户self-only边界待3.4。
- [ ] 8.2 在授权且双模式地址合规的真实目标验独立索引/集合/身份映射/两路等价/更新删除partial补偿和默认重排；缺目标/协议时阻塞，不用stub/单路无重排代替。
- [ ] 8.3 无生产真实业务采用一个有界开发窗口：备份配置/镜像/种子历史/事件水位，暂停受影响写入、核对实例/在途并追平；版本初始化和新身份/地址/数据/默认能力gate全过才恢复，不要求虚构旧RPC消费者兼容发布。
- [ ] 8.4 RAG停机结构化功能/无知识Supervisor可用、显式降级、真实REST/技能、ADMIN/BROKER/无效用户/跨偏好反例及历史审计恢复。
- [x] 8.5 当前c338b7e／886构建版本全仓生产／测试Java及相关配置、文件名只读复核：旧执行底座、collector/retriever、signed legacy证明链零命中，旧两个RPC仅反射否定测试；agent/listing/memory为RPC纯包装，ES／SpringAI Milvus／embedding／重排集中RAG。六组REST／工具／知识门控审计、其他AgentRpc四方法、正常index/backfill/outbox和worker双路revision/hash补偿均保留，营销独立ES与通用SpringAI／HttpClient不误删。七新镜像已实启；此项闭合纯清理，不代替8.3切流／水位或8.2默认能力。
- [x] 8.6 已核查来源本次执行依赖/配置/源码及重复bootstrap清理，其他业务依赖不误删；新SDK/配置增量需8.7重新验证。
- [x] 8.7 A2A拒绝ID转换／官方Starter HTTP及ES隐式object严格兼容／创建读回新增输入冻结后，22模块串行clean test及package各948项／142 XML，0失败／错误／跳过，七外部类显式排除。14Boot、common及十消费者common-a2a嵌套精确一致、四接口七throws、九官方未改class／两源重建／license／后端隔离／唯一共享运行库／Java17 probe证明通过；1219输入LF指纹3c39145488241d3254aa7e5ad1f19fa8d2eec8d3b2c22a58f2a3227205901004，证明D:/project/rag-schema-a2a-artifact-verification-20261014.json，SHA465dc44a7c739ce462e42639be31753445098c74ba206d6508fc30657e447501。KI-106／107、LR-51／52已维护，真实发布／初始化／默认能力另验；918证明仅对应d9b63e6历史制品，不借用旧计数。以下v2.4.17官方DescribeCollection现代100／兼容UnexpectedError同一ServerException精确schema-only分类及回归冻结；Milvus16／ES8定向通过，22模块串行clean test及package各918项／142 XML，0失败／错误／跳过，七外部类显式排除。14Boot／common一致／四接口七throws／九官方未改class／两源重建／license／后端隔离／十共享运行库／Java17 probe证明通过；1219输入LF指纹668860a838ef0da892211e0a13b5f8a9d37aa04e497e81857a29ad206092d2c1，新证明D:/project/rag-milvus-modern-artifact-verification-20261014.json。LR-50／KI-104已维护；新镜像及物理缺失／初始化／默认能力仍须实测，不以此项补勾运行任务。以下916项仅对应1ee06f2冻结已部署历史版本：当前RAG异常throws精确注册、ES reactor所有权、Milvus schema类型分类及Listing结构化HTTP错误增量冻结后，22模块串行clean test及package各916项／142 XML，0失败／错误／跳过，七外部类仍显式排除。14Boot嵌套common.jar逐字节一致及javap四接口七方法throws、九官方未改class／两源精确重建／license／后端隔离／十共享运行库／Java17 probe均过；1219输入LF指纹9d4161fae609fae71be327d7e83c4c4ba1a8eac8ed1753063d8f953876220457，新证明D:/project/rag-contract-reactor-artifact-verification-20261014.json。LR-48／49／50及KI-103／104／105维护，实际新镜像与空间能力另验。以下896项仅对应accf97f冻结历史版本：Listing三权威桥Qualifier与INSPECT有界执行增量冻结后，22模块串行clean test及package各896项／142 XML，0失败／错误／跳过；七外部联调类仍显式排除。14Boot JAR、九官方未改类、两个官方源精确重建、license／后端隔离、十共享运行库及Java17当前probe编译全过。1218源输入LF指纹22f9fe2a579d98f15937ca31b207cb909354348eb2c84f4852f228c163fde736；新证明D:/project/rag-inspect-wiring-artifact-verification-20261014.json。LR-46／47和KI-101／102维护，实际部署／默认能力仍另验。以下886项仅对应c338b7e冻结历史版本：未选DashScope模型、分类型DNS及14个纯Dubbo消费者protocol修复冻结后，22模块串行clean test和package各886项／142份Surefire XML，0失败／错误／跳过；七外部联调类仍显式排除。14 Boot JAR、九官方未改类、两个源精确重建、license／后端隔离、十共享运行库及Java17私有probe编译全部通过。1218源输入LF指纹4356c418965cfe851cdbdc118ae672ec7416b62beed20b4a9f6e7c4ac98a0cd5；新证明D:/project/rag-dns-protocol-artifact-verification-20261014.json。实际部署／默认检索另验，不能以本项补勾运行任务。以下855项证明仅对应已提交7bd7a52冻结版本：真实部署暴露共享Dubbo缺运行依赖、裸直连探针序列化不匹配、Agent额外自动配置及营销同名Bean，定点修复后22模块串行clean test及package均BUILD SUCCESS，各855项测试、0失败/错误/跳过；以140份Surefire XML计数，不重复累加控制台汇总。14个Boot JAR、唯一SDK补丁、两官方源精确重建、其余9个官方类逐字节一致及license、后端依赖隔离、Java17私有probe编译已验证，十个实际A2A消费者制品均具唯一OkHttp4.12/Okio-jvm3.6/Kotlin运行库。1216源输入指纹977fd9b2ba50968fcd7dfcbd626502ab15c9cecc51412c7f4d610ea9376b188e；日志及七项显式外部测试排除详见本地验收记录。此前4e5307…／848项及失败保留，实际部署/模型/五操作验收不在此项内，后续执行源码改动必须重新构建。
- [ ] 8.8 演练按版本/config/事件水位恢复新兼容版本，若退旧先重放差异并符合最新双模式安全；保留所有索引/集合/卷/种子历史，不down-v/prune/drop或公开服务。
- [ ] 8.9 后续已验证实施提交维护新增/替代LR/KI及验收记录，依主会话授权中文commit/push；当前apply维护docs/LR/KI和已验证实施提交，旧[x]已按新要求重开，所有实施门禁真实完成才归档。
