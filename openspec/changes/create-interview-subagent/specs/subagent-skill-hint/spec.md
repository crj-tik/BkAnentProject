# subagent-skill-hint Delta

## Purpose

定义 Supervisor 向子 Agent 传递建议性技能提示（skill_hint）的契约、子 Agent 的预激活行为，以及未携带提示时的向后兼容保障。

## ADDED Requirements

### Requirement: skill_hint 元数据契约

Supervisor 在将意图路由给子 Agent 时，SHALL 可在 A2A 请求 metadata 的 supervisor 命名空间中携带 `skill_hint`（值为目标子 Agent 技能目录中的技能名）；该字段 MUST 为建议性——子 Agent 忽略 hint 不得导致任何请求失败。

#### Scenario: 路由时携带提示

- **WHEN** Supervisor 将「生成案例报告」意图路由给 interview-agent 且已匹配到具体技能
- **THEN** 请求 metadata 中包含 `skill_hint`，值为对应的报告技能名

### Requirement: 预激活行为

子 Agent 收到 `skill_hint` 且该技能存在于本域技能目录时，MUST 以激活态开始会话：首轮即加载该技能正文并按其声明收窄工具面，无需先经历目录浏览；hint 指向不存在的技能时 MUST 回退为默认的技能目录注入行为。

#### Scenario: 合法提示预激活

- **WHEN** 携带合法 `skill_hint` 的请求到达子 Agent
- **THEN** 首轮对话即以该技能激活态执行，工具面按该技能声明收窄

#### Scenario: 非法提示回退

- **WHEN** `skill_hint` 指向的技能不在本域技能目录中
- **THEN** 行为与未携带 hint 的请求完全一致（注入技能目录，由模型自主选择）

### Requirement: 向后兼容

未携带 `skill_hint` 的调用（包括全部既有子 Agent 与既有 Supervisor 链路）MUST 行为不变：技能目录注入、换装收窄、热更新回退等既有行为不受影响。

#### Scenario: 既有调用无感

- **WHEN** 既有子 Agent 收到不含 `skill_hint` 的请求
- **THEN** 技能路由拦截器的行为与该能力上线前一致
