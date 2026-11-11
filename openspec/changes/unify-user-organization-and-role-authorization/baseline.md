# 授权与业务身份迁移基线

核查时间：2026-11-10。仅为实施前观察，不保证后续运行环境与数据不变。应用及数据迁移前必须重新采集并生成逐用户、逐权限的对账结果，不导出凭据。

## 现有变更与部署交接（任务 1.3）

- `unify-versioned-permission-context` 当前 60/65；剩余 8.3、8.4（浏览器/前端冻结）、11.1、11.4、11.6（真实环境与回退验收）。本变更不更改其任务勾选。
- 旧 change 的 `authentication-baseline`、`versioned-authorization-context`、`isolated-rag-retrieval`、`domain-rag-consumption` 等规范允许可信 `businessAdmin` 不依赖角色获取全部有效受限权限，且要求签名 BROKER/STORE 映射。新 change 要求仅通过角色授权及逐域迁移 userId，两者是明确的版本演进关系，不能并行作为同一运行版本的互不限定规则。切换前需协调旧 change 的协议/归档及消费者，不能仅更新主规范或只修改 Auth。
- WSL Ubuntu 开发栈 Auth、Gateway、Agent 运行镜像为 `bk-anent-services:0af714bca7ee1d41eee7b2151105e37ba700a24a`；容器环境中 Auth 授权、Auth Redis 授权、Gateway 授权和 Agent 授权标记均为 `true`。这是容器环境观察，不单独证明每个 Nacos 属性或业务入口均已启用。
- 工作区 HEAD 在盘点时为 `ff3be6d2`，开发服务镜像并非该 HEAD；部署前需核对制品来源与并发会话，不能把源码事实直接当成容器行为。
- 实施顺序：保留旧协议和运行范围 → 盘点所有消费方及原权限 → 建立角色和管理员权限逐项对账 → 兼容部署及影子验证 → 成组切换授权事实和域消费者 → 完成实际失效、回退和跨服务验收后才退役旧字段。旧 change 未完成的验收不因本设计而跳过。

## 管理员基线（任务 1.1 已盘点）

- 开发库查询结果：2 个有效 `business_admin` 用户；有效权限目录 125 条；两个用户均有至少一个有效角色，但按角色关联到的有效目录项计数均为 0。当前 `EffectivePermissionService` 将有效管理员直接赋予活动受限目录项，因此直接移除管理员分支会造成显著权限缺失。125 是目录计数，不等同于最终快照中的权限数量：基础操作需要按 `AuthzOperationPolicy` 排除。
- 管理员授权入口：`EffectivePermissionService` 直接扩权；`AuthAccountMutationService`、`RoleAuthorizationManagementService` 和 `AuthController` 还有直接标记检查和最后管理员计数。`A2aBeaconStore`、`RagPrincipalRpcServiceImpl` 用其影响角色有效性。
- 共享协议和消费点：`AuthzRequestScope`、`VerifiedDomainAuthorization`、`AuthorizationSnapshot` 与签名/哈希；Customer 的 controller、管理服务、咨询服务及 Listing 客户端；Listing 的管理、RPC、RAG identity/grant；RAG、Memory 的 principal/scope 签名；Agent、Marketing、Media 的 RAG 或 Listing 客户端。逐调用点需区分权限授予、业务宽范围选择、签名兼容和数据传输，不能统一简单删除判断。
- 全仓生产 Java、SQL、YAML 中 `businessAdmin/business_admin/BUSINESS_ADMIN/isBusinessAdmin/isAdmin/ADMIN` 相关文件清单见同目录 `business-admin-consumers.txt`，后续按清单逐处区分权限决策、业务宽范围、签名字段及旧模式兼容，并补充后续新引入消费者。
- `scripts/acceptance/audit-auth-admin-roles.py` 从本地实际 Gateway 读取权限目录、角色和所有用户，并排除源码 `AuthzOperationPolicy` 中的基础操作；核对两次列表无可见变动后给出每位管理员的旧受限权限、当前有效角色授予和逐权限缺口。2026-11-11 连续两次对账内容一致：2 个管理员均有 125 个受限目录项而现有有效角色授予 0 项，完整逐项结果位于本机 `runtime-logs/diagnostics/2026-11-11/auth-admin-role-audit-20261111.json`，未提交用户数据或凭据。该脚本要求已部署运行的 Auth 与当前源码基线一致，报告是迁移前快照；切换前必须重新对账并额外验证到期边界。

## 门店与业务身份初查（任务 1.2 进行中；1.4 已盘点）

- 开发库当前 BROKER 映射 1 条、STORE 映射 0 条。`auth_user_store_identity` 每用户最多一门店，同一门店可有多个用户；该表本身没有门店主数据或可追溯来源，不能证明任一数字代表真实门店。
- Customer 的 `customer_profile` 及跟进/委托/收藏/咨询等数据、Listing 的 `listing_info` 和多个派生记录使用 brokerId；其字段语义、独立业务主体与历史变更须逐表核验。平台运营存储 `employee_kpi_stat`、`employee_daily_workload`、`store_dashboard_snapshot` 和门店复盘事实，但这些事实表不能独自证明员工/门店主数据及其与账号的关系。
- 仓库 MySQL schema 和 Java 实体/mapper 检索未发现独立公司、部门、员工或门店主数据表；平台经营的数据是分析或复盘事实，并不负责维护门店名册。外部系统是否存在门店名册尚未得到证据。现有 `setStoreIdentity` 只校验 storeId 为正、用户有效并记录审计，没有核实门店存在；`VerifiedStoreScopeAdapter` 将 Auth 签名 STORE 与请求 storeId 比较。不能由这条映射倒推出门店主数据。
- 2026-11-10 开发库核对：`auth_user_store_identity` 0 行、`store_review_matter` 0 行、`auth_org_unit` 中 STORE 0 行，开发库当前无待回填门店映射；这不证明其他环境或外部来源为空。
- 迁移处理：先将新建组织门店登记为 Auth 受控真源，并以显式 `store_business_id` 唯一约束关联业务 storeId。新 storeId 必须由服务端持久分配，查重已知旧 ID 和已绑定 ID，不能根据组织节点的数字相同推断。其他环境逐条导出旧映射，以权威名册或可审计的管理员核实证据建立旧 storeId→组织门店一对一账本；无证据、冲突、无效账号或停用门店列为待处理，不导入有效成员或授权。跨用户共享同一门店应落在同一已验证组织节点，不能重复创建不同门店。
- 过渡期间继续由旧签名 STORE 支撑现有消费者；仅在已确认的成员关系与旧映射一致、成员变动可事务更新授权版本/失效并覆盖到期边界后，按用户切换来源。`auth_user_store_identity` 保留至消费者、对账和回退验收完成。KI-204/206 的受限能力维持原门禁。

## 本人档案实现（任务 3.1 已完成）

- 新增 `PATCH /auth/me/profile`，从 access token 确定 actor，仅接受非空 `displayName`，拒绝 userId、角色等其他字段。数据库更新仅作用于有效本人账号，显示名更新不改变角色或授权版本。
- `AuthControllerTest` 与 `AuthorizationUserInfoTest` 定向 13 项通过，覆盖身份字段伪造拒绝、本人字段更新及已停用账号拒绝；测试未覆盖真实 Gateway 请求。
- `/auth/me` 返回仅本人当前有效组织摘要，保留旧字段与构造器；H2 和本人跨用户隔离测试已覆盖。Auth 模块 222 项测试通过；上游 common 回归中另一会话未提交的 `RagVerifyDubboSerializationTest` 在 STRICT 序列化白名单下有 3 个错误，排除该类后 common + Auth 构建通过，不能称为全量通过。已部署开发 Auth 镜像并通过 Gateway 调用 `/auth/me`；Graph 及业务域完整 API 验收属于任务 5.4。

## 组织表部分实现（任务 2.1/2.2 未完成）

- 新增 `auth_org_unit`、`auth_org_membership` 及受管 manifest 条目 `auth-organization-20261110-v1`；未变更现有账号、角色和授予数据。表结构约束节点类型、父节点存在、门店业务 ID 唯一、成员关联及时间区间；单一主归属、树循环和同公司规则仍需管理服务的事务锁校验，不能仅凭表结构声称已完成。
- 已在 WSL 开发栈通过受管 runner 执行 upgrade 和重复 upgrade，后者验证迁移已应用；未进行 fresh 数据库演练。组织管理尚未完成，角色分类和成员失效/outbox 仅部分实现，因此任务 2.1/2.2 保持未完成。

## 角色分类部分实现（任务 3.3 未完成）

- 受管迁移 `auth-role-type-20261111-v1` 已在开发库完成 upgrade 和重复执行校验；既有角色默认 `UNCLASSIFIED`，不从 ADMIN、BROKER 等名称推断类别。新增角色分类修改入口只允许 `JOB`、`MANAGEMENT`、`UNCLASSIFIED`，并沿用角色写锁、版本/outbox 与审计；分类仅为元数据，不授予权限或管理员标记。
- Auth 模块 224 项通过；common 中另一会话未提交的已知序列化测试按前述范围排除。分类接口尚未部署、既有及验收角色尚未显式分类；自动隐性角色、管理员由角色授权及最后管理员保护仍未实现，任务 3.3 未勾选。
- Auth 新增纯角色权限影子计算，用与普通用户一致的有效角色、目录、基础操作排除和生效/到期边界计算管理员的候选集合；它不更改当前签名快照。定向 31 项测试通过，覆盖旧管理员特权与影子角色缺口、未来生效和已停用账号。此计算尚未用于生产切换或自动赋权。
- 开发库经受管迁移 `auth-prepare-management-role-20261111-v1` 准备 `PLATFORM_ADMIN`：当前 125 项受限权限已显式绑定角色，该角色不可一般分配且没有绑定任何用户。迁移与重复 upgrade 校验通过；两位管理员的实际角色授权缺口仍是 125，影子假设加入待迁移角色后缺口均为 0。旧标记的授予/撤销联动及最后管理员保护尚未全部完成，因此不得提前分配该角色或切换鉴权；目录新增权限后需重新对账和显式授予。
- 旧 `businessAdmin` 撤销源码已增加同事务移除 `PLATFORM_ADMIN` 关系和重置授予边界，角色权限不会因撤销标记而残留；14 项角色管理测试通过，包含审计失败时标记、角色及权限版本整体回滚。此保护尚未部署；授予同步、其他入口和最后管理员保护仍未完成，不可据此绑定准备角色。
- 在只包含提交 `a63702ea` 的隔离工作树中，完整 common 192 项与 Auth 224 项测试通过，排除了主工作区其他会话未提交文件；角色分类新镜像构建时 Docker 构建连接中断，WSL Docker 服务显示 active 但内存与 swap 接近耗尽，当前开发 Auth 仍运行此前 `auth-org-f08ae55f` 镜像。

## 组织管理部分实现（任务 3.2 未完成）

- 新增受现有 `auth.users.manage` 权限保护的组织节点创建及管理列表接口。公司只能作为根节点，门店和部门必须属于有效公司；创建使用授权写锁和审计，组织代码冲突拒绝。此阶段不接受客户端提交 storeId，也不产生组织成员或门店授权。
- 新增初始主归属登记、结束和成员列表：有效用户可被管理人员关联到门店或部门，变更在事务内递增权限版本、写 outbox 和审计；已有旧 STORE 授权的用户须等待可信迁移。此记录不生成 STORE 授权。`OrganizationManagementControllerTest` 覆盖节点层级、审计、无权限、重复成员、版本与 outbox、结束及旧映射冲突。成员到期调度和门店授权范围仍未接通，故任务 3.2 不勾选；结束接口尚未部署至开发栈。

## 开发样例与部署（任务 5.1/5.2 已完成）

- `scripts/acceptance/bootstrap-auth-organizations.py --apply-development` 显式在本地 Gateway 创建公司 1、门店 2、运营部门 3；经纪人 userId 4 和运营职能人员 userId 5 分别为两个子节点的成员。两角色 `ACCEPTANCE_BROKER` / `ACCEPTANCE_OPERATIONS` 均显式授予且不包含受限权限；不能因此推断门店或经纪人私有业务授权。
- 两组验收账号凭据保存在被 Git 忽略的 `.env` 的 `ORG_ACCEPTANCE_BROKER_*` 和 `ORG_ACCEPTANCE_OPERATIONS_*` 键中；脚本不输出密码或 token。脚本已重复运行，确认对象不重复创建，并验证非管理员无法访问组织管理及不能伪造本人档案 userId。
- 当前开发 Auth 容器使用 `bk-anent-services:auth-org-f08ae55f`，基于先前镜像替换 `f08ae55f` 对应 Auth jar；Gateway、Agent 及其他服务未重建。版本仅在本次 Compose 启动时覆盖，没有改 `.env` 的全局 `APP_VERSION`；后续以旧全局版本重建 Auth 可能恢复旧镜像，需显式使用此版本或按后续发布流程更新。
