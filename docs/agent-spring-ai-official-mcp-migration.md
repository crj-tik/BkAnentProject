# Agent 服务迁移到官方 Spring AI + MCP 的设计说明

## 1. 目标

当前 `agent-service` 的工具体系核心是：

- `@Tool`
- `ChatClient.defaultToolCallbacks(...)`
- Planner 复用同一套工具元数据
- 本地 Java Bean 直接执行业务逻辑

这套方式适合项目内部快速集成，但它本身不是官方 MCP 的标准接入方式。

本设计的目标是：

- 保留当前 `Planner + Tool` 编排能力
- 将适合外移的能力迁移到官方 MCP Client / Server 体系
- 让 Agent 能以标准方式发现和调用外部工具

## 2. 迁移前的现状

在迁移前，为了避免概念混乱，仓库已经做过一轮命名收口：

- `MilvusMcpTool` -> `MilvusVectorStoreTool`
- `agent.model.mcp` -> `agent.model.vector`
- `AgentMcpDecision` -> `AgentToolDecision`

迁移前的执行结构大致是：

1. `ChatClient` 通过 `defaultToolCallbacks(...)` 注册基础工具
2. `@Tool` 方法既供模型直接调用，也供 Planner 注册执行
3. `AgentPlanActionRegistry` 通过反射调本地工具方法
4. Milvus、营销、业务统计等能力都还是 JVM 内本地 Bean 调用

## 3. 迁移后的目标分层

建议拆成三层：

### 3.1 本地工具层

继续保留适合本地直调的能力，例如：

- `AgentResponseTool`
- 轻量格式化工具
- 与当前进程强耦合的上下文工具

### 3.2 MCP Client 代理工具层

为外部能力建立 MCP Client 代理工具，例如：

- 房源查询代理工具
- 营销资产代理工具
- 渠道发布代理工具
- 海报生成代理工具

对 Agent 来说，这些仍然表现为 Tool，但底层调用的是 MCP Server。

### 3.3 MCP Server 层

把适合独立部署的业务能力拆成 MCP Server，例如：

- 房源查询 MCP Server
- 海报生成 MCP Server
- 营销内容 MCP Server
- 渠道发布 MCP Server
- OCR MCP Server

## 4. 优先迁移对象

优先迁移这几类工具：

### 4.1 渠道发布工具

原因：

- 与第三方平台耦合重
- 独立演进频率高
- 适合脱离 `agent-service`

### 4.2 海报生成工具

原因：

- 可能由 Python、模板引擎、设计引擎驱动
- 未来有较高概率独立扩容

### 4.3 房源检索工具

原因：

- Agent 只应依赖“房源查询能力”
- 不应耦合房源系统内部实现细节

## 5. 不建议首批迁移的对象

以下能力更适合继续保留为本地工具：

- `AgentResponseTool`
- Milvus 本地向量检索
- Planner 内部草稿与总结类工具

原因：

- 对时延敏感
- 与当前 Agent 上下文强耦合
- 拆成 MCP Server 的收益不高

## 6. 迁移步骤

### 第一步：稳定能力抽象

先把当前工具按“业务能力”稳定下来，例如：

- 房源查询
- 文案生成
- 海报生成
- 内容保存
- 渠道发布

每个能力先形成稳定输入输出 schema。

### 第二步：引入 MCP 代理层

在 `agent-service` 中增加一层代理：

- 对外仍表现为 Tool
- 底层执行从“本地 Bean”逐步切到“MCP Client”

### 第三步：按能力逐个外移

建议迁移顺序：

1. `promotion-service`
2. `media-worker-service`
3. `listing-master-service`
4. `marketing-content-service`

### 第四步：Planner 保持统一注册表

当前 Planner 和 Tool 共用同一套元数据模型，这个设计建议保留。

只需要把动作执行来源扩展成两类：

- `LOCAL_BEAN`
- `MCP_CLIENT`

这样 Planner 不需要知道底层是本地调用还是 MCP 调用。

## 7. 对当前代码的改造建议

### 7.1 工具定义增加执行来源

建议在动作元数据中增加：

- `executionType`
- `mcpServerName`
- `mcpToolName`

### 7.2 执行注册表支持双通道

`AgentPlanActionRegistry` 不应只支持本地反射执行，后续应同时支持：

- 本地 Tool 执行器
- MCP Tool 执行器

### 7.3 保留 Tool 上下文跟踪

即使迁移到 MCP，`AgentToolContextHolder` 仍然值得保留。

建议继续记录：

- 调用了哪个 MCP Server
- 调用了哪个 MCP Tool
- 请求参数
- 响应摘要
- 失败原因

## 8. 推荐迁移顺序

建议顺序：

1. 先补齐房源查询 Tool
2. 再拆文案生成和海报生成 Tool
3. 然后把发布渠道切到 MCP
4. 最后视需要把内容资产和 OCR 切到 MCP

## 9. 结论

当前项目不适合“一步到位、全量切官方 MCP”。

更合理的路线是：

- 先把现有本地工具抽象稳定
- 保留 `@Tool + Planner` 主链路
- 再按服务边界逐步把部分工具替换为官方 MCP Client / Server

这样风险最小，也最符合当前项目的演进节奏。
