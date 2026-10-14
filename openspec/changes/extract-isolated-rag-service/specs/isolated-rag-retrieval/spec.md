## Purpose

本能力使业务域、记忆域与获准 SubAgent 通过统一服务取得受权限约束的混合检索候选，以独立关键词索引和向量集合隔离不同知识空间，并明确策略参数、相关度解释和依赖失败行为，避免各域重复执行检索底座或依赖共享集合标签隔离。

## ADDED Requirements

### Requirement: 知识空间采用真实存储边界并由服务端寻址

系统 SHALL 为不同域及需要内容隔离的不同 SubAgent 知识空间分配不同关键词索引和向量集合。物理索引/集合的读写目标 SHALL 由服务端版本化空间注册信息决定，调用者 MUST NOT 提交物理目标或后端地址。集合分离 MUST NOT 被视为授权，空间内仍 SHALL 实施用户/资源范围限制；未注册空间 MUST NOT 自动创建。

#### Scenario: 同内容在两个独立空间中索引
- **WHEN** 房源空间和记忆空间包含相同关键词及相同逻辑 documentId
- **THEN** 它们使用不同物理索引/集合，检索、更新或删除一个空间不会触及另一个空间

#### Scenario: 调用者尝试直接选择物理集合
- **WHEN** 请求包含任意 collectionName、indexName、URL 或未授权 spaceId
- **THEN** 请求在访问检索后端前被明确拒绝，不回退到默认共享空间

### Requirement: 服务身份与最终用户授权先于召回

每次检索 SHALL 验证调用服务、当前有效用户、空间及操作交集；停用/删除/离职/角色失效用户在所有相关入口禁止操作，未过期token不豁免。仅ADMIN为管理员，BROKER为当前普通成员，MANAGER/未知角色不默认准入。房源ADMIN全读未删除项（含非ON_SALE），BROKER仅ON_SALE；scope SHALL 用显式business-wide与activeOnly语义并由RAG独立按当前role复核，不用空资源集/通配或强制资源grant规范。偏好读写和用户管理入口 SHALL 绝对self-only，ADMIN不得跨用户。用户身份 MUST NOT 从模型/userId/普通metadata/服务发现推断；后台写身份无用户读取/管理权。强制授权AND领域过滤 SHALL 等价施于双路，未获授权正文不得送重排或返回；认证故障失败关闭。

#### Scenario: ADMIN与普通成员房源基线
- **WHEN** 当前有效ADMIN或BROKER搜索房源
- **THEN** ADMIN可检索全部未删除房源，BROKER仅ON_SALE，RAG独立复核角色/activeOnly且领域最终检查，不要求全量资源枚举

#### Scenario: 管理员跨用户偏好或遗留角色
- **WHEN** ADMIN指定他人偏好owner，或MANAGER被默认当管理员，或用户状态/角色已失效
- **THEN** 操作拒绝，双路及重排不接触未授权正文，不凭管理角色或有效旧token豁免

#### Scenario: 搜索其他用户记忆
- **WHEN** 用户 A 的有效请求指定用户 B 的 owner 条件或试图省略 owner 限制
- **THEN** 系统拒绝扩权或追加不可覆盖的用户 A 范围，两路及重排均不读取用户 B 的文档

#### Scenario: 伪造调用方或认证依赖失效
- **WHEN** 请求只有伪造 gateway 标记、服务名称或 userId，或者身份校验服务不可用
- **THEN** 返回身份/授权不可用错误，后端调用次数为零，不将其报告为空结果

### Requirement: 默认混合召回和语义重排由 RAG 集中执行

未显式覆盖策略时，服务 SHALL 同时执行关键词与向量召回、融合、去重及通用语义重排。域 SHALL 只通过声明的策略契约影响执行，不自行调用或编排任何一路。调用者 SHALL 能明确关闭混合召回并选择受支持的单路，或关闭重排；关闭开关 MUST NOT 转交执行权给域。

#### Scenario: 默认房源或记忆查询
- **WHEN** 获准调用者只提交空间、查询和领域条件
- **THEN** RAG 执行两路召回及语义重排，并返回实际执行模式、策略版本和候选

#### Scenario: 显式关闭部分阶段
- **WHEN** 请求设置 hybrid=false、singleRecall=VECTOR、semanticRerank=false
- **THEN** 仅 RAG 执行向量召回，响应标明未使用关键词召回及重排，不调用域内检索底座

### Requirement: 参数和结构化过滤具有有界契约

服务 SHALL 发布契约版本、每空间字段 schema、受支持运算符、策略枚举、默认值及数量范围。v1 SHALL 支持 eq、in、gte、lte 及房源 region 的 contains_ci，字段类型与运算符必须匹配；所有条件以 AND 合成，原生查询表达式、任意脚本和未知字段 MUST 被拒绝。数量参数 SHALL 控制各路召回、重排候选池和最终返回池；无法满足数量关系或超过空间授权上限的请求 SHALL 明确报参数错误而非静默截断。字段必须在语料投影中真实存在，缺失时不得假装成功应用过滤。

#### Scenario: 偏好类别和时间硬过滤
- **WHEN** 偏好请求同时限制 category in [deployment] 与 lastObservedAt gte 指定 UTC 时间
- **THEN** 两路均只召回满足条件的文档，缺少该时间字段的文档被排除，过滤不被当作时间衰减排序

#### Scenario: 不受支持的策略
- **WHEN** 请求含任意原生过滤字符串、final_scoring 脚本、非法字段或 candidateLimit 大于候选池
- **THEN** 返回可定位字段的参数错误，不忽略输入，不执行部分检索

### Requirement: 融合去重与相关度解释明确

服务 SHALL 按空间内稳定文档身份合并候选，保留各路原始分数和来源，不直接将关键词分数与向量分数相加。v1 SHALL 提供版本化 RRF 融合及确定性同分顺序；语义重排的原始分数、经过明确评分配置生成的 relevanceScore 与 scoreKind SHALL 分别返回。关闭或故障绕过重排时 MUST 标明相关度来自融合/单路排序，MUST NOT 伪装为语义分数或相关概率。语料内检索文本 MUST 被当作数据，不具有更改授权或策略的执行权。

#### Scenario: 两路召回相同文档
- **WHEN** 同一文档由两路命中且原始分数量级不同
- **THEN** 返回一条候选，保留两个来源及分数，依据声明的融合/重排规则排序而非原始分数相加

#### Scenario: 重排分数转换
- **WHEN** 重排提供方返回通过已验证评分配置转换的分数
- **THEN** 响应包含原始分数、relevanceScore、scoreKind 和 scoringVersion，调用方可区分不同语义尺度

### Requirement: 足量候选保留领域排序所需字段

服务 SHALL 返回文档身份、内容、投影版本、相关度信息及空间声明的领域评分元数据。candidateLimit SHALL 表达供域排序的候选池，不等同于域最终 topK；域 SHALL 能在有界上限内扩大召回与返回池。服务 MUST NOT 自动应用记忆时间衰减、重要性或房源商业排序。无匹配返回空候选是成功，但依赖失败不得伪装成无匹配。

#### Scenario: 记忆域从候选中选择最终偏好
- **WHEN** 记忆域请求 50 条偏好候选，之后按相关度、lastObservedAt 和 confidence 选 5 条
- **THEN** RAG 在授权且有足量匹配时返回 50 条及上述元数据，不在域排序前擅自截成 5 条；未来 occurredAt/importance 等字段仅在真实空间 schema 注册后适用

### Requirement: 故障与有界降级可观察

默认 failurePolicy SHALL 为 STRICT，任一路或要求的重排失败时返回明确阶段错误。显式允许 ALLOW_DEGRADED 的请求 SHALL 能在一条召回路失败时使用存活路，在重排失败时使用融合顺序，并标明失败阶段、实际模式、scoreKind 与 degraded=true。两路均失败、身份/安全校验失败或存储隔离验证失败 SHALL 始终失败，不因降级选项放行。所有调用 SHALL 受超时、候选和并发上限约束。

#### Scenario: 一路故障
- **WHEN** 关键词后端不可用而向量后端正常
- **THEN** STRICT 请求明确失败；ALLOW_DEGRADED 请求返回向量候选及关键词失败标记，不在域侧调用旧 ES 或 SQL 召回兜底

#### Scenario: 非法重排响应
- **WHEN** 重排返回重复/越界候选下标、非有限分数或遗漏要求的候选
- **THEN** 系统将其归为重排协议失败，按显式 failurePolicy 处理，不静默返回空列表或错误内容
