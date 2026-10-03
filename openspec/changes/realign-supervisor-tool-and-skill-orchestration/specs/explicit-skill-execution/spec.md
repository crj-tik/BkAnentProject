# Spec Delta

## Purpose

为用户本次显式指定的技能提供可验证的执行语义，区分专项指引与固定跨服务流程，使能力范围、步骤顺序、条件、数据传递和版本在整个任务及恢复期间保持一致，避免技能失配后扩大调用范围。

## ADDED Requirements

### Requirement: 请求级显式技能选择

请求 SHALL 支持可选 skill 名称及版本；未指定时为 AUTO，指定有效 instruction/workflow 时进入相应技能模式。技能归属、版本和必需依赖 MUST 在执行前验证；关键词、知识技能匹配与旧 hint MUST NOT 隐式选择固定工作流。

#### Scenario: 未指定 skill
- **WHEN** 新请求未携带 skill 选择但消息包含某技能的触发词
- **THEN** 请求仍为 AUTO，触发词不能强制选择 workflow skill

#### Scenario: 指定 skill 不存在
- **WHEN** 请求指定未发布的技能名称、非法归属或不存在版本
- **THEN** 返回明确技能错误，不回到默认 Agent 或自由模式

### Requirement: Instruction 技能约束有效能力范围

显式 instruction skill SHALL 注入专项指引，并以声明的 allowlist 或显式 inherit 策略限定有效能力。执行中的模型调用与实际工具调用 MUST 遵守同一范围；缺失工具、解析失败、后续 SkillTool 改名 MUST NOT 导致扩权或改变显式技能。

#### Scenario: 指定技能后尝试切换
- **WHEN** 显式选定技能执行中模型尝试激活另一技能或调用范围外工具
- **THEN** 返回越界错误且原技能继续有效，不扩大能力范围

#### Scenario: 工具集合无交集
- **WHEN** 显式技能的必需能力无法解析为当前有效工具
- **THEN** 技能执行被拒绝，不开放全量工具

### Requirement: Workflow 技能声明固定跨能力过程

workflow skill SHALL 声明稳定能力引用、步骤标识、依赖、条件、输入输出映射及必要审批。系统 MUST 按声明的 A2A/MCP/本地/LLM/审批步骤执行；模型只在声明允许的生成步骤或字段中参与，MUST NOT 擅自更换目标、顺序或跳过必需步骤。首版步骤依赖 MUST 无环。

#### Scenario: 固定混合协议流程
- **WHEN** 指定技能声明房源 A2A → 对比 MCP → 营销 A2A 三个依赖步骤
- **THEN** 按该依赖顺序调用并传递实际结果，执行日志显示三步及相应稳定目标

#### Scenario: 条件分支与并行
- **WHEN** 技能声明两项独立步骤和一个依赖二者结果的条件步骤
- **THEN** 独立步骤可在限额内并行，条件步骤在依赖完成后按声明条件执行或明确跳过

### Requirement: 固定流程执行前检查必需依赖

系统 SHALL 在开始固定流程前验证所有必需能力、参数、技能版本和权限；缺失时 MUST 报告具体步骤与依赖。允许替代目标或跳过步骤的行为 MUST 由技能显式声明并通过治理校验，不得由关键词或默认 Agent 隐式补偿。

#### Scenario: 第三个步骤工具缺失
- **WHEN** 必需的第三个步骤目标不可用
- **THEN** 开始执行前拒绝流程，不执行前两个步骤的副作用，也不选择未声明的替代能力

### Requirement: 技能版本与作用域在任务内固定

显式技能 SHALL 在接受任务时解析并保存不可变版本和内容快照；热更新仅影响新任务，恢复 MUST 使用原快照。技能选择 MUST 只作用于本次任务或明确续接的任务；父技能只在子步骤显式声明时作为下游技能传递。

#### Scenario: 热更新后恢复
- **WHEN** 暂停任务的技能文件更新后该任务恢复
- **THEN** 继续使用原版本和步骤，新请求可使用新版本

#### Scenario: 新请求不继承旧技能
- **WHEN** 同一会话完成技能任务后发起不携带 skill 的新请求
- **THEN** 新请求进入 AUTO，不自动沿用旧技能

### Requirement: 知识技能与非显式技能保持独立

知识技能 SHALL 仅提供背景知识；AUTO 中模型主动选择 instruction 技能 SHALL 保留模型选择来源记录。固定 workflow skill MUST 在首版仅接受本次显式请求选择。旧技能、旧 hint 的非显式行为 SHALL 保持兼容，不与 explicit selection 混淆。

#### Scenario: 知识提示不强制路由
- **WHEN** 行业知识技能因关键词匹配而注入上下文
- **THEN** 系统不据此预先调用固定领域 Agent

### Requirement: 技能模式共享平台治理与恢复

技能的参数、调用结果、步骤状态、审批和产物 SHALL 持久化并支持重启恢复。平台权限和审批要求 MUST 优先于技能声明；待办 SHALL 以任务最新 checkpoint 状态判定，恢复不得重复已完成副作用。

#### Scenario: Skill 未写审批但工具要求审批
- **WHEN** 技能执行的工具动作属于平台要求审批的动作
- **THEN** 动作在执行前暂停并等待有效批准，不能以技能指引代替批准
