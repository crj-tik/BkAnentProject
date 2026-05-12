# 当前系统架构说明

## 1. 总览

当前仓库是一套面向房产中台场景的多服务系统，核心交互方式有四类：

- HTTP：对外 API 与前端入口
- Dubbo：服务间同步 RPC
- MCP：Agent 工具调用协议
- DeepSeek：大模型推理、工具决策、Planner 生成与总结

当前 Agent 主链路已经稳定为：

- `DeepSeek` 负责回答、工具决策和 Planner 生成
- `agent-service` 通过 `MCP` 调用业务工具
- `Dubbo` 只保留正常业务 RPC，不再承担模拟 MCP 的职责

## 2. 服务职责

### gateway

- 系统统一入口
- 做鉴权过滤、请求转发、网关级路由

### auth-service

- 用户账号与权限管理
- 提供认证相关 HTTP 接口
- 通过 Dubbo 向网关和内部服务提供权限能力

### listing-master-service

- 房源主数据服务
- 管理房源增删改查与房源详情
- 向其他服务提供房源 Dubbo RPC

### customer-service

- 客源、业主档案、跟进记录、收藏关系管理
- 负责客户提醒等业务逻辑
- 在需要跨服务数据时使用 Dubbo

### notification-service

- 统一消息通知记录管理
- 管理通知发送状态、已读状态
- 提供通知 Dubbo RPC

### marketing-content-service

- 营销内容资产管理
- 支持内容创建、平台变体、发布状态更新、内容搜索
- 对 Agent 暴露 MCP 工具
- 仍保留面向其他内部服务的 Dubbo RPC

### promotion-service

- 推广发布记录、效果统计、品牌素材管理
- 负责推广链路与效果数据沉淀
- 通过 Dubbo 接入其他业务服务

### business-service

- KPI、员工日工作量、房源流转效率、门店看板
- 对 Agent 暴露业务分析类 MCP 工具
- 对其他内部服务保留 Dubbo RPC

### compare-engine-service

- 房源对比分析
- 报告缓存与 PDF 生成
- 对 Agent 暴露对比类 MCP 工具
- 对其他服务保留 Dubbo RPC

### contract-service

- 合同模板、合同主记录、附件、OCR、签章、归档、提醒
- 通过 provider dispatcher 管理 OCR 与电子签集成
- 提供合同 Dubbo RPC

### settlement-service

- 结算主记录、分佣明细、月汇总、规则、发放批次、打款记录
- 管理佣金与结算流程
- 通过 Dubbo 获取上游业务数据

### media-worker-service

- 异步媒体任务执行
- 接入 RocketMQ 与对象存储
- 提供内部 Worker RPC

### common

- 共享 DTO、RPC 契约、基础实体模型

## 3. 核心交互方式

### HTTP

- 外部请求通过 `gateway` 进入系统
- 各业务服务通过 controller 提供业务接口

### Dubbo

- 用于正常的服务间同步调用
- 当前不再作为 Agent 模拟工具调用通道

### MCP

- `business-service`、`compare-engine-service`、`marketing-content-service` 暴露 MCP 工具
- `agent-service` 通过 `HttpAgentMcpClient` 统一调用
- Agent 的业务工具执行只允许走 MCP

### DeepSeek

- `agent-service` 使用 Spring AI 官方 DeepSeek 接入
- 在 `TOOL` 模式下负责直接回答与工具调用判断
- 在 `PLANNER` 模式下负责生成计划、修复计划、总结执行结果

## 4. Agent 服务

### 4.1 核心职责

- 聊天入口与执行模式分发
- Milvus 知识检索
- MCP 工具发现与调用
- Planner 计划生成、执行、重规划、日志记录

### 4.2 核心组件

- `AgentController`
  - `/agent/chat`
  - `/agent/mcp/tools`
  - `/agent/planner/sessions/{sessionNo}`
  - RAG / Milvus 管理接口
- `AgentOrchestratorService`
  - 根据请求选择 `TOOL` 或 `PLANNER`
- `DeepSeekChatService`
  - 对 `ChatClient + DeepSeekChatOptions` 做封装
- `HttpAgentMcpClient`
  - 通过 HTTP 调用远端 MCP Server
- `AgentPlanExecutorServiceImpl`
  - 串行执行 Planner 步骤，维护上下文与重规划
- `AgentPlannerServiceImpl`
  - 生成 Planner JSON、自动修复非法计划、生成最终总结
- `AgentPlanActionRegistry`
  - 注册 Planner 动作并决定走本地执行还是 MCP 执行
- `AgentPlannerLogPersistenceService`
  - 持久化 Planner 会话和步骤日志

## 5. Agent 的两种执行模式

### 5.1 TOOL 模式

这条链路可以理解为当前系统里的 ReAct 风格执行，但不是手写 ReAct Loop，而是：

- 模型自行判断是否要调用工具
- 工具通过 Spring AI Tool callback 注册
- 执行结果再回到模型生成最终回答

执行流程：

1. `AgentController` 接收 `/agent/chat`
2. `AgentOrchestratorService` 默认把未显式指定的请求视为 `TOOL`
3. 初始化 `AgentToolContextHolder`
4. `DeepSeekChatService` 发送：
   - 系统提示词
   - 用户问题
   - 工具上下文
   - 默认工具回调
5. DeepSeek 在推理中自行判断是否需要工具
6. 如果需要：
   - 本地知识工具可访问 Milvus
   - 业务工具通过 MCP 调用远端服务
7. 工具轨迹被记录到 `AgentToolContextHolder`
8. 最终回答返回，并附带 `AgentToolDecision`

特点：

- 没有旧的 Dubbo fallback
- 是否调用工具完全由模型决定
- 更适合单轮、低开销、反应快的请求

### 5.2 PLANNER 模式

这条链路用于多步骤、强依赖链式上下文、允许失败后重规划的任务。

执行流程：

1. 请求携带 `executionMode=PLANNER`
2. `AgentOrchestratorService` 调用 `agentPlanExecutorService.execute(message)`
3. `AgentPlannerServiceImpl` 让 DeepSeek 输出严格 JSON Planner
4. 返回结果会先做：
   - JSON 解析
   - 步骤归一化
   - 串行链路合法性校验
5. `AgentPlanExecutorServiceImpl` 开始按步骤串行执行
6. 每一步执行前：
   - 从上一步输出解析 `inputKey`
   - 解析 `${outputKey.fieldPath}` 模板
   - 构造执行上下文
7. `AgentPlanActionRegistry` 根据动作定义决定：
   - 本地方法执行
   - MCP 客户端执行
8. 成功结果写回：
   - 文本输出上下文
   - payload 上下文
   - completed step 列表
9. 如果某一步失败：
   - 记录失败结果
   - 若还有预算，触发 DeepSeek 重新规划剩余步骤
   - 已成功执行的步骤不会重复执行
10. 全部执行结束后，再让 DeepSeek 基于真实结果生成最终总结
11. Planner 会话和步骤日志落库到 `bk_agent`

特点：

- 严格串行
- 下一步必须消费上一步输出
- 有限次重规划
- 非法 Planner JSON 支持自动修复

## 6. Agent 中的 MCP 调用路径

当前 Agent 的 MCP 路径是：

1. 在 `agent.mcp.servers` 中配置远端 MCP 地址
2. `HttpAgentMcpClient` 为每个服务建立 `McpSyncClient`
3. `listTools()` 拉取远端工具元数据
4. `callTool(server, tool, arguments)` 发起远程工具调用
5. Planner 和 Tool 模式都消费返回的：
   - `text`
   - `payload`

当前 MCP Server 包括：

- `business-mcp-server`
- `compare-mcp-server`
- `marketing-mcp-server`

## 7. 关键数据流

### 7.1 Agent 问答 -> 工具回答

1. Client -> `gateway`
2. `gateway` -> `agent-service`
3. `agent-service` -> DeepSeek
4. DeepSeek 视情况调用：
   - Milvus 本地知识工具
   - business / compare / marketing MCP 工具
5. `agent-service` 汇总工具结果
6. 返回最终回答

### 7.2 Agent 规划 -> 多步骤执行

1. Client -> `gateway`
2. `gateway` -> `agent-service`
3. `agent-service` -> DeepSeek 生成 Planner
4. `agent-service` 串行执行步骤
5. 步骤内部调用本地工具或 MCP 工具
6. 失败时 -> DeepSeek 重规划
7. 完成后 -> DeepSeek 生成最终总结
8. 会话日志落到 `bk_agent`

### 7.3 合同 OCR 流程

1. 调用合同附件 OCR 接口
2. `ContractManagementServiceImpl` 读取 `contract.integration.ocr-provider`
3. `ContractOcrExtractorDispatcher` 选择匹配 Provider
4. OCR 结果写入 `contract_attachment`
5. 合同主记录更新 `ocrSummary`

### 7.4 合同签章流程

1. 调用合同签章接口
2. `ContractManagementServiceImpl` 读取电子签 provider
3. `ContractEsignClientDispatcher` 选择具体适配器
4. 签章结果更新：
   - `seal_provider`
   - `seal_time`
   - `external_seal_no`
   - `signed_document_url`

## 8. 存储边界

- 每个业务服务拥有自己的 MySQL 库
- `agent-service` 将 Planner 日志持久化到 `bk_agent`
- 启用向量检索时，RAG 数据存储在 Milvus
- 营销搜索启用时，可落 Elasticsearch

## 9. 当前架构边界结论

- Agent 业务工具执行：只走 MCP
- 多步骤复杂任务：只走 Planner
- 大模型底层：只走 DeepSeek
- 内部同步业务调用：Dubbo 继续保留并正常使用
