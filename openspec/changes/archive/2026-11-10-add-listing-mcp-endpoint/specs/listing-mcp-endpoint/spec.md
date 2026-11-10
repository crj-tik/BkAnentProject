## Purpose

定义房源域 MCP 通道的对外行为：只读工具集、逐工具鉴权、与 A2A/REST 并存不重复，使 agent-service 的 MCP 客户端及任何标准 MCP 客户端能消费房源搜索、详情、版本化来源与对比能力。

## ADDED Requirements

### Requirement: 房源域暴露只读 MCP 工具集

listing-master-service SHALL 在 `/mcp` 端点（STREAMABLE）暴露以下 MCP 工具，全部包装现有服务层：摘要搜索、按 ID 摘要、完整详情、版本化来源搜索（返回 sourceSystem/sourceId/revision/contentHash/dataMode）、创建对比（响应含卡片投影与指标归属）、读取对比报告。工具 MUST NOT 包含房源写操作（登记、维护、状态、素材、业主、OCR、核验）。

#### Scenario: MCP 客户端搜索房源
- **WHEN** 已获准的 MCP 调用方发起摘要搜索工具调用
- **THEN** 返回当前授权范围内的房源摘要，不含业主联系方式、证件号与受限素材

#### Scenario: MCP 客户端创建对比
- **WHEN** 已获准的 MCP 调用方以真实来源引用与预期版本发起对比工具调用
- **THEN** 返回对比报告（含 cards 卡片投影与 metricWinners），版本冲突时明确失败，不编造引用

#### Scenario: 写操作不可经 MCP
- **WHEN** MCP 工具目录被列出
- **THEN** 不存在房源登记、状态变更、素材绑定、业主变更类工具

### Requirement: 逐工具鉴权与公开工具白名单

MCP 端点 SHALL 使用仓库既有 McpAuthorizationFilter 逐工具鉴权：调用方为 agent-service，需 Auth 签名的逐工具授权上下文；`tool-operations` 将工具名映射到操作码（摘要/详情/来源搜索→`listing.read`，对比创建→`compare.report.create`，报告读取→`compare.report.read.own`），`public-tools` 列出只读公开工具。鉴权开关 MUST 默认关闭，公钥接线并验收后才开启；开关关闭时端点不暴露任何工具。

#### Scenario: 未授权工具调用被拒
- **WHEN** 调用方对需要操作码的工具没有对应授权
- **THEN** 工具调用被拒绝，不返回业务数据

#### Scenario: 鉴权开关关闭
- **WHEN** `mcp.authorization.server.enabled` 为 false
- **THEN** MCP 工具不注册，端点不暴露房源能力

### Requirement: 与 A2A 和 REST 并存不重复

MCP 通道 SHALL 与 listing-agent（A2A）及 REST 契约并存：三通道调用同一服务层，MCP 工具不重新实现业务逻辑，也不改变 A2A 技能、REST 接口与既有授权语义。agent-service 的 MCP 连接目录 SHALL 新增 listing-mcp-server 条目。

#### Scenario: 三通道能力一致
- **WHEN** 同一授权主体分别经 REST、A2A、MCP 查询同一房源
- **THEN** 返回的服务层事实一致，权限范围遵循各自通道的既有授权模型

#### Scenario: A2A 不受影响
- **WHEN** MCP 端点上线后
- **THEN** listing-agent 的技能、工具与授权映射保持不变
