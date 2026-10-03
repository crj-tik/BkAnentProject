# Spec Delta

## Purpose

为未指定技能的 Supervisor 请求提供基于模型工具调用的自主执行能力，使模型根据实际可用 A2A Agent、MCP 工具和本地工具的描述选择调用，并统一记录执行、审批、恢复和失败结果。

## ADDED Requirements

### Requirement: 正常请求由模型决定能力调用

未显式指定 skill 的新请求 SHALL 进入 AUTO 模式，由 LLM 根据能力 description 和输入 schema 决定直接回答、请求补充信息或调用能力。系统 MUST NOT 先通过消息关键词、默认领域、默认 Agent、领域默认 intent 或固定业务链决定目标。

#### Scenario: 含否定词的请求不被入口关键词分流
- **WHEN** 无 skill 的请求为“不要发送通知，只分析这份合同”
- **THEN** 平台不预先调用 notification Agent，模型在可用能力中决定分析动作，通知动作必须有实际有效调用及权限/审批依据才可执行

#### Scenario: 可以直接回答
- **WHEN** 模型认为问候请求无需外部能力并返回最终答案
- **THEN** A2A 与 MCP 调用记录均为空，任务正常完成

### Requirement: 模型获得可实际执行的统一能力目录

系统 SHALL 向模型提供当前已发现、有效且授权可见的 A2A/MCP/本地能力，每项包含稳定身份、无冲突模型名称、description 和输入 schema。A2A 描述 MUST 保留 Agent 的技能描述与能力事实，MCP MUST 保留实际工具 schema；每项可见能力 MUST 对应真实执行入口。

#### Scenario: 动态新增领域不需要关键词配置
- **WHEN** 一个新领域 Agent 已被成功发现且调用者有权限，其 description 和 schema 有效
- **THEN** 后续模型调用能够看到并委托该 Agent，无需新增业务关键词

#### Scenario: 两个 MCP 服务拥有同名工具
- **WHEN** 两个连接均声明工具名称 `search` 且调用者有权限
- **THEN** 两项能力以不同稳定身份可见并可准确调用，不静默覆盖

### Requirement: 工具结果驱动后续模型决策

系统 SHALL 把实际能力执行结果以可关联的工具结果返回模型，由模型决定下一步或结束；`nextHints` 和风险判断 SHALL 作为结果数据提供，MUST NOT 直接触发隐式下游调用。模型发出的并行调用 SHALL 遵守依赖、并发和预算约束。

#### Scenario: 建议不自动触发通知
- **WHEN** Subagent 返回 `nextHints=[notification.send]`
- **THEN** 平台把建议交给模型，未出现后续有效工具调用时不执行通知

#### Scenario: 下一次委托使用实际结果
- **WHEN** 房源能力返回候选 ID 后，模型决定调用对比能力
- **THEN** 对比调用使用可验证的候选 ID，结果与相应调用记录关联

### Requirement: 决策失败不切换业务关键词路由

模型不可用、工具不可用或参数缺失时，系统 SHALL 返回明确的失败或待输入/恢复状态，允许同模式有界重试。系统 MUST NOT 静默改为关键词路由、listing 兜底或替换为另一业务目标。

#### Scenario: 模型不可用
- **WHEN** AUTO 请求的模型调用重试耗尽
- **THEN** 记录模型不可用错误，不发起通过关键词猜测的 A2A/MCP 请求

### Requirement: 权限与审批在实际调用边界生效

每次 A2A/MCP/本地调用 MUST 重新验证身份、目标、schema、权限及适用审批策略；模型不得提供有效身份或审批批准结果覆盖服务端绑定。需要审批的动作 SHALL 在执行前保存目标和参数并暂停，批准后仅恢复该动作；参数变化 MUST 重新审批。

#### Scenario: 审批批准后恢复
- **WHEN** 一个动作等待审批并获批准
- **THEN** 系统恢复原调用及已批准参数，不重新生成不同目标或执行未批准副作用

#### Scenario: 能力已被撤销
- **WHEN** 模型看到的能力在实际执行前失去权限或已下线
- **THEN** 该调用明确失败或返回不可用结果，不利用旧目录继续调用

### Requirement: 可恢复调用记录与可观测执行

系统 SHALL 持久化执行模式、执行版本、调用 ID、能力身份、参数关联、状态、远端 Task 与产物，并关联 checkpoint、审批和 SSE。恢复 SHALL 复用已完成结果；已接受的远端任务 SHALL 续查；提交结果未知的副作用 MUST 先对账或等待确认，不能盲目重发。系统 MUST NOT 对无远端幂等支持的调用承诺 exactly-once。

#### Scenario: 进程在结果持久化后重启
- **WHEN** 调用已完成并记录结果但模型尚未处理该结果时进程重启
- **THEN** 恢复时复用原结果，模型继续执行而不重复调用

#### Scenario: 提交结果未知
- **WHEN** 有副作用的请求已发出但连接超时，远端是否执行无法确认
- **THEN** 记录结果未知状态并对账，不因 checkpoint 恢复自动再次执行

### Requirement: 各入口共享一致决策语义

Supervisor 同步/异步任务与工作流 SHALL 使用同一模式选择及能力执行语义；普通聊天兼容入口 SHALL 逐步接入同一核心并保留现有字段与 MCP 禁用选项。新旧未完成任务 MUST 依据已记录的执行版本恢复。

#### Scenario: 异步执行不改变选择权
- **WHEN** 相同无 skill 请求通过同步和异步入口提交
- **THEN** 两者均由模型选择能力，异步入口不会恢复旧关键词分流

#### Scenario: 旧任务恢复
- **WHEN** 新部署加载升级前的未完成 checkpoint
- **THEN** 使用相容旧执行版本恢复，不用新状态机解释旧状态
