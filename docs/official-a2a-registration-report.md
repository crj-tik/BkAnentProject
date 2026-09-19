# Alibaba 官方 A2A 注册检查报告

检查范围：`nacos/*-service.yaml` 中的域 Agent 注册元数据，以及 `agent-service` 的静态/动态注册入口。

## 当前注册结果

| Agent | 服务配置 | provider | Agent Card | A2A endpoint | 流式 | 异步 |
| --- | --- | --- | --- | --- | --- | --- |
| listing-agent | `listing-master-service.yaml` | official | `/.well-known/agent.json` | `/a2a` | 是 | 是 |
| trade-agent | `business-service.yaml` | official | `/.well-known/agent.json` | `/a2a` | 是 | 是 |
| marketing-agent | `marketing-content-service.yaml` | official | `/.well-known/agent.json` | `/a2a` | 是 | 是 |
| media-agent | `media-worker-service.yaml` | official | `/.well-known/agent.json` | `/a2a` | 是 | 是 |
| compare-agent | `compare-engine-service.yaml` | official | `/.well-known/agent.json` | `/a2a` | 是 | 是 |
| contract-agent | `contract-service.yaml` | official | `/.well-known/agent.json` | `/a2a` | 是 | 是 |
| settlement-agent | `settlement-service.yaml` | official | `/.well-known/agent.json` | `/a2a` | 是 | 是 |
| notification-agent | `notification-service.yaml` | official | `/.well-known/agent.json` | `/a2a` | 是 | 是 |

## 校验规则

`OfficialA2aRegistrationValidator` 会在静态注册和动态发现路径检查：

- provider 只能是 `official` 或明确可验证为官方的 `auto`，`custom`、`custom_http` 和未知值直接拒绝；
- Agent Card 必须来自 `/.well-known/agent.json`；
- Agent Card 必须包含官方 A2A endpoint；
- 动态注册在 Agent Card 获取失败时不会创建 metadata fallback descriptor；
- 自定义任务创建、状态查询和流式路径不会参与运行时选择。

当前报告未发现仓库内官方域 Agent 的自定义 provider 配置。外部 Agent 若仍只提供旧自定义 HTTP 接口，会在注册阶段收到迁移错误。
