## MODIFIED Requirements

### Requirement: RPC authorization SHALL verify the account exists and is active

授权RPC SHALL 在建立新外部请求或新业务执行上下文时验证真实凭据、账号存在、活跃且未删除，再提供明确登记权限的版本化授权。普通用户权限来自有效角色并集，业务管理员不依赖角色取得全部已登记有效权限，但不能绕过业务状态或个人Memory owner。普通userId和权限码非空 MUST NOT 代替认证或跨服务受限委托。有效执行上下文可在规定期限和最多30秒失效兼容窗口内本地复用，之后按版本通知和对账失效，而非每节点重查账号；已知失效、硬到期或新入口不能使用旧上下文。

#### Scenario: Unknown user requests permission
- **WHEN** 新授权请求引用未知、停用或已删除用户
- **THEN** RPC拒绝并且不签发新授权上下文

#### Scenario: Existing execution receives account invalidation
- **WHEN** 已接受执行的账号被停用并收到失效通知
- **THEN** 后续受保护动作停止，已完成事实保持；未收到通知的兼容使用不得超过30秒或凭据硬到期

#### Scenario: Legacy user ID is presented without authenticated context
- **WHEN** 普通调用只提供userId和权限码，没有真实凭据或有效受限委托
- **THEN** 不得建立新执行授权，管理员身份也不能由请求声明取得

## ADDED Requirements

### Requirement: Gateway establishes one trustworthy request authorization

Gateway SHALL 清除客户端伪造身份与授权头，对受保护请求一次向Auth认证并取得签名的请求级完整有效权限上下文；下游验证签名、audience、期限及请求关联，不再重复解析同一Bearer。公开规则 SHALL 使用路径和HTTP方法精确匹配，精确POST /auth/refresh仍由refresh凭据自身验证；直接服务入口没有可信上下文时必须走独立认证，不信固定marker或内网来源。现有签名、到期、注销撤销语义保持，已接受运行失效采用明确兼容窗口，不把权限快照变成长期登录凭据。

#### Scenario: Forged client authorization headers
- **WHEN** 客户端携带伪造actor或管理员上下文头
- **THEN** Gateway清除并使用真实认证结果，下游不从伪造字段取得身份

#### Scenario: Refresh without a valid access token
- **WHEN** 客户端向精确POST /auth/refresh提交有效refresh凭据而access已过期
- **THEN** 可到达refresh验证；其他方法和相似路径仍按独立规则保护

#### Scenario: Request body or routing is replaced after entry authorization
- **WHEN** Gateway已授权请求的body、HTTP方法、目标路径、query或audience被替换
- **THEN** 下游在解析/执行前核对实际转发请求绑定并拒绝，无业务副作用，不能只验证固定身份头

#### Scenario: Business administrator authenticates without a role
- **WHEN** 有效businessAdmin无角色或普通用户具有多个有效登记角色执行login/refresh/me
- **THEN** 身份与授权使用可信管理员标记和角色集合，不被旧ADMIN/BROKER白名单误拒绝；Token签名与撤销验证保留

#### Scenario: Logout invalidates running contexts without promising all-device logout
- **WHEN** 用户注销提交token而其他设备仍有有效access/refresh
- **THEN** 提交token被吊销且该用户旧运行上下文失效，其他有效token仍可重新认证建立新上下文；重复同注销不重复递增失效版本

#### Scenario: One request crosses Gateway into Supervisor
- **WHEN** 正常受保护请求已由Gateway取得Auth授权上下文
- **THEN** Supervisor消费同一可信结果，不再对相同入口凭据重复调用resolvePrincipal
