# 授权与业务身份迁移基线

核查时间：2026-11-10。仅为实施前观察，不保证后续运行环境与数据不变。应用及数据迁移前必须重新采集并生成逐用户、逐权限的对账结果，不导出凭据。

## 现有变更与部署交接（任务 1.3）

- `unify-versioned-permission-context` 当前 60/65；剩余 8.3、8.4（浏览器/前端冻结）、11.1、11.4、11.6（真实环境与回退验收）。本变更不更改其任务勾选。
- 旧 change 的 `authentication-baseline`、`versioned-authorization-context`、`isolated-rag-retrieval`、`domain-rag-consumption` 等规范允许可信 `businessAdmin` 不依赖角色获取全部有效受限权限，且要求签名 BROKER/STORE 映射。新 change 要求仅通过角色授权及逐域迁移 userId，两者是明确的版本演进关系，不能并行作为同一运行版本的互不限定规则。切换前需协调旧 change 的协议/归档及消费者，不能仅更新主规范或只修改 Auth。
- WSL Ubuntu 开发栈 Auth、Gateway、Agent 运行镜像为 `bk-anent-services:0af714bca7ee1d41eee7b2151105e37ba700a24a`；容器环境中 Auth 授权、Auth Redis 授权、Gateway 授权和 Agent 授权标记均为 `true`。这是容器环境观察，不单独证明每个 Nacos 属性或业务入口均已启用。
- 工作区 HEAD 在盘点时为 `ff3be6d2`，开发服务镜像并非该 HEAD；部署前需核对制品来源与并发会话，不能把源码事实直接当成容器行为。
- 实施顺序：保留旧协议和运行范围 → 盘点所有消费方及原权限 → 建立角色和管理员权限逐项对账 → 兼容部署及影子验证 → 成组切换授权事实和域消费者 → 完成实际失效、回退和跨服务验收后才退役旧字段。旧 change 未完成的验收不因本设计而跳过。

## 管理员基线（任务 1.1 进行中）

- 开发库查询结果：2 个有效 `business_admin` 用户；有效权限目录 125 条；两个用户均有至少一个有效角色，但按角色关联到的有效目录项计数均为 0。当前 `EffectivePermissionService` 将有效管理员直接赋予活动受限目录项，因此直接移除管理员分支会造成显著权限缺失。125 是目录计数，不等同于最终快照中的权限数量：基础操作需要按 `AuthzOperationPolicy` 排除。
- 管理员授权入口：`EffectivePermissionService` 直接扩权；`AuthAccountMutationService`、`RoleAuthorizationManagementService` 和 `AuthController` 还有直接标记检查和最后管理员计数。`A2aBeaconStore`、`RagPrincipalRpcServiceImpl` 用其影响角色有效性。
- 共享协议和消费点：`AuthzRequestScope`、`VerifiedDomainAuthorization`、`AuthorizationSnapshot` 与签名/哈希；Customer 的 controller、管理服务、咨询服务及 Listing 客户端；Listing 的管理、RPC、RAG identity/grant；RAG、Memory 的 principal/scope 签名；Agent、Marketing、Media 的 RAG 或 Listing 客户端。逐调用点需区分权限授予、业务宽范围选择、签名兼容和数据传输，不能统一简单删除判断。
- 尚需输出各管理员现有快照与目标管理角色逐权限差异、角色到期和目录基线排除结果；继续搜索非 Java 协议、SQL、配置及运行部署消费者。计数仅作为现状证据，不是迁移前后权限等价证明。

## 门店与业务身份初查（任务 1.2/1.4 进行中）

- 开发库当前 BROKER 映射 1 条、STORE 映射 0 条。`auth_user_store_identity` 每用户最多一门店，同一门店可有多个用户；该表本身没有门店主数据或可追溯来源，不能证明任一数字代表真实门店。
- Customer 的 `customer_profile` 及跟进/委托/收藏/咨询等数据、Listing 的 `listing_info` 和多个派生记录使用 brokerId；其字段语义、独立业务主体与历史变更须逐表核验。平台运营存储 `employee_kpi_stat`、`employee_daily_workload`、`store_dashboard_snapshot` 和门店复盘事实，但这些事实表不能独自证明员工/门店主数据及其与账号的关系。
- 当前初查未发现公司、部门、员工或门店的完整主数据表；这不是全仓及外部系统权威性结论。不存在可验证来源时不能给旧 STORE 分配组织节点；新建开发样例须建立明确的组织门店与 storeId 映射，避免同号推断。KI-204/206 的受限能力维持原门禁。

## 本人档案部分实现（任务 3.1 未完成）

- 新增 `PATCH /auth/me/profile`，从 access token 确定 actor，仅接受非空 `displayName`，拒绝 userId、角色等其他字段。数据库更新仅作用于有效本人账号，显示名更新不改变角色或授权版本。
- `AuthControllerTest` 与 `AuthorizationUserInfoTest` 定向 13 项通过，覆盖身份字段伪造拒绝、本人字段更新及已停用账号拒绝；测试未覆盖真实 Gateway 请求。
- `/auth/me` 的组织摘要、组织成员管理与开发栈 API 验收尚未实现，因此任务 3.1 不勾选，服务也尚未部署。

## 组织表部分实现（任务 2.1/2.2 未完成）

- 新增 `auth_org_unit`、`auth_org_membership` 及受管 manifest 条目 `auth-organization-20261110-v1`；未变更现有账号、角色和授予数据。表结构约束节点类型、父节点存在、门店业务 ID 唯一、成员关联及时间区间；单一主归属、树循环和同公司规则仍需管理服务的事务锁校验，不能仅凭表结构声称已完成。
- 已在 WSL 开发栈通过受管 runner 执行 upgrade 和重复 upgrade，后者验证迁移已应用；未进行 fresh 数据库演练。组织数据、管理 API、角色分类及成员失效/outbox 仍未实现，因此任务 2.1/2.2 保持未完成。
