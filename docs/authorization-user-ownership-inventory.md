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

## 各服务实际用户 ID 字段清单

本节按当前源码和受管 SQL migration 逐表记录“用户 ID 表示谁、为什么要保存、值从哪里来”。
`Auth userId` 均指 `bk_auth.user_account.id`。角色 ID、业务主体 ID、组织 ID、工号不是 Auth userId；
除明确写为“目标用户”的字段外，用户 ID 必须由已验证的 Auth 上下文取得，不能从 LLM/tool 参数或未经校验的请求字段取得。

### Auth 与跨服务调用

| 表.字段 | 保存对象与用途 | 应写入谁 |
| --- | --- | --- |
| `user_account.id` | 平台账号的稳定主键，其他 Auth 与业务用户关联的根 | Auth 创建的账号本身；这是后续字段引用的 userId |
| `auth_user_role.user_id` | 获得该角色授权的用户 | 被授予角色的用户；不是角色 ID，也不是授予操作人 |
| `auth_user_business_identity.user_id` | 用户与 `BROKER` 等业务身份的映射端 | 映射所指向的 Auth 用户；`identity_id` 才是 broker 等业务 ID |
| `auth_user_store_identity.user_id` | 旧门店身份兼容映射的用户端 | 映射所指向的 Auth 用户；`store_id` 是业务门店 ID |
| `auth_org_membership.user_id` | 公司/部门/门店组织成员关系 | 实际组织成员；与角色授予分开维护 |
| `auth_user_authorization_state.user_id`、`auth_authorization_grant_boundary.user_id` | 权限版本、凭据状态及角色授予边界所针对的用户 | 被计算/变更授权的用户，不是发起变更的人 |
| `auth_authorization_audit.actor_id` | 修改账号授权关系的操作者审计 | 实际登录并执行管理操作的 Auth 用户 |
| `auth_account_logout_event.user_id` | 登出/撤销事件所属的令牌用户 | 被撤销令牌对应的 Auth 用户 |
| `auth_authorization_execution_binding.actor_id` | 持久 run/session 执行绑定的最终用户主体 | 原始请求的已验证 actor；绑定不能换成模型生成的用户 |
| `a2a_task_owner.actor_id`、`a2a_task_execution_reservation.actor_id` | A2A 任务归属与执行重放/并发预留 | 发起该 A2A 任务的已验证用户；记录在提供 A2A 的服务库中 |

### Customer、Listing 与 Marketing

| 表.字段 | 保存对象与用途 | 应写入谁 / 当前代码情况 |
| --- | --- | --- |
| `customer_profile.owner_user_id` | 客户档案当前平台负责人，使本人列表、详情和修改能按账号隔离 | 负责该客户的经纪人 Auth userId。当前新建要求有 BROKER 身份，并从已验证 actor 写入；`broker_id` 同时保存该人的 broker 业务 ID |
| `customer_follow_record.owner_user_id` | 单条跟进的记录人/跟进责任账号，不等于客户档案的永久负责人 | 实际写入跟进的已验证用户；当前同时要求经纪人身份。可由客户档案负责人范围继承可见性，但作者字段保留个人审计 |
| `customer_listing_inquiry.owner_user_id` | 买方咨询的负责/发起账号，供本人范围和咨询隔离 | 实际负责该咨询的已验证用户；当前由 actor 写入，`broker_id` 保存对应 broker 业务 ID |
| `customer_inquiry_comparison.created_by` | 哪个用户把比较报告关联到咨询，便于操作审计 | 执行关联操作的 Auth actor；代码从请求授权上下文取得 |
| `customer_inquiry_command_receipt.actor_user_id` | 命令幂等键的用户命名空间，防止不同用户的同一 requestId 串单 | 发起命令的 Auth actor；这是回执键，不是客户负责人 |
| `listing_info.owner_user_id` | 房源的当前平台负责人，控制房源本人读写范围 | 负责该房源的经纪人 Auth userId；当前新建把已验证 actor 写入，`broker_id` 保存经纪人业务 ID |
| `listing_asset.created_by` | 谁上传/登记房源图片、户型图等素材 | 执行素材登记/替换的 Auth 用户；仅表示操作人，不等于房源负责人 |
| `listing_price_record.operator_user_id`、`listing_status_record.operator_user_id` | 谁调价、谁执行状态变化，支持审计和同一操作者幂等 | 实际执行本次变更的 Auth actor；角色可以不同，字段仍记录人 |
| `listing_command_receipt.actor_user_id` | 房源命令幂等回执的调用人范围 | 发起该命令的 Auth actor；不是房源 owner |
| `marketing_content.owner_user_id` | 营销内容当前平台负责人 | 实际负责内容的 Auth 用户；当前创建/复制流程从已验证 actor 写入。`owner_broker_id` 是另一命名空间的经纪人业务 ID |
| `marketing_campaign_plan.owner_user_id` | 活动计划的创建人/后续可查看和操作该计划的用户 | 创建计划的已验证用户；CampaignLifecycle 使用当前 actor 并按 owner 限定访问 |
| `marketing_campaign_publish_request.owner_user_id` | 发布命令的发起人，也参与 `(owner_user_id, request_id)` 幂等唯一性 | 发起发布请求的已验证用户；不能由计划内容或模型参数指定 |

`customer_favorite_listing`、`owner_entrust_record`、Listing 的素材/历史子表及多数 Campaign receipt/attempt 子表没有独立 userId：
前两类通过客户/房源关系及父记录授权；Listing 子表通过 listingId 继承房源范围；Campaign 子表通过 plan/request 关联继承 owner。
若业务需要追查“谁创建了收藏/委托”或“谁批准了活动/最终发布”，当前没有独立用户列就不能从父记录负责人反推该操作者。

### Agent、Memory 与通知

| 表/字段 | 保存对象与用途 | 应写入谁 |
| --- | --- | --- |
| `agent_orchestration_run.user_id` | 持久 run 的用户归属，限制续跑、恢复、审计和下游 memory session | 原始请求的已验证用户；`AgentPrincipalContext` 将请求身份绑定到网关注入的 principal，LLM context 不能改写它 |
| `agent_async_task.user_id`、`agent_async_workflow.user_id` | 异步任务/工作流的所有者，用于查询、取消、重试及状态订阅隔离 | 已绑定到原始 run 的 Auth userId（当前以字符串存储）；任务继续时不允许换 owner |
| `studio_work.owner_id`、`studio_command.owner_id`、`studio_run.owner_id`、`studio_artifact_series.owner_id` | Studio 工作区、命令、运行及成果系列的所有者；读写都按 owner 过滤 | 请求的已验证 Auth userId（字符串表示）。消息、成果版本等子行由 work/series 关系继承，不重复存 owner |
| `session_shared_memory.user_id` | 旧版 session memory 所属用户，防止相同 session 被不同用户接管 | 创建该 session memory 的已验证用户；历史兼容列可空，新写入不可借用模型提供的 userId |
| `user_preference_memory.user_id` | 用户个人偏好的所有者，也是唯一键的一部分 | 偏好所属用户，而非后台抽取偏好的服务；当前 Memory 端通过签名请求核对该 ID |
| `memory_conversation.owner_id`、`memory_share_grant.owner_id`、`memory_shared_export.owner_id`、`memory_preference_block.owner_id`、`memory_experience_event.owner_id`、`memory_experience*` 各记录的 `owner_id` | Memory v2 的私有记忆、偏好块、经验、共享来源所属人；与 domain/agent/conversation 共同组成授权范围 | 签名请求中 Auth actor 的十进制 userId 字符串。相关 revision/source 通过复合主键继承同一 owner；共享目标的 domain/agent/conversation 是目标范围，不是 Auth 用户 |
| `notification_record.recipient_user_id` | 站内通知的收件人，决定谁能读/标记已读 | 真实目标收件人的 Auth userId。Customer 委托提醒从已存 ownerUserId 派生；不能使用 brokerId 或模型生成的收件人 |
| `notification_event_consume.recipient_user_id` | 个性化消息事件消费/幂等上下文中的收件人，可空表示事件没有单一用户收件人 | 事件确认的目标用户；不是发送服务或触发任务的 actor |

Agent 内的 tool invocation、checkpoint、artifact 等多数行通过 `run_id`、`task_id`、`session_id` 关联到上述 owner；不应在每个节点重复从 LLM 输入拷贝 userId。
异步恢复和 A2A 回调必须继续使用首次签名上下文中的 owner，不能让后续 tool_calling 改写。

### Interview 与 Platform Operations

| 表.字段 | 保存对象与用途 | 应写入谁 / 当前边界 |
| --- | --- | --- |
| `interview_case.created_by_user_id`、`interview_session.created_by_user_id` | 哪个 Auth 用户创建案例/会话，支持列表隔离及审计；历史行允许空，表示尚未核实 | 创建请求的 Auth actor。旁边的 `creator_work_no` 是工号，不是用户 ID，二者不能互相推算 |
| `interview_participant.user_id` | 被授权参与某场面试的参与用户（CREATE/RESPOND 是资源关系，不是角色） | 被邀请/参与的人；必须来自可信的用户选择或服务端参与关系解析 |
| `interview_participant.invited_by_user_id` | 邀请参与者的操作人 | 实际发出邀请的 Auth actor |
| `interview_participant_ticket.user_id` | 某场会话、某用途 ticket 的持有人 | ticket 实际签发给的参与用户；用于阻止跨用户转用 |
| `interview_director_command.requested_by_user_id`、`interview_report_task.requested_by_user_id` | 延迟导演命令/异步报告任务的请求人，控制谁能查看或恢复结果 | 发起该命令/报告的 Auth actor；旧行或系统任务允许为空 |
| `interview_asset.approved_by_user_id` | 谁批准面试资产发布/公开 | 实际批准的 Auth 用户；未发布/撤回状态应清空，不是案例创建人 |
| `store_review_matter.actor_id` | 创建门店经营复盘事项的用户 | 当前已验证的门店成员 actor；门店归属本身仍由独立 `store_id` 表示 |
| `store_review_plan_revision.owner_actor_id` | 负责某个复盘执行方案的用户 | 创建该版本方案的 Auth actor；责任人可随新版本变化，门店范围仍看 storeId |
| `knowledge_case.owner_actor_id` | 个人范围知识条目的所有者；STORE 范围知识仍由 storeId 控定范围 | 建立知识条目的 Auth actor。STORE 条目中此字段至少是创建/管理人，不可误认为门店 ID |
| `knowledge_revision.created_by_actor_id`、`knowledge_generation_task.created_by_actor_id` | 知识版本作者、生成任务发起人；也用于隔离任务状态和结果 | 实际创建版本/发起生成任务的 Auth actor |
| `knowledge_review_decision.reviewer_actor_id` | 谁作出知识审核结论 | 实际审核的 Auth actor |
| `knowledge_operation_receipt.actor_id` | 知识写操作幂等回执的调用人 | 发起本次写操作的 Auth actor |

### 业务 ID 与没有独立用户列的服务

| 字段/模块 | 它实际是什么 | 处理结论 |
| --- | --- | --- |
| `broker_id`、`owner_broker_id` | 经纪人业务主体 ID | 若需要找登录用户，必须经 Auth 的 `auth_user_business_identity` 显式映射；绝不直接写进 `owner_user_id` |
| `employee_id`、`payee_employee_id`、`creator_work_no` / `work_no` | 员工/收款人/工号业务主数据 | 不是 Auth userId。当前没有已验证的员工/收款人权威映射；不能用同号、姓名或某个运营角色补齐 |
| `store_id`、`store_business_id` | 业务门店 ID | 不是用户 ID；仅通过 Auth 组织门店的显式业务 ID 映射派生门店范围 |
| Gateway | 仅校验/转递 Auth principal，没有业务数据库表 | 不在 Gateway 表里补用户 owner；下游必须保存自己确实需要的业务 owner/actor |
| RAG | MySQL 侧没有业务用户归属表；向量索引使用受保护的 `userId` / `ownerId` 元数据过滤 | 元数据值由签名 principal/scope 派生，不从查询条件或 LLM 文本相信任 |
| Media Worker | 没有已发现的 SQL 用户 owner 列；后台任务访问用签名 permit 的 `ownerActorId` 校验 | permit owner 必须来自已验证 actor；不是业务数据库里的用户档案 |
| `listing_rag_grant.actor_user_id`、`managed_by` | 资源授权表设计的授权主体与授权维护人 | 当前运行代码使用 listing 域授权基线，源码未发现对该表的运行读写；先不要把它当成当前必须填充的房源归属表 |
| `employee_kpi_stat.employee_id`、`employee_daily_workload.employee_id` | Platform Operations 的员工绩效/工作量业务主体 | 这是员工目录 ID，不是 Auth userId；当前没有可信 user↔employee 映射，不能用岗位角色直接填 |
| Interview `interview_case.creator_work_no`、`interview_session.creator_work_no` | 创建人工作号 | 是工号业务字段，不能代替同表的 `created_by_user_id`；若业务需要按 Auth 用户检索，使用后者 |
| 旧 `contract_record.broker_id`、`settlement_record.employee_id`、`settlement_monthly_summary.employee_id`、`settlement_payment_record.payee_employee_id` | 合同经纪人、结算员工、收款员工业务主体 | 合同/结算数据库已退役，相关副作用受 KI-204/KI-206 门禁；这些业务 ID 不能当 Auth userId，也不属于当前已接通的服务写入链 |
| 旧 `promotion_publish_record.operator_name` | 历史促销操作人展示名 | 文本姓名不是稳定用户 ID，不能反查 Auth 用户；旧促销链路已退役 |

## 目前能确认的用户身份写入规则与缺口

1. **负责人（owner）**：客户档案、房源和营销内容的 owner 应当是承担该业务关系的 Auth 用户。若经纪人自己录入，当前代码把已验证 actor 写为 owner，并另存其 brokerId；这代表“负责人=录入人”。若运营/助理可代录且负责人仍是另一位经纪人，必须分别记录 `owner_user_id` 与 `created_by_user_id`，并通过受控的经纪人映射/委托校验指定 owner，不能继续默认相等。
2. **操作审计（created_by/operator/actor/requested_by/approved_by）**：填写当次实际执行者的 Auth userId，与该记录归属角色无关。谁拥有客户不等于谁跟进、调价、审批或发起异步任务。
3. **目标主体（recipient/participant/ticket.user_id）**：填写真实被通知、被邀请或持有凭据的 Auth userId；它不是当前操作者。目标用户必须由业务关系/可信用户选择确认，不能让模型自由决定。
4. **父子关系继承**：当子表通过不可变的 parent ID 继承 owner，且业务无需单独追踪该子操作人时不新增用户字段；一旦需要审计“谁做的”，单独加 actor 字段，不能用父 owner 冒充。
5. **当前未打通的主要身份域**：运营 KPI/工作量中的 employeeId、Interview 工号、合同/结算收款员工和旧 broker 数据没有可审计的一致映射；Auth 里有用户—BROKER 映射及组织成员，但这不自动证明 employeeId、storeId 或历史数据的归属。对应链路应继续按缺少权威映射处理，不能通过角色名推用户。
6. **同一 userId 的列类型不完全一致**：Auth、Customer、Listing、Interview 多用 BIGINT；Agent、Memory v2、Knowledge 的部分 owner/actor 列用 VARCHAR 保存十进制 userId。它们语义上仍必须是同一个 `user_account.id`，字符串只是序列化形式；不能因为目标列是字符串就把 brokerId、workNo 或 username 写进去。

本清单是源码/迁移定义审计，不代表本地或生产数据库逐表行值、空值数量已经核验；实际数据回填仍须按独立证据与账本办理。
