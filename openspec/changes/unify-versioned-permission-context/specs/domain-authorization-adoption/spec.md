## Purpose

规定尚未完全落地业务域采用可信用户上下文、操作权限和资源权威判断的逐域准入方法，避免只登记服务公钥或工具名称就被误认成完整授权；迁移以真实能力和可核验主体关系为依据，不扩张为未实现业务功能建设。

## ADDED Requirements

### Requirement: Domain admission requires an explicit operation and subject mapping

每个业务域 SHALL 为真实REST/RPC/A2A/MCP动作登记稳定权限码、执行入口、可信actor来源、业务目标字段、资源真源和状态条件；不存在的资源主体映射或未落地能力 MUST 明确拒绝，不以工具描述、固定caller名称、模型userId/employeeId/brokerId/recipient或服务公钥登记推断授权。业务管理员全操作权限 SHALL 仅作用于明确的业务资源范围，不扩展个人Memory。

#### Scenario: A registered agent still lacks a recipient mapping
- **WHEN** 通知Agent服务身份有效但未能证明当前用户对目标recipient的读取或发送关系
- **THEN** 受保护消息读取/发送在工具执行前拒绝，不因Memory caller已登记而开放

#### Scenario: Tool description advertises an unimplemented action
- **WHEN** 工具声称能签章、支付、发布或生成视频，但真实业务执行未接通
- **THEN** 能力目录与验收标记不可用，不授权mock结果作为真实业务完成

### Requirement: One policy applies to every protocol and recovery entry

同一业务动作经REST、RPC、A2A、MCP、回放、结果查询或后台恢复 SHALL 使用一致操作许可与资源检查。模型生成的目标ID可以参与业务请求，但actor与许可仅来自可信上下文。领域入口独立验证本次委托后内部使用本地权限，跨域新执行必须重新验证边界；不存在用户上下文不得退化为服务全权。

#### Scenario: Direct RPC claims a permitted user's identifier
- **WHEN** 请求仅传有权限用户ID而没有可验证身份和调用服务证明
- **THEN** RPC拒绝，不把一般hasPermission结果当成用户持有凭据的证明

#### Scenario: Invocation is authorized but the target resource is not
- **WHEN** 用户可以调用客户或合同Agent但目标客户/合同不在获准范围
- **THEN** 领域拒绝该资源动作，编排权限不覆盖资源权威判断

### Requirement: Adoption is staged and evidence based

迁移 SHALL 首先验收共享链、Supervisor、Interviewer与Memory，再按实际启用集合逐域验收真实能力、主体绑定、数据范围、副作用和输出。未验域或操作保持关闭，不允许静默旧凭据回退。每域 SHALL 记录跨用户、管理员业务范围、私有记忆拒绝、跨协议、撤权窗口及任务恢复的证据；mock wiring成功不等同现网资源授权。升级和回滚须保留业务数据、原调用及幂等事实。

#### Scenario: Compare still uses a retired unsigned resource RPC
- **WHEN** 比较生成依赖无可信凭据的旧房源读取路径
- **THEN** 该能力不能通过准入，须迁移至受权资源读取后再启用，不将fixture放行视为生产成功

#### Scenario: Business data relationship is not yet defined
- **WHEN** 员工、门店、经纪人或收款主体与平台用户的映射无法核验
- **THEN** 对应私有查询或副作用不启用，记录缺口，不虚构租户/组织策略或扩大管理员读取为个人Memory旁路
