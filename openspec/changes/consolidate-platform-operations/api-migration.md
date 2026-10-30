# 平台经营接口迁移映射（历史对照）

本表记录旧业务入口到平台经营域的迁移关系，旧路径仅作历史对照。最终接口、输入输出、错误码和安全约束以[平台经营 OpenAPI 契约](../../../docs/contracts/platform-operations-v1.openapi.yaml)为准；供前端设计页面的功能说明见[平台经营域前端功能清单](../../../docs/platform-operations-frontend-function-list.md)。

| 原接口 | 目标接口 | 功能 |
| --- | --- | --- |
| GET `/business/kpis/monthly` | GET `/platform-operations/analytics/kpis/monthly` | 月度 KPI |
| GET `/business/workloads/daily` | GET `/platform-operations/analytics/workloads/daily` | 日工作量 |
| GET `/business/kpis/assessments` | GET `/platform-operations/analytics/kpis/assessments` | KPI 评估 |
| GET `/business/rankings` | GET `/platform-operations/analytics/rankings` | 排行榜 |
| POST `/business/rankings/rebuild` | POST `/platform-operations/analytics/rankings/rebuild` | 重建排行榜 |
| GET `/business/listing-turnover-report` | GET `/platform-operations/analytics/listing-turnover-report` | 房源流转 |
| GET `/business/store-dashboard` | GET `/platform-operations/analytics/store-dashboard` | 门店看板 |
| GET `/business/big-dashboard` | GET `/platform-operations/analytics/big-dashboard` | 综合看板 |
| GET `/business/health` | GET `/platform-operations/analytics/health` | 经营能力状态 |
| GET、POST `/business-review/reviews` | GET、POST `/platform-operations/reviews` | 列表与创建 |
| GET `/business-review/reviews/{reviewId}` | GET `/platform-operations/reviews/{reviewId}` | 复盘详情 |
| GET、POST `/business-review/reviews/{reviewId}/plans` | GET、POST `/platform-operations/reviews/{reviewId}/plans` | 查看、编写计划 |
| POST `/business-review/reviews/{reviewId}/plans/confirm` | POST `/platform-operations/reviews/{reviewId}/plans/confirm` | 确认计划 |
| GET `/business-review/reviews/{reviewId}/actions` | GET `/platform-operations/reviews/{reviewId}/actions` | 查看行动 |
| POST `/business-review/reviews/{reviewId}/actions/{actionId}/feedback` | POST `/platform-operations/reviews/{reviewId}/actions/{actionId}/feedback` | 行动反馈 |
| GET `/business-review/reviews/{reviewId}/observations` | GET `/platform-operations/reviews/{reviewId}/observations` | 观察指标 |
| POST `/knowledge/v1/cases` | POST `/platform-operations/knowledge/v1/cases` | 创建知识案例 |
| GET `/knowledge/v1/cases/{knowledgeId}` | GET `/platform-operations/knowledge/v1/cases/{knowledgeId}` | 案例详情 |
| POST `/knowledge/v1/cases/{knowledgeId}/{action}` | POST `/platform-operations/knowledge/v1/cases/{knowledgeId}/{action}` | action 分别为 edit、submit、generate、review、publish、withdraw |
| GET `/knowledge/v1/cases/{knowledgeId}/generation/{taskId}` | GET `/platform-operations/knowledge/v1/cases/{knowledgeId}/generation/{taskId}` | 生成任务状态 |
| POST `/knowledge/v1/published/{action}` | POST `/platform-operations/knowledge/v1/published/{action}` | action 分别为 search、batch-read、check |

迁移时复盘请求/响应和操作码参考已退役服务的原契约，知识发布接口参考知识域并行契约；这些旧文件不再是当前服务的运行依赖。平台经营 OpenAPI 与前端功能清单已随实现交付，本表不代替当前接口契约。

已识别调用方：复盘的 `DomainParallelHttpReadAdapter` 调用知识 search/check，合并后迁入域内调用并保留当前来源校验；`settlement-service` 使用 `BusinessRpcService.getMonthlyKpis`，随结算退役；Gateway、Supervisor 和 MCP/Agent 调用方须切到新身份与路径。仓库外调用方未核实。

数据归属：分析使用四张 `employee_kpi_stat`、`employee_daily_workload`、`listing_turnover_stat`、`store_dashboard_snapshot`；复盘使用 `store_review_*`；知识使用 `knowledge_*` 及访谈受限视图。现有操作包括 `business.*`、六个 `business.review.*.store` 和 `knowledge.case.*`；迁移时须逐操作映射，不自动授予角色。旧 Agent 为 trade、business-review、knowledge；目标为一个平台经营 Agent，按用户确认隔离三类工具、权限与记忆。
