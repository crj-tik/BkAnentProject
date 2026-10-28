# 内容营销域合并设计

## Context

动机见 proposal.md。`PromotionManagementServiceImpl.publishContent` 在本地事务内调用外部发布，再通过 `MarketingContentRpcService.updatePublishStatus` 回写内容，事务不覆盖外部调用或远端数据库。`syncEffectStat` 当前按发布记录 ID 构造曝光/点击/线索，并用 `leadCount * 1000 / cost` 得出样例 ROI，没有真实渠道来源。用户确认当前数据模拟。

`marketing-content-service` 已有内容、变体、素材、搜索和 `marketing-agent`；promotion 持有发布记录、效果和品牌素材。电子渠道 provider 仍有未实现路径。媒体 Worker 的异步计算与对象存储职责不随本业务合并迁移。

## Goals / Non-Goals

**Goals:** 以内容 revision 和发布计划串起审核、确认、平台尝试、对账与效果来源，消除内容状态跨服务回写。

**Non-Goals:** 不采购或伪造真实平台接入，不搬迁媒体 Worker/通知服务，不接管 Studio work/command/文件能力，不重构房源和知识域。

## Decisions

### 1. 保留 marketing-content-service，推广成为 campaign 包

将品牌素材、发布计划、平台尝试和效果记录迁入营销模块；`marketing-agent` 保留，推广能力通过同域工具/技能提供。相比重命名整个服务，该选择减少现有身份、Memory 和客户端配置调整；相比继续跨库双写，同域事务可以绑定计划、内容修订和本地回执。

数据迁入 `bk_marketing` 的独立表/命名段，建立 `legacySource/legacyId` 映射和唯一约束。原 `bk_promotion` 库/表先只读保留，ID 不能直接混用。营销搜索索引仍是原独立职责，本 change 不引入新的通用 RAG 执行链。

### 2. 内容、计划和尝试使用不同版本身份

ContentRevision 不可变；PublishPlan 固定 contentRevision、素材版本、目标平台和受权账号、规则版本及 argumentsHash。审核通过表示该内容/范围获准，店长/操作人确认计划才受理对应平台尝试；修改任一绑定必须创建新计划并重新确认。

请求幂等键绑定 owner、planId、requestId 和 bodyHash；相同请求找回原回执，不同内容冲突。一次多目标发布拥有每目标稳定 attemptId，部分成功可以单独核对失败目标，不能重发已成功目标。客户端或模型声明的账号名不授予发布权限。

### 3. 本地受理与外部成功分别保存

事务先保存本地 ACCEPTED/待执行尝试及可靠派发记录，外部 provider 调用在事务外完成；回执通过同一 attemptId 条件更新。provider 支持幂等时使用稳定外部键；不支持或超时结果不明时标记 OUTCOME_UNKNOWN，按原 ID 查询/人工核对，不自动重发。

内容的发布摘要从本域尝试记录生成，不再远程回写第二个真源。没有真实 provider 时在副作用前返回 PROVIDER_NOT_CONFIGURED；fixture 只在显式模拟模式使用，结果标记 SIMULATED，不能标成真实平台成功。外部授权和凭据沿受管配置注入，不进入模型或普通日志。

### 4. 效果来源与收益证据独立

按日期和渠道回执幂等接收曝光/点击/线索，附来源系统、原事件 ID/revision、观察窗口与数据模式。将现有确定性样例算法移到 fixture provider。重复同步不能重复累计，迟到修正按来源版本更新对应观察而不覆盖历史。

模式由受管 provider 确认；历史性质不明为 UNKNOWN，不从旧 publishStatus 推断真实。全部来源确认真实才聚合为 REAL，存在未知为 UNKNOWN，真实/模拟混合为 MIXED；具体指标是否可计算单独表达。

真实渠道缺字段时返回未知，不用 fixture 补足；只有实际可归属收入和成本数据时才提供标明定义版本的 ROI。线索数量可以形成线索成本等明确指标，不能乘固定金额生成收入。模拟结果与真实结果分别查询、聚合和导出。

### 5. 独立交付并约束旧入口

原 `/promotions/**` 可在营销服务内保留受权适配入口，不能继续保留独立写库和远程状态回写。`implement-studio-v2` 继续拥有发布命令/审批门面，本 change 提供领域 prepare/read/confirm/receipt 契约；Studio task 4.1/4.2/4.4 不在这里勾选或复制。

保留路径不等于保留旧 wire contract：当前迁移入口使用营销域新的请求/响应 DTO，不兼容旧 `/promotions/**` 负载。方案将这一点标记为 breaking migration，要求清点并迁移已知调用方；旧形状须返回明确的契约不支持错误，不猜测字段或静默套用新语义。若盘点发现必须支持的外部旧调用，需另行定义版本化适配契约。

房源仅通过公开来源引用读取，不新增 listing 私有表访问。营销 A2A 会话使用共享 Memory v2 hook，Memory owner 必须从认证租约解析，Memory 只属于个人会话上下文，不构成角色权限、房源关系或组织共享营销知识。当前 campaign 操作使用 Auth 的 `AuthPermissionRpcService` 兼容入口；这不是 Auth 版本化签名上下文的域内 adoption。Marketing 还没有内容 owner 字段/关系，且 Auth 权限目录当前未登记 campaign/content 操作码；这些必须通过 `unify-versioned-permission-context` task 10.3 补齐后才能启用真实用户操作。外部协议变更或不同微服务合并不要求把个人 Memory 改成组织知识。

### 6. 并行交付和可替换依赖

本会话只拥有营销/推广模块、`common/marketing/campaign/**` 和本域必要调用方差异；需要房源时按 `domain-parallel-v1` 的公开 HTTP契约实现自己的 consumer wire DTO/mapper和有界 fixture，不依赖房源 change 的新类型，也不引入知识或经营实现。固定路径、字段和错误见 [并行总览](../../../docs/business-domain-change-roadmap.md)。

发布生命周期、回执、持久派发和效果口径是真实实现。渠道 provider 缺失时真实 mode 失败关闭。Marketing A2A 生产路径使用必需 Memory hook；通用 hook 测试验证 Memory/授权不可用时在 Agent 执行前失败。Auth campaign 局部测试使用精确替身，只能证明本域逻辑对拒绝/撤权的反应，不能证明新 Auth 域目录、版本化上下文或内容资源授权已联通。旧业务数据迁移及单writer切换先在私有夹具/库验证，已运行共享实例由协调合并后处理。

独立 worktree/分支、`.m2repo`、端口/数据库按总览执行，只push自己的分支。用户要求本 change 同步维护数据库、Nacos 与 Compose 部署工件，因此营销 migration/check 已登记到版本化 manifest，runner 仅新增精确的 `upgrade:bk_marketing` allowlist；这不会修改已应用的 bootstrap SQL/hash，也不代表 migration 已在任何共享库执行。Compose 继续只部署 `marketing-content-service`，默认 MySQL MCP 只读范围排除保留作迁移/回退材料的 `bk_promotion`；Nacos 保留旧 promotion 配置作为历史制品，营销模板的房源快照地址与 listing 服务实际端口一致。Auth v2 的域准入由其共享 change task 10.3 所有；本 change 可以完成 fixture 业务闭环，但在营销内容操作码、owner/关系映射和 REST/RPC/A2A/MCP 统一消费版本化授权未通过验收前，不得作为完整生产授权合并或启用。

### 7. Supervisor、A2A、ReAct 与 MCP 分层治理

A2A 的 marketing-draft 技能只允许读取既有内容并返回未保存的草稿。营销服务内 ReAct 技能可检索、创建 DRAFT 内容并准备发布计划，但不得调用确认发布/通知能力，也不得仅凭模型输出把状态标为 PUBLISHED；只有真实平台 receipt 可证明发布结果。copy-generation 技能的流程在 prepare 后结束，确切确认由 Supervisor 的独立 marketing-campaign-publish 技能（agent-service/src/main/resources/skills/supervisor/marketing-campaign-publish.md）发起。

Supervisor 发布技能通过动态能力目录逐步调用结构化 MCP：草稿创建返回可复用的 contentId，prepare 返回 planId、planVersion、snapshotHash、内容/素材/渠道/账号快照且不产生外部副作用；用户看到完整计划后，confirmMarketingCampaign 以该 planId、expectedPlanVersion 和 expectedSnapshotHash 调用。Supervisor 的 approvalCapabilities 只配置确认工具的精确目录 capabilityId，不把审批放在不透明的整个 marketing-agent A2A 调用上。confirm 结果返回 receiptId 与每目标 attemptId/status；MCP 还须暴露回执读取和原 attempt reconcile，未知结果只核对原 attempt。

营销 change 维护自己的 Supervisor 发布技能文件，不改由房源 change 维护的找房/对比/草稿组合技能。每个实现分支在隔离 profile 用自己的 capabilityId 验收并交付配置差异；共享 agent.supervisor.orchestration.approval-capabilities 在四项合并协调时按能力 ID 并集合并，不由本 change 覆盖。域内 fixture 验收必须经过实际 Supervisor 目录/审批和 MCP 调用路径；它不代表真实平台、Auth/Memory 登记或生产跨服务联通已完成。

## Risks / Trade-offs

- [外部发布已发生但响应丢失] → 保留原 attempt、稳定外部 ID 和 UNKNOWN 对账，不用重新发布恢复。
- [旧数据 ID 冲突或被认为都是模拟可丢弃] → 来源命名空间、迁移清单、计数/hash 核对和旧库备份；未验证前保留旧表。
- [模拟指标进入经营结论] → 来源模式贯穿效果 API/聚合/导出，真实效果不由样例算法补齐。
- [部署期两个 writer 并行] → 停旧新接收并围栏写入后迁移，验证消费者切换后退役旧服务。

## Migration Plan

以下为合并后的共享部署计划；独立会话交付兼容、迁移/围栏/回退能力和隔离验证，不直接停原服务或迁移共享库。

1. 盘点 promotion HTTP/RPC/MCP 调用方、原库数据、在途尝试及 provider 能力；生成稳定来源映射，备份原数据。
2. 在营销库增加内容修订、计划、尝试、效果来源和回执表；迁移时保持原外部 ID/状态与来源，重复执行迁移不得复制记录。
3. 上线营销域 prepare/read/confirm/receipt 及旧路径适配，切换调用方；旧服务停止接收新发布，在途结果按原尝试核对。
4. 确认单 writer、回执可查询、历史记录一致后，退役 promotion 模块、Compose 服务及配置导入入口；原库/回滚制品保留。
5. 回退恢复匹配路由和消费版本，保持写围栏及原 attemptId，不把已成功发布再次执行，也不把模拟效果转换为真实。

## 功能覆盖矩阵与完成门槛

该 change 合并的是营销内容到推广效果的完整业务链。每个原功能都要落到统一营销域的目标入口，并记录调用方迁移和验收；只搬表或只把旧路径转成“准备计划”不构成完整合并。

| 原有能力/入口 | 统一营销域目标能力 | 完成证据与边界 |
| --- | --- | --- |
| 创建内容、创建渠道版本、更新审核/发布状态、按房源列表及多条件搜索 | 内容创建/变体、内容审核与状态机、按房源及条件检索 API | 验证新旧内容和渠道版本可查；发布状态只能由受控审核/有效回执推进，不能手工伪造已发布 |
| `POST /promotions/publish`、Dubbo `PromotionRpcService.publish` 的单次发布结果 | 创建或选择内容后准备计划、审核、精确展示计划、获批 confirm、查询 receipt/reconcile | 迁移所有调用方；prepare 只代表计划准备，只有 provider 回执才代表发布成功，UNKNOWN 沿原 attempt 核对 |
| 同步效果、按房源查询效果、ROI 报告 | 有来源和观察窗口的 effect sync/read、ROI 查询 | 覆盖重复同步、修正和缺失归属收入；不得把样例统计或线索数说成真实收入/ROI |
| 品牌素材创建和列表 | 统一营销域品牌资产管理 API | 既有素材可映射并保持可检索、可被新计划引用，来源与权限不丢失 |
| BEIKE、DOUYIN、XIAOHONGSHU 本地发布适配器 | 显式 fixture provider 与同目标回执模型 | 三个模拟目标可重复验收并标记 SIMULATED；没有真实 provider 时生产模式明确不可用，不回退模拟成功 |
| 旧 REST/Dubbo、ReAct、A2A、MCP、Supervisor 与 Studio/其它客户端 | 新营销 REST/DTO、草稿与发布分离的 A2A 技能、结构化 MCP 工具、Supervisor 确认审批能力 | 逐项迁移并验证活动目录和真实调用链；没有已知调用方依赖旧 DTO/旧写入口后才允许退役旧接收 |

若任一行缺少目标业务入口、调用迁移或可重复验收证据，旧推广部署和接收围栏不得按“合并完成”退役。真实平台接入是明确的独立边界，不得以 fixture 成功证明真实平台已接通。
