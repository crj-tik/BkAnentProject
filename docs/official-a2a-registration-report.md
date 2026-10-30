# Alibaba 官方 A2A 注册检查报告

检查范围：当前受管 Nacos 服务模板中的官方域 Agent 元数据，以及 `agent-service` 的静态/动态注册入口。结果反映仓库模板，不代替目标 Nacos namespace 配置回读或服务运行态注册检查。

## 当前活动 Agent

| Agent | 服务配置 | Provider | Agent Card | A2A endpoint | 流式 | 异步 |
| --- | --- | --- | --- | --- | --- | --- |
| `listing-agent` | `listing-master-service.yaml` | official | `/.well-known/agent.json` | `/a2a` | 是 | 是 |
| `platform-operations-agent` | `platform-operations-service.yaml` | official | `/.well-known/agent.json` | `/a2a` | 是 | 是 |
| `marketing-agent` | `marketing-content-service.yaml` | official | `/.well-known/agent.json` | `/a2a` | 是 | 是 |
| `media-agent` | `media-worker-service.yaml` | official | `/.well-known/agent.json` | `/a2a` | 是 | 是 |
| `notification-agent` | `notification-service.yaml` | official | `/.well-known/agent.json` | `/a2a` | 是 | 是 |
| `interview-agent` | `interview-service.yaml` | official | `/.well-known/agent.json` | `/a2a` | 是 | 是 |

平台经营服务只对外注册一张 `platform-operations-agent` Card。经营分析、门店经营复盘和知识运营由服务内部路由，工具授权与 Memory domain 仍分别隔离。

## 已退役 Agent 与配置

合同、结算、独立知识运营、独立门店复盘、旧交易 Agent 与 Compare Engine 不属于当前服务注册面。Compare Engine 的源码、维护测试、Compose 服务和旧 RAG 空间不再由当前代码或模板提供；旧 Nacos data ID 由受管清理脚本按精确白名单单独核查。历史验收材料保留为审计记录，不作为当前注册清单。

## 校验规则

`OfficialA2aRegistrationValidator` 在静态注册和动态发现路径检查：

- provider 只能是 `official` 或明确可验证为官方的 `auto`，`custom`、`custom_http` 和未知值直接拒绝；
- Agent Card 必须来自 `/.well-known/agent.json`；
- Agent Card 必须包含官方 A2A endpoint；
- 动态注册在 Agent Card 获取失败时不会创建 metadata fallback descriptor；
- 自定义任务创建、状态查询和流式路径不会参与运行时选择。

外部 Agent 若只提供旧自定义 HTTP 接口，会在注册阶段收到迁移错误。
