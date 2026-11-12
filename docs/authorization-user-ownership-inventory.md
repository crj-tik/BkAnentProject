# 平台用户归属与业务身份盘点

核查范围：Auth、Customer、Listing、Marketing、通知、平台运营、Interview，以及 REST、Dubbo/RPC、MCP、RAG、A2A 和消息入口。`owner_user_id` 只表示平台账号负责人；`broker_id`、`store_id`、`employee_id`、`payee_employee_id` 继续保留其业务实体语义。

## 字段语义与处理

| 域/记录 | 当前字段 | 真实含义 | 迁移决定 |
| --- | --- | --- | --- |
| Auth 用户 | `user_account.id` | 平台身份主键 | 唯一 actor 来源；从已验签 Auth 上下文取值 |
| Auth 经纪人映射 | `auth_user_business_identity(BROKER)` | Auth 管理的用户到经纪人业务主体映射 | 继续保留；只可经受控 Auth 管理接口维护，不接受请求体或模型输出 |
| Auth 门店映射 | `auth_org_unit.store_business_id` / 旧 `auth_user_store_identity.store_id` | 门店组织与业务门店 ID 的明确映射/兼容值 | 仅一对一显式绑定；不能因数字相同自动关联 |
| Customer 客户档案 | `customer_profile.broker_id` | 当前负责经纪人业务主体 | 新增 `owner_user_id`；新建由已验签 actor 写入。旧记录只以证据账本回填，未回填记录保留受控 broker 兼容读取 |
| Customer 跟进 | `customer_follow_record.broker_id` | 跟进记录作者的经纪人业务主体 | 新增 `owner_user_id` 表示作者；新建从 actor 派生。客户档案归属仍决定客户内历史跟进的可见范围 |
| Customer 买方咨询 | `customer_listing_inquiry.broker_id` | 创建咨询时的负责经纪人业务主体 | 新增 `owner_user_id` 表示创建/负责该咨询的平台用户；新建从 actor 派生 |
| Customer 收藏 | `customer_favorite_listing.customer_id` | 内部客户档案关联的收藏 | 不改成 Auth 用户收藏；当前不是平台用户账号关系 |
| Customer 委托 | `owner_entrust_record.customer_id/listing_id/contract_no` | 业主档案、房源及委托合同关系 | 不按创建人推导负责人；关系和状态继续按客户/房源权限及合同业务校验 |
| Listing 房源 | `listing_info.broker_id` | 当前负责经纪人业务主体 | 新增 `owner_user_id`；新建由已验签 actor 写入，既有公开查询范围保留 |
| Listing 素材/价格/状态 | `listing_asset.created_by`、`operator_user_id` | 创建或执行具体变更的 Auth 用户 | 已是用户归属/审计字段，不替换为 brokerId |
| Marketing 内容 | `marketing_content.owner_broker_id` | 经纪人业务主体 | 保留业务字段并新增 `owner_user_id`；新建由验签 actor 写入，own 查询优先 userId，旧行受控 broker 兼容 |
| 营销活动/发布请求 | `owner_user_id` | 平台用户发起人/负责人 | 已使用 userId；保持写入来自已验签上下文 |
| 通知 | `recipient_user_id` | 平台通知收件账号 | 保持 userId。不能把 brokerId 或模型输出当收件人 |
| 平台运营员工统计 | `employee_id` | 员工/绩效事实的独立业务主体 ID | 不直接映射为 `user_account.id`；待员工权威名册与可审计映射存在后另行建立 |
| 结算 | `payee_employee_id`、`employee_id` | 财务事实中的收款员工及结算主体 | 保持原字段和既有强门禁；不以登录用户、姓名或数字相同推导收款关系 |
| Interview | `creator_work_no`、参与者 | 工作号及面试/评估参与关系 | 已有 `created_by_user_id` 只用于确认为 Auth actor 的创建归属；工作号仍是独立字段 |
| RAG/MCP/RPC/A2A | 签名 `actorId`、权限/来源上下文 | 调用者身份与能力证明 | 在每个传输边界验证签名；LLM/tool 参数中的 userId、brokerId、storeId 均不改变 actor |

## 新旧读写规则

- REST 新建 Customer 档案、跟进、咨询和 Listing 时，`owner_user_id` 由验签请求上下文的 actor 写入。API 参数中的 brokerId 仅兼容性校验，不能设置负责人。
- 受限 own 操作优先比较非空 `owner_user_id` 与签名 actor；只有 `owner_user_id IS NULL` 的旧行才走受信 `BROKER` 映射兼容。精确 all 权限仍由角色授权链决定。
- 自有列表查询使用 `(owner_user_id = actor) OR (owner_user_id IS NULL AND broker_id = trustedBroker)`，避免一个 broker 数值下 owner 已明确属于他人的数据被旧查询带出。
- Dubbo/RPC、MCP、RAG 和 A2A 的 actor 必须来自经过验证的上下文；服务间重复 subAgent 调用继续本地校验同一受限上下文，不从工具输入重新解析身份。
- 新 owner 列可空是兼容迁移状态，**不代表**未知历史归属已授权。无法证明的旧行维持隔离/既有兼容门禁，不自动补值。

## 历史回填账本与并发保护

Customer、Listing 和 Marketing 各自有 owner migration ledger，按记录类型和记录 ID 唯一标识候选映射；只有 `status=VERIFIED`、候选与复核用户仍存在、复核用户和证据引用齐全的行可应用。应用语句必须同一事务执行，并同时满足 `owner_user_id IS NULL`、对应旧 broker 字段与账本旧值一致，以及同一 broker 的未拒绝候选只有一个 userId，成功后原子地将账本记为 `APPLIED`。并发改动或候选冲突导致条件不符时不覆盖业务行，账本保持待处理并进入人工复核。`scripts/migrations/report-user-owner-state.sql` 提供只读空负责人、账本状态和候选冲突统计。

不能按用户名、姓名、工号、ID 数字相等或当前数据行最近修改者猜测历史 owner。对每个 brokerId 先核对 Auth 当前映射、历史映射审计和业务事实；出现一对多、映射变更、失活账号或缺少审计证据时，记为 `REJECTED`/人工处理而不回填。

## 运行前对账

升级后按域统计空 owner 与账本状态。部署代码前先应用受管 manifest，再发布同时支持新列的代码。只有以下条件全部满足后，才能在对应域关闭旧 broker 兼容读取或退役 broker 字段：账本无未处理冲突、已回填行数与独立审核报告一致、所有 REST/RPC/MCP/RAG/A2A/消息消费者均支持 userId、授权撤销和失败回退已验收。收藏、委托、员工、门店、收款字段不随平台负责人字段一起退役。

## 调用面清点索引（OpenSpec 任务 1.2）

| 身份/业务字段 | 数据与事实维护方 | REST/入口 | RPC、MCP、RAG、A2A 与异步消费者 | 授权含义/处理 |
| --- | --- | --- | --- | --- |
| Auth `userId`、显式角色、权限码 | `auth-service`：`user_account`、`auth_user_role`、`auth_role_permission`、`auth_user_business_identity`；`AuthorizationRepository` 计算快照 | `AuthController`、`UserManagementService`、`RoleAuthorizationManagementController`、`OrganizationManagementController` | `AuthAuthorizationRpcServiceImpl` 签发请求级 snapshot/context/freshness；`A2aBeaconStore`、`RagPrincipalRpcServiceImpl` 消费角色派生身份 | 唯一可信 actor 由已验凭据/签名上下文产生；request body、tool 参数、提示词中的 userId 不得覆盖。Graph 内多次 subAgent 调用复用同一原始上下文，各传输入口本地验签，按既有版本刷新与硬截止失效，不对每个节点访问 Auth。 |
| Customer `broker_id` 与新增 `owner_user_id` | `customer_profile`、`customer_follow_record`、`customer_listing_inquiry`；收藏通过 customer 档案关联，委托保留 customer/listing/contract 关系 | `CustomerController`、`CustomerInquiryController` | `CustomerRpcServiceImpl`、`CustomerProfileVerifyRpcServiceImpl`、Customer MCP/A2A agent；客户提醒由 `CustomerReminderServiceImpl` 调 `CustomerNotificationPermitClient` | broker 是经纪人业务主体；平台本人数据优先 userId。私有 RPC 验签后仍按 owner 和业务状态过滤。收藏/委托不按创建 actor 重写；提醒只读已存 ownerUserId，历史缺 owner 不发送。 |
| Listing `broker_id` 与新增 `owner_user_id` | `listing_info`；素材/价格/状态记录另有 Auth operator/creator 字段 | `ListingController`、`ListingBusinessSourceController`、`ListingComparisonController` | `ListingMasterRpcServiceImpl`、`ListingBusinessSourceRpcServiceImpl`、`ListingInquiryVerifyRpcServiceImpl`、`RagListingDomainRpcServiceImpl`、`ListingTools`、`ListingMcpTools`、`ListingRagIdentity`、`ListingRagGrantService`；Marketing/Customer 经签名 RPC 读取 | 在售摘要保留现有公开范围；own 详情/写入使用 userId owner；显式 owner 不因 broker 数字相同而命中旧范围。`legacy-local` 和可选 HTTP source 均在消费端执行 owner 优先过滤；HTTP source 还要求回传 owner 证据并本地复核。 |
| Marketing `owner_broker_id` 与新增 `owner_user_id` | `marketing_content`；campaign plans/publish requests 已用 `owner_user_id` | `MarketingContentController`、`CampaignController` | `MarketingContentRpcServiceImpl`、`MarketingMcpTools`、`MarketingTools`、Marketing A2A、`MarketingContentSearchServiceImpl` 的 MySQL/Elasticsearch 查询、Listing snapshot client | 内容/计划负责人来自 Auth actor；经纪人业务字段保留。搜索两种后端统一 owner 条件。活动审批、渠道发布仍由显式受限权限点控制。 |
| Auth `STORE` 与业务 `storeId` | Auth `auth_org_unit.store_business_id` 显式唯一映射；`auth_user_store_identity` 为旧签名兼容 | Auth 组织/成员管理 API | `VerifiedStoreScopeAdapter` 及签名授权上下文供下游读取；Platform Operations 的 `BusinessController`、`ReviewController`、`PlatformOperationsAnalyticsMcpTools`、`KnowledgeRepository` 消费各自业务 storeId | 组织 store 与业务 store 必须显式一对一关联。门店成员仅 primary、有效、映射完整的关系派生 STORE；运营部门/次成员不产生门店授权。平台经营和知识范围仍按自身业务规则校验 storeId。 |
| `employee_id`、`work_no`、`payee_employee_id`、`contract_id` | Platform Operations 经营/绩效事实；Interview 使用工作号/参与者事实；员工/收款/合同权威映射尚未证实 | `BusinessController`、Interview `InterviewController`/`InterviewAssetController` | `AnalyticsTools`、`PlatformOperationsAnalyticsMcpTools`、`InterviewTools`/`InterviewMcpTools`；结算/合同副作用仍受 KI-204/KI-206 约束 | 都是独立业务主体字段，不能由 userId、姓名、同号 ID 或模型输出反推。员工、收款、合同关系未有可审计权威映射前，相关宽域读取和副作用继续保持既有门禁。 |
| Notification `recipient_user_id` | `notification_record`；事件 consumer 保存服务端 actor/recipient | `NotificationController` | `NotificationRpcServiceImpl` 拒绝无证明的旧发送方法；Customer 特定提醒使用 Customer 公钥签名的窄 permit；`NotificationMcpTools` 本人读取按 actor 收件人过滤 | 收件人始终是 Auth userId；禁止 brokerId、工具字段或模型内容指定他人。签名提醒只覆盖固定 operation/scene、内容摘要和幂等键，不作为通用服务发送凭据。 |

数据库候选关系见 `sql/migrations/20261111_*_user_owner.sql` 与 owner ledger；REST/Dubbo/MCP/A2A/RAG 源索引见上表。仓库当前没有可验证的 employee/payee/contract 权威目录，未闭环能力保持关闭。
