# 平台经营接口迁移盘点（草案）

以下目标路径用于实施，不代表已上线。除路径和服务身份外，输入、响应、状态机与权限应沿用原契约；实现时同步维护 OpenAPI 并验收每个调用方。

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

复盘请求/响应和操作码以 `business-review-service/src/main/resources/openapi/business-review-api.yaml` 为迁移基线；知识发布接口以 `docs/contracts/knowledge-domain-parallel-v1.openapi.yaml` 为基线；知识案例 DTO 与经营分析参数以对应控制器及 DTO 为基线。新接口 OpenAPI、错误码和鉴权示例须随实现生成，不能把本表当作已完成的前端契约。

已识别调用方：复盘的 `DomainParallelHttpReadAdapter` 调用知识 search/check，合并后迁入域内调用并保留当前来源校验；`settlement-service` 使用 `BusinessRpcService.getMonthlyKpis`，随结算退役；Gateway、Supervisor 和 MCP/Agent 调用方须切到新身份与路径。仓库外调用方未核实。

数据归属：分析使用四张 `employee_kpi_stat`、`employee_daily_workload`、`listing_turnover_stat`、`store_dashboard_snapshot`；复盘使用 `store_review_*`；知识使用 `knowledge_*` 及访谈受限视图。现有操作包括 `business.*`、六个 `business.review.*.store` 和 `knowledge.case.*`；迁移时须逐操作映射，不自动授予角色。旧 Agent 为 trade、business-review、knowledge；目标为一个平台经营 Agent，按用户确认隔离三类工具、权限与记忆。
