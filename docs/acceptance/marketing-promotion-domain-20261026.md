# 营销推广合并独立验收记录

## 会话与基线

- OpenSpec change：`consolidate-marketing-promotion-domain`
- 共同基线：`6d13117169fb6eeb3c15165b43a08cc08b142889`
- 分支：`codex/consolidate-marketing-promotion-domain`
- Worktree：`C:\Users\User\.codex\worktrees\marketing-promotion-domain\BkAnentProject`
- Maven 本地仓库：该 worktree 下被忽略的 `.m2repo/`；构建显式设置 `-Dmaven.repo.local=.m2repo`
- 基线没有依赖房源、知识、经营 change 的实现提交。本 change 未修改 `auth-service/**`、其它业务域的生产实现、公共框架、已应用 `sql/mysql-init.sql`、活动 `docker/mysql/init-manifest.tsv` 或 manifest runner allowlist；只从 `common-a2a` 的服务配置测试枚举移除已退役 promotion 模块。
- 未启动 Docker、共享数据库、Nacos 或共享服务；所有数据库验证使用进程内 H2（MySQL mode），房源 HTTP fixture 只绑定 loopback 随机端口。

## 范围与隔离依赖

- 负责 `marketing-content-service`、`common/marketing/campaign/**`、旧 promotion 的营销域接收适配、最小 gateway/Compose/reactor/Nacos 差异和新 campaign schema。
- LR-102 已落实；本 change 使用保留段 LR-130–137，并维护已登记问题 KI-167。KI-194–201 仅为并行预留编号，未发现需要新增登记的问题时不创建空条目。实现提交：`1c5fc2da3f064317c597a253d6c233506ea829a2`。
- 没有修改其它 change 的 Java 类型或依赖其分支制品。营销使用自己的 `CampaignListingSnapshotClient` consumer DTO，测试 fixture 覆盖冻结的 `domain-parallel-v1`。测试中的 Auth RPC 是精确 mock；生产 Auth 缺失或拒绝时 campaign 操作失败关闭。
- 本期不迁移媒体 Worker、通知服务或 Studio work/command/审批存储；Studio 可以消费营销的 plan snapshot 和 receipt DTO。

## 已实现的生命周期

1. 新建内容/变体强制为 `DRAFT`；审核入口要求 `marketing.content.review`。直接更新旧 publish-status 的 HTTP/RPC 写入返回 `410 LEGACY_WRITE_DISABLED`。
2. `POST /marketing/campaigns/prepare` 检查审核状态和账号 grant，绑定内容 revision/hash、房源快照、素材 hash、目标平台/账号 revision/参数 hash、规则版本和受管 data mode；响应含 `createdAt` 和 `expiresAt`。
3. `POST /marketing/campaigns/plans/{planId}/confirm` 要求 expected version/hash 和请求幂等 ID，并重查内容、房源、素材、账号 revision 及 Auth 权限。相同请求返回原回执；异体复用 ID 冲突；过期计划拒绝。
4. 本地事务先保存 receipt、每目标稳定 attempt 和 outbox。定时 dispatcher 在事务外调用 provider，按 lease token 与 attempt version 回写；进程中断将原 attempt 转为 `OUTCOME_UNKNOWN`，通过同一 attempt 核对，不自动换 ID 重发。回执分别保留部分成功、失败和未知状态。
5. 效果观察保存来源系统/事件/version/窗口/采集时间/data mode/payload hash；重复版本不累加，高版本修正追加 revision。fixture 样例只在显式 `SIMULATED` 模式生成，不产生收入；真实 ROI 需同币种的归属收入、成本和 ROI 定义版本，数据不足返回 null，线索成本可单独计算。
6. 旧 `/promotions/**` 网关路由指向营销服务，由同一 campaign 授权服务处理。`/promotions/publish` 接受新的 prepare 请求 DTO；旧 `listingId/platforms/prompt` 发布体返回 HTTP 410 `LEGACY_CONTRACT_UNSUPPORTED` 和迁移说明，不再静默解析为空字段或宣称兼容。

主要 API：

| 路径 | 用途 |
| --- | --- |
| `POST /marketing/campaigns/prepare` | 创建固定快照的发布计划 |
| `GET /marketing/campaigns/plans/{planId}` | 读取本人计划 |
| `POST /marketing/campaigns/plans/{planId}/confirm` | 确认精确快照并受理发布 |
| `GET /marketing/campaigns/receipts/{receiptId}` | 读取原回执及目标 attempts |
| `POST /marketing/campaigns/attempts/{attemptId}/reconcile` | 核对原未知 attempt |
| `GET/POST /marketing/campaigns/attempts/{attemptId}/effects[/sync]` | 读取或向 provider 同步渠道观察 |
| `GET /marketing/campaigns/roi-report` | 按渠道/数据模式汇总有证据的指标 |

所有 campaign API 经 actor 解析和细粒度 Auth RPC；需要协调/Auth 会话登记并授予 `marketing.campaign.read`、`marketing.campaign.prepare`、`marketing.campaign.publish`、`marketing.campaign.effects.read`、`marketing.campaign.assets.manage`、`marketing.content.review`。目标账号还须由受管配置预先登记在 `marketing_campaign_account`，为其指定 `marketing.channel.<platform>.publish` 权限和 REAL/SIMULATED 模式；客户端不能自行创建授权。没有这些登记时请求失败关闭。

## 数据迁移、历史和回退

原库映射继续按 `bk_marketing.marketing_content`、`bk_promotion.promotion_publish_record`、`promotion_effect_stat`、`brand_asset` 逐类核对。新 `marketing_campaign_legacy_map` 的来源键为 `(source_system, entity_type, legacy_id)`，发布尝试/素材分别生成营销域新 ID；旧成功发布不得重放，旧效果来源未核实时保持 UNKNOWN。`sql/checks/marketing-promotion-inventory.sql` 是只读盘点；共享库行数、在途发布、导出哈希和实际 ID 冲突未读取。

本 change 只增加空的营销生命周期 schema，没有对共享数据库执行 DDL、历史回填或 writer 切换。协调迁移前仍须在隔离/维护终端完成两库盘点和备份，围栏旧 writer，按映射保留旧外部 ID/状态后再切流；不得把旧库直接删掉或把历史模拟效果改标为 REAL。旧 `bk_promotion`、`sql/mysql-init.sql`、`nacos/promotion-service.yaml`、`nacos/mysql-common.yaml` 的 promotion 配置以及 Auth 的旧 service identity/数据库列表继续作为历史和回退材料保留。

应用回退时恢复旧 gateway 配置和旧部署制品，并保持新旧 writer 单一有效；已创建的 campaign attempt 不可交给旧发布服务重放。新增表可以保留用于核对，不能把成功/未知结果转换成旧入口的自动发布任务。

## Schema 和 manifest 协调差异

迁移 `sql/migrations/20261026_marketing_campaign_lifecycle.sql` 新建 13 张 `marketing_campaign_*` 表；对应 check 文件验证表存在。H2 MySQL mode 下重复执行 migration 和 ID 命名空间冲突用例通过。以下是供协调会话审阅后纳入 runner 的**建议**，不是当前活动 manifest：

```text
270	upgrade	marketing-campaign-lifecycle-20261026-v1	bk_marketing	sql/migrations/20261026_marketing_campaign_lifecycle.sql	a4d316dbca92b48b18b71d4c6284022c63b1e319063616416d26ef9b56c358cb	docker/mysql/checks/marketing-campaign-lifecycle.sql	398e0d546afc0b4c516ce71124bf7a909c900f48812d01fc6a754ed2798f55cb
```

对应 runner 的受审 allowlist 变更是在 `scripts/deploy/run-init-manifest.sh` 的 `case "$phase:$db"` 中新增 `upgrade:bk_marketing`。本分支没有改当前 `docker/mysql/init-manifest.tsv`、runner allowlist 或数据库初始化脚本；不得直接对共享库执行该 migration。

## 部署与真实联机边界

- 根 Maven reactor 与 `docker-compose.yml` 不再构建/启动 `promotion-service`，旧模块源码从本分支删除；gateway 的 `/promotions/**` 改由 `marketing-content-service` 接收。原配置和 Git 历史保留为回退/历史核对材料。
- `nacos/marketing-content-service.yaml` 明确设 `marketing.campaign.mode: REAL`、outbox dispatch、rule version、900 秒计划 TTL 和 `LISTING_SNAPSHOT_BASE_URL`。REAL 房源消费要求 `domain-parallel-v1` 响应含正确 source identity、revision、SHA-256、时间、数据集、facts 和 REAL 模式。
- 当前无真实平台发布/效果 provider，也未接入 Auth 新权限/账号登记。默认 REAL 确认在生成 attempt/outbox 前返回 `PROVIDER_NOT_CONFIGURED`；房源/Auth 依赖异常亦拒绝，不回落模拟。仅显式 SIMULATED 测试/本地配置允许 fixture，其发布/效果都标 `SIMULATED`。
- 真实房源生产者及共享 Nacos/Auth/数据库登记、旧实例停服和历史回填留给协调会话。本会话未发布或联机验证它们，fixture 成功不代表真实渠道成功或真实 ROI。

## 验证

营销模块及其上游依赖完整测试命令：

```powershell
mvn -gs .mvn-settings.xml -s .mvn-settings.xml "-Dmaven.repo.local=.m2repo" -pl marketing-content-service -am test
```

2026-10-26 营销模块 reactor 测试通过：`marketing-content-service` 23 项通过，0 失败、0 错误；上游 `common`、`common-memory`、`common-skill`、`common-a2a` 均通过（common-a2a 有 1 项既有 skip）。覆盖 H2 migration 幂等和命名空间冲突；loopback 房源契约 fixture；计划过期、内容/账号变更和权限拒绝；相同请求重放/同 ID 异体；部分成功；租约崩溃、迟到回调拒绝和原 attempt 核对；模拟效果重复/修订；缺收入/同币种/ROI 口径；旧 `/promotions` 委托和 Memory hook wiring。

根工程验证命令：

```powershell
mvn -gs .mvn-settings.xml -s .mvn-settings.xml "-Dmaven.repo.local=.m2repo" -DskipTests compile
```

根工程 22 个模块编译通过。全量 `mvn ... test` 在 `agent-service` 的 `AgentPrincipalContextTest.separateHttpRequestScopesReuseOmittedSessionForAllFourSupervisorSubmissionPaths` 有 1 项错误：Spring test context 缺少 `AgentExecutionBindingCoordinator` Bean；该测试上下文错误不在本 change 的营销草稿/发布路径内，且在本次 Agent 改动前已记录。此次新增的 Agent/Supervisor/技能定向回归 39 项通过。营销模块及其上游依赖完整测试独立通过。

`openspec validate consolidate-marketing-promotion-domain --strict` 通过。SQL 与 check 文件 SHA-256 分别为 `a4d316dbca92b48b18b71d4c6284022c63b1e319063616416d26ef9b56c358cb`、`398e0d546afc0b4c516ce71124bf7a909c900f48812d01fc6a754ed2798f55cb`。尚未纳入活动 manifest/runner allowlist，也未执行共享数据库迁移；对应 runner allowlist 由协调会话审阅后登记。

## 功能合并矩阵与调用方迁移

| 来源功能/入口 | 合并后统一能力 | 已知调用方/入口 | 验收证据 |
| --- | --- | --- | --- |
| 营销内容创建、版本/平台变体、检索 | marketing-content 的内容服务及当前 CRUD/search API；Agent/MCP 写入一律创建 DRAFT | marketing REST、`MarketingContentRpcService`、marketing ReAct、MCP `createMarketingContentDraft` | `MarketingToolsTest`、`MarketingMcpToolsTest`、`AgentCapabilityServiceImplTest` |
| 内容审核状态 | `/marketing/campaigns/contents/{contentId}/review`；仅 `marketing.content.review` | REST 审核人；Supervisor skill 只等待审核，不拥有审核工具 | `CampaignLifecycleIntegrationTest.draftCannotPrepareUntilAuthorizedReviewerApprovesIt` |
| 发布计划、目标账号、规则与快照 | `/marketing/campaigns/prepare` 和 MCP `prepareMarketingCampaign` | REST、MCP、Supervisor | `CampaignLifecycleIntegrationTest`、`MarketingMcpToolsTest` |
| 明确批准、投递与回执 | confirm 建立原回执/attempt/outbox；Supervisor 的 `mcp:marketing-mcp-server:confirmMarketingCampaign` 要求审批 | MCP、明确选择的 `marketing-campaign-publish` Supervisor skill | `SupervisorToolLoopGraphTest.configuredMarketingConfirmCapabilityWaitsForApprovalAndRejectedConfirmNeverExecutes`、campaign 集成测试 |
| 未知发布结果 | receipt 查询和原 attempt reconcile，不生成新 attempt | REST、MCP、Supervisor | `CampaignLifecycleIntegrationTest.partialSuccessAndUnknownReconciliationKeepPerTargetAttemptIds`、`MarketingMcpToolsTest` |
| 效果同步/查询与来源版本 | campaign effect API、来源事件/version/window/data mode 与历史 revision | REST 以及历史同步适配 | `CampaignLifecycleIntegrationTest` 的效果重放、修正及 UNKNOWN 断言 |
| ROI 和线索成本 | campaign ROI API；缺归属收入/币种/定义版本不返回真实 ROI | REST 报表 | `CampaignLifecycleIntegrationTest` 的无收入、缺币种、混合模式和有证据计算断言 |
| 品牌素材创建/列表/计划绑定 | campaign brand asset API；不可变快照纳入计划 | REST、campaign prepare | `CampaignLifecycleIntegrationTest.brandAssetAndAllFixtureChannelsCompleteTheUnifiedCampaignFlowAsSimulated` |
| BEIKE/DOUYIN/XIAOHONGSHU 渠道实现 | 同一 provider 接口；本地/测试 fixture 只标 SIMULATED，REAL 无 provider 时失败关闭 | campaign REST/MCP/Supervisor | 同一多渠道集成用例及 `realModeWithoutProviderFailsBeforeCreatingReceiptOrOutbox` |
| marketing A2A 草稿 | `marketing-draft` 仅搜索既有内容并返回未保存草稿 | `listing-compare-marketing-draft` 及 A2A 调用方 | `ExplicitSkillAgentWiringTest`、`DemonstrationSkillTest`；房源技能文件未改动 |
| ReAct 草稿 | marketing copy-generation 仅搜索、保存 DRAFT、准备；不确认、不通知、不写 PUBLISHED | marketing ReAct；旧 Agent preview 改为仅保存 DRAFT | `MarketingToolsTest`、`MarketingAgentServiceImplTest`、`AgentCapabilityServiceImplTest` |
| Supervisor 发布操作 | 独立、显式选择的 allowlist 技能；确认能力配置在测试 profile 中，合并配置要求与其它审批 capability 取并集 | Supervisor 动态技能目录 | `MarketingCampaignPublishSkillTest`、`MarketingCampaignApprovalProfileTest`、Supervisor tool-loop 审批测试 |

旧调用方盘点结果：仓库内没有 `PromotionRpcService.publish` 的消费者或 provider 实现；已删除无引用的旧 common Dubbo interface。`AgentRpcService.publishListingByPrompt(String)` 的唯一仓内执行链被迁移成 `createMarketingDraft(PublishRequest)`，旧的硬编码房源 ID/平台及 `publishMarketingContent` MCP 调用被移除。Agent REST 新路径为 `/agent/marketing/drafts`；旧 `/agent/publish-preview` 仅作为 DRAFT 创建别名，返回内容 ID/平台/状态，不执行发布。marketing A2A `marketing-draft` 不创建内容、不更新状态；Supervisor 新技能把其草稿保存到 MCP 后必须等授权审核。

仓内无 Postman collection/OpenAPI 文件；本次 API 契约以同 change 的 OpenSpec spec 和代码 DTO/工具 schema 为准。仓内没有其它 `/promotions/publish` 调用端；旧 `/promotions/publish` 只保留路径级过渡接收，其请求体采用当前 CampaignPlanPrepareRequest 契约。`PromotionRpcService` 为无人调用的旧契约，已经从公共模块移除。外部仓库/共享部署是否仍有客户端，需要协调环境另行盘点，不能从本地静态检索推断为零。

## 2026-10-27 Supervisor/MCP 与完整性补充

- 新增显式 Supervisor skill `marketing-campaign-publish`，由 classpath 技能目录动态加载；allowlist 只列营销 A2A 与草稿/prepare/confirm/receipt/reconcile MCP tools。它保留房源 change 拥有的 `listing-compare-marketing-draft` 文件不变，后者仍为只读草稿链路。
- MCP 草稿结果为结构化 `{contents:[{contentId,platform,status}]}`；prepare 可直接消费返回的 contentId。MCP 新增 `getMarketingCampaignReceipt` 和 `reconcileMarketingCampaignAttempt`，后者接收并调用原 attempt ID。
- `agent-service/src/test/resources/application-marketing-campaign-test.yml` 单独配置 `mcp:marketing-mcp-server:confirmMarketingCampaign`。这是隔离测试差异，未写入共享 `nacos/agent-service.yaml`；合并 Nacos 审批清单时需要与其它已批准 capability 取并集，不能覆盖既有值。
- 旧 Agent 的生成函数不再调用不存在/越权语义的 `publishMarketingContent` MCP 工具，也不再把 listingId 固定为 1、忽略平台。它按请求中真实 listingId/platforms 生成草稿并返回新内容身份。
- 多渠道 fixture 以一个已审核草稿绑定品牌素材，走 MCP draft → 内容审核 → MCP prepare → Supervisor confirm gate → MCP receipt → 三渠道派发 → effects/ROI；UNKNOWN 用 MCP reconcile 原 attempt。另有 A2A/ReAct/Supervisor 各自真实文件/工具边界测试。没有启动真实 MCP/Nacos/外部平台，也没有把 fixture 断言为真实联机。
- 2026-10-27 扩展集成测试发现品牌素材生成键与 JDBC 列名大小写问题，已在提交 `281f03a` 中限定主键生成列并用稳定 RowMapper 固定快照键；KI-172 记录该修复及防回归用例。

新增/调整验证命令：

```powershell
mvn -gs .mvn-settings.xml -s .mvn-settings.xml "-Dmaven.repo.local=.m2repo" -pl marketing-content-service -am "-Dtest=CampaignLifecycleIntegrationTest,MarketingMcpToolsTest,LegacyPromotionsControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -gs .mvn-settings.xml -s .mvn-settings.xml "-Dmaven.repo.local=.m2repo" -pl agent-service -am "-Dtest=AgentCapabilityServiceImplTest,MarketingCampaignPublishSkillTest,MarketingCampaignApprovalProfileTest,SupervisorToolLoopGraphTest,DemonstrationSkillTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

完整 `marketing-content-service` reactor 回归 32 项通过，包含 A2A wiring、campaign fixture、MCP 目录/契约、旧 DTO 围栏和 ReAct 伪造 PUBLISHED 反例；Agent/Supervisor/Skill 定向用例 39 项通过。根 reactor 22 个模块编译，OpenSpec strict validation 已通过。全量 Agent suite 目前有 1 项上述既有 Spring test context 错误，其余本次定向覆盖通过。共享数据库迁移、活动 manifest/runner allowlist 登记及真实渠道联机不属于本分支本地验收，仍须协调环境执行；fixture 结果不代表真实渠道已接通。KI-172 修复于 `281f03a`。
