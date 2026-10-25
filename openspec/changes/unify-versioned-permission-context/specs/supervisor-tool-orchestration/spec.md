## MODIFIED Requirements

### Requirement: 模型获得可实际执行的统一能力目录

系统 SHALL 向模型提供当前已发现、有效且授权可见的 A2A/MCP/本地能力，每项包含稳定身份、无冲突模型名称、description 和输入 schema，并对应真实执行入口。能力所需权限 MUST 来自明确登记映射，不从domain首项、任意权限名前后缀或generic server fallback推断。A2A描述保留发布事实，MCP保留实际schema；目录 SHALL 在单轮一致并在后续规划按当前有效完整权限集合刷新。同版不重复下载或远程逐工具查权限，动态发现的获准能力不得因初始名单冻结而丢失。endpoint更新保留已接受任务关联。

#### Scenario: 动态新增领域不需要关键词配置或业务节点
- **WHEN** 新Agent已发现、真实能力和权限映射有效，当前用户集合包含所需权限
- **THEN** 后续目录本地交集显示该能力，不新增关键词/领域执行节点，也不逐能力发Auth请求

#### Scenario: 两个 MCP 服务拥有同名工具
- **WHEN** 两个连接声明search且当前用户被授予各自登记操作权限
- **THEN** 两项以独立稳定身份可见并执行，不将server可读视为其任意私有资源授权

#### Scenario: Agent 地址发生变化
- **WHEN** 同一Agent发布了有效新endpoint
- **THEN** 新调用使用新地址，已接受任务仍用原关联续查，授权集合不授予任意地址选择权

### Requirement: 补充输入具有明确等待与续接语义

模型 SHALL 通过结构化补充输入提出问题，系统持久化WAITING_USER_INPUT与原上下文，不以关键词决定等待。续接 SHALL 重新在外部入口认证原owner并取得当前授权版本，核对原session、请求去重和等待状态，串行处理。原模式、技能正文快照、MCP选项、请求政策上限及预算 MUST 保留；当前权限与动态目录取交集，不因初始名单冻结获准新能力，也不因角色增加或缺省值扩大原政策。审批回调不得兼作输入续接。

#### Scenario: 缺失参数后继续原任务
- **WHEN** 原owner经新入口认证补充输入并提供原关联
- **THEN** 沿原消息和技能继续，重复输入不重复推进，不从checkpoint取回凭据

#### Scenario: 新请求与显式续接分离
- **WHEN** 同会话输入未携带续接关联
- **THEN** 新建任务并独立绑定request/session/actor，不把重复requestId视为另一session旧结果

#### Scenario: 续接试图切换技能
- **WHEN** 显式运行续接要求换另一个技能
- **THEN** 拒绝策略变更，不覆盖原正文版本或授权范围

#### Scenario: 续接不能扩权或重置预算
- **WHEN** 原请求禁用MCP或已消耗预算，续接省略选项或用户角色新增权限
- **THEN** 禁用政策和累计预算不变，新增权限不绕过原审批

#### Scenario: AUTO 续接后发现新 Agent
- **WHEN** 新Agent获准且符合原政策和最新权限版本
- **THEN** 后续规划可见，不因初始Agent列表冻结排除

### Requirement: 调用治理与失败语义保持硬边界

每次A2A/MCP/本地实际调用 MUST 本地核对目标、schema、稳定操作许可、有效技能范围、预算、审批和服务端actor；新跨服务执行边界独立验证受限委托。新请求一次取得完整权限，重新进入业务Model只核版本，同版继续，不逐节点/工具/SSE远程复验。账号/角色失效遵循版本通知和最多30秒窗口；已知撤回及硬到期必须停止新动作。资源关系、状态、Memory来源/grant、参数hash、租约、取消及副作用事实仍受当前权威约束。模型不可改actor或审批；权限拒绝可返回未执行结构化结果再规划，不提权换用户或盲重发。

#### Scenario: 审批批准后恢复
- **WHEN** 新认证的原owner批准原调用及参数
- **THEN** 验证当前权限并只恢复原动作，批准不升级角色、不跳业务状态

#### Scenario: 执行前能力失效
- **WHEN** 目标下线、参数版本变化或本地已获知相关权限撤回
- **THEN** 实际执行前拒绝，不用旧目录跨越已知失效

#### Scenario: 模型不可用
- **WHEN** 同模式有界模型重试耗尽
- **THEN** 明确记录模型不可用，不切关键词路由或默认业务

#### Scenario: One model return only verifies the authorization version
- **WHEN** 工具完成后回Model且授权未变
- **THEN** 只做一次版本核对；同批工具、节点前后和逐delta新增Auth用户RPC为零

## ADDED Requirements

### Requirement: All Supervisor entries bind session owner and request identity

chat、同步/异步提交、续接、审批、查询、取消、SSE及审计 SHALL 使用同一持久actor/session/run归属规则。requestId幂等 MUST 绑定actor、session及归一化输入/政策；同ID换session或正文必须报冲突，不返回旧结果或发布到他人session。业务管理员的业务诊断全读只通过明确管理入口和审计获得，不扩展普通用户范围或Memory私有读取。

#### Scenario: A chat request reuses another user's session
- **WHEN** 用户提交属于其他actor的sessionId
- **THEN** 在运行和事件发布前拒绝，Memory复合键不替代事件通道owner核验

#### Scenario: One user reuses a request ID across sessions
- **WHEN** 同actor把已用requestId用于不同session或输入
- **THEN** 返回409冲突，不返回旧会话结果或新副作用

#### Scenario: Ordinary user reads event audit
- **WHEN** 普通用户不带筛选参数查询事件审计
- **THEN** 只返回其持久session/run拥有范围，其他事件正文/metadata不出现

### Requirement: Platform governance requires explicit management authority

平台限流、灰度及全局治理配置读写 SHALL 使用独立已登记管理操作许可并审计actor、范围、版本和变化。普通聊天、工作流读、Agent调用许可不得授权全局修改，模型工具不得自行创建该权限。

#### Scenario: Ordinary user attempts a global governance override
- **WHEN** 普通用户持有效登录但无治理管理权限修改或清空限流/灰度配置
- **THEN** 在任何持久写入前拒绝，平台配置不变化
