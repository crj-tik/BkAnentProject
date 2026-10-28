## Purpose

建立访谈三协议面的操作和数据授权，使同一普通用户在不同场次中的创建、受访关系随资源确定，而非永久角色；同时定义业务管理员的业务全读、可信场次凭据交付以及私有记录和明确公开资产的不同访问边界。

## ADDED Requirements

### Requirement: Interview participation is a resource relationship

访谈 SHALL 持久绑定经认证的创建用户与经服务端验证的受访用户；创建、受访是场次关系，不新增发起人、受访者或导演系统角色。工号、姓名、组织文字及模型参数 MUST NOT 直接成为认证或归属证明。只有产品策略限制发起资格时，普通用户发起才要求有效 `interview.initiate`；普遍开放的创建、本人参与记录读取和回答不逐项要求权限码，但必须校验有效身份、场次参与关系及状态。创建用户可协助自己创建的场次，受访用户可回答自己的场次；业务管理员不得因访谈管理权读取其他用户的 Agent 私有记忆。

#### Scenario: A user initiates one session and responds in another
- **WHEN** 同一普通用户有发起权限并创建场次A，同时在场次B被确认是受访用户
- **THEN** 可读取A与B及获准结果，协助操作只根据各场次关系和具体许可决定，不创建永久参与者角色

#### Scenario: A respondent has no initiate permission
- **WHEN** 普通用户没有发起权限但与场次有可信受访关系
- **THEN** 允许本人受访记录及相关結果读取和有效场次答题，拒绝发起新访谈

#### Scenario: Model supplies another creator work number
- **WHEN** 模型或请求声明另一个创建人工号或伪造受访用户
- **THEN** 当前actor不被替换，未经服务端身份映射或受访确认的关系不得用于访问或创建他人归属记录

### Requirement: Private interview records and business administrator scope

场次、题单、原始话轮、未公开逐字稿、报告任务和相关结果 SHALL 以可信创建/受访关系限定普通用户读取；可选 creator 过滤和持有 ID 不授予权限。业务管理员 SHALL 可读取未删除业务访谈及相关结果，写动作仍需具体操作与业务条件。关系核查 SHALL 适用于 REST、A2A 工具、列表/详情/搜索/回执及源结果复用，不只在前端过滤。

#### Scenario: Ordinary user queries an unrelated report by ID
- **WHEN** 普通用户查询与其没有创建或受访关系的私有报告、任务或逐字稿
- **THEN** 在正文返回前拒绝，列表/搜索也不显示该私有对象或其摘要和内部错误

#### Scenario: Administrator views all business interview results
- **WHEN** 业务管理员查询未删除场次及其结果
- **THEN** 可按业务全读范围返回，但不能扩展为其相关其他owner的Memory工作集或偏好读取

### Requirement: Collaboration actions remain distinct from state-machine authority

题单创建/修改/确认、启动、场次凭据重签、协助指令、主动结束、重评级和报告生成 SHALL 使用明确操作许可及资源关系，并核对允许的业务状态。创建用户协助访谈不引入独立导演身份。组件职责与状态转换来源 MUST NOT 被当作登录用户权限；已有目标状态的幂等返回也不能绕过用户或操作检查。运行开始后题单写入 MUST 被拒绝；协助命令只在进行中接受，结束 SHALL 由原运行管线按受权结束请求执行，不在治理工具冒用运行职责直接推进。

#### Scenario: A valid respondent ticket is used for collaboration
- **WHEN** 受访用户持有效参与资格请求修改题单、重新签票或提交协助命令
- **THEN** 因其没有对应创建关系和操作许可而拒绝，不以票据有效授予全部场次动作

#### Scenario: Question confirmation arrives after start
- **WHEN** 场次已进行中或已结束，合法创建用户再次提交题单修改或确认
- **THEN** 在任何题目更新前拒绝，不仅阻止状态跳转而仍修改题单

#### Scenario: Repeated start attempts to rotate a credential
- **WHEN** 已进行中的场次收到重复start并附新模式
- **THEN** 仅在明确授权的操作契约内幂等处理，不隐式更新模式或重签凭据

### Requirement: Session credential delivery never passes through the model

场次资格 SHALL 从真实用户与资源关系派生，票据只作为认证后的短期场次参与绑定，不代替Gateway登录凭据。开台/启动工具 SHALL 只返回非凭据场次引用；授权前端通过受鉴权领取接口获取自己的场次凭据，不经模型转述或A2A业务结果携票。票据 SHALL 绑定用户、场次、用途、截止与撤销版本，支持独立重签/撤销，原文只在领取响应和专用请求头中使用，不进入URL、prompt、普通metadata、Memory、checkpoint、日志或SSE。认证上下文到期/撤销采用统一失效语义，场次关系和票据状态仍本域核查。

领取契约固定为 `POST /interviews/{sessionId}/credentials`，由当前Gateway签名actor领取 `MANAGE`、`RESPOND` 或 `READ` 用途；响应 MUST 设置 `Cache-Control: no-store`，仅回传本次随机凭据和领取元数据。会话 REST 与 SSE 请求 MUST 通过 `X-Ticket` 头提交票据，并继续携带登录认证；任何 query-ticket 形式 MUST 在业务解析前拒绝。浏览器 SSE SHALL 使用可携带自定义请求头的 fetch/stream API，不使用不能设置认证头的原生 `EventSource`。

#### Scenario: A2A session start reaches the front end
- **WHEN** 合法用户通过Supervisor启动访谈
- **THEN** 输出含安全场次导航与领取标识，前端经受鉴权接口领取票据；工具消息、模型、Memory、终态artifact和progress均无票据原文

#### Scenario: Ticket is replayed by another user or purpose
- **WHEN** 一个有效票据用于其他用户、其他场次或未授予的用途
- **THEN** 目标入口拒绝，不因持有票据或相同工号放行

#### Scenario: Ticket is revoked while the session remains active
- **WHEN** 创建用户或受权管理员撤销指定票据
- **THEN** 后续该票据访问被拒绝，其他参与资格不受无关重签影响，密钥轮换不会被误认为自动完成票据撤销

#### Scenario: Browser sends a session credential
- **WHEN** 已登录的浏览器调用受保护会话 REST/SSE
- **THEN** 登录认证继续由Gateway签署为请求上下文，场次票据仅放在 `X-Ticket`；凭据放在 query 或使用不能携带自定义头的原生 `EventSource` 时拒绝/无法集成，凭据领取响应不被缓存

### Requirement: Published interview material is a separate authorized projection

普通用户的私有访谈读取范围与公共材料读取 SHALL 分离。公开逐字稿及衍生报告 MUST 经过明确发布许可、内容脱敏、有效来源关联及L2/L3资格；评级本身不自动发布。所有公开搜索、按ID详情、报告生成与成功结果复用 SHALL 重新核对公开状态与源资产资格。上传只新增资产仍 MUST 核验提交操作与case关联并实际脱敏，不以变量命名或调用者声明证明已脱敏。降级、删除、撤回或来源失效 SHALL 阻止公开读取和派生正文复用。

#### Scenario: Authorized participant reads or generates a private report
- **WHEN** 本人参与者读取自己的未公开结果，或具有生成操作的创建用户生成合法私有源报告
- **THEN** 按操作与资源范围准入，不要求PUBLISHED；该报告不会因此在公共投影中出现

#### Scenario: Private high-grade transcript has not been published
- **WHEN** 一个L2/L3资产尚未显式获准发布
- **THEN** 只有本人参与关系或业务管理员可读，公开能力面不显示该对象

#### Scenario: Source asset is withdrawn after a report was generated
- **WHEN** 公开报告的源资产被删除、降级或撤销发布
- **THEN** 公开列表、详情、搜索和结果复用不返回该报告正文或摘要，内部已完成事实不被改写为未执行

#### Scenario: An uploaded transcript contains private values
- **WHEN** 用户通过获准新增路径提交逐字稿
- **THEN** 系统验证case关联并生成实际脱敏投影，在未经发布核准前不自动公开或将原文送模型

### Requirement: Report and collection workers use exact accepted facts

报告提交 SHALL 在接受前验证actor操作、case/asset/session真实关联、来源可用性与内容版本，后台仅使用该精确已接受事实。租约接管后的迟到执行 MUST NOT 覆盖新结果；成功结果复用及最终发布 SHALL 核对当前来源和读取资格。归集应保持原状态机和完整已落库话轮，不因权限缓存或报告摘要跳过运行事实；来源撤回阻止正文使用/发布但不自动重执已完成模型或业务动作。

#### Scenario: Request combines a case and another case's asset
- **WHEN** 调用者对某case有操作许可但提交其他case的asset
- **THEN** 在任务创建和模型调用前拒绝，不用ID组合hash冒充合法来源快照

#### Scenario: Old worker completes after lease takeover
- **WHEN** 报告worker失去领取epoch后返回模型结果
- **THEN** 不能覆盖新epoch的任务、失败状态或已确认报告，不向公开读链发布迟到正文

### Requirement: Protocol adapters and legacy data have explicit admission gates

REST、A2A与MCP SHALL 映射到相同领域操作与资源政策；MCP工具声明只有完成真实注册和鉴权接线后才可宣称可调用。旧case/session/asset只有工号而无可信user映射时 SHALL 保留并隔离，不按模型信息推断owner，不无条件公开；受权管理员可在审计映射流程下补齐。运行面保持固定决策、质量门、话轮幂等与原回执，不把鉴权改造变为ReAct自由流程。

#### Scenario: Legacy record has an unresolved creator mapping
- **WHEN** 旧场次尚无法把工号映射为可信用户
- **THEN** 不授予普通用户归属访问，保留原记录供受权映射，不删除历史或猜测主体

#### Scenario: Skill excludes an essential registered action
- **WHEN** 访前技能声明与实际题单创建工具的允许范围不一致
- **THEN** 接入验收失败且能力不启用，不通过放宽用户权限或绕过技能守卫补救
