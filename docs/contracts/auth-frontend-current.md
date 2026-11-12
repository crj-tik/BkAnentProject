# 认证与授权管理接口（代码基线 d56e521b）

依据 Auth 控制器、请求/响应模型及授权服务整理。路径均相对于 Gateway；实际可用性取决于部署配置。`/auth/authorization/**` 仅在 `auth.authorization.enabled=true` 时注册。

## 约定

除登录、刷新及健康检查外，访问受保护接口时发送 `Authorization: Bearer <accessToken>`。登录和刷新使用 JSON，返回 `ApiResponse<T>`：`{success, code, message, data}`，成功 `code="0"`，失败 `data=null`。登录/刷新参数或凭据错误由控制器返回 `success=false`，应同时检查 HTTP 状态和响应体；用户管理验证错误可能返回 HTTP 400，认证失败 401，权限不足 403。授权管理控制器返回原始对象或数组，不使用 `ApiResponse` 包装；错误使用 HTTP 状态，错误体不能假定与其他接口相同。

## 登录与用户

| 方法和路径 | 输入 | 成功响应 `data` / 影响 |
| --- | --- | --- |
| `POST /auth/login` | JSON `username`、`password`；模型还包含 `tenantCode`，当前登录实现未用它筛选账号 | `AuthToken`；空用户名或密码返回 `AUTH_400`，账号或密码无效返回 `AUTH_401` |
| `POST /auth/refresh` | JSON `refreshToken` | `AuthToken`；刷新后保存返回的新 access/refresh token。刷新实现针对同秒产生相同 token 的情况不会撤销相同字符串 |
| `POST /auth/logout` | Authorization 请求头可选 | `null`；撤销提供的 token |
| `GET /auth/me` | access token | `CurrentUser` |
| `GET /auth/roles` | 管理员 access token | `RoleOption[]` |
| `GET /auth/users` | 管理员；query `page`、`pageSize` 默认 1、20；可选 `keyword`、`roleCode`、`accountStatus` | `PageResult<UserItem>` |
| `POST /auth/users` | 管理员；JSON `CreateUser` | HTTP 201，`UserItem` |
| `PUT /auth/users/{userId}` | 管理员；JSON `UpdateUser` | `UserItem` |
| `PUT /auth/users/{userId}/password` | 管理员；JSON `{password}`，长度 8–72 | `null` |
| `DELETE /auth/users/{userId}` | 管理员 | `null` |
| `GET /auth/health` | 无业务参数 | 服务健康信息 |

版本化授权启用时，用户管理要求业务管理员和 `auth.users.manage`；关闭时使用 `ADMIN` 角色。禁止前端自行声称管理员身份。

### 数据结构

- `AuthToken`：`accessToken:string`、`refreshToken:string`、`userId:integer`、`roleCode:string`、`displayName:string`、`roleName:string`、`tenantCode:string`、`businessAdmin:boolean`、`roles:string[]`、`permissionRevision:integer`、`credentialEpoch:integer`。非版本化模式中后三项使用默认值。
- `CurrentUser`：`userId`、`username`、`displayName`、`roleCode`、`roleName`、`tenantCode`、`businessAdmin`、`roles`、`permissionRevision`、`credentialEpoch`。
- `UserItem`：`CurrentUser` 同名字段，以及 `accountStatus`、`createdAt`。`RoleOption`：`roleCode`、`roleName`、`description`。`PageResult<T>`：`records:T[]`、`total`、`page`、`pageSize`。
- `CreateUser`：`username` 必填，匹配 `[A-Za-z0-9._-]{3,64}`；`password` 必填，8–72 字符；`displayName` 必填、最多 64；`roleCode` 必填、最多 32；`tenantCode` 必填、最多 64。
- `UpdateUser`：`displayName`、`roleCode`、`tenantCode` 均必填且长度约束同上；`accountStatus` 必填，取 `ACTIVE` 或 `DISABLED`。

## 版本化授权管理

以下路径以 `/auth/authorization` 为前缀。均要求 Bearer access token；版本化授权下还须由 Auth 快照确认管理员身份，表中权限码是附加的精确能力门槛。普通角色即使误获管理权限码，也不能访问或变更这些管理资源。

| 方法和路径 | 输入 | 输出 | 权限及规则 |
| --- | --- | --- | --- |
| `GET /catalog` | 无 | `Permission[]` | `auth.roles.manage` |
| `GET /roles` | 无 | `Role[]`，最多 1000 条 | `auth.roles.manage` |
| `POST /roles` | `RoleDefinition` | `ManagementResult` | `auth.roles.manage`；权限码必须在活动目录中 |
| `PUT /roles/{roleId}` | `RoleDefinition` | `ManagementResult` | `auth.roles.manage`；角色 code 不可修改 |
| `DELETE /roles/{roleId}` | 无 | `ManagementResult` | `auth.roles.manage` |
| `GET /users/{userId}/roles` | 无 | `UserRoleGrant[]` | `auth.users.manage` |
| `PUT /users/{userId}/roles` | `{grants:UserRoleGrant[]}` | `ManagementResult` | `auth.users.manage`；全量替换，最多 100 项 |
| `PUT /users/{userId}/business-admin` | `{enabled:boolean}` | `ManagementResult` | `auth.users.manage` 且业务管理员；不能撤销自己的管理员标记 |
| `GET /users/{userId}/business-identities` | 无 | `BusinessIdentity[]` | `auth.business-identity.manage` 且业务管理员 |
| `PUT /users/{userId}/broker-identity` | `{brokerId:integer|null}` | `{userId,brokerId,eventId,versions}` | 同上；null 撤销，正整数绑定 |
| `PUT /users/{userId}/store-identity` | `{storeId:integer|null}` | `{userId,storeId,eventId,versions}` | 同上；null 撤销；一人最多一个门店，同一门店可有多人 |

`Permission={code,domain,operation,active,version}`；`RoleDefinition={code,name,description,active,assignable,permissionCodes}`；`Role` 在此基础上含 `id`；`UserRoleGrant={roleId,validFrom,validUntil}`，时间为 Instant，可为空；`BusinessIdentity={type,identityId}`；`ManagementResult={targetId,eventId,versions,publicationConfirmed}`；`versions` 元素为 `{userId,permissionRevision,credentialEpoch,status}`。角色和身份变更会推进相关用户授权版本；`publicationConfirmed=false` 不能理解为广播已确认。

来源：`AuthController`、`AuthUserManagementModels`、`RoleAuthorizationManagementController`、`RoleAuthorizationManagementService` 和 `AuthorizationRepository`。实际网关行为及部署开关需与目标环境核对。
