# 门店经营卡点复盘契约

## Purpose

在可信门店范围内，把房源维护、带看和成交推进中的卡点转为有出处、可确认、可跟进的经营事项。首期以明确标记的模拟数据验证流程，并保留后续真实来源、授权和效果观察边界。

## ADDED Requirements

### Requirement: 经营输入使用有来源的独立数据集

系统 SHALL 读取带看、跟进、议价和维护事实，并为每项输入保留稳定来源、版本、事件/观察时间、dataset 和数据模式。房源与知识 SHALL 通过其公开来源接口引用，逐字稿继续由访谈域管理。模拟输入 MUST 标记 SIMULATED，性质未知的来源 MUST 标记 UNKNOWN，含不同性质证据的结果 MUST NOT 标为 REAL。

#### Scenario: 真实房源与模拟带看共同参与诊断
- **WHEN** 一次复盘使用真实接口房源和模拟带看记录
- **THEN** 诊断、计划、指标和导出标记 MIXED 并展示各来源模式，不能宣称全部依据真实经营事实

### Requirement: 复盘事项绑定当前主体与门店范围

创建、查询、修改、确认和观察经营事项 SHALL 核验当前可信用户及门店资源范围。请求中的 storeId、employeeId、ownerId 和 Agent 摘要 MUST NOT 授予权限。经营事项、执行任务与 Memory 事项身份 SHALL 保持不同语义。

#### Scenario: 主体读取其他门店的复盘事项
- **WHEN** 请求包含已知事项 ID，但当前主体没有该事项所属门店的操作范围
- **THEN** 系统拒绝返回正文或修改事项，不因请求填写相同工号而通过

### Requirement: 卡点解释区分事实、假设和资料缺口

复盘 SHALL 输出有出处的事实、原因假设、待核实问题和建议动作，并保留证据与知识 revision。缺少关键事实时 SHALL 明确说明缺口；模型 MUST NOT 生成不存在的带看、反馈、成交、价格或因果结论。

#### Scenario: 仅有带看量且没有客户反馈
- **WHEN** 店长请求解释带看后未推进的原因
- **THEN** 系统说明缺少反馈依据，给出待核实问题及有条件假设，不直接断言价格、经纪人能力或客户意向是已确认原因

### Requirement: 来源变化使旧计划待复核

计划确认和依赖内容使用前 SHALL 复核当前房源版本、知识发布状态及来源授权。依赖变化 MUST 将相关建议标为待复核或拒绝旧版本确认；已发生的执行事实仍可按权限查询。

#### Scenario: 确认前打法已被撤回
- **WHEN** 店长准备确认的计划引用了已撤回的打法 revision
- **THEN** 系统不接受该旧计划确认，不展示失效依赖正文，并保留原计划和已完成动作的审计身份

### Requirement: 推进动作由当前版本的明确确认产生

系统 SHALL 将计划内容、负责人、期限、证据/知识版本和实际参数 hash 绑定为不可变确认对象。确认 MUST 具备当前权限及 expectedPlanVersion；相同 requestId 和相同 hash 重试返回原回执，不同内容复用同号或旧确认批准新计划 MUST 拒绝。

#### Scenario: 确认响应丢失后重试
- **WHEN** 店长以相同 requestId 和相同计划版本重试确认
- **THEN** 系统返回原动作集合和回执，不重复创建任务

### Requirement: 动作完成与上游经营状态分别确认

动作 SHALL 记录提出、确认、执行中、完成或取消状态及操作者、时间和证据级别。仅人工反馈 MUST 标记 USER_REPORTED；只有可核验的对应来源回执才能标记 SOURCE_CONFIRMED。任务 DONE MUST NOT 自动宣称客户成交、房源调价或外部消息发送成功。

#### Scenario: 经纪人反馈已联系客户
- **WHEN** 动作只有操作人的完成反馈而没有上游状态变化回执
- **THEN** 系统保存 USER_REPORTED 的动作完成，保持原客户/房源/成交事实，不增加来源确认的成交数量

### Requirement: 指标记录口径、窗口及未知状态

经营指标 SHALL 记录定义版本、观察时间、时区、样本范围、聚合单位及分子/分母；效率观察与经营阶段结果分开表达。分母为零、事实缺失或来源不可用时 MUST 返回 UNKNOWN 和原因，前后差异 MUST NOT 自动成为因果增长证明。

#### Scenario: 当前窗口没有可用机会样本
- **WHEN** 有效下一步率的分母为零或反馈记录缺失
- **THEN** 系统返回 UNKNOWN 及明确原因，不显示零转化或凭建议动作数生成改善百分比

### Requirement: 模拟结果不发布真实效果证据

模拟或混合数据产生的复盘、动作及观察 SHALL 保留来源模式和 dataset 隔离，不能通过改标记迁入真实历史，也不得发布为生产 Memory 业务结果证明。模型自评、工具受理或人工勾选完成 MUST NOT 构成受信经营结果。

#### Scenario: 模拟场景产生正向转化变化
- **WHEN** fixture 数据显示转化改善
- **THEN** 系统可以报告模拟流程观察，不能宣称真实门店业绩增长或将其写成生产有效经验的结果事件

### Requirement: 经营流程保留领域权威和通用 Agent 治理

经营状态 SHALL 由经营域保存并以稳定回执查询，Supervisor 仅协调已授权调用。A2A 能力授权 SHALL 使用稳定 capability 与精确 Auth 操作；业务 API MUST 另行检查精确 business.review.*.store 操作和真实门店范围。Agent skill、诊断文字和调用许可 MUST NOT 代替用户对具体计划版本的明确确认。

#### Scenario: Agent 有调用许可但缺少门店操作权限
- **WHEN** business-review-agent 获准被调用，但签名上下文没有 business.review.plan.confirm.store
- **THEN** Agent 调用许可不会使计划确认成功，系统在产生动作前拒绝该操作

### Requirement: 复盘使用独立的服务和 API 路径

系统 SHALL 通过 /business-review/reviews 提供复盘事项、计划、动作和观察 API；既有 /business/** 经营分析路径 SHALL 保持原路由及响应行为。新事项 MUST 由复盘业务域持久保存，不要求移动旧经营数据或旧服务代码。

#### Scenario: 新旧经营 API 并行调用
- **WHEN** 客户端分别请求 /business-review/reviews 和既有 /business/** 经营分析接口
- **THEN** 复盘请求由新复盘能力处理，既有分析请求继续由原经营 API 处理，两者状态互不覆盖

### Requirement: Agent 必须使用隔离且受保护的 Memory 生命周期

官方复盘 Agent SHALL 以 business-review-service / business-review / business-review-agent 专属身份读取、授权核验并归档私域 Memory。Memory 读取和授权核验 MUST 在模型或业务工具产生任何调用和副作用之前完成；Memory 不可用、身份不匹配或 Agent/domain 未登记时 MUST 拒绝该轮次。Memory 内容 MUST NOT 代替复盘业务库中的事实、计划、动作或结果。

#### Scenario: Memory 不可用时尝试生成复盘
- **WHEN** Agent 已收到请求但 Memory 服务无法完成当前身份的读取与授权核验
- **THEN** 系统在调用模型及任何业务工具前拒绝该轮次，不创建事项、动作或外部副作用

#### Scenario: 复盘 Agent 请求读取 trade 历史
- **WHEN** business-review-agent 需要跨轮次上下文
- **THEN** 系统仅提供 business-review 私域中授权的记忆，不暴露 business-service / trade / trade-agent 的历史

### Requirement: 固定消费契约允许本域独立运行

经营域 SHALL 按 domain-parallel-v1 的公开 HTTP 契约消费房源及已发布知识，并提供显式选择的模拟来源与真实接口模式。模拟来源只替代远端读取，本域诊断、计划、动作、回执和持久记录仍须实际执行。真实接口模式遇到未配置、未知版本、拒绝或依赖故障时 MUST 返回相应失败，不得回退模拟；Auth 或 Memory 生产接线缺失时 MUST NOT 旁路。

#### Scenario: 房源与知识新服务尚未部署
- **WHEN** 经营域显式选择固定 v1 模拟来源，本域真实处理器、存储和受控测试身份可用，而房源/知识新增实现尚不存在
- **THEN** 系统能完成模拟卡点诊断、确认、执行反馈和回执查询，输出保留模拟标记且不要求等待其它 change

#### Scenario: 已选真实接口但不可连接
- **WHEN** 经营域已选择真实 HTTP 来源且目标不可用
- **THEN** 系统返回结构化依赖不可用，不转成模拟建议成功、不伪造无匹配，也不将模拟验收记录标成真实互通证据
