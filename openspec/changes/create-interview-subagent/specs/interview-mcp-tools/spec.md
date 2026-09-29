# interview-mcp-tools（能力面）Delta

## Purpose

定义 interview-service 作为 MCP server 对外暴露的只读资产能力、隔离边界与异步任务语义，使其他子 Agent 与 Supervisor 能以管道方式复用访谈知识资产。

## ADDED Requirements

### Requirement: 公开只读资产暴露

MCP 面 SHALL 暴露以下工具：逐字稿检索、报告读取、逐字稿提交（含自动评级归档）、确定性评级、报告异步生成。对外可见范围 MUST 限定为 L2/L3 已归档逐字稿及其衍生报告；L0/L1 资产 MUST NOT 经 MCP 面可见。

#### Scenario: 等级过滤

- **WHEN** 调用方通过 MCP 检索逐字稿
- **THEN** 结果仅包含 L2/L3 已归档资产

#### Scenario: 外部逐字稿归集

- **WHEN** 外部系统经 MCP 提交一份逐字稿文本
- **THEN** 系统完成归档评级并返回资产标识与等级

### Requirement: 管理动作不进 MCP 面

删除、改名、重评级、题目确认、会话干预等管理或写操作动作 MUST NOT 通过 MCP 面暴露；MCP 面唯一允许的写路径为逐字稿提交（产生新资产，不修改既有资产）。

#### Scenario: 工具面无管理能力

- **WHEN** 列举 interview MCP 面暴露的全部工具
- **THEN** 不存在删除、改名、重评级类工具

### Requirement: 异步任务语义

经 MCP 发起的报告生成 SHALL 返回任务标识供轮询，终态为完成或阻塞（附原因）；任务 MUST 幂等——相同输入快照复用同一任务；阻塞态 MUST 说明缺失项分类，不消耗重试额度。

#### Scenario: 提交后轮询取结果

- **WHEN** 调用方提交报告生成并获得任务标识后持续轮询
- **THEN** 任务最终返回报告结果或带缺失分类的阻塞原因

### Requirement: MCP 注册与可发现性

interview-service SHALL 按仓库既有 MCP server 惯例（STREAMABLE 协议、`/mcp` 端点）暴露工具，且 Supervisor SHALL 能经其 MCP client 连接发现并合并 interview 工具到统一工具面。

#### Scenario: Supervisor 发现新工具

- **WHEN** agent-service 与 interview-mcp-server 的连接建立并刷新
- **THEN** interview 的 MCP 工具出现在 Supervisor 的合并工具面中
