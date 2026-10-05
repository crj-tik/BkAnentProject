# subagent-skill-contract Specification

## Purpose

为 Supervisor 与领域 Subagent 建立明确的 A2A 显式技能契约，使下游在保留既有模型工具循环和领域职责的同时，验证技能归属、版本与能力范围，持续理解被委托的任务，并兼容旧建议提示。

## Requirements

### Requirement: A2A 区分显式技能与建议提示

A2A 委托 SHALL 使用官方 Message/Task/Artifact，并以独立 metadata 传递显式 skillSelection 的名称、归属、版本和内容身份。旧 skillHint SHALL 保持可覆盖建议语义，显式选择 MUST NOT 降级为 hint。目标 SHALL 明确发布支持的显式契约，以及 Card skill ID 与可执行技能名称、归属、版本的映射。

#### Scenario: 上游指定下游技能
- **WHEN** 委托显式选择目标 Agent 已发布且支持的技能版本
- **THEN** 下游验证并固定该技能，返回可关联的有效技能与版本信息

#### Scenario: 旧实例不支持显式契约
- **WHEN** 目标实例未声明支持显式技能契约
- **THEN** 该显式调用明确失败并报告不支持，不以旧 hint 代替

### Requirement: Subagent 首轮前验证并加载显式技能

Subagent MUST 根据本地已发布内容验证技能名称、owner、版本、内容身份和有效能力策略，不接受调用方任意正文代替发布内容。未知名称、归属错误、版本不符或声明能力缺失 SHALL 返回结构化错误，不能进入扩大工具范围的默认执行。有效正文和任务 SHALL 在首轮模型前加载，指定技能不得绕过模型对委托意图和参数的理解。

#### Scenario: 跨 Agent 技能
- **WHEN** 给 listing Agent 显式指定只属于 contract Agent 的技能
- **THEN** listing Agent 在业务执行前拒绝并返回归属错误

#### Scenario: 显式技能中的业务参数不足
- **WHEN** 技能有效，但委托任务缺少必要业务参数
- **THEN** 模型获得正文和委托任务，可以返回澄清或缺失信息，不因显式参数存在而直接执行所有指引

### Requirement: 显式技能持续约束模型与实际调用

有效 explicit selection MUST 优先于历史激活及 skillHint，在整个子执行和恢复期间固定正文、版本与范围。模型可见工具和实际执行边界 MUST 同时验证有效范围，不允许模型通过换名、换版本、无交集回退或额外回调扩权。业务调用顺序仍由模型遵循正文，平台 MUST NOT 新增技能顺序、依赖或完成检查。

#### Scenario: 历史激活与显式选择冲突
- **WHEN** 历史消息曾激活技能 B，本次明确指定技能 A
- **THEN** 当前执行使用 A 的正文、版本和范围，历史激活和 hint 不覆盖 A

#### Scenario: 绕过模型暴露范围调用工具
- **WHEN** 一个实际工具调用目标不在显式技能有效范围内
- **THEN** 即使调用进入执行端也被拒绝，不仅依赖模型工具列表隐藏

### Requirement: 领域 Agent 继续使用既有自主工具循环

Subagent SHALL 保留既有领域模型工具循环、本地工具和业务逻辑，不为显式技能创建独立流程执行器；未指定技能时 SHALL 保持旧技能浏览和 hint 兼容。远程 MCP/A2A 仅按明确领域需求开放，MUST NOT 自动继承 Supervisor 全量能力；旧 hint 可以被本次模型选择覆盖。

#### Scenario: 无 selection 的旧请求
- **WHEN** 旧 A2A 客户端只传任务和可选 hint
- **THEN** 子 Agent 按兼容模式自主选用本域技能和工具，不要求改为技能 DAG

#### Scenario: 显式 selection 的请求
- **WHEN** 子 Agent 收到有效显式选择
- **THEN** 加载及范围策略变化，但仍由其领域模型循环理解任务并选择工具，业务工具本身无需因该选择改为 MCP

### Requirement: 父级技能与子技能作用域分离

父执行关联 SHALL 使用服务端绑定的 parentRunId、callId 和 parentSkill，MUST NOT 依赖技能步骤 ID。下游 skillSelection MUST 只来自模型工具参数中的明确目标本地技能选择，不能将父级跨服务技能自动注入每个子 Agent。每个子调用 SHALL 有稳定独立身份并关联父调用，不能多个委托复用父 Task 作为子任务身份。

#### Scenario: 父技能没有明确选择子技能
- **WHEN** Supervisor 在某个跨服务指引中委托一个 Agent，只提供目标和任务
- **THEN** 下游收到父执行与调用关联，没有强制子技能，并按自身能力理解任务

#### Scenario: 父调用恢复
- **WHEN** 一个已接受的子 A2A 委托在父执行恢复时仍未完成
- **THEN** 使用原 callId 与子 Task 关联续查，不因重新阅读父技能正文生成新子任务

### Requirement: 访谈运行面职责保持既定边界

interview Agent 的治理面 SHALL 接入共享技能契约；高频话轮、确定性决策、状态机权限、脱敏证据域和 MCP 可见范围 MUST 继续由 interview-service 既有运行面负责，不能迁移为 Supervisor 的自由工具决策。

#### Scenario: 访谈话轮请求
- **WHEN** 前端发送访谈运行面话轮
- **THEN** 使用 interview 的运行面管线和状态约束，不作为 Supervisor 多 Agent 工具循环的一轮
