# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Full project compile (uses Aliyun mirror through .mvn-settings.xml)
mvn -gs .mvn-settings.xml -s .mvn-settings.xml compile

# Compile a single module and its dependencies
mvn -pl agent-service -am compile

# Compile with tests skipped
mvn -pl agent-service -am -DskipTests compile
```

All modules share parent `bk-agent-project:1.0.0-SNAPSHOT` (root `pom.xml`). Version properties are centralized in the root POM's `<properties>`.

## Architecture

This is a **multi-agent real-estate middle-platform** built on Spring Cloud Alibaba, Spring AI (DeepSeek + DashScope), Dubbo, MCP, and Nacos.

### Service Map

| Service | Port | Role |
|---------|------|------|
| `gateway` | — | API gateway |
| `agent-service` | 9002 | **Supervisor agent** — orchestrates sub-agents via A2A, aggregates MCP tools, manages sessions/workflows |
| `memory-service` | 9011 | Vector memory (Milvus) RPC |
| `business-service` | 9010 | Trade feasibility analysis agent (A2A + MCP server) |
| `compare-engine-service` | 9006 | Listing comparison agent, MCP server |
| `marketing-content-service` | 9008 | Content generation agent, MCP server |
| `listing-master-service` | — | Listing master data (Dubbo RPC) |
| `customer-service` | — | Customer/owner management |
| `contract-service` | — | Contract lifecycle (A2A agent) |
| `settlement-service` | — | Commission & settlement (A2A agent) |
| `notification-service` | — | Notifications (A2A agent) |
| `media-worker-service` | — | Async media tasks (A2A agent) |
| `promotion-service` | — | Multi-platform publishing |
| `auth-service` | — | Authentication & authorization |

### Agent Orchestration (agent-service)

- **Supervisor**: `ReactAgent` (Spring AI Alibaba Graph) with DeepSeek `ChatModel` and aggregated tools
- **Planning**: `SupervisorGraphPlanner` runs an `OfficialPlanningGraph` (DAG) through nodes: LoadSession → LlmIntentPlan → PlanValidation → ParseIntent → PlanTask → SelectAgent
- **Execution**: Based on plan output, dispatches to `SingleAgentSubgraph` (one agent) or `ParallelAgentSubgraph` (multiple agents in parallel)
- **Approval**: If required, transitions state to `WAITING`, waits for external callback, then resumes
- **Handoff/Resume**: `WorkflowResumeSupport` handles agent switching, regeneration with feedback, parallel-result routing, cancellation, and completion chaining
- **State**: `SupervisorGraphState` shared across subgraphs with checkpoint persistence via `GraphCheckpointStore`

### A2A Protocol (Agent-to-Agent Communication)

Sub-agents expose themselves as A2A agents in two styles:
1. **`ReactAgent.builder()`** — Trade agent (`business-service`) uses the framework's ReAct loop with `ChatModel` + `ToolCallbackProvider`
2. **`BaseAgent` + `StateGraph`** — All other sub-agents define a single-node graph, delegate to a domain `*AgentService`

Agent discovery in `agent-service` follows a priority chain:
1. `NacosAgentCardProvider.getAgentCard(name)` — queries Nacos 3.x Agent Registry directly (preferred)
2. `HTTP GET /.well-known/agent.json` — fallback HTTP fetch
3. Nacos metadata (agent-id, agent-domains, etc.) — final fallback

### MCP (Model Context Protocol)

Three sub-agents expose MCP tools:
- `business-mcp-server` → `queryMonthlyKpis`
- `compare-mcp-server` → `compareListings`
- `marketing-mcp-server` → `publishMarketingContent`

`agent-service` connects to each via `McpSyncClient` (HTTP streamable transport), wraps them as `SyncMcpToolCallbackProvider`, and merges them with local tools (`AgentMilvusTool.milvusKnowledgeSearch`) into a single `combinedToolCallbackProvider` for the ChatClient.

### Common Module (`common/`)

Shared across all services: `AgentCard` record, Dubbo RPC interfaces, base models/constants.

### RPC Layer

Inter-service data access uses Dubbo RPC (Nacos registry). Agent coordination uses A2A protocol. The two are independent — Dubbo handles CRUD data, A2A handles agent task delegation.

## Configuration Conventions

- **Local `application.yml`**: Only `server.port`, `spring.application.name`, `spring.config.import` (pointing to Nacos), `spring.cloud.nacos.*`, Dubbo config
- **`nacos/*.yaml`**: All runtime config — datasource, Redis, RocketMQ, Milvus, DeepSeek/DashScope, A2A, business parameters. These files mirror what's in the live Nacos server
- **`@ConfigurationProperties`**: All config classes use this annotation; no hardcoded addresses or keys
- **Env variables**: Convention is `${VAR_NAME:default}`, using `MYSQL_*`, `REDIS_*`, `NACOS_*`, `DEEPSEEK_*`, `DASHSCOPE_*`, `MILVUS_*`, `ROCKETMQ_*` prefixes
- **Maven settings**: `.mvn-settings.xml` uses Aliyun mirror for dependency resolution

## Key Dependencies (Managed Versions)

- Spring Boot 3.5.0, Spring Cloud 2025.0.0, Spring Cloud Alibaba 2025.0.0.0
- Spring AI 1.1.2, Spring AI Alibaba BOM 1.1.2.0
- Dubbo 3.2.15, MyBatis-Plus 3.5.7
- Nacos client/api 3.1.0 (explicit override of BOM's 3.0.3)
- MCP SDK 0.14.0
- RocketMQ Spring 2.3.3

## Nacos Version Requirement

The A2A agent registry feature requires **Nacos 3.x server**. Nacos 2.x will throw `errCode: 501, errMsg: Request Nacos server version is too low, not support agent registry feature`. When running Nacos 2.x, set `spring.ai.alibaba.a2a.nacos.registry.enabled: false` in sub-agent configs.
