## Purpose

本能力定义业务域和 Supervisor 消费独立 RAG 的边界，使领域继续掌握语料、权限、生命周期与最终业务排序，而检索执行集中在唯一服务。它同时保护工具身份、历史审计与实际旧接口的过渡兼容，并要求最终清理来源服务中的旧执行路径。

## ADDED Requirements

### Requirement: 域消费统一 RAG 而不保留后端执行链

房源域、记忆域及本次接入的 SubAgent SHALL 仅向 RAG 提交文档变更和检索请求，不直接执行关键词召回、向量召回、embedding、融合或通用语义重排。领域 SHALL 保留真源、语料装配、业务权限、生命周期及最终结果校验。房源 SQL 列表/详情接口保持领域职责；未接入此次 RAG 的营销独立业务搜索不作为本次混合召回来源，也不宣称已迁移。

#### Scenario: 房源 Agent 执行知识搜索
- **WHEN** 房源 Agent 调用保留名称的 searchListingsByKeyword 工具
- **THEN** 工具作为领域包装调用默认混合加重排的 RAG，技能正文及描述说明其升级语义，房源进程没有执行 ES 召回

#### Scenario: 后端故障不恢复旧实现
- **WHEN** RAG 不可用
- **THEN** 消费者返回明确检索不可用，不切回域内 ES/Milvus、旧 agent RAG 或本地 SQL 的混合召回替代实现；原有独立 SQL 列表操作仍可使用

### Requirement: 领域最终排序与语义重排分离

域 SHALL 能取得足量候选及评分元数据，并按本域规则决定最终结果；时间衰减和重要性不得被硬过滤冒充，也不得自动在 RAG 执行。域 SHALL 依据声明的 scoreKind/scoringVersion 使用相关度，缺失评分字段时采用显式领域规则或拒绝该评分策略，不混用原始 BM25/向量分数。最终输出前 SHALL 依据最新真源检查权限、有效性和请求条件；淘汰候选需要补取时 SHALL 使用有界的扩大候选请求或返回不足事实，不无限重试或提前裁成最终 topK。

#### Scenario: 旧但相关的记忆仍可入选
- **WHEN** 记忆域启用领域时间衰减且一条较旧记忆仍高度相关
- **THEN** 时间衰减影响最终顺序而非直接删除该候选，除非请求另有明确时间硬过滤

#### Scenario: 过期房源命中索引
- **WHEN** RAG 返回的房源在真源中已下架、删除或不再有权限
- **THEN** 房源域不返回该项或其详情，并在预算内补取或明确说明候选不足

### Requirement: Supervisor 知识工具保留治理和审计身份

Supervisor 的 milvusKnowledgeSearch SHALL 保留稳定本地能力身份、query 输入、会话 topK、知识开关、格式化内容以及工具/向量结果审计字段，底层仅调用获准知识空间的远程 RAG。生产执行入口 SHALL 尊重请求政策，不能固定开启知识检索或由模型指定物理集合。远程检索成功、无匹配和确定的不可用 SHALL 有可区分的工具结果；确定的只读失败 MUST NOT 伪装为权限拒绝或副作用结果未知。已完成工具结果及历史 checkpoint 的只读查询 SHALL 保持字段兼容，不恢复旧 runner 或重发已完成检索。

#### Scenario: 生产请求关闭知识检索
- **WHEN** 请求政策禁止知识检索且模型提出该工具
- **THEN** 治理层拒绝执行，RAG 调用次数为零，拒绝事实仍可审计

#### Scenario: 读取迁移前工具快照
- **WHEN** chat 或诊断查询读取含 milvusResults 的历史完成记录
- **THEN** 内容、分数及文档关联仍可反序列化展示，不需要旧存储客户端或旧 Graph 执行器

### Requirement: 实际旧 REST 入口有受控兼容期

系统 SHALL 在首个切流发布保留 GET /agent/vector-store/milvus/search 及 /agent/mcp/milvus/search、POST /agent/vector-store/milvus/collections/init 及 /agent/mcp/milvus/collections/init、POST /agent/rag/listings/index 及 /agent/rag/listings/query 的方法、路径、成功响应字段和既有权限入口。代理 SHALL 绑定可信身份并转换为新领域/RAG 请求，不继续执行旧后端逻辑。旧 collectionName SHALL 仅通过受控别名映射到获准逻辑空间；未注册/未获准值明确拒绝。旧初始化的维度输入 SHALL 与空间 schema 校验，不再忽略；初始化仅开放给获准管理身份，不因普通检索自动创建。接口退役 SHALL 在所有已知消费者迁移及至少一个兼容发布周期后单独执行并提供公告。

#### Scenario: 旧房源查询脚本继续使用
- **WHEN** 获准旧客户端调用 /agent/rag/listings/query
- **THEN** 代理调用新房源领域入口取得检索及最新详情，以原响应字段返回，没有 agent 本地 RAG 执行

#### Scenario: 旧数量参数保留最终返回语义
- **WHEN** 旧房源调用缺省两路数量和topK，或知识工具提供受支持topK
- **THEN** 兼容层使用旧房源基线20+20/最终10，或以工具topK设置最终候选池，并显式生成合法重排池，不直接套新50/100默认造成参数错误；非法显式数量关系被明确拒绝

#### Scenario: 旧集合初始化试图扩权
- **WHEN** 旧客户端提交任意集合名或与获准空间不一致的 dimension
- **THEN** 返回权限或 schema 错误，不自动创建新物理集合，不将受控兼容解释为保留旧任意寻址行为

### Requirement: 索引 RPC 迁移按消费者版本分阶段退役

首个切流发布 SHALL 将旧索引 RPC 转为纯代理兼容，仅在停写排空后依据房源域已确认真源生成授权完整投影事件，不继续执行 agent 的索引代码；无法证明原事务提交/删除或次序的调用 SHALL 返回 LEGACY_RECONCILIATION_REQUIRED，不宣称自动可靠兼容。升级后的房源写入口 SHALL 直接产生事件交付 RAG，不经过 agent 或反向文档读取环。旧 RPC 的删除 SHALL 等到所有消费者升级、在途旧调用清空且新事件水位追平后实施。首次切流发布 SHALL 同步清除来源服务的后端执行类/bean/依赖、旧索引触发、重复初始化及 RAG 本地 fallback，不能只关闭配置保留第二执行路径；后续清理发布只退役旧 RPC 签名/引用及残余纯包装。

#### Scenario: 切换后核对遗留索引调用
- **WHEN** 停写排空后收到旧 syncListingKnowledge 或 deleteListingKnowledge 遗留调用
- **THEN** 只有当前真源及持久版本能证明该操作时才生成并确认领域事件；当前仍存在的文档不得被旧删除意图删除，提交不明或回滚的调用明确要求核对，不自动重试为成功

#### Scenario: 最终清理发布
- **WHEN** 消费者已完成升级并通过迁移门禁
- **THEN** 已在首次切流删除的来源 RAG 实现不会恢复，旧索引 RPC 签名/引用被删除，允许保留的 REST/工具包装仅通过新契约执行
