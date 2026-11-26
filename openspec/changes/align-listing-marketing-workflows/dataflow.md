# 房源登记、审核上架与营销发布数据流

本图对应一期目标流程。房源域维护挂牌状态和价格，客户域提供受权业主档案，营销域维护内容与发布结果。Supervisor 保存任务和人工待办，经 A2A 传递业务请求与恢复决定。下文「现有字段」以受管 SQL 为依据，「目标记录」为设计字段，实际表名与迁移实现由实施确定。 [提案](proposal.md) · [受管初始化清单](../../../docker/mysql/init-manifest.tsv)

## 系统上下文

```mermaid
flowchart LR
    Broker[负责经纪人] --> ListingUI[房源管理]
    ReviewAdmin[房源审核管理员] --> ListingUI
    ListingUI -->|登记、提交、审核、资料校正| Listing[房源域]
    Auth[Auth 可信主体、角色与范围] -.-> Listing
    Listing <-->|业主档案引用与核验结果| Customer[客户域]
    Listing --> LDB[(房源、上架申请与审核、价格与状态流水)]
    Listing -->|按权限提供在售候选与版本| Select[咨询、匹配、对比]

    Operator[发起人或授权发布操作者] --> Studio[任务页、审核与发布确认]
    Maintainer[公共账号负责人] --> Studio
    Studio <-->|待办、决定、处理结果| Supervisor[Supervisor]
    Auth -.-> Supervisor
    Supervisor --> ADB[(任务运行、暂停点和决定回执)]
    Supervisor <-->|A2A 房源候选与确认前复查| Listing
    Supervisor <-->|A2A 草稿、人工处理与发布任务| Marketing[营销 Agent]
    Marketing <-->|营销域工具| MDB[(草稿修订、审核记录、发布计划与尝试)]
    Marketing -->|调用营销域发布工具| Publish[营销域 MCP 工具与渠道适配]
    Publish <-->|登录探测、二维码、发布请求与回执| MCP[xiaohongshu-mcp]
    MCP -->|使用部门会话发布| XHS[部门小红书账号]
```

## 房源登记、上架、在售校正与下架后再次申请

```mermaid
flowchart TD
    Create[经纪人代业主录入房源] --> Owner[房源域校验经纪人主体与业主档案]
    Owner --> Save[保存房源和登记流水]
    Save -->|首次申请| Submit[记录本次申请编号与送审版本，进入待审]
    Submit --> Review[管理员审核本次申请资料的真实性]
    Review -->|退回并填写理由| Return[保存退回决定与资料版本]
    Return --> Edit[经纪人按理由修改]
    Edit -->|首次申请重提| Submit
    Edit -->|重新上架申请重提| Compare
    Review -->|通过| Approve[记录申请编号、审核人、审核决定及资料版本]
    Approve --> MarkLive[按本次批准执行上架并写状态流水]
    MarkLive --> Active[ON_SALE 在售]
    Active --> Select[按访问范围选用并返回来源版本]
    Active --> Correct[负责经纪人校正业主关联以外的业务资料]
    Correct --> Commit[保存资料与变更记录，更新版本和索引]
    Commit -->|涉及挂牌价| PriceHistory[同事务保存价格流水]
    PriceHistory --> Active
    Commit --> Active
    Active -->|日常下架或准备更换业主| Delist[保存本次下架流水和当时的资料快照]
    Delist --> Registered[已登记：同一套房源的可编辑草稿]
    Registered -->|更换业主| Rebind
    Registered -->|再次点击上架| Compare{与最近一次下架快照比对}
    Compare -->|业主差异未处理或关联缺失| FixOwner[提示先调整业主关联，拦截本次申请]
    FixOwner -->|实际更换业主| Rebind[原房源换绑有效业主档案，留存变更记录]
    Rebind --> Registered
    Compare -->|本次换绑已完成| OwnerChanged[展示已办理的业主变更及其他资料差异]
    OwnerChanged -->|继续申请| Apply
    Compare -->|同一业主，基础资料变化| Changes[展示字段原值与现值]
    Changes -->|继续申请| Apply[提交时复核当前版本]
    Compare -->|资料一致且有效| Apply
    Apply -->|重新上架申请| Submit
    Compare -->|历史快照缺失| Missing[提示无法比对，保留草稿]
```

- 管理员逐次审核送审版本中的业主关联与基础展示信息。首次与重新申请都在审核通过并执行上架后才进入在售候选。待审或退回时，仅负责人和有管理权限的管理员按管理规则查看。审核决定和上架各留业务记录，可在同一事务提交。版本不符时需重新核对送审资料。
- 负责经纪人可直接校正地址、户型、面积、挂牌价、素材等有权维护的资料，保存后继续在售，记录字段前后值、操作者、时间和资料版本。涉及价格时同事务保存价格流水。普通校正不生成上架申请或审核待办。匹配、对比和营销草稿的候选及引用复查统一要求 `ON_SALE`。房源全量读取权限决定访问范围。 [现有资料维护实现](../../../listing-master-service/src/main/java/com/bkanent/listing/service/impl/ListingManagementServiceImpl.java#L245) · [管理与选用规则 LR-196](../../../docs/logic-rationale.md#L1778)
- 业主比对使用档案 ID。同名的两个档案也会产生差异。经纪人在原房源完成换绑后，再次申请时可看到原业主、现业主及其他资料差异；管理员在同一次上架审核中核实当前关联。
- 在售房源更换业主依次经过下架、原房源换绑、重新申请、管理员审核及执行上架。下架时保存旧业主，换绑时保存新旧关联，审核目标为新业主及本次送审资料。

## 营销草稿、房源复查和人工决定

```mermaid
sequenceDiagram
    actor U as 发起人
    participant F as 前端
    participant S as Supervisor
    participant L as Listing Agent
    participant M as 营销 Agent
    U->>F: 选择房源并起草
    F->>S: 提交选择和任务请求
    S->>M: A2A 委托起草，携带已选房源引用
    M-->>S: WAITING_DOMAIN 请求房源可选状态与快照
    S->>L: A2A 校验可选状态并取房源快照
    L-->>S: 在售快照或不可选结果
    S->>M: RESUME_DOMAIN 回传房源结果
    alt 房源可选
        M-->>S: WAITING_HUMAN 已存草稿与待审核动作
    else 房源已下架或不可选
        M-->>S: WAITING_HUMAN 重新选择房源
        S-->>F: 保留任务，提醒重新选择房源
    end
    loop 当前草稿存在且待审核
        S-->>F: 展示草稿与具体审核待办
        U->>F: 提交退回意见或确认草稿
        F->>S: 办理决定，关联待办和草稿版本
        alt 退回修改
            S->>M: A2A 退回决定、版本和意见
            M-->>S: 保存同任务新版并再次送审
        else 确认草稿
            S->>M: RESUME_HUMAN 确认当前草稿版本
            M-->>S: WAITING_DOMAIN 请求复查当前房源
            S->>L: A2A 复查房源状态和草稿引用的挂牌价
            L-->>S: 在售快照或不可选结果
            S->>M: RESUME_DOMAIN 回传复查结果
            alt 房源已下架或不可选
                M-->>S: WAITING_HUMAN 重新选择房源
                S-->>F: 返回草稿阶段，保留旧稿并提示重新选择
            else 挂牌价变化
                M-->>S: WAITING_HUMAN 价格变更与可选决定
                S-->>F: 暂停确认并展示原引用价和当前价
                U->>F: 选择更新改稿或不更新并确认原稿
                F->>S: 提交绑定该次提醒的选择
                S->>M: RESUME_HUMAN 回传用户选择
                M-->>S: 按最新复查结果送审新版或冻结原稿
            else 可选且价格一致
                M-->>S: 保存审核结论和已确认的内容素材快照
            end
        end
    end
```

草稿可以多次退回。每次使用原任务和草稿标识，保存新的草稿版本并创建新待办，同一退回决定重放时复用已经生成的新版。重新选择房源后先取新房源快照，再在原任务内送审。调价时，用户选择「不更新，确认原稿」可继续发布准备，营销域同一笔审核记录须能说明「已提示调价，用户选择不更新」，并保留原引用价、提示时现价、发生时间、操作者和内容版本。草稿审核通过时绑定审核对象、素材及房源事实快照。此后发布阶段以该冻结快照为输入。 [现有内容版本与发布快照](../../../sql/migrations/20261026_marketing_campaign_lifecycle.sql#L2) · [审核素材版本缺口 KI-276](../../../docs/known-issues.md#L2262)

## 发布确认、渠道登录和人工重发

```mermaid
sequenceDiagram
    actor U as 发布确认人
    actor A as 公共账号负责人
    participant F as 前端
    participant S as Supervisor
    participant M as 营销 Agent 与本域发布工具
    participant X as xiaohongshu-mcp
    S->>M: A2A 从已确认快照准备发布
    M-->>S: 计划编号、版本、快照摘要及目标账号
    S-->>F: 展示最终文案、素材和目标账号
    U->>F: 点击发布，确认当前计划版本
    F->>S: 提交人工确认记录
    S->>M: A2A 执行，携带计划版本和本次确认
    M->>X: 核对部门会话与目标账号
    alt 登录失效
        X-->>M: 登录过期或待登录，返回二维码
        M-->>S: 待登录与恢复指引
        S-->>F: 保留计划并展示登录待办
        A->>X: 扫码恢复部门账号登录
        X-->>M: 部门会话已恢复
        M-->>S: 登录恢复结果
        S-->>F: 回到原计划确认页
        U->>F: 核对后再次点击发布
        F->>S: 提交新的人工确认记录
        S->>M: A2A 执行并核对已选会话
    end
    M->>X: 使用部门账号提交本次发布
    X-->>M: 明确成功、失败或结果待核实
    M-->>S: A2A 返回已记录的发布尝试和回执
    S-->>F: 展示本次发布状态
```

发布只核对渠道执行所需的信息，包括计划版本、快照、确认记录、目标账号会话、素材交付和回执。部门账号与平台确认人的身份分别记录。MCP 明确返回成功时，本次业务发布办结并保存响应；外部笔记 ID 按渠道实际返回记录。登录恢复只打开原确认页，由本次确认人再次点击。渠道返回不明确时，保留原尝试与状态以便核对。

人工发现漏发时，原确认人或相应部门发布管理员登记「确认未发布」，保存复核人、时间、原因和原尝试引用。操作人核对并明确确认重发后，创建新的发布尝试，关联原确认、原尝试及本次确认。同一次点击重复送达复用同一回执。原回执与人工复核记录可一起查看。 [现有请求、回执和尝试 SQL](../../../sql/migrations/20261026_marketing_campaign_lifecycle.sql#L90)

## 主要表与字段

| 落库方与用途 | 现有表与字段 | 目标记录及关联 |
| --- | --- | --- |
| Listing 在售资料校正 | 当前资料与版本存于 `listing_info`，素材存于 `listing_asset`，价格变化有 `listing_price_record`。 | 字段级变更记录拟保存 `listing_id, from_version, to_version, changed_fields_json, operator_user_id, request_id, created_at`，按字段权限保留前后值。普通校正与当前资料原子提交，房源状态仍为 `ON_SALE`。敏感值沿用其保护方式。 |
| Listing 更换业主 | `listing_info.id, owner_customer_id, version`。新业主指向 `customer_profile.id` 对应的有效业主档案。 | 拟在变更记录保存 `listing_id, delist_record_id, from_owner_customer_id, to_owner_customer_id, listing_version, operator_user_id, created_at`。当前关联和记录在同一事务写入。申请时核对本次下架周期的变更记录和当前有效关联。 |
| Listing 下架草稿与再次申请 | 当前草稿沿用 `listing_info.id, owner_customer_id, title, address, layout, area, asking_price_yuan, status, version`。下架动作见 `listing_status_record`。 | 拟按下架流水关联不可变的 `listing_id, delist_record_id, listing_version, owner_customer_id, facts_json`。预检和提交均由服务端读取最近一次下架快照。按当前办理人的管理权限展示字段、原值及现值。 |
| Auth 和 Customer 提供可信身份、业主与部门关系 | `bk_auth.user_account.id`；`auth_user_business_identity.user_id, identity_type, identity_id`；`auth_org_membership.user_id, org_id, status, valid_from, valid_until`；`bk_customer.customer_profile.id, profile_type, owner_user_id`。 [用户和客户表](../../../sql/migrations/20261013_mysql_init_bootstrap.sql#L17) · [业务身份](../../../sql/migrations/20261026_auth_business_identity_mapping.sql#L4) · [组织](../../../sql/migrations/20261110_auth_organization.sql#L27) · [客户负责人](../../../sql/migrations/20261111_customer_user_owner.sql) | `actor_user_id` 取可信 Auth 主体，`broker_id` 取受信经纪人映射；`owner_customer_id` 引用客户域 `OWNER` 档案。发布管理员权限和部门范围用 Auth 事实核验。 |
| Listing 房源及上架 | `listing_info.id, broker_id, owner_user_id, owner_customer_id, title, address, community_name, district_code, layout, area, floor_level, decoration, school_zone, traffic, total_price, asking_price_yuan, status, ocr_status, verification_status, verification_source, verification_remark, version, deleted`。主表还有历史 `owner_name, certificate_no, property_certificate_url, contract_url` 字段。关联 `listing_asset.listing_id, asset_type, storage_key, visibility, status` 和 `listing_private_info.listing_id, certificate_no_ciphertext, certificate_key_id`。 [房源表](../../../sql/migrations/20261013_mysql_init_bootstrap.sql#L71) · [扩展列和子表](../../../sql/migrations/20261105_listing_internal_business.sql#L5) · [房源负责人](../../../sql/migrations/20261111_listing_user_owner.sql#L4) | 上架申请记录包含 `application_id, application_type, listing_id, listing_version, submitted_by_user_id, submitted_at, status`。审核决定关联 `application_id` 和送审版本，并记录 `reviewer_user_id, decision, reviewed_at, return_reason`。上架流水引用本次申请及通过决定，生效后业务状态转入 `ON_SALE`。 |
| Listing 价格、状态及写入回执 | `listing_price_record.listing_id, old_price_yuan, new_price_yuan, reason, operator_user_id, request_id, created_at`；`listing_status_record.listing_id, from_status, to_status, action, reason, operator_user_id, request_id, created_at`；`listing_command_receipt.actor_user_id, operation, request_id, request_hash, listing_id, status`。 [房源流水与回执](../../../sql/migrations/20261105_listing_internal_business.sql#L126) | 复用现有流水和操作归属。对外来源快照携带 `listingRef`、`revision`、`contentHash`、价格单位、挂牌状态及观察时间，供 Supervisor 交给营销确认流程。 |
| Supervisor 任务暂停、待办与续接 | `agent_orchestration_run.run_id, user_id, request_json`；`agent_tool_invocation.run_id, call_id, capability_id, arguments_hash, status, result_json, remote_association_json`；`agent_workflow_checkpoint.task_id, session_id, trace_id, workflow_status, pending_approval_id, snapshot_json, checkpoint_version`；`agent_workflow_approval_claim.approval_id, task_id, approval_version, decision_status, result_json`。 [编排日志](../../../sql/migrations/20261004_supervisor_orchestration.sql#L3) · [暂停点与审批回执](../../../sql/migrations/20261013_mysql_init_bootstrap.sql#L761) | 待办关联 `runId`、原 A2A Agent 和子任务、`actionId`、动作与契约版本、业务对象和版本、办理人或授权范围、展示快照引用。续接记录携带决定标识与实际操作者；重复提交核对动作和内容摘要后复用结果。 |
| Marketing 草稿版本与人工内容审核 | `marketing_content.id, listing_id, owner_user_id, title, copywriting, asset_urls, cover_image_url, video_url, version_no, audit_status, status`；`marketing_campaign_content_revision.id, content_id, revision_no, content_hash, snapshot_json, data_mode, created_at`。 [草稿表](../../../sql/migrations/20261013_mysql_init_bootstrap.sql#L235) · [内容负责人](../../../sql/migrations/20261111_marketing_user_owner.sql#L4) · [内容修订表](../../../sql/migrations/20261026_marketing_campaign_lifecycle.sql#L2) | 保存草稿修订与素材摘要、原稿中的房源引用和来源版本。审核决策绑定该修订、审核人、时间、退回意见，并关联 Supervisor 决定。调价审计包含提醒编号、展示的新旧价格与单位、当前来源版本、用户选择和最终保留的草稿版本。 |
| Marketing 发布计划与目标账号 | `marketing_campaign_plan.plan_id, owner_user_id, content_id, content_revision, content_hash, asset_hash, targets_hash, snapshot_hash, plan_version, status, content_snapshot_json, assets_snapshot_json`；`marketing_campaign_plan_target.plan_id, target_index, platform, account_id, account_revision, external_account_ref, arguments_hash`；`marketing_campaign_account.id, platform, external_account_ref, permission_code, revision_no, active`。 [账号、计划及目标](../../../sql/migrations/20261026_marketing_campaign_lifecycle.sql#L35) | 计划绑定已通过的草稿审核记录。平台用户的发布确认绑定 `plan_id`、`plan_version`、`snapshot_hash`、目标账号、本次确认人和时间；部门归属绑定共享渠道账号。会话凭据由渠道适配维护。 |
| Marketing 执行、回执与人工重发 | `marketing_campaign_publish_request.plan_id, owner_user_id, request_id, body_hash, receipt_id`；`marketing_campaign_publish_receipt.receipt_id, plan_id, request_id, status, data_mode`；`marketing_campaign_publish_attempt.attempt_id, receipt_id, plan_id, target_index, account_id, status, external_publish_id, message, attempt_version`；`marketing_campaign_outbox.event_id, attempt_id, state`；`marketing_campaign_event.event_id, attempt_id, event_type, payload_json, created_at`。 [发布流水](../../../sql/migrations/20261026_marketing_campaign_lifecycle.sql#L90) | 本次执行与确认记录关联；重发增加原尝试引用和人工复核记录。现有请求表 `UNIQUE(plan_id)` 需要演进为可保留多次受确认发布请求的存储关系，继续对单次点击执行幂等。 |

## 关键验收点

| 场景 | 从用户操作追溯到的结果 |
| --- | --- |
| 首次上架、重新上架、退回后重提 | 每次申请都生成本次待审记录。管理员批准这份申请后才可执行上架，只有进入 `ON_SALE` 后才纳入候选。 |
| 下架后只改挂牌价 / 更换业主 | 只改价时展示两次价格。换业主时在原房源办理换绑，保存前后业主，当次申请数为零。经纪人再次申请时可使用新的业主关联。 |
| 在售房源调价 | 价格和版本同步更新，原价、新价、操作者、时间和请求编号可查。 |
| 在售校正地址、户型、面积或素材 | 保存新版本与变更记录，房源继续在售，本次上架申请和审核待办的数量均不增加。 |
| 在售更换业主关联 | 先下架并退出候选，原房源换绑后仍处于已登记；重新申请并取得本次管理员批准，执行上架成功后才再次进入候选。 |
| 草稿退回、确认期间调价或下架 | 同任务生成新版并重新送审，或明确确认保留原价并有提醒审计；下架返回草稿阶段并保留原任务。 |
| 草稿通过、发布计划确认 | 保存的发布版本可按确认记录追溯到草稿和素材快照；确认后的房源价格变化留在房源业务中。 |
| 部门小红书账号待登录或人工重发 | 扫码之后展示原计划并由本次确认人再次点击；MCP 回执与原尝试、复核决定、新尝试均可单独查询。 |
