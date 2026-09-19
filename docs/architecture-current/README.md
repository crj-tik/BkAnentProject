# 当前架构图集

2026-09-20，根据当前工作区源码生成，包含生成前已存在的未提交代码。描述实现结构，不表示已部署或端到端验收。

打开 [图集浏览页](index.html) 查看全部图片。高清分层架构图：[PNG](architecture-overview.png) · [JPG](architecture-overview.jpg)。PNG 适合分享，SVG 适合放大与编辑。

| 图 | 位图 | 矢量图 |
|---|---|---|
| 服务架构｜模块与职责 | [PNG](01-service-architecture.png) | [SVG](01-service-architecture.svg) |
| Supervisor｜规划、路由与执行 | [PNG](02-supervisor-flow.png) | [SVG](02-supervisor-flow.svg) |
| 房源 Agent｜执行流程 | [PNG](03-listing-agent.png) | [SVG](03-listing-agent.svg) |
| 房源对比 Agent｜执行流程 | [PNG](04-compare-agent.png) | [SVG](04-compare-agent.svg) |
| 营销内容 Agent｜执行流程 | [PNG](05-marketing-agent.png) | [SVG](05-marketing-agent.svg) |
| 媒体素材 Agent｜执行流程 | [PNG](06-media-agent.png) | [SVG](06-media-agent.svg) |
| 经营分析 Agent｜执行流程 | [PNG](07-trade-agent.png) | [SVG](07-trade-agent.svg) |
| 合同审查 Agent｜执行流程 | [PNG](08-contract-agent.png) | [SVG](08-contract-agent.svg) |
| 佣金结算 Agent｜执行流程 | [PNG](09-settlement-agent.png) | [SVG](09-settlement-agent.svg) |
| 消息通知 Agent｜执行流程 | [PNG](10-notification-agent.png) | [SVG](10-notification-agent.svg) |
| 审批与恢复｜暂停、决策、继续 | [PNG](11-approval-resume.png) | [SVG](11-approval-resume.svg) |
| 数据流｜请求、上下文、产物与记忆 | [PNG](12-data-flow.png) | [SVG](12-data-flow.svg) |
| RAG｜房源索引与混合检索 | [PNG](13-rag-flow.png) | [SVG](13-rag-flow.svg) |
| 异步与事件｜任务执行、流式反馈、通知 | [PNG](14-async-events.png) | [SVG](14-async-events.svg) |
| Skills 与工具｜当前接入边界 | [PNG](15-skills-tools.png) | [SVG](15-skills-tools.svg) |
| 全系统总览｜房地产中台多 Agent 系统 | [PNG](16-system-overview.png) | [SVG](16-system-overview.svg) |

## 重要实现边界

- 官方 A2A 子 Agent 共 8 个，直接绑定本地 @Tool；历史 AgentService 与 MCP 入口不能混画为官方必经路径。
- 官方主图并行映射有 7 个领域，未包含 compare。
- 营销 publishContent 仅更新本地发布状态。
- 媒体消费者实际生成图片，不是完整视频生成链路。
- 默认规划为 rule-first、LLM 规划关闭；会话流 provider 默认 memory。
- SubAgentSkillSupport 已存在，但未见官方 Provider 接入。
- 外部发布、邮件、签章、媒体 Provider 受集成模式约束，不能因本地模拟成功认定真实集成可用。

## 复现

使用安装 Pillow 的 Python 运行 `python docs/architecture-current/generate.py`。脚本使用 Windows 微软雅黑字体，并检查文本是否溢出。`manifest.json` 记录图内容、参考文件 SHA-256 及生成时 HEAD；HEAD 不代表工作区没有未提交修改。

## 源码依据

### 服务架构｜模块与职责
- [`pom.xml`](../../pom.xml)
- [`gateway/src/main/resources/application.yml`](../../gateway/src/main/resources/application.yml)
- [`nacos/agent-service.yaml`](../../nacos/agent-service.yaml)
### Supervisor｜规划、路由与执行
- [`agent-service/src/main/java/com/bkanent/agent/graph/official/OfficialSupervisorGraphFactory.java`](../../agent-service/src/main/java/com/bkanent/agent/graph/official/OfficialSupervisorGraphFactory.java)
- [`agent-service/src/main/java/com/bkanent/agent/service/A2aExecutionService.java`](../../agent-service/src/main/java/com/bkanent/agent/service/A2aExecutionService.java)
- [`nacos/agent-service.yaml`](../../nacos/agent-service.yaml)
### 房源 Agent｜执行流程
- [`listing-master-service/src/main/java/com/bkanent/listing/a2a/ListingOfficialA2aAgent.java`](../../listing-master-service/src/main/java/com/bkanent/listing/a2a/ListingOfficialA2aAgent.java)
- [`listing-master-service/src/main/java/com/bkanent/listing/a2a/A2aSupervisorContextInterceptor.java`](../../listing-master-service/src/main/java/com/bkanent/listing/a2a/A2aSupervisorContextInterceptor.java)
- [`listing-master-service/src/main/java/com/bkanent/listing/tool/ListingTools.java`](../../listing-master-service/src/main/java/com/bkanent/listing/tool/ListingTools.java)
### 房源对比 Agent｜执行流程
- [`compare-engine-service/src/main/java/com/bkanent/compare/a2a/CompareOfficialA2aAgent.java`](../../compare-engine-service/src/main/java/com/bkanent/compare/a2a/CompareOfficialA2aAgent.java)
- [`compare-engine-service/src/main/java/com/bkanent/compare/a2a/A2aSupervisorContextInterceptor.java`](../../compare-engine-service/src/main/java/com/bkanent/compare/a2a/A2aSupervisorContextInterceptor.java)
- [`compare-engine-service/src/main/java/com/bkanent/compare/tool/CompareTools.java`](../../compare-engine-service/src/main/java/com/bkanent/compare/tool/CompareTools.java)
### 营销内容 Agent｜执行流程
- [`marketing-content-service/src/main/java/com/bkanent/marketing/a2a/MarketingOfficialA2aAgent.java`](../../marketing-content-service/src/main/java/com/bkanent/marketing/a2a/MarketingOfficialA2aAgent.java)
- [`marketing-content-service/src/main/java/com/bkanent/marketing/a2a/A2aSupervisorContextInterceptor.java`](../../marketing-content-service/src/main/java/com/bkanent/marketing/a2a/A2aSupervisorContextInterceptor.java)
- [`marketing-content-service/src/main/java/com/bkanent/marketing/tool/MarketingTools.java`](../../marketing-content-service/src/main/java/com/bkanent/marketing/tool/MarketingTools.java)
### 媒体素材 Agent｜执行流程
- [`media-worker-service/src/main/java/com/bkanent/media/a2a/MediaOfficialA2aAgent.java`](../../media-worker-service/src/main/java/com/bkanent/media/a2a/MediaOfficialA2aAgent.java)
- [`media-worker-service/src/main/java/com/bkanent/media/a2a/A2aSupervisorContextInterceptor.java`](../../media-worker-service/src/main/java/com/bkanent/media/a2a/A2aSupervisorContextInterceptor.java)
- [`media-worker-service/src/main/java/com/bkanent/media/tool/MediaTools.java`](../../media-worker-service/src/main/java/com/bkanent/media/tool/MediaTools.java)
### 经营分析 Agent｜执行流程
- [`business-service/src/main/java/com/bkanent/business/a2a/TradeOfficialA2aAgent.java`](../../business-service/src/main/java/com/bkanent/business/a2a/TradeOfficialA2aAgent.java)
- [`business-service/src/main/java/com/bkanent/business/a2a/A2aSupervisorContextInterceptor.java`](../../business-service/src/main/java/com/bkanent/business/a2a/A2aSupervisorContextInterceptor.java)
- [`business-service/src/main/java/com/bkanent/business/tool/TradeTools.java`](../../business-service/src/main/java/com/bkanent/business/tool/TradeTools.java)
### 合同审查 Agent｜执行流程
- [`contract-service/src/main/java/com/bkanent/contract/a2a/ContractOfficialA2aAgent.java`](../../contract-service/src/main/java/com/bkanent/contract/a2a/ContractOfficialA2aAgent.java)
- [`contract-service/src/main/java/com/bkanent/contract/a2a/A2aSupervisorContextInterceptor.java`](../../contract-service/src/main/java/com/bkanent/contract/a2a/A2aSupervisorContextInterceptor.java)
- [`contract-service/src/main/java/com/bkanent/contract/tool/ContractTools.java`](../../contract-service/src/main/java/com/bkanent/contract/tool/ContractTools.java)
### 佣金结算 Agent｜执行流程
- [`settlement-service/src/main/java/com/bkanent/settlement/a2a/SettlementOfficialA2aAgent.java`](../../settlement-service/src/main/java/com/bkanent/settlement/a2a/SettlementOfficialA2aAgent.java)
- [`settlement-service/src/main/java/com/bkanent/settlement/a2a/A2aSupervisorContextInterceptor.java`](../../settlement-service/src/main/java/com/bkanent/settlement/a2a/A2aSupervisorContextInterceptor.java)
- [`settlement-service/src/main/java/com/bkanent/settlement/tool/SettlementTools.java`](../../settlement-service/src/main/java/com/bkanent/settlement/tool/SettlementTools.java)
### 消息通知 Agent｜执行流程
- [`notification-service/src/main/java/com/bkanent/notification/a2a/NotificationOfficialA2aAgent.java`](../../notification-service/src/main/java/com/bkanent/notification/a2a/NotificationOfficialA2aAgent.java)
- [`notification-service/src/main/java/com/bkanent/notification/a2a/A2aSupervisorContextInterceptor.java`](../../notification-service/src/main/java/com/bkanent/notification/a2a/A2aSupervisorContextInterceptor.java)
- [`notification-service/src/main/java/com/bkanent/notification/tool/NotificationTools.java`](../../notification-service/src/main/java/com/bkanent/notification/tool/NotificationTools.java)
### 审批与恢复｜暂停、决策、继续
- [`agent-service/src/main/java/com/bkanent/agent/graph/official/OfficialSupervisorGraphFactory.java`](../../agent-service/src/main/java/com/bkanent/agent/graph/official/OfficialSupervisorGraphFactory.java)
- [`agent-service/src/main/java/com/bkanent/agent/graph/official/OfficialApprovalDecisionGraphFactory.java`](../../agent-service/src/main/java/com/bkanent/agent/graph/official/OfficialApprovalDecisionGraphFactory.java)
- [`sql/migrations/20260918_agent_workflow_approval_claim.sql`](../../sql/migrations/20260918_agent_workflow_approval_claim.sql)
### 数据流｜请求、上下文、产物与记忆
- [`agent-service/src/main/java/com/bkanent/agent/client/OfficialA2aAgentClient.java`](../../agent-service/src/main/java/com/bkanent/agent/client/OfficialA2aAgentClient.java)
- [`agent-service/src/main/java/com/bkanent/agent/memory/HttpMemoryStoreClient.java`](../../agent-service/src/main/java/com/bkanent/agent/memory/HttpMemoryStoreClient.java)
- [`memory-service/src/main/java/com/bkanent/memory/controller/MemoryController.java`](../../memory-service/src/main/java/com/bkanent/memory/controller/MemoryController.java)
### RAG｜房源索引与混合检索
- [`agent-service/src/main/java/com/bkanent/agent/milvus/listing/ListingMilvusService.java`](../../agent-service/src/main/java/com/bkanent/agent/milvus/listing/ListingMilvusService.java)
- [`agent-service/src/main/java/com/bkanent/agent/milvus/listing/BgeRerankService.java`](../../agent-service/src/main/java/com/bkanent/agent/milvus/listing/BgeRerankService.java)
- [`nacos/agent-service.yaml`](../../nacos/agent-service.yaml)
### 异步与事件｜任务执行、流式反馈、通知
- [`agent-service/src/main/java/com/bkanent/agent/service/SupervisorAsyncRuntimeDispatcher.java`](../../agent-service/src/main/java/com/bkanent/agent/service/SupervisorAsyncRuntimeDispatcher.java)
- [`agent-service/src/main/java/com/bkanent/agent/stream/RocketMqSessionEventBus.java`](../../agent-service/src/main/java/com/bkanent/agent/stream/RocketMqSessionEventBus.java)
- [`notification-service/src/main/java/com/bkanent/notification/service/NotificationWorkflowEventService.java`](../../notification-service/src/main/java/com/bkanent/notification/service/NotificationWorkflowEventService.java)
- [`nacos/agent-service.yaml`](../../nacos/agent-service.yaml)
### Skills 与工具｜当前接入边界
- [`agent-service/src/main/java/com/bkanent/agent/skill/SkillFileLoader.java`](../../agent-service/src/main/java/com/bkanent/agent/skill/SkillFileLoader.java)
- [`agent-service/src/main/java/com/bkanent/agent/graph/node/SkillMatchNode.java`](../../agent-service/src/main/java/com/bkanent/agent/graph/node/SkillMatchNode.java)
- [`agent-service/src/main/java/com/bkanent/agent/skill/SubAgentSkillSupport.java`](../../agent-service/src/main/java/com/bkanent/agent/skill/SubAgentSkillSupport.java)
- [`contract-service/src/main/java/com/bkanent/contract/a2a/ContractOfficialA2aAgent.java`](../../contract-service/src/main/java/com/bkanent/contract/a2a/ContractOfficialA2aAgent.java)
### 全系统总览｜房地产中台多 Agent 系统
- [`pom.xml`](../../pom.xml)
- [`agent-service/src/main/java/com/bkanent/agent/graph/official/OfficialSupervisorGraphFactory.java`](../../agent-service/src/main/java/com/bkanent/agent/graph/official/OfficialSupervisorGraphFactory.java)
- [`nacos/agent-service.yaml`](../../nacos/agent-service.yaml)
- [`memory-service/src/main/java/com/bkanent/memory/controller/MemoryController.java`](../../memory-service/src/main/java/com/bkanent/memory/controller/MemoryController.java)
