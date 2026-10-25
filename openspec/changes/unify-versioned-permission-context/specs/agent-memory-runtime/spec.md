## MODIFIED Requirements

### Requirement: Restart recovery and credential separation

系统 SHALL 在重启或冷恢复后依据可靠会话材料和原执行事实重建获准工作集、继续未确认交付而不重复消息。用户读取须原actor重新认证并取得当前版本授权，重新验证owner/domain/agent与各来源；持久owner/context/任务及版本不代替凭据。来源撤回使摘要和锚点失效或从获准证据重建。账号与粗权限可使用统一有效上下文和通知窗口，来源/grant/删除和writer事实仍由领域实时核查。真实token、授权证明、A2A信标、访谈ticket和身份租约 MUST NOT 进入记忆、摘要、普通metadata、执行快照或日志，不能由恢复标识换发。

#### Scenario: Restart reconstructs memory without duplicate delivery
- **WHEN** 已记录消息的交付未确认时进程重启
- **THEN** 当前身份和领域授权成立后恢复原context并继续同一消息，不重复追加

#### Scenario: Durable delivery is distinct from resumed user access
- **WHEN** 原用户凭据失效而可靠事件仍待交付
- **THEN** SERVICE仅按精确事件许可补交付，不能搜索用户记忆或恢复票据；来源删除/撤回仍有效

#### Scenario: Expired credentials require authentication again
- **WHEN** checkpoint仅保留owner和运行标识而无当前有效授权
- **THEN** 要求原用户重新认证，不从快照身份/版本生成凭据

#### Scenario: Recovery preserves accepted task association
- **WHEN** 原远端任务已接受且关联可靠保存
- **THEN** 继续原任务，不因重建工作集更换运行或重发副作用

#### Scenario: Interview tool returns an entry reference
- **WHEN** 访谈开台或启动工具返回给模型及归档
- **THEN** 只包含非凭据业务引用，Memory消息、历史恢复和摘要均不含ticket原文

## ADDED Requirements

### Requirement: Cached user authorization does not replace current memory resource checks

Memory SHALL 在新用户执行边界独立验证统一委托并复用其有效身份/粗权限，内部操作、模型、工具及流事件不逐次查询Auth。Auth通知、规划核版本及有界对账控制账号/角色失效；私有owner/domain/agent/context交集、共享purpose/recipient/有效期、来源revision/hash/撤回/删除、writer fence和消息幂等继续依据当前领域真源验证。业务管理员全操作权限不豁免个人Memory。数据缓存命中不授权陈旧来源，SERVICE精确补交付不变；基础Memory必需接入与失败关闭不被性能优化移除。

#### Scenario: Account grant changes while private source stays active
- **WHEN** 用户授权变更通知尚在30秒兼容窗内且旧执行上下文尚未到期
- **THEN** 仅按原许可和有效上下文继续，收到失效或达到期限后停止新读取，不能跨owner扩权

#### Scenario: Shared source is revoked despite unchanged Auth version
- **WHEN** Auth操作权限版本不变但共享grant或个人来源已撤回
- **THEN** 下一领域使用/输出边界阻止正文，不能等待Auth通知或用操作快照放行

#### Scenario: Model or tool uses a warm working set
- **WHEN** Supervisor或子Agent以热缓存进入新的模型/工具/输出边界
- **THEN** 本地授权状态和当前来源约束仍核验，新增Auth远程复验为零；依赖无法核验的私有正文不得使用
