# 当前系统架构

本文按当前仓库源码和活动部署模板整理。仓库中保留的旧 OpenSpec、迁移与验收记录属于历史审计资料，不是当前运行配置。

## 活动业务域

| 业务域 | 服务 | 主要职责 |
| --- | --- | --- |
| 房源 | `listing-master-service` | 房源主数据、检索、比较报告和 PDF |
| 客户 | `customer-service` | 客户/业主资料、跟进和收藏 |
| 营销内容 | `marketing-content-service` | 内容、素材、发布流程和效果来源 |
| 媒体 | `media-worker-service` | 异步图片素材任务 |
| 消息通知 | `notification-service` | 站内信、邮件和工作流事件 |
| 访谈 | `interview-service` | 访谈采集、归档、脱敏、逐字稿与发布来源 |
| 平台经营 | `platform-operations-service` | 经营分析、门店复盘和知识运营 |

合同、结算和旧的独立经营服务已退役。平台经营使用 `bk_platform_operations`；访谈仍由 `bk_interview` 管理事实数据，并通过受限版本化视图向平台经营提供符合条件的脱敏来源。平台经营应用和来源账号均不能直接读取访谈基础表。

## 请求与 Agent

- 外部业务 API 经过 `gateway` 和 `auth-service`；各服务入口再次验证签名授权上下文与当前授权版本。
- `agent-service` 的 Supervisor 负责规划、A2A 调度、并行执行、审批/恢复、异步任务、会话事件和工作流审计。
- Listing、Marketing、Media、Notification、Interview 通过官方 A2A 提供各自能力。平台经营只发布一张 `platform-operations-agent` Agent Card，按签名请求中的 `platformCapability=analytics|review|knowledge` 选择独立工具、操作权限及 Memory 身份。
- 复盘与知识案例的状态变更由业务 API 显式执行；Agent 只能按分支能力读取资料或起草内容，不代替用户确认计划、审核或发布。
- MCP 仅作为配置启用的工具接入通道；房源比较属于 Listing，不再依赖 Compare 服务或 Compare MCP。

## 数据和基础设施

- Auth、Agent、业务服务、Interview、Memory 和 RAG 使用各自受管数据库及 migration ledger。数据库初始化和升级必须经 `docker/mysql/init-manifest.tsv`。
- Memory 保存签名调用者可访问的会话、偏好、摘要和经验，不是真实经营事实来源；平台经营的 analytics、review、knowledge 使用独立 domain/agent 空间。
- RAG 管理检索索引、向量集合、embedding 和 rerank；是否可用取决于已配置空间和后端实际状态。
- Nacos 管理分布式运行配置与服务发现；Docker Compose 的服务地址使用 Compose DNS。`.env.example` 维护连接凭据和启动变量，不重复承载业务策略。
- Redis、RocketMQ、Elasticsearch、Milvus、MinIO 由 Compose profile 按开发需要启动；配置存在或容器健康不能单独证明业务来源和权限链已验收。

## 主要入口

- 平台经营 HTTP：`/platform-operations/**`
- 平台经营 A2A：`/.well-known/agent.json` 与 `/a2a`
- Listing：`/listings/**`；兼容旧比较入口时仍由 Listing Controller 验证来源与 owner
- Supervisor：`/agent/**`
- Nacos 活动配置清单：`nacos/README.md`
- 前端设计输入：[平台经营域前端功能清单](platform-operations-frontend-function-list.md)
- 服务图与源码依据：[当前架构图集](architecture-current/README.md)
