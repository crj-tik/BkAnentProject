## MODIFIED Requirements

### Requirement: 服务身份与最终用户授权先于召回

每次检索 SHALL 验证调用服务、有效版本用户上下文、空间和操作交集，不能从模型userId、固定marker或服务发现推断身份。账号停用/删除及角色授权失效按统一通知与最多30秒兼容窗口处理，已知失效、硬到期及新入口必须拒绝，不逐召回或工具重复Auth解析。业务管理员由可信businessAdmin标记而非ADMIN角色决定，普通用户通过有效多角色取得显式操作；旧ADMIN/BROKER只作受控迁移映射，未知角色不自动获权限。业务管理员房源全读未删除项含非ON_SALE，普通获准用户默认仅ON_SALE；scope用签名businessWide/activeOnly和操作许可，RAG独立验证新授权事实及领域范围，不用空列表或通配。个人偏好和Memory读写绝对self-only，包括业务管理员；后台写身份没有通用用户读取权。不可覆盖授权AND过滤等价施于双路，未授权正文不得重排或输出，资源删除/来源/grant撤回仍按领域当前真源处理。

#### Scenario: ADMIN与普通成员房源基线
- **WHEN** 可信businessAdmin或普通多角色获准用户搜索房源
- **THEN** 管理员可检索全部未删除项，普通用户仅ON_SALE；RAG核签名scope/操作/授权版本而非单roleCode，不要求全量资源枚举

#### Scenario: 管理员跨用户偏好或遗留角色
- **WHEN** businessAdmin指定其他owner，或未知/旧角色声称管理范围，或已知用户授权失效
- **THEN** 操作拒绝，双路及重排不接触未授权正文，不凭管理员或旧token豁免

#### Scenario: 搜索其他用户记忆
- **WHEN** 用户A请求指定用户B的owner或省略owner条件
- **THEN** 拒绝扩权或追加不可覆盖A范围，两路及重排不读B文档

#### Scenario: 伪造调用方或认证依赖失效
- **WHEN** 新边界仅有伪造marker、服务名或userId，或首次授权依赖不可用
- **THEN** 身份/授权失败且后端调用为零，不报告空结果；仅既有有效上下文可在统一兼容窗继续

#### Scenario: Administrator has no roles assigned
- **WHEN** 有效businessAdmin没有角色但请求登记的空间管理或业务读取
- **THEN** 按管理员内建操作权限和领域范围处理，个人Memory self-only和服务ACL仍生效
