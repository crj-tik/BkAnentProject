# 门店经营卡点复盘设计

## Context

见 proposal.md。当前 `BusinessController` 使用 `/business` 根路径，有条件启用的 `BusinessAuthorizationIngressFilter`，分析服务在方法内调用 `VerifiedDomainAuthorization.requireOperation`；授权开关默认关闭，且暂无复盘 API/模型。官方 Agent 保持 `business-service / trade / trade-agent`，`TradeOfficialA2aAgent` 通过 `A2aAuthenticationToolInterceptor`、Supervisor 上下文拦截器和必需 Memory Hook 执行；它的 `TradeTools` 是业务 Agent 内部工具，不是 Supervisor 的本地工具目录。`TradeMcpTools` 目前只有 KPI 查询，Business MCP 授权映射为空且未开放公开工具。

当前版本化授权目录只配置 `local:milvusKnowledgeSearch`，没有 `a2a:trade-agent` 映射；仓库也没有 `agent.workflow.invoke.trade` 的 Auth 权限登记。`AuthorizationContext.businessIdentities` 的校验目前只接受 `BROKER`，没有可信 STORE 身份或用户到门店映射。Business 已有 `business.*.all` 精确方法操作，但门店复盘所需的 store 操作码尚无登记。上述 Auth 代码/目录和身份映射归 Auth/协调方，不属于本业务拆分。

首期房源与知识按固定 `domain-parallel-v1` HTTP 契约消费，逐字稿仍归访谈域。已有 `implement-studio-v2` 尚有未完成任务；本 change 不复制其 work/command/文件/发布门面。

## Goals / Non-Goals

**Goals:** 把“带看后停滞”和“房源维护缺口”转为有来源、可确认、可跟进的经营事项；明确效率观察与业绩结果的证据层次；在 Business 边界内独立验收，且与 Auth、房源、知识、Studio 和 Agent 通用编排保持明确交接面。

**Non-Goals:** 不做新人培训、自动给员工打绩效分、自动联系客户、调价或付款；不合并房源/客户/知识服务，不在此实现上游业务系统；不修改 Auth 身份/权限框架或 Auth 全局目录，不改 Supervisor/Graph 通用授权与审批，不接 Studio 前端或 `implement-studio-v2` 未完成事项。

## Decisions

### 1. 保留 business-service，增加 review 业务包

新增 ReviewMatter、EvidenceSnapshot、DiagnosisRevision、ActionPlanRevision、ReviewAction、ActionReceipt、OutcomeObservation。业务状态及幂等回执归 `bk_business`，Supervisor/Memory 只引用业务 ID、版本和获准结果，不另存一份可推进业务的权威状态。

相比只扩充看板，这些对象让店长能处理问题并跟踪后续；相比建立完整客户/交易系统，事项只引用上游的客户、房源、带看及机会身份。客户需求、房源价格和成交状态仍由各自来源决定。

### 2. 模拟经营输入走可替换的数据端口

`StoreBusinessEvidencePort` 提供指定门店、时间窗口和 dataset 下的带看事件、跟进事件、议价状态及维护记录。首期实现固定版本 fixture provider，不从 employeeId、记录 ID 或模型文本随机推算业绩。每条记录包含 `sourceSystem/sourceId/revision/observedAt/eventTime/dataMode/datasetId`。房源读取由 `ListingBusinessReadPort`、知识由 `PublishedKnowledgeReadPort` 消费冻结的 `domain-parallel-v1` HTTP契约；本模块拥有 consumer wire DTO/mapper 及 HTTP fixture，不引用另两个 change 新增的 common 类型，不跨域读库或复制逐字稿。

本地证据快照仅保存获准的有界字段和来源定位。模拟与真实记录按 dataset 和来源标记分开，不能通过更新 dataMode 将旧模拟历史“转正”；任何模拟证据参与输出都继承为 SIMULATED 或 MIXED。真实 feed 的后续接入可替换端口实现，数据结构与本期状态机不改变。

来源不明为 UNKNOWN，含此类来源的派生结果也为 UNKNOWN；全部来源为 REAL 才输出 REAL，已知真实/模拟混合为 MIXED。来源模式与指标计算状态独立，仍逐项保留事实缺口及分母问题。

### 3. 把事实、假设、行动和结果拆开

诊断结果包含事实/出处、卡点假设、待核实问题、适用知识 revision、建议动作及其理由。来源不足时先返回缺口；低转化率不自动成为某个原因的证明。规则先计算客观数量和缺口，模型在有界获准材料上解释和建议；禁止模型创造带看、成交或价格事实。

知识域返回的已发布版本在装配和输出前复核，撤回或来源失效时停止显示相关正文并标记原建议待复核。知识查询不可用与合法无匹配分开表达；前者不能被伪装成没有案例。

### 4. 动作确认和执行反馈有独立回执

计划绑定 diagnosisRevision、evidenceVersions、knowledgeRevisionRefs 和 canonical argumentsHash。店长以当前权限确认 expectedPlanVersion 后，事务内创建动作与幂等回执；重复 requestId 同内容返回原结果，不同内容冲突，旧确认不能批准新计划。

动作状态为 `PROPOSED / CONFIRMED / IN_PROGRESS / DONE / CANCELLED`。DONE 需记录执行者、时间和证据级别；只有人工反馈时标记 USER_REPORTED，只有对应来源回执时才能标记 SOURCE_CONFIRMED。动作完成不自动更新房源/客户/成交状态。首期记录的是推进执行过程，外部发送、调价等需其他领域能力和独立后续 change。

### 5. 指标采用明确口径，模拟验收不承诺增长

指标定义版本固定时间区间、Asia/Shanghai 时区、聚合单位（事件/客户/机会）、样本集合、分子、分母及观察时间。例：带看后有效下一步率以同一观察窗口内的去重机会为单位；何谓“有效下一步”随定义版本存储，不从生成建议或 DONE 数量推断。分母为零或事实缺失返回 UNKNOWN 及原因，不当作零转化。

效率指标记录资料整理耗时、重复输入和动作闭环时间；成交推进指标记录来源确认的阶段变化与成交周期。前后对比只表达观察，不能自动声称因果增长。模拟数据和 USER_REPORTED 反馈不发送生产 MemoryExperienceOutcome；模拟验收仅覆盖业务流程，真实增长依赖后续实际业务观察。

### 6. 按稳定能力、精确操作和可信门店范围授权

Supervisor 目录把 `CapabilityId.a2a("trade-agent")` 映射到唯一的 `agent.workflow.invoke.trade` 操作码；不得按 Agent Card 的 domain、skill、模型参数或通用 `chat.use` 推导权限。当前映射与 Auth 权限登记均缺失：本 change 可交付 `agent-service` 受管配置中的这一条最小映射及登记清单，但只有 Auth/协调方把精确操作登记进当前签名授权集合后才可见、可调用；映射缺失或签名 context 不含该操作时隐藏并拒绝。目录授权 A2A 调用本身，不替代 Business 服务的细粒度操作和资源范围检查。

Business 复盘服务逐方法检查以下精确操作，另由 `TrustedStoreScopePort` 校验事项所属门店在当前已验签上下文范围内：`business.review.create.store`、`business.review.read.store`、`business.review.plan.write.store`、`business.review.plan.confirm.store`、`business.review.action.feedback.store`、`business.review.observation.read.store`。REST 请求经过现有 `/business` 签名 ingress，再在领域服务检查操作；A2A 由最新 D9 请求级协议绑定实际序列化 JSON-RPC body、caller/audience、actor、run/task、requestId、nonce 和期限，接收端在 TaskStore/Queue 前本地验签，领域服务随后检查精确 Business 操作与门店范围。两条入口都复用已验证的签名 context/freshness，不为每个内部工具调用 Auth。

STORE 范围是生产启用门槛而不是业务域推断逻辑。当前 `common/authz` 只接受 `BROKER`，本 change 不得把 userId、brokerId、employeeId、店名或请求 `storeId` 翻译成门店身份，也不得扩展 Auth 签名契约。Auth/协调方的明确交接是扩展签名 `businessIdentities` 身份类型以支持 `STORE`，并让 Auth 在签发 context 前核验用户对所选门店的关系；STORE 值进入现有 context/snapshot 的签名和版本失效链。首期每个签名 context 只携带一个已核验门店。Business 只定义可替换的 `TrustedStoreScopePort`：测试实现可提供精确 actor/store 授权夹具；生产实现只读取 Auth 已验签的 STORE 身份。生产适配或签名 STORE 身份缺失时，所有受保护查询和写入前置拒绝。一个请求的 `storeId` 仅可作为已签名 STORE 的选择器/一致性断言，不能扩大范围；多门店集合上下文扩展归 Auth 决策。

经营 Agent 保留 `trade-agent` 身份、`trade` Memory 私域、SkillRegistry 与现有认证/上下文拦截器。`TradeTools` 内部 callback 不是 Supervisor `local:*` capability，不另造本地 capability 映射；新增复盘 callback 只使用 store-scoped review 服务和 `business.review.read.store` 生成不落库的诊断/计划草稿，不复用现有 `business.*.all` KPI 查询作为门店证据。创建复盘、保存计划、确认计划及提交执行反馈只走用户明确触发的领域 API；skill 选择、Agent 调用许可或诊断文字均不构成写授权。确认请求绑定当前 actor/store、`requestId`、`expectedPlanVersion` 和服务端规范化参数 hash。新写操作不加入 `TradeMcpTools`，Business MCP 操作映射和公开能力维持空；未来若单独增加 MCP 只读能力，须另按稳定 `CapabilityId.mcp(server, tool)`、精确 Auth 操作和逐请求 proof 设计。

本 change 的领域 HTTP 契约固定如下，响应沿用 `ApiResponse<T>`；`storeId` 如出现在查询/创建参数中仅作选择器并必须匹配签名范围，分页 cursor 与 actor/store 绑定，缺省或越权范围均拒绝而非返回空集合：

| 方法与路径 | 授权操作 | 契约要点 |
|---|---|---|
| `POST /business/reviews` | `business.review.create.store` | `scenario/window/datasetId` 创建事项并返回初始证据与诊断版本；不接收可自授权限的 owner/employee 字段。 |
| `GET /business/reviews?cursor&limit&storeId` | `business.review.read.store` | 仅返回签名门店范围内事项及不透明 `nextCursor`。 |
| `GET /business/reviews/{reviewId}` | `business.review.read.store` | 先定位事项，再检查其真实 store owner，范围检查前不返回正文。 |
| `GET /business/reviews/{reviewId}/plans` | `business.review.read.store` | 返回该事项计划版本与当前依赖状态。 |
| `POST /business/reviews/{reviewId}/plans` | `business.review.plan.write.store` | 带 `expectedDiagnosisRevision` 建立不可变计划修订，绑定 diagnosis、evidence、knowledge revisions 和规范化参数 hash。 |
| `POST /business/reviews/{reviewId}/plans/confirm` | `business.review.plan.confirm.store` | 请求带 `requestId`、`expectedPlanVersion`；同号同 hash 返回原回执，不同 hash 冲突，旧版本拒绝。 |
| `GET /business/reviews/{reviewId}/actions` | `business.review.read.store` | 仅返回该事项下的动作及执行回执。 |
| `POST /business/reviews/{reviewId}/actions/{actionId}/feedback` | `business.review.action.feedback.store` | 带 `requestId`、`expectedActionVersion` 和来源/反馈；同号同 hash 返回原回执，不同 hash 冲突；人工反馈仍标记 `USER_REPORTED`。 |
| `GET /business/reviews/{reviewId}/observations` | `business.review.observation.read.store` | 返回带口径版本、窗口及来源模式的观察值；缺数据为 `UNKNOWN`。 |

以上操作需在 Auth 精确登记并由当前签名授权集合明确授予。现在仓库没有这些 `business.review.*.store` 登记，和 `agent.workflow.invoke.trade` 一样，由本 change 交付精确清单，Auth/协调方完成目录与授权接入；本 change 不向用户角色自授新权限。接口 DTO 只落在 `common/business/review/**` 或 business-service 内，不扩充 common auth DTO。Studio 前端仍由其原 change 对接，领域 API 完成不宣称浏览器工作台已经落地。

### 7. 无跨 change 等待并按域拆分交付

本 change 只拥有 `business-service` 的复盘领域/API/存储/技能、`bk_business` 复盘迁移、`common/business/review/**` 最小业务 DTO、本域 fixture，以及一条 `a2a:trade-agent` 受管配置映射。它不拥有 `common/authz/**`、`auth-service/**`、Auth 全局权限/身份目录、Memory 全局登记/密钥服务表、初始化 runner 的跨域 allowlist、房源/知识生产者与数据库、Studio 页面/work/command、其它业务服务或通用 Agent/Graph 代码；需要的权限码、STORE 身份和共享配置由协调方登记/合并。

工作区/分支、Maven 仓库及测试资源按[并行总览](../../../docs/business-domain-change-roadmap.md)隔离。房源和知识 HTTP consumer adapter 必须实际实现；路径、字段、批量检查和错误语义采用冻结 v1，使用本会话有界 HTTP服务器返回精确模拟来源/版本/撤回夹具，直接运行本域处理器、状态和数据库验证闭环。消费 DTO 和 mapper 留在 Business，不读其它域数据库或依赖另一个 change 新增的 Java 类型。

Auth/Memory 的当前协议通过测试配置下精确服务/用户登记替身覆盖，但 Auth 测试替身只验证业务边界消费已可信 actor/store 范围的行为，不证明当前生产 Auth 已支持 STORE；生产缺配置或签名范围不支持时仍前置拒绝。`peerMode` 显式选择 fixture/http，HTTP 依赖不可用、版本不支持和拒绝不能自动回退。由此 Business change 可在没有另外三个实现提交时完成自身编译、处理器/持久化和 v1 消费契约验收，不把 fixture 结果说成 Auth/生产服务已经接通。

共享根配置只提交 Business 本域必需的最小差异；`agent-service` 仅增加上述稳定 A2A 映射。Auth/Memory 全局登记、权限授予、STORE 签名身份、初始化 runner 的 `bk_business` allowlist 及真实来源接入由协调/Auth 会话合并。合并后的权限注册核验、生产者互通、组织范围和浏览器联调单独记录，不纳入本 change 的独立实施完成前置条件，但在生产启用前必须通过。

## Risks / Trade-offs

- [模拟样本看起来像真实经营] → dataset、来源模式随 API/结果/导出保留，模拟观察不进入真实增长证明。
- [重复确认或丢回执导致重复任务] → request hash、expectedVersion、事务唯一键及原回执查询。
- [知识或房源更新后继续执行旧计划] → 确认与后续使用前复核依赖版本，失效即要求重新确认，已完成执行事实保留。
- [当前 Auth 只签 BROKER 身份且没有 trade-agent/review 操作登记] → 业务侧通过范围端口和精确测试登记独立验证；Auth 未提供签名 STORE 范围、未登记精确操作时生产路由保持关闭/拒绝。
- [Agent capability 映射被误当作业务写授权] → A2A invoke 操作与每个 Business 方法的精确操作、store owner 检查分开；写动作只有用户对具体计划版本的直接确认才产生。

## Migration Plan

以下为合并后的共享环境计划；独立会话使用自身 fixture/隔离库验证行为并交付可应用步骤，不操作共享栈。

1. 增量增加 review 表及 schema 检查；原 KPI 表、API 和历史数据保持可读，不把历史看板行改写为已执行动作。
2. 实现输入、指标和事项/动作状态，以及房源/知识 v1 HTTP适配；独立验收用明确 fixture 运行全链，合并后将显式 HTTP配置指向已实现的生产者核验互通。
3. 保留现有 Agent 身份与 Memory 接入，交付一条精确 A2A capability 映射配置及 Auth 登记交接清单；仅发布诊断/计划草稿技能，写操作仍走明确 API 确认，不启用 MCP 写工具或外部发送。
4. 使用固定模拟数据完成带看停滞与房源维护两条功能验收；分别报告功能、效率采样和真实效果未验证状态。
5. 回退停止创建新复盘事项并撤下新技能，保留已确认动作、回执及观察记录，不清空数据或重放完成动作。

## Open Questions

首批 fixture 的具体字段值、门店代号和展示示例可以在实施中替换，但必须满足既定来源、模式、事件时间及版本契约。真实经营 feed 的地址和业务样本不构成本期模拟功能验收的前置条件。Auth 登记 `STORE` 身份及精确操作码是按第 6 节约定的外部交接，不是本 change 的开放设计问题。
