# 门店经营卡点复盘设计

## Context

当前 business-service 运行在 9010，公开控制器前缀为 /business，使用 bk_business 和 business-service.yaml。Gateway 将 /business/** 路由给 business-service。该服务持有现有 trade/trade-agent，其 A2A Memory 身份固定为 business-service / trade / trade-agent；required Memory Hook、业务技能、拦截器和 trade 历史均已在此服务中使用。上述服务和配置本身不承载门店复盘新业务。

仓库根 Maven POM 显式列出服务模块；Compose 使用服务 artifact 和每服务私钥挂载。Nacos 发布脚本、启动预检、数据库初始化 manifest、MySQL MCP 默认库列表和 ServiceIdentityKeygen 服务名单也包含显式清单，新增微服务必须同步更新，不会因新增目录自动接入。当前可用服务端口扫描中 9015 尚未占用；实施会话开始前仍需复核并行 change 的预留。

Agent 的版本化 capability 配置当前只映射本地 Milvus 工具，没有 trade Agent 映射；Auth 侧尚未登记本 change 的 Agent 调用和门店操作权限。签名 businessIdentities 当前只接受 BROKER，生产 STORE 范围必须由 Auth 注册并写入签名上下文，不能由新服务推断。

## Goals / Non-Goals

**Goals：** 独立部署和回退门店经营复盘能力；保存有来源的事项、证据、假设、计划、动作、回执和观察；隔离现有 trade 服务及 Memory 历史；明确新服务的 Auth、Memory、Gateway、数据库与配置登记边界；让多个服务拆分会话可以并行开发和由协调会话安全合并。

**Non-Goals：** 不改写或移动 business-service；不迁移、复制或双写 bk_business 中的 KPI/交易数据；不修改 trade/trade-agent 的技能、权限映射、私域历史或现有配置；不修改 Auth 通用协议与 Auth 全局目录；不新建 Memory RAG 投影；不替代房源、客户、访谈、知识、合同或结算数据源；不自动发送消息、调价、付款或宣称业绩因果增长。

## Decisions

### 1. 新建独立的 business-review-service

新增 Maven 模块 business-review-service，建议端口 9015，独立 Nacos Data ID business-review-service.yaml、数据库 bk_business_review、服务身份和 Gateway 路径 /business-review/**。公开领域 API 统一使用 /business-review/reviews。新服务以自己的 Spring 应用名、Dubbo 应用名、A2A audience 和容器服务名运行。

旧 /business/** 继续只指向 business-service。禁止将新代码加入 business-service、修改它的 application.yml 或 business-service.yaml、增加其 API、改写其数据库映射、路由或 trade Agent。新复盘事项不迁移到旧库；两服务不共享可变实体或 Java DTO。本地 API DTO 放在 business-review-service，公共 ApiResponse 可复用。

此边界使门店复盘可独立验收、发布和回退，也让另一个并行拆分拥有独立服务名、数据库和配置块。与把复盘功能塞进现有服务相比，新增服务增加一份启动、路由和密钥登记，但减少旧服务回归风险和并行拆分的所有权冲突。

### 2. 经营域独占复盘状态和来源快照

新库保存 ReviewMatter、EvidenceSnapshot、DiagnosisRevision、ActionPlanRevision、ReviewAction、ActionReceipt 和 OutcomeObservation。Memory、Supervisor 和模型只能引用业务 ID、版本和许可展示的摘要，不能充当可推进业务状态的真源。

带看、跟进、议价、维护数据先由固定版本 fixture 输入；房源、知识通过 domain-parallel-v1 HTTP 只读契约消费。逐字稿继续归访谈域。consumer wire DTO、mapper 和 fixture 均位于 business-review-service，不依赖并行会话新增的 common Java 类型，不读其它域数据库。

每条来源保留来源系统、来源 ID、revision、观察时间、事件时间、dataMode、datasetId 和获准事实字段。未知来源保持 UNKNOWN；全 REAL 才能派生 REAL；混合来源为 MIXED。模拟记录不能通过改标记转为真实历史。

### 3. 分离事实、假设、动作和结果

诊断分开保存已知事实及出处、卡点假设、待核实问题、知识版本和建议动作。数据不足先返回缺口；模型不能编造客户反馈、带看、价格、成交或因果事实。知识或房源版本撤回/变化后，旧计划需复核，不再展示失效正文。

计划绑定 diagnosisRevision、evidenceVersions、knowledgeRevisionRefs 和规范化参数 hash。确认要求当前 actor/store 权限和 expectedPlanVersion；事务创建动作及幂等回执。同 requestId、同 hash 重试返回原回执；同号异内容冲突；旧确认不能批准新计划。

动作状态为 PROPOSED、CONFIRMED、IN_PROGRESS、DONE、CANCELLED。人工完成标记 USER_REPORTED；仅收到可核验来源回执时才标记 SOURCE_CONFIRMED。动作完成不改变房源、客户或成交真源。

### 4. 指标保留可解释口径且不冒充增长

指标固定定义版本、窗口、Asia/Shanghai 时区、样本集合、聚合单位、分子、分母和观察时间。分母为零、事实缺失或来源不可用均返回 UNKNOWN 和原因。资料整理耗时、重复输入和闭环时间属于效率观察；成交阶段和周期属于经营结果观察。模拟验收、模型自评、工具受理和 USER_REPORTED 完成不得发布成生产 MemoryExperienceOutcome，也不能说明真实业绩或因果增长。

### 5. 使用最新稳定 Auth 能力映射与逐方法门禁

Agent 目录只使用稳定 CapabilityId.a2a(business-review-agent) 映射到一个精确操作 agent.workflow.invoke.business-review。不得按 domain、skill、模型参数或 chat.use 推导授权。此映射只批准调用新 Agent，不替代门店复盘 API 的资源和操作授权。

新服务 API 逐项检查以下 Auth 操作，并另外核验事项真实 store owner 属于已签名 STORE 范围：

- business.review.create.store
- business.review.read.store
- business.review.plan.write.store
- business.review.plan.confirm.store
- business.review.action.feedback.store
- business.review.observation.read.store

Auth/协调交接包括登记 agent.workflow.invoke.business-review、上述六个操作、业务角色授予，以及 Auth 对用户与 STORE 关系的验证和签名。当前签名身份类型只支持 BROKER，因此生产 STORE 权限登记完成前，新服务受保护入口保持失败关闭。不能从 userId、brokerId、employeeId、请求 storeId 或 Agent 摘要推断门店范围。

A2A 使用当前 D9 请求级 proof。签名上下文绑定 caller、audience、actor 和允许操作；请求 proof 绑定真实 JSON-RPC body、run/task、requestId、nonce 和期限。新服务在持久任务受理前本地验签，再由领域服务核验精确操作和门店资源，不为每个 Agent 内部工具调用 Auth。REST 入口使用自己的 Gateway route audience 并在领域层复核操作与资源范围。

领域 HTTP 契约沿用 ApiResponse<T>。请求中的 storeId 仅是已签名门店范围内的选择器/一致性断言；cursor 绑定 actor 和 store；缺失或越权范围拒绝而不是返回空列表。

| 方法与路径 | 操作 | 行为 |
| --- | --- | --- |
| POST /business-review/reviews | business.review.create.store | 根据 scenario、window、datasetId 创建事项，返回证据和初始诊断；不接受自授 owner/employee。 |
| GET /business-review/reviews?cursor&limit&storeId | business.review.read.store | 返回签名门店范围内事项和不透明 nextCursor。 |
| GET /business-review/reviews/{reviewId} | business.review.read.store | 定位事项后检查真实 store owner，通过前不返回正文。 |
| GET /business-review/reviews/{reviewId}/plans | business.review.read.store | 返回计划版本和依赖状态。 |
| POST /business-review/reviews/{reviewId}/plans | business.review.plan.write.store | 按 expectedDiagnosisRevision 写入不可变计划并绑定依赖版本和参数 hash。 |
| POST /business-review/reviews/{reviewId}/plans/confirm | business.review.plan.confirm.store | 按 requestId、expectedPlanVersion 幂等确认，过期版本拒绝。 |
| GET /business-review/reviews/{reviewId}/actions | business.review.read.store | 返回动作及执行回执。 |
| POST /business-review/reviews/{reviewId}/actions/{actionId}/feedback | business.review.action.feedback.store | 按 requestId、expectedActionVersion 幂等记录反馈并表达来源等级。 |
| GET /business-review/reviews/{reviewId}/observations | business.review.observation.read.store | 返回含口径、窗口和来源模式的指标；缺数返回 UNKNOWN。 |

新增写操作不发布为 MCP 写工具。Agent skill 可基于允许证据生成不落库草稿；事项创建、计划确认和执行反馈须由用户明确触发领域 API。

### 6. 接入隔离的必需 Memory 身份

新 Agent 通过 A2aMemoryHookFactory 使用固定 caller/domain/agent：business-review-service / business-review / business-review-agent，并以独立 Ed25519 服务身份签名。所有官方 Agent 必需的 Memory 读取、授权核验和归档没有关闭或绕过开关；压缩是唯一可选项，默认关闭。

Memory Nacos 受信调用者必须增加 business-review-service 专属条目：登记 business-review-service-current 公钥、开启 service-delivery，并将 business-review-agent 精确限制在 business-review domain。密钥由 ServiceIdentityKeygen 新服务名单生成；公钥进入生成的 public-keys.env 并由 Compose 提供给 Memory，不手写私钥或将公钥伪装成密钥放进 .env.example。

调用顺序要求先完成当前主体的 Memory 读取和授权核验，再调用模型或业务工具；Memory 服务不可用、身份不匹配或 agent/domain 未登记时，在任何副作用前拒绝 Agent 轮次。归档只表示本轮经过授权的上下文，不证明经营事实；MySQL 新服务记录始终是复盘、动作及效果的唯一业务真源。新服务不共享 business-service 的 trade-agent 私域，也不导入旧历史。

本 change 不为 business-review-agent 增加 Memory RAG domain projection、RAG 空间或 experience outcome publisher。必需私域 Memory 生命周期不依赖新增 RAG 投影；若未来需要跨轮次 RAG 或经验学习，另行定义 owner、来源和授权 change。保留旧 BUSINESS_MEMORY_V2_COMPRESSION_ENABLED 与 BUSINESS_MEMORY_EXPERIENCE_OUTCOME_PUBLISHER，新增服务的配置使用独立名称，不移除或重解释旧键。

### 7. 配置文件按服务身份同步，按共享责任合并

本服务新增与共享文件的差异如下。共享表格必须只做本服务的追加项，不全文件重排；Memory/Auth/密钥注册由协调会话将此 change 的精确片段与其它拆分合并。

| 文件或配置面 | 本 change 的新增项 | 并行责任 |
| --- | --- | --- |
| 根 pom.xml | 只追加 business-review-service Maven module。通用 Dockerfile 默认打包全模块，实施时确认新 jar 可进入镜像；只有发现固定模块清单才改 Dockerfile。 | 根模块列表为共享文件，协调合并所有新增服务行。 |
| business-review-service/application.yml | 新应用名、端口 9015、独立 A2A audience 与 service identity key ID/private-key path；Memory 压缩键默认为 false。 | 只归本服务。 |
| nacos/business-review-service.yaml | 新库连接、业务服务配置、A2A card、Memory 可选压缩及 readiness 配置；不复用 business-service.yaml。 | 只归本服务；Nacos 发布白名单另按行追加。 |
| docker-compose.yml | 独立服务块、业务 jar 名、内部 A2A URL http://business-review-service:9015、私钥只读挂载和数据库连接；按实际依赖设置 health/readiness，不因为旧服务依赖 Redis 就默认引入 Redis。 | 只增加本服务块；容器名和端口不得占用其它拆分。 |
| .env.example 与本机 .env | 模板增加 MYSQL_DB_BUSINESS_REVIEW=bk_business_review 和 BUSINESS_REVIEW_MEMORY_V2_COMPRESSION_ENABLED=false。若本机忽略的 .env 存在，只补缺项，不覆盖已有值或凭据、不提交 .env。 | 环境模板为共享文件；合并时保留别的域键。 |
| nacos/mysql-common.yaml | 增加 business-review 独立数据库名和 JDBC 别名 MYSQL_DB_BUSINESS_REVIEW。 | 共享数据库映射，协调合并。 |
| nacos/gateway.yaml | 新增 /business-review/** 到 business-review-service 的路由和该 audience 的授权关联；保留 /business/** 到 business-service。 | 共享路由块，协调合并。 |
| nacos/agent-service.yaml | 新增稳定 capability 条目 a2a:business-review-agent 到 agent.workflow.invoke.business-review。 | 共享权限映射，Auth 登记完成前保持不可见/不可调用。 |
| nacos/memory-service.yaml | 新增 business-review-service caller、公钥 key ID、service-delivery 和精确 domain-agent allowlist；不发布经验结果。 | Memory 全局可信调用者清单由协调会话合并。 |
| scripts/security/ServiceIdentityKeygen.java 与 public-keys.env | 服务名单增加 business-review-service；生成器为其输出独立 key ID 和公钥变量 BUSINESS_REVIEW_SERVICE_IDENTITY_PUBLIC_KEY。 | 全局服务登记表由协调会话合并；私钥只写入 .secrets。 |
| scripts/verification/RagRuntimeProbe.java | A2A audience 探针纳入 business-review-service；不将 business-review-agent 加进 Memory RAG domain-agent 投影表。 | 只扩展服务 audience 检查，不创建 RAG 空间。 |
| sql/mysql-init.sql 与 docker/mysql/init-manifest.tsv | 新建 bk_business_review；增加本域 schema、独立校验以及本服务持久 A2A task authorization 表的 migration/check/manifest 行。 | 已应用 manifest 项不改；序号、hash、database allowlist 由协调会话复核。 |
| scripts/deploy/run-init-manifest.sh | 固定允许集合新增 bk_business_review。 | 共享 allowlist，由协调会话合并。 |
| scripts/deploy/publish-nacos.py 与 init-environment.sh | 精确发布 ALLOWED 集合和必需 Nacos Data ID 加入 business-review-service.yaml。 | 显式 data ID；NACOS_CONFIG_DATA_IDS 默认仍空。 |
| .env.example、docker-compose.yml 的 MYSQL_MCP_DATABASES | 默认库列表保持排除 bk_business_review，避免新门店证据自动进入通用只读 MCP。 | 这是有意保留的最小权限配置，不作为漏配修正。 |
| rag-service.yaml 与 MEMORY_V2_DOMAIN_AGENTS | 不加 business-review RAG 空间或领域投影；业务新域仅接必需私域 Memory Hook。 | 维持现有 Memory/RAG 注册。 |

所有密钥按服务隔离；A2A_PUBLIC_BASE_URL 在 Compose 内固定为服务 DNS 地址，Nacos data ID 通过发布白名单显式选择。NACOS_CONFIG_DATA_IDS 默认空的保护行为保持不变。没有当前已部署的复盘配置项需要废止；本 change 撤销初版计划中的 a2a:trade-agent 新映射设想，但既有 trade 配置不变。新增或废除配置都必须在实施收尾时与上表逐项核对，不能遗留重复、指向旧域或无消费者键。

### 8. 并行拆分的唯一所有权与合并规则

本服务的身份元组为 business-review-service / bk_business_review / 9015 / /business-review/** / business-review-service.yaml / business-review-agent / business-review / business-review-service-current。实现前与其它活动 change 复核该元组无冲突；如冲突先由协调会话分配新值，再一起更新 app、Compose、Gateway、Nacos、密钥、manifest 和测试，不只改其中一份。

各会话在共同基线的独立 worktree、独立 Maven repo 和隔离测试数据库中实现。不得共用暂存区、端口、数据库、密钥或测试输出。共享配置仅追加各自独有的服务块和名称；不改别的服务配置，不在并行会话中发布共享 Nacos、迁移共用数据库、轮换密钥或重启对方服务。协调会话逐项合并 pom、Compose、Gateway、Nacos、.env.example、keygen、SQL manifest 与发布清单，并验证配置集合无遗漏或重复。

旧 business-service 的源文件、application.yml、Nacos 配置、数据库、默认 env 键、Compose 块、Gateway audience 和 trade-agent Memory 登记全部视为本 change 的只读基线。提交前执行路径级差异审查，确认没有对这些旧资产的修改。

### 9. 风险与取舍

- 新服务存在临时 Auth/Memory 登记缺口：测试使用精确 caller、permission 和 STORE 测试身份；生产缺 Auth 签名 STORE 范围或 Memory caller key 时拒绝业务副作用。
- 拆分可能与其它会话分配到相同端口或共享配置：实施前复核唯一身份元组；服务专属块单独拥有，根注册文件由协调会话合并。
- 配置添加容易漏掉脚本白名单或数据库清单：按第 7 节矩阵审查 Nacos 发布、启动预检、keygen、SQL manifest、Compose readiness 和 gateway audience。
- 原有经营分析依赖可能意外被移动：新服务只调用 domain-parallel-v1，不复制旧数据库、旧实体或旧服务源码；旧路由和 trade Agent 保持原样。
- Memory 可能被误当作事实记录：Memory 仅是获准上下文的读取/归档面，新库是真实复盘状态；模拟和人工反馈不发布为经验结果。
- 9015 当前未被占用但可能在实施前被其它 change 预留：开始编码前重新扫描所有活动 change 与容器端口映射。

## Migration Plan

1. 不迁移或重写 bk_business；新增 bk_business_review 和本服务的业务/A2A 授权持久表，只在隔离数据库演练对应 manifest。
2. 按并行唯一元组创建新 Maven 模块、服务模板、Nacos Data ID 与 Compose 块；同步 .env.example 的非敏感值，并在本地 .env 只补缺失项。
3. 交付 Gateway route、Agent capability、Auth permission/STORE 身份、Memory trusted caller/keygen 和初始化 runner 的精确注册差异；协调会话合并共享项后，按真实 key 和签名身份做失败关闭验收。
4. 先完成新服务本地 fixture、业务 API、持久化、Memory/Auth 拒绝行为，再显式选择新 Nacos Data ID 并部署；不以模拟联机结果声称生产服务已接通。
5. 回退时先从 Gateway 移除新路由并停止新服务，保留新库中的事项、确认、回执和观察；不删除库、不清除旧 trade 历史或改写业务状态。密钥退役按服务身份登记操作执行。

## Open Questions

没有未决的领域契约。9015 在规划核对时空闲，实施会话仍须复核并行 change 的端口预留；Auth 的 STORE 身份签名及全局权限登记和 Memory 全局调用者登记是明确的协调交接，不是可绕过的实现选项。
