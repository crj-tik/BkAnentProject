# 四域前端功能清单

基线：`dev-fix`，代码状态按 2026-10-29 本次后端收尾后的版本整理。本文供页面设计和前后端对齐使用；“已实现”表示当前服务代码有对应入口，不代表所有目标环境已部署或完成真实身份、数据源及跨服务联调。接口路径为服务端路径；网关模板包含 `/listings/**`、`/knowledge/**`、`/business-review/**` 和 `/marketing/**` 路由，运行环境是否已发布需另行确认。

首批使用者为知识运营人员和店长；房源、营销能力还需按实际账号权限展示。页面应以服务端返回的权限与数据为准，不从用户输入的门店、经纪人、角色或数据模式推定权限。

## 页面总览

| 页面 | 用户可以完成的事 | 设计与接入状态 |
| --- | --- | --- |
| 房源查询与比较 | 检索获准房源，选择 2–6 套，生成比较报告并下载 PDF | Listing 比较、按 reportId 读取、PDF 和旧 `/compare/**` 兼容入口已实现；匿名分享已退役。当前没有“我的报告”分页接口；来源与签名身份仍需在目标环境联调 |
| 知识运营 | 创建案例、编辑、生成候选、提交审核、审核、发布与撤回 | 单案例操作已实现；待办列表和真实身份、来源、Memory 联调仍需补齐或确认 |
| 知识检索 | 按问题、场景和标签查找已发布知识，查看适用条件与来源 | 发布知识查询契约已实现；是否由浏览器直接调用取决于实际身份接线 |
| 门店经营复盘 | 发起复盘、查看证据和诊断，拟定并确认计划，记录反馈、查看观察指标 | 服务接口已实现；真实 STORE 身份、权限与 Memory 未完成接线 |
| 内容营销 | 创建和筛选内容、管理素材、审核内容、准备发布、确认及查看回执和效果 | campaign 接口已实现；真实渠道和 Auth v2 逐入口准入仍待验收 |

## 房源查询与比较

1. **房源选择**：展示标题、面积、总价、户型等来源事实，并展示数据模式、观察时间和未知字段。查询返回空列表时展示无匹配；来源不可用时提示稍后重试。房源来源查询 `POST /listings/business/v1/search` 使用 `domain-parallel-v1`，支持 keyword、cursor、limit；身份与服务间调用方式需接入时确认。
2. **比较配置**：选择 2–6 套房源，提交每套的来源标识及当前版本，可选择是否包含结论。调用 `POST /listings/comparisons`。保留服务端返回的 reportId，以便后续读取。
3. **比较报告**：展示房源快照、比较行、指标、结论、数据模式和生成时间；读取 `GET /listings/comparisons/{reportId}`，PDF 使用 `GET /listings/comparisons/{reportId}/pdf`，需要当前身份有权读取。
4. **状态处理**：来源版本变化时要求重新选择或重新生成；无权访问、来源不可用和无数据应分别提示。金额单位以服务端返回为准，来源契约金额单位为元。

报告详情可以使用创建时返回的 `reportId` 再读；当前没有按当前用户列出全部报告的 API，因此不能设计为已有完整的“我的比较历史”列表。匿名分享与分享码入口已退役。旧 `/compare/listings`、`/compare/reports` 仅为现有调用方保留兼容，新的页面应使用 `/listings/comparisons` 契约。旧路径仍需要登录后的签名身份，不能用兼容入口绕过来源和 owner 检查。房源管理还提供 `/listings` 下的创建、编辑、删除、搜索、状态、资产、OCR 和核验入口；这些入口的真实数据源、操作授权和迁移回归仍需逐项确认。实现边界见 [切换清单](listing-comparison-cutover.md)；历史 change 任务状态以归档材料为准。

## 知识运营与检索

### 知识案例

| 操作 | 需设计的内容 | 接口 |
| --- | --- | --- |
| 创建 | 范围、标题、问题、场景、标签，以及案例正文 | `POST /knowledge/v1/cases` |
| 详情与编辑 | 展示当前状态、修订、版本、摘要、来源；编辑前提、动作、观察结果、适用边界、未核实项及证据级别 | `GET /knowledge/v1/cases/{knowledgeId}`；`POST .../{knowledgeId}/edit` |
| 生成候选 | 选择逐字稿来源 assetId，显示任务状态、失败原因及生成结果；保留人工复核环节 | `POST .../{knowledgeId}/generate`；`GET .../{knowledgeId}/generation/{taskId}` |
| 提交、审核、发布、撤回 | 显示提交、通过或拒绝及原因、发布、撤回等动作；按实际权限与状态控制可见和可操作性 | `POST .../{knowledgeId}/submit`、`/review`、`/publish`、`/withdraw` |

案例状态为 `DRAFT`、`REVIEW_PENDING`、`PUBLISHED`、`WITHDRAWN`。审核通过与发布是两个动作；审核权限和创建者权限分别控制。编辑及状态操作携带服务端返回的版本和本次请求 ID；版本冲突后应重新读取，重复提交应沿用原请求 ID 查询或重试。

目前 `KnowledgeCaseController` 只有按 ID 读取，未发现草稿列表、审核待办列表或案例分页入口。页面可以先设计列表和筛选，但需要后端补充契约后才能实现完整工作台，不能把已发布搜索当作草稿或待审列表。逐字稿选择器的数据来源与真实授权也需另行对接。

### 已发布知识

按问题、场景、标签检索，展示摘要、适用条件、来源引用、修订和数据模式；读取时应复核来源可用性。相关接口为 `POST /knowledge/v1/published/search`、`/batch-read` 和 `/check`，请求使用 `domain-parallel-v1`。来源失效、撤回或无权访问时停止展示受限正文；依赖不可用时展示不可用，不能将旧正文当作最新结果。

知识服务的目标环境数据库、Interview 来源、真实 Auth/Memory 和 STORE 身份尚待运行态验收，见 [知识服务运行手册](knowledge-service-operations.md) 与 KI-218。

## 门店经营复盘

1. **复盘列表和发起**：按门店查看复盘及游标分页，创建时选择门店与数据集。接口：`GET /business-review/reviews`、`POST /business-review/reviews`。
2. **复盘详情**：展示数据模式、事实、证据、诊断中的已确认事实、假设、证据缺口和建议动作。接口：`GET /business-review/reviews/{reviewId}`。
3. **计划与确认**：展示计划历史及来源版本，编辑动作和期限；确认前展示计划版本及参数摘要。接口：`GET/POST .../{reviewId}/plans`、`POST .../{reviewId}/plans/confirm`。版本变化、范围变化或来源过期时，重新读取并要求重新确认；重试使用同一 requestId。
4. **动作反馈与观察**：列出动作，记录状态、反馈说明和证据级别；分别展示人工反馈和来源确认。接口：`GET .../{reviewId}/actions`、`POST .../{reviewId}/actions/{actionId}/feedback`、`GET .../{reviewId}/observations`。观察指标需展示窗口、时区、分子、分母和未知原因；变化不应直接展示为因果增长。

复盘状态包含 `OPEN`、`PLAN_PENDING_REVIEW`、`PLANNED`、`IN_PROGRESS`、`CLOSED`；动作状态包含 `PROPOSED`、`CONFIRMED`、`IN_PROGRESS`、`DONE`、`CANCELLED`。完成动作只记录推进反馈，不代表房源状态、成交或业绩已自动变化。

生产 STORE 身份、门店范围、Auth 和 Memory 的真实接线尚未完成；计划准备与确认的 Supervisor 专属技能及审批接入也仍待完成，详见 [门店复盘 change 任务](../openspec/changes/implement-store-business-review/tasks.md)。

## 内容营销与推广

1. **内容工作区**：创建内容及平台变体，按房源、平台、关键词、标签、类型、审核和发布状态筛选。入口为 `POST /marketing/contents`、`POST /marketing/contents/{id}/variants`、`GET /marketing/contents/search`；`GET /marketing/contents` 要求 listingId。内容包括标题、文案、媒体地址、封面、视频和标签。
2. **内容审核和品牌素材**：审核内容 `POST /marketing/campaigns/contents/{contentId}/review`；新增或查询品牌素材 `POST/GET /marketing/campaigns/brand-assets`。素材地址的上传、存储与访问权限需另行确认，不能假设此接口完成文件上传。
3. **发布准备与确认**：选择内容、素材和目标平台账号，调用 `POST /marketing/campaigns/prepare`；展示计划的内容版本、目标、有效期及数据模式，读取 `GET /marketing/campaigns/plans/{planId}`；确认调用 `POST /marketing/campaigns/plans/{planId}/confirm`，携带服务端计划版本、快照 hash 和 requestId。内容、素材、账号或规则变化后重新准备，不能沿用旧确认。
4. **回执与对账**：按计划和 requestId 或 receiptId 查询回执；逐目标展示状态、平台、账号和更新时间。未知结果应使用 `POST /marketing/campaigns/attempts/{attemptId}/reconcile` 对账，避免创建新的重复发布。
5. **效果与 ROI**：读取或同步单次尝试效果，以及查询 ROI 汇总。接口分别为 `GET .../attempts/{attemptId}/effects`、`POST .../attempts/{attemptId}/effects/sync`、`GET /marketing/campaigns/roi-report`。展示统计窗口、数据来源、模式、未知项及不可用原因；缺少可归属收入或成本时，ROI 展示为未知。

账号选择、可用平台和素材上传若需要独立页面，应先确认相应管理接口与权限；当前上述控制器没有提供账号列表接口。真实渠道未配置时应展示不可用，模拟发布及效果必须明显标记。Marketing 的 Auth v2 逐入口准入仍待完成，见 [营销 change 任务](../openspec/changes/consolidate-marketing-promotion-domain/tasks.md)。

## 共同交互规则

- 来源模式显示 `REAL`、`SIMULATED`、`MIXED`、`UNKNOWN`；仅服务端确定模式。模拟数据和未知数据不能标成真实经营结果。
- 显示空结果、无权、版本过期、依赖不可用和操作结果未知等不同状态。无权或失效后不继续展示受限正文；未知外部结果先查询原回执或对账。
- 保留服务端返回的 ID、版本和 hash。对接口要求 requestId 的动作，新动作生成新 requestId，同一动作的网络重试沿用原 requestId。服务端才是权限、状态与数据真实性的判断方。
- 本清单记录现有服务能力和页面设计需求；不代表浏览器调用、网关部署、跨域联调或真实外部渠道已经验证。Studio v2 尚有独立未完成任务，不能假设这些页面已通过 Studio 完整接入。

接口字段与错误码以代码及对应契约为准：[房源业务接口](listing-business-api-v1.md)、[知识发布接口 OpenAPI](contracts/knowledge-domain-parallel-v1.openapi.yaml)、[门店复盘控制器](../business-review-service/src/main/java/com/bkanent/businessreview/web/ReviewController.java)、[营销活动控制器](../marketing-content-service/src/main/java/com/bkanent/marketing/controller/CampaignController.java)。
