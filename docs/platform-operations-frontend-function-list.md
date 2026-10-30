# 平台经营域前端功能清单

**用途**：交给前端设计页面、菜单、交互和 API 接入。当前仓库没有平台经营前端；下文按现有 Controller、DTO 和授权守卫整理，表示后端已提供的能力，不代表所有账号都拥有权限或外部来源已齐备。

**域边界**：经营分析、门店经营复盘和知识运营合并到平台经营域。对外只有一个平台经营 Agent；页面通过业务 API 读取和写入，不直接调用 Agent 来代替状态变更。合同、结算与 Compare 独立服务不在当前功能范围内；房源比较仍由 Listing 提供。

**接口契约**：[平台经营 API OpenAPI](contracts/platform-operations-v1.openapi.yaml)；已发布知识检索/批量读取的详细响应契约见 [published knowledge contract](contracts/platform-operations-published-knowledge-v1.openapi.yaml)。

## 页面与功能

| 页面 | 主要功能 | 当前接口 | 设计要点 |
| --- | --- | --- | --- |
| 经营总览 | 按月查看 KPI、门店/区域汇总、业绩和房源流转摘要 | `GET /platform-operations/analytics/big-dashboard?month&startDate&endDate&regionName&topN` | 日期区间、月份和区域筛选；分别呈现门店数、在售房源、带看、新客、成交、业绩、满意度、流转天数；区分 0 与无数据 |
| KPI 与考核 | 查看员工月度 KPI、完成率、转化率、满意度等 | `GET /analytics/kpis/monthly?month`、`GET /analytics/kpis/assessments?month` | 显示员工、成交、新增房源/客户和完成率；指标以服务返回为准 |
| 员工工作量 | 查看指定日期的员工工作量 | `GET /analytics/workloads/daily?statDate` | 日期选择；展示员工、门店、区域、带看、新房源、新客户、跟进数 |
| 排行榜 | 按月份和范围查看个人、门店或区域排名；有权限时重建 | `GET /analytics/rankings?month&scope&topN`、`POST /analytics/rankings/rebuild?month` | `scope` 为 `PERSONAL`、`STORE`、`REGION`；重建为有副作用的管理动作，应二次确认并显示完成/失败状态 |
| 房源流转 | 查询房源从发布到带看到成交的天数与状态 | `GET /analytics/listing-turnover-report?month` | 展示房源、门店、区域、分阶段天数、总流转天数和流转状态 |
| 门店看板 | 查看指定门店的经营概览 | `GET /analytics/store-dashboard?storeName` | `storeName` 是查询参数；授权仍由服务端上下文核验，页面不得据此建立用户门店身份 |
| 复盘列表 | 查看可信门店范围内的复盘事项，游标分页 | `GET /platform-operations/reviews?storeId&cursor&limit` | 返回下一游标与复盘事项；门店 ID 必须来自当前 Auth 身份允许的范围，不能通过任意输入绕过后端门店校验 |
| 创建复盘 | 选择有效数据集并创建复盘事项 | `POST /platform-operations/reviews`，正文 `{storeId, datasetId}` | 现有后端没有独立的数据集目录接口；数据集选项需由前后端另行约定，不应在页面里伪造 ID |
| 复盘详情 | 查看诊断、来源事实、来源修订、模拟/真实模式、计划、动作和观察值 | `GET /reviews/{reviewId}`、`GET /reviews/{reviewId}/plans`、`GET /reviews/{reviewId}/actions`、`GET /reviews/{reviewId}/observations` | 对假设和证据缺口做视觉区分；来源修订过期时显示冲突并要求刷新，不呈现失效正文 |
| 复盘计划 | 创建或更新计划，确认计划并生成行动项 | `POST /reviews/{reviewId}/plans`、`POST /reviews/{reviewId}/plans/confirm` | 携带 `expectedMatterVersion`、`expectedDiagnosisRevision`、`expectedPlanVersion`；确认操作显示动作清单和到期时间，使用 `requestId` 幂等提交 |
| 行动反馈 | 更新行动执行状态并记录证据等级和说明 | `POST /reviews/{reviewId}/actions/{actionId}/feedback` | 状态为 `IN_PROGRESS`、`DONE`、`CANCELLED`；证据为 `USER_REPORTED` 或 `SOURCE_CONFIRMED`；不能把人工反馈自动展示成已验证业绩 |
| 知识案例 | 创建、查看和编辑案例修订 | `POST /knowledge/v1/cases`、`GET /knowledge/v1/cases/{knowledgeId}`、`POST /{knowledgeId}/edit` | 正文包含问题、场景、前提、动作、观察结果、适用条件、未验证项和证据等级；提交编辑时传 `expectedVersion` |
| 知识候选生成 | 提供访谈资产 ID 和规则版本，生成异步候选并查看任务状态 | `POST /{knowledgeId}/generate`、`GET /{knowledgeId}/generation/{taskId}` | Agent 只接触获准的来源 ID、修订和任务状态；界面不展示逐字稿原文。来源撤回、版本过期或服务不可用时显示可识别状态 |
| 知识审核与发布 | 提交审核、审核通过/拒绝、发布、撤回 | `POST /{knowledgeId}/submit`、`/review`、`/publish`、`/withdraw` | 审核人不能是案例 owner；批准和发布是分开的动作；写操作携带 `requestId` 与 `expectedVersion`，展示状态变更回执 |
| 已发布知识检索 | 按问题、场景和标签检索可见案例，或按精确 revision 检查/读取 | `POST /knowledge/v1/published/search`、`/batch-read`、`/check` | 常规知识运营页优先展示搜索结果；批量读取和 check 用于引用校验。每次返回都重新验证当前发布版本和访谈来源 |

所有路径以 Gateway 根路径为准，域前缀是 `/platform-operations`。分析接口的查询参数需使用实际日期/月格式；评估、筛选等规则由后端返回值定义。

## 关键状态与字段

### 经营分析

- KPI：`employeeId`、`employeeName`、`closedDeals`、`newListings`、`newCustomers`、`completionRate`。
- 考核：成交量、业绩金额、完成率、转化率、满意度等指标；数值为后端 `BigDecimal` 或整数。
- 工作量：`statDate`、`viewingCount`、`newListings`、`newCustomers`、`followUpCount`。
- 看板：门店/区域聚合、在售房源、带看、新客、成交、业绩、满意度与流转天数。
- 当前接口未提供分析列表的分页游标；前端不应自行假设分页 API。

### 门店复盘

- Matter 状态：`OPEN`、`PLAN_PENDING_REVIEW`、`PLANNED`、`IN_PROGRESS`、`CLOSED`。
- Action 状态：`PROPOSED`、`CONFIRMED`、`IN_PROGRESS`、`DONE`、`CANCELLED`。
- 来源模式：`REAL`、`SIMULATED`、`MIXED`、`UNKNOWN`。显示模式及来源引用，不能将模拟或混合结果标成真实成效。
- 诊断包含确认事实、假设、证据缺口和建议动作；计划会绑定事项版本、诊断修订和来源版本。
- `SOURCE_CONFIRMED` 只表示来源确认等级，不自动证明动作造成业绩改善。

### 知识运营

- 案例经过草稿编辑、提交审核、人工决定、发布或撤回的版本化生命周期。
- 详情包含 `state`、`currentRevision`、`publishedRevision`、`dataMode`、`version`、`contentHash`、`summary`、`body`、`sourceRefs` 和 `updatedAt`。
- 生成任务提供 `taskId`、`status`、`revision`、`errorCode`；页面据此呈现处理中、完成和失败状态。
- 读写前端不传 `actorId`；`storeId` 不能代替 Auth 签发的可信门店身份。

## 登录、授权和反馈

- 用户先通过 Auth 登录，由前端携带 `Authorization: Bearer <access token>` 请求 Gateway。内部授权签名头由 Gateway/服务链维护，浏览器不生成或篡改。
- 菜单和按钮依据当前用户获授操作显示；后端仍逐请求检查操作权限、资源 owner、可信门店、版本和来源状态。`401` 提示重新登录，`403` 提示无权访问。
- 建议统一处理错误 envelope：`{success, code, message, data}`。`409` 表示版本/状态/来源冲突；`404` 表示资源不存在或当前调用者不能读取；`503` 表示数据库或来源服务不可用。
- 所有写操作用唯一 `requestId`；版本化请求在冲突后刷新详情，再由用户确认重试。
- 空列表是正常状态。数据库没有业务事实时展示空态，不用 fixture 或虚构 KPI 填充。

## 当前前端接入缺口

1. 知识案例目前只有按 ID 查询，没有案例列表、我的草稿/待审筛选、服务端分页或标签字典接口；页面列表需要新增后端 API 后才能完整实现。
2. 复盘创建需要 `datasetId`，目前没有数据集目录接口；需要明确数据集来源和前端如何选取。
3. 分析 API 的授权角色/范围由 Auth 当前 operation catalog 决定，页面路由守卫需接入 Auth 的实际权限查询，不得按姓名、角色名或门店输入推断。
4. 页面无前端实现，因此交互稿仍需联调后端校验、错误码和目标环境实际数据源；成功的空列表不代表数据源故障。
