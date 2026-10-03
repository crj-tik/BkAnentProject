# Spec Delta

## Purpose

为 Supervisor 与领域 Subagent 的 A2A 委托建立明确的显式技能选择契约，在维持官方通信格式和现有领域职责的同时，使下游技能校验、执行范围和版本约束与上游请求保持一致，并保留旧提示兼容性。

## ADDED Requirements

### Requirement: A2A 区分显式技能与建议提示

A2A 委托 SHALL 使用官方 Message/Task/Artifact，并以独立的 metadata 字段传递显式 skillSelection 的名称、版本、内容身份及归属。旧 skillHint SHALL 继续表示可覆盖建议；显式选择 MUST NOT 被降级为 hint。Card skill ID 与可执行 skill name 的映射 SHALL 明确发布。

#### Scenario: 上游指定下游技能
- **WHEN** 有效委托显式选择目标 Agent 已发布且支持的技能版本
- **THEN** 下游按该技能执行并返回有效技能/版本的可关联信息

#### Scenario: 旧实例不支持显式契约
- **WHEN** 目标实例未声明支持显式技能契约
- **THEN** 上游拒绝该显式调用并报告能力不支持，不以旧 hint 代替

### Requirement: Subagent 在执行前验证技能

Subagent SHALL 根据本地已发布技能验证名称、owner、版本和内容身份，不接受调用方任意技能正文代替发布内容。未知技能、跨域/跨 owner、版本不符和必需能力缺失 MUST 返回结构化错误，并且不得进入扩大工具范围的默认执行。

#### Scenario: 跨 Agent 技能
- **WHEN** 给 listing Agent 显式指定只属于 contract Agent 的技能
- **THEN** listing Agent 在业务执行前拒绝并返回归属错误

### Requirement: 显式技能持续约束子执行

有效 explicit selection SHALL 优先于消息历史的技能激活及 skillHint，在整个子执行与恢复期间生效；Subagent 的模型工具范围及实际执行边界 MUST 同时验证该限制。模型不能通过 SkillTool 改名、换版本或扩权。

#### Scenario: 历史激活与显式选择冲突
- **WHEN** 历史消息曾激活技能 B，本次明确指定技能 A
- **THEN** 当前执行使用 A，历史激活和 hint 不覆盖 A

### Requirement: 未指定技能的领域 Agent 保留自主工具执行

未收到显式 selection 的 Subagent SHALL 保留基于自身能力描述的 ReAct 工具选择与旧技能浏览/hint 行为。Subagent 可以继续使用本地工具；远程 MCP/A2A 工具 SHALL 仅按明确领域需求开放，不能自动继承 Supervisor 全量能力。

#### Scenario: 无 selection 的旧请求
- **WHEN** 旧 A2A 客户端只传任务与可选 hint
- **THEN** 子 Agent 按兼容模式执行，可以自主选用本域技能和工具

### Requirement: 父流程和子技能作用域分离

父 workflow 的名称和步骤标识 SHALL 作为 lineage 传递；下游 skillSelection MUST 只来自该步骤或模型调用参数中的明确选择，不能把父级跨服务 skill 原样注入每个领域 Agent。调用身份和执行关联 MUST 由服务端绑定。

#### Scenario: 父技能未指定子技能
- **WHEN** Supervisor 固定流程的一项 A2A 步骤只声明目标 Agent 与任务
- **THEN** 下游收到父步骤关联但没有强制子技能，按自身能力执行该任务

### Requirement: 访谈运行面职责不被编排改造改变

interview Agent 的治理面 SHALL 接入共享技能委托契约；高频话轮状态、确定性决策、状态机权限、脱敏证据域和 MCP 可见范围 MUST 继续由 interview-service 现有运行面负责，不能迁移到 Supervisor 自由 ReAct 决策。

#### Scenario: 访谈话轮请求
- **WHEN** 前端发送访谈运行面话轮
- **THEN** 使用 interview 的运行面管线和状态约束，不作为 Supervisor 多 Agent 工具循环的话轮
