# 统一前后端接口契约（v1）

**用途**：这是仓库内浏览器前端与后端接口契约的统一入口，汇总共同调用规则、Auth 契约和各业务域详细契约的位置及完成状态。接口均以 Gateway 暴露路径为准。本文不把内部 RPC、A2A、MCP、数据库或健康探针自动视为浏览器接口。

**契约层级**：已有版本化 OpenAPI 文件对其覆盖的路径和 schema 具有接口级权威性；本文是所有前端对接资料的总入口，并维护 Auth 接口和跨域规则。标注为“草稿 / 局部 / 盘点”的文件只提供线索，不代表该域已经具备完整联调契约。源码与文档不一致时，应先记录差异并修订契约，不能默默让前端猜测。

**范围边界**：网关路由存在只说明有路由定义，不代表目标环境配置已发布、用户有权访问、下游依赖可用或业务验收已完成。运行面路由验收见 [KI-236](../known-issues.md)。

## 全局调用规则

- 浏览器请求通过 Gateway；使用部署提供的 Gateway 根地址和下表中的完整路径。
- 对受保护请求发送 `Authorization: Bearer <accessToken>`。登录、刷新按各自请求契约携带凭据；健康检查是否经 Gateway 开放取决于路由配置。登录后必须以 Auth 返回的 token 调用接口。
- 浏览器不得自造 `actorId`、管理员标记、权限列表、业务身份或内部授权签名头。Gateway/服务链从已验证登录态建立签名上下文；业务服务仍逐请求校验操作、资源归属和业务范围。
- `roleCode`、`roles`、`businessAdmin` 可用于显示账户信息，不能替代目标操作权限检查。`storeId`、`brokerId` 等请求字段不能建立调用者身份。
- 响应封装不是全仓统一的：Auth 普通接口和大多数业务 API 使用 `{success, code, message, data}`；`/auth/authorization/**` 直接返回 DTO 或数组并用 HTTP 状态表达失败。其它域按各自 OpenAPI / 详细契约执行，不能全局假定一个 envelope。
- 登录和刷新会在成功响应的 `data` 中返回令牌；前端必须保存刷新响应里的新 `accessToken` 和新 `refreshToken`。正常轮换会撤销旧 refresh token；若同秒重签产生完全相同的 token 字符串，服务端跳过撤销以避免吊销新 token。详见 [LR-16](../logic-rationale.md)。

## Auth 契约

Auth 是登录令牌、角色授权和业务身份的唯一管理入口。除特别说明外，路径相对于 Gateway；受保护接口带 Bearer access token。

### 登录态与当前用户

| 方法和路径 | 请求 | 成功响应 | 认证与行为 |
| --- | --- | --- | --- |
| `POST /auth/login` | `{username, password, tenantCode?}` | `ApiResponse<AuthToken>` | 无 Bearer。`username` 与 `password` 必须非空；当前实现不使用 `tenantCode` 过滤账号。空字段为 `AUTH_400`，无效/停用账户或凭据为 `AUTH_401`；失败检查 envelope 的 `success/code`，不能只看 HTTP 状态。 |
| `POST /auth/refresh` | `{refreshToken}` | `ApiResponse<AuthToken>` | 无 access-token 要求；只接受有效 refresh token。缺少 token 为 `AUTH_400`，无效或过期为 `AUTH_401`。成功后同时替换本地两个 token；新旧 token 字符串不同才撤销旧 refresh token。 |
| `POST /auth/logout` | 空 body；可带 `Authorization` | `ApiResponse<null>` | 可选 Bearer。登出时应传当前 access token；省略时虽返回成功但没有可撤销的 token。 |
| `GET /auth/me` | 无 | `ApiResponse<CurrentUser>` | 要求有效 access token；返回当前账户与版本化授权摘要。 |
| `GET /auth/health` | 无 | `ApiResponse<HealthStatus>` | Controller 不读取用户身份；是否经 Gateway 对浏览器开放仍由目标路由配置决定。仅表示服务健康，不代表登录或业务能力可用。 |

`AuthToken={accessToken:string,refreshToken:string,userId:int64,roleCode:string,displayName:string,roleName:string,tenantCode:string,businessAdmin:boolean,roles:string[],permissionRevision:int64,credentialEpoch:int64}`。旧授权模式下 `businessAdmin`、`roles`、`permissionRevision`、`credentialEpoch` 使用 `false`、空数组和 `0` 等兼容默认值。

`CurrentUser={userId:int64,username:string,displayName:string,roleCode:string,roleName:string,tenantCode:string,businessAdmin:boolean,roles:string[],permissionRevision:int64,credentialEpoch:int64}`。它当前不返回该用户的完整有效权限码清单；前端不得用角色名推算按钮权限，目标 API 的 403 才是权威授权结果。若 UI 必须按逐项权限隐藏功能，需要另定义只读的当前用户权限查询契约。

### 用户管理

以下管理入口要求当前管理员 access token。版本化授权开启时，Auth 同时要求 `businessAdmin=true` 和 `auth.users.manage`；旧授权模式要求 `roleCode=ADMIN`。前端不能提交或覆盖自己的管理员声明。

| 方法和路径 | 请求 | 成功响应 |
| --- | --- | --- |
| `GET /auth/roles` | 无 | `ApiResponse<RoleOption[]>` |
| `GET /auth/users?page=1&pageSize=20` | 可选 `page:int64`、`pageSize:int64`、`keyword:string`、`roleCode:string`、`accountStatus:string` | `ApiResponse<PageResult<UserItem>>` |
| `POST /auth/users` | `CreateUser` | HTTP 201，`ApiResponse<UserItem>` |
| `PUT /auth/users/{userId}` | `UpdateUser` | `ApiResponse<UserItem>` |
| `PUT /auth/users/{userId}/password` | `{password}` | `ApiResponse<null>` |
| `DELETE /auth/users/{userId}` | 无 | `ApiResponse<null>` |

`CreateUser`：`username` 必填且匹配 `[A-Za-z0-9._-]{3,64}`；`password` 必填、8–72 字符；`displayName` 必填、最多 64 字符；`roleCode` 必填、最多 32 字符；`tenantCode` 必填、最多 64 字符。

`GET /auth/roles` 只返回活动且可分配的角色；旧授权模式还会限制为 Auth 支持的角色集合。创建和更新用户时应使用此接口返回的 `roleCode`，服务端仍会重新校验角色状态。

`UpdateUser`：`displayName`、`roleCode`、`tenantCode` 均必填且使用上述长度上限；`accountStatus` 必填，只接受 `ACTIVE` 或 `DISABLED`。重置密码请求的 `password` 必填、8–72 字符。

`RoleOption={roleCode:string,roleName:string,description:string|null}`。`UserItem` 包含 `CurrentUser` 的同名账户与授权摘要字段，另有 `accountStatus:string`、`createdAt:LocalDateTime`。`PageResult<T>={records:T[],total:int64,page:int64,pageSize:int64}`。列表默认 `page=1`、`pageSize=20`；服务端将 `page` 限制为至少 1、将 `pageSize` 限制在 1–100。

Auth 登录/刷新方法内的失败通过 `ApiResponse.fail` 的 `success/code` 表达，当前 Controller 不为这些失败设置非 200 状态；参数校验异常为 HTTP 400 / `AUTH_400`。受保护 Auth 接口未认证为 HTTP 401，权限不足为 HTTP 403，用户不存在为 HTTP 404，用户管理中角色无效为 HTTP 400，用户冲突为 HTTP 409。`/auth/authorization/**` 直接返回 HTTP 错误且错误正文不保证采用 Auth envelope；客户端按状态处理，不解析未约定的错误文案。

### 角色、权限与业务身份管理

`/auth/authorization/**` 仅在 `auth.authorization.enabled=true` 时注册。下列接口都需要 Bearer access token，返回原始 DTO/数组（不包 `ApiResponse`）；错误使用 HTTP 状态。

| 方法和路径 | 请求 | 成功响应 | 权限规则 |
| --- | --- | --- | --- |
| `GET /auth/authorization/catalog` | 无 | `Permission[]` | `auth.roles.manage` |
| `GET /auth/authorization/roles` | 无 | `Role[]` | `auth.roles.manage`，最多 1000 项 |
| `POST /auth/authorization/roles` | `RoleDefinition` | `ManagementResult` | `auth.roles.manage` |
| `PUT /auth/authorization/roles/{roleId}` | `RoleDefinition` | `ManagementResult` | `auth.roles.manage`；角色 code 不可修改 |
| `DELETE /auth/authorization/roles/{roleId}` | 无 | `ManagementResult` | `auth.roles.manage`；执行停用/删除 |
| `GET /auth/authorization/users/{userId}/roles` | 无 | `UserRoleGrant[]` | `auth.users.manage` |
| `PUT /auth/authorization/users/{userId}/roles` | `{grants: UserRoleGrant[]}` | `ManagementResult` | `auth.users.manage`；全量替换，最多 100 项 |
| `PUT /auth/authorization/users/{userId}/business-admin` | `{enabled}` | `ManagementResult` | `auth.users.manage` 且调用者是业务管理员；不能撤销自己的管理员标记 |
| `GET /auth/authorization/users/{userId}/business-identities` | 无 | `BusinessIdentity[]` | `auth.business-identity.manage` 且调用者是业务管理员 |
| `PUT /auth/authorization/users/{userId}/broker-identity` | `{brokerId}`，`null` 清除 | `BusinessIdentityChange` | `auth.business-identity.manage` 且调用者是业务管理员 |
| `PUT /auth/authorization/users/{userId}/store-identity` | `{storeId}`，`null` 清除 | `StoreIdentityChange` | `auth.business-identity.manage` 且调用者是业务管理员 |

核心类型：`Permission={code:string,domain:string,operation:string,active:boolean,version:int64}`；`RoleDefinition={code:string,name:string,description:string|null,active:boolean,assignable:boolean,permissionCodes:string[]}`；角色 code 匹配 `[A-Z][A-Z0-9_]{0,31}`、name 最多 64 字符、description 最多 255 字符、`permissionCodes` 最多 500 项且各项唯一并已在活动目录登记；`Role` 在其基础上增加 `id:int64`；`UserRoleGrant={roleId:int64,validFrom:Instant|null,validUntil:Instant|null}`，同一批次的 `roleId` 必须唯一且角色可分配；`BusinessIdentity={type:string,identityId:int64}`，broker/store ID 必须为正数或用 `null` 清除；`ManagementResult={targetId:int64,eventId:string,versions:UserVersion[],publicationConfirmed:boolean}`，其中 `UserVersion={userId:int64,permissionRevision:int64,credentialEpoch:int64,status:string}`；`BusinessIdentityChange` 含 `userId,brokerId,eventId,versions`，`StoreIdentityChange` 含 `userId,storeId,eventId,versions`。`publicationConfirmed=false` 不代表变更已向所有消费者确认。

角色授权与业务身份是两套独立关系：给用户分配角色不会自动分配 `BROKER` 或 `STORE` 身份；页面不得把门店/经纪人输入当成已授权身份。相关设计边界见 [LR-160、LR-163](../logic-rationale.md)。

### Auth 示例

登录请求：

```http
POST /auth/login
Content-Type: application/json

{"username":"<username>","password":"<password>"}
```

成功响应的令牌位于 envelope 的 `data`：

```json
{"success":true,"code":"0","message":"OK","data":{"accessToken":"<accessToken>","refreshToken":"<refreshToken>","userId":123,"roleCode":"BROKER","displayName":"<displayName>","roleName":"经纪人","tenantCode":"<tenantCode>","businessAdmin":false,"roles":[],"permissionRevision":0,"credentialEpoch":0}}
```

清除业务身份时使用 JSON `null`，例如 `PUT /auth/authorization/users/123/store-identity` 的请求体为 `{"storeId":null}`。不要在 URL、查询参数、日志或普通业务字段中传递 token。

## 按业务域的契约目录

本表列出当前面向 Gateway/前端集成的资料，并明确其覆盖程度。内部服务调用资料会单独标记，不应由浏览器直接使用。

| 域 | 详细契约 / 实现依据 | 当前状态与边界 |
| --- | --- | --- |
| Auth | 本文件“Auth 契约”；`auth-service` 的 `AuthController`、`RoleAuthorizationManagementController` 与 DTO | 浏览器身份、用户管理、角色和身份管理已按源码整理；当前以本文作为 Auth 接口契约，尚无独立 Auth OpenAPI；完整有效权限码的自助查询端点尚未定义。 |
| Gateway | [后端接口盘点](../backend-api-inventory-draft.md)、[Gateway 模板](../../nacos/gateway.yaml)、[KI-236](../known-issues.md) | 路由/受众仅是接入前提；目标环境逐路由验收未闭环。 |
| Agent / Studio | [Agent 对话和任务参数](agent-supervisor-frontend-draft.md)、[Studio API 说明](../studio-v2-api.md)、[Studio schema](studio-v2.schema.json)、[后端接口盘点](../backend-api-inventory-draft.md) | 已补充 Agent 对话、任务、审批和流及 Studio 工作项请求参数；MCP、RAG、诊断、治理等入口和运行行为仍需逐项核对。 |
| Listing / Customer | [房源与客户接口草稿](listing-customer-frontend-draft.md)、[Listing Business API v1](../listing-business-api-v1.md)、[内部 Listing API](../internal-listing-business-api.md) | 前端 CRUD 草稿字段较多但尚未逐项核实；`Listing Business API v1` 是受签名授权的服务间来源接口，不是普通浏览器契约。 |
| Platform Operations | [平台经营 OpenAPI](platform-operations-v1.openapi.yaml)、[已发布知识 OpenAPI](platform-operations-published-knowledge-v1.openapi.yaml)、[前端功能清单](../platform-operations-frontend-function-list.md) | OpenAPI 覆盖分析、复盘、知识接口；复盘响应 schema 等字段仍需与实际 DTO 继续对齐，相关数据源字段问题见 [KI-252](../known-issues.md)。 |
| Marketing | [营销接口请求参数](marketing-frontend-draft.md)、[后端接口盘点](../backend-api-inventory-draft.md) | 请求字段及已确认的校验已按源码补充；响应字段、全部授权和错误码仍需逐项核对。准备及兼容效果接口的未使用字段见 KI-254。 |
| Notification | [Notification 鉴权契约](../notification-api-auth-contract.md)、[后端接口盘点](../backend-api-inventory-draft.md) | 已说明身份和权限边界；REST 请求/响应 schema 尚需补齐。 |
| Interview | [访谈接口请求参数](interview-frontend-draft.md)、[浏览器凭据与请求契约](../interview-credential-client-contract.md) | 已补充当前 REST/SSE 请求参数、场次凭据和回执规则；响应完整 schema、全部异常及运行行为仍需验收。 |
| Compare / Promotions | [后端接口盘点](../backend-api-inventory-draft.md) | 仅有兼容路由/能力线索；使用前需核实目标 Gateway 路由和实际控制器，不默认当作新页面接口。 |
| Media / Memory / RAG 及其他 RPC | 对应服务源码与内部协议说明 | 默认属于服务间接口；只有明确登记 Gateway 路由及浏览器调用契约后才纳入前端。 |

接口参数逐域核对的范围与剩余工作见 [KI-253](../known-issues.md)。目录入口和已有契约文件不意味着所有 Gateway 接口都已完成字段级核对。

旧合同和结算域已退役，不属于当前前端契约。

## 契约维护要求

新增或变更浏览器接口时，在所属域的版本化契约中记录：方法和完整路径、必填/可选参数与约束、请求/响应 schema 和样例、成功 HTTP 状态、错误 envelope/code、权限及资源范围、分页/幂等/副作用、Gateway 路由状态。然后在本文件的域目录登记链接和覆盖状态。遇到无法从代码确认的语义应标注“待确认”，不得补猜测值。

MVC 路径变量及查询参数必须显式写绑定名；接口变更需同步契约与 Controller/DTO。源码参数绑定约定见 KI-251。
