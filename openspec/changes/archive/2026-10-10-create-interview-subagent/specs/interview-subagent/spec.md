# interview-subagent（治理面）Delta

## Purpose

定义 interview-service 作为 A2A 子 Agent 的治理面行为：开台磋商与题目确认制、对话式与表单式双入口的一致性、会话凭据交接、访谈入口卡片工件，以及 Supervisor/gateway 的注册约定。

## ADDED Requirements

### Requirement: 对话式开台与题目确认制

Supervisor SHALL 能将「发起访谈」意图经 A2A 委托给 interview-agent；interview-agent MUST 依确认制完成开台：先依据开台要素（场景、访谈目标、组织三级、受访者角色、任务类型与参考时长）产出候选题目清单（每题含 focus 标签与风险提示），MUST NOT 在发起人确认之前将任何题目置为可访谈状态；参考时长只用于控制题目预算，MUST NOT 触发到点自动结束。

#### Scenario: 开台产出候选题

- **WHEN** 发起人通过对话提供完整开台要素
- **THEN** 返回候选题目清单与按时长推导的题目预算，且无任何题目处于已确认状态

#### Scenario: 未确认题不进入访谈

- **WHEN** 发起人仅确认部分候选题后启动访谈
- **THEN** 仅已确认题进入访谈题单，未确认题不计入「未回答」统计

### Requirement: 开台双入口一致性

对话式（经 Supervisor A2A）与表单式（前端直调 interview-service REST）两个开台入口 SHALL 调用同一领域服务并写入同一组数据；REST 入口 MUST 记录与 A2A structuredContext 对齐的同一组元数据字段（创建人、组织三级、场景、案例状态等）。

#### Scenario: 双入口数据等价

- **WHEN** 分别以两种入口创建参数相同的会话
- **THEN** 两者产生的会话在进度查询、报告生成、资产检索中不可区分

#### Scenario: 直开场次可被对话查询

- **WHEN** 用户在 Supervisor 对话中询问其名下访谈进度
- **THEN** 无论会话由哪个入口创建，均可按创建人检索并返回进度

### Requirement: 会话凭据交接

治理面完成会话启动后 SHALL 返回 sessionId 与本场会话凭据；凭据 MUST 仅放行本场会话的话轮提交与监播读取，MUST NOT 授权跨会话访问或任何管理动作。

#### Scenario: 凭据放行本场话轮

- **WHEN** 持本场凭据向运行面提交该场话轮
- **THEN** 请求被接受

#### Scenario: 凭据不可跨场使用

- **WHEN** 持 A 场凭据访问 B 场会话的话轮接口
- **THEN** 请求被拒绝

### Requirement: 访谈入口卡片工件

对话式开台完成后，interview-agent SHALL 在结构化输出中返回入口工件（sessionId、场景、已确认题数、参考时长、入口参数）；Supervisor 侧 MUST 将其持久化为可渲染工件，前端据此呈现「进入访谈」入口，且该入口在会话进行期间保持可再次进入。

#### Scenario: 开台完成返回卡片

- **WHEN** 会话启动成功
- **THEN** A2A 结构化输出包含 sessionId 与入口参数，且工件被持久化供前端渲染

### Requirement: 进度查询对入口无差别

Supervisor 治理面 SHALL 支持按会话 ID 或按创建人列表查询访谈进度，数据来源为 interview-service 的会话状态；Supervisor MUST NOT 自持访谈状态副本。

#### Scenario: 按创建人列表查询

- **WHEN** 查询某创建人的全部访谈会话
- **THEN** 返回各会话状态机当前位置与进度概要（已问题数/总题数、是否收尾）

### Requirement: 注册与路由约定

interview-service SHALL 以 `agentId=interview-agent`、`supportedDomains=[interview]` 完成 A2A Agent Card 注册；Supervisor 领域目录 SHALL 依注册表派生机制自动纳入 `interview` 域（无需修改 Supervisor 代码）；gateway MUST 提供 `/interviews/**` 前缀路由至 interview-service 运行面。

#### Scenario: 注册即入目录

- **WHEN** interview-agent 注册成功且注册表刷新
- **THEN** Supervisor 领域词表包含 `interview`，规划可合法产出该域任务

#### Scenario: 运行面经网关可达

- **WHEN** 前端请求 gateway 的 `/interviews/**` 路径
- **THEN** 请求被路由到 interview-service 运行面
