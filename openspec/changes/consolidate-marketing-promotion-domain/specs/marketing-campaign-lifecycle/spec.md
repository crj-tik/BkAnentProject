# 内容营销统一生命周期契约

## Purpose

统一内容版本、审核、发布计划、平台尝试及效果记录的业务身份，让用户能确认精确内容并核对实际渠道结果。合并过程中保留旧记录和幂等身份，明确模拟与真实发布、效果和收入证据的差异，避免双写状态及未知结果重发。

## ADDED Requirements

### Requirement: A2A 与 ReAct 营销能力不得绕过发布确认

A2A marketing-draft SHALL 只读取既有内容并返回未保存的草稿。营销服务内 ReAct 能力可以创建 DRAFT 内容和准备发布计划，但 MUST NOT 确认发布、发送通知或仅凭模型结果宣称 PUBLISHED；发布状态必须由本域保存的实际 provider 回执决定。Supervisor 发布技能 SHALL 使用独立的发布路径，不得把写动作藏在普通 marketing-agent A2A 调用中。

#### Scenario: ReAct 创建草稿并准备计划
- **WHEN** ReAct Agent 生成草稿并为已审核内容准备营销计划
- **THEN** 草稿保持 DRAFT、准备结果返回确切计划快照且没有外部发布尝试；模型输出的 PUBLISHED 或通知提示不能构成发布事实

#### Scenario: A2A 草稿请求包含发布意图
- **WHEN** Supervisor 调用 marketing-draft A2A 子技能生成草稿
- **THEN** 子技能只返回草稿及其待确认事实，不创建发布任务、不调用确认或通知工具

### Requirement: Supervisor 只在确切计划获用户批准后确认发布

Supervisor 的独立营销发布技能 SHALL 先展示内容、revision、素材、平台、账号、planId、planVersion 和 snapshotHash，再通过单独的 confirmMarketingCampaign MCP capability 执行确认；该调用 MUST 在平台动作前等待用户批准。prepare 只准备并返回固定计划，不调用平台或发送通知，确认仍须由领域服务复核当前账号授权、计划版本和 hash。内容人工审核与本次计划确认是两个不同条件。

#### Scenario: 用户尚未批准或拒绝计划
- **WHEN** Supervisor 已展示计划，但确认能力仍等待批准或用户拒绝
- **THEN** 不调用 confirmMarketingCampaign、不创建发布 attempt，也不发生平台副作用

#### Scenario: 用户批准后计划已变化
- **WHEN** 用户批准了计划，但确认时 planVersion 或 snapshotHash 已改变
- **THEN** 领域服务拒绝旧确认并要求重新准备和展示新计划，不发布旧内容或新账号目标

### Requirement: Supervisor 可取得结构化内容身份和发布回执

MCP 草稿创建 SHALL 返回每条已保存草稿的 contentId、platform 和 DRAFT 状态，不得只返回无法继续使用的自然语言摘要。Supervisor SHALL 可通过 MCP 读取原 receipt 并核对原 attempt；OUTCOME_UNKNOWN 必须沿原 attemptId reconcile，不得重新确认或新建 attempt。

#### Scenario: 创建草稿后准备发布计划
- **WHEN** MCP 创建一个或多个营销草稿
- **THEN** 返回稳定的 contentId 列表，Supervisor 可将选中的 ID 传给 prepareMarketingCampaign

#### Scenario: 平台结果未知后续查
- **WHEN** 原发布 attempt 的结果为 OUTCOME_UNKNOWN
- **THEN** Supervisor 可读取原 receipt 并调用原 attempt 的核对能力，返回更新后的原 attempt 状态，不产生新 attempt

### Requirement: 旧推广路径不暗示旧 DTO 兼容

受权的 /promotions/** 过渡路径 SHALL 明确使用营销域当前版本的请求/响应契约。旧请求/响应 DTO 不兼容；旧形状必须返回明确的契约不支持错误，并提供迁移指引，不得静默补默认值或伪装成功。

#### Scenario: 旧调用方发送旧请求体
- **WHEN** 调用方经 /promotions/** 提交旧版促销接口的请求体
- **THEN** API 返回明确的旧契约不支持结果及迁移指引，不创建计划、receipt 或平台 attempt

### Requirement: 内容和推广由同一营销责任边界管理

系统 SHALL 在同一营销域维护内容、品牌素材、发布计划、平台尝试及效果来源，对外保留受权适配和稳定来源映射。旧模块退役后 MUST 不再产生两处独立的内容发布状态写入，媒体生成及通知继续按其公开能力消费。

#### Scenario: 新平台回执更新发布状态
- **WHEN** 一个营销发布尝试获得有效平台回执
- **THEN** 同一领域内更新该尝试及内容发布摘要，不再跨服务回写另一个内容状态真源

### Requirement: 发布确认绑定内容素材账号和版本

发布计划 SHALL 固定内容 revision、素材版本、目标平台及受权账号、规则版本和实际参数 hash，确认要求当前权限和 expectedPlanVersion。任何绑定变化必须重新准备并确认，不得用旧批准执行新内容或新账号。

#### Scenario: 审批后正文或目标账号变化
- **WHEN** 发布内容或目标账号与已确认计划不同
- **THEN** 系统拒绝沿用原确认，要求对新版本重新受理，不执行外部发布

### Requirement: 同一确认可靠返回原发布回执

系统 SHALL 以稳定请求和每目标尝试身份幂等受理发布，持久区分本地接受与平台成功。重复同内容请求返回原回执，不同内容复用同号 MUST 拒绝；本地事务失败不能伪造外部撤销或成功。

#### Scenario: 多平台发布部分成功后重查
- **WHEN** 同一计划包含成功、失败和结果未知的目标
- **THEN** 回执分别返回每目标真实状态及原尝试身份，不能整体标为成功或重新发布所有目标

### Requirement: 未接真实平台不得退回模拟成功

真实发布缺少已实现 provider、有效配置或账号授权时 SHALL 在副作用前返回明确不可用。模拟 provider 仅在显式模拟模式受理，API、回执、事件和导出 MUST 继承 SIMULATED，不得由调用者改为真实。

#### Scenario: 生产模式选择未实现渠道
- **WHEN** 目标渠道没有可用真实实现
- **THEN** 系统返回 PROVIDER_NOT_CONFIGURED 或对应结构化不可用，不生成真实成功发布号

### Requirement: 外部结果未知只核对原尝试

网络超时或响应丢失后无法确认外部副作用时 SHALL 保留原尝试及 OUTCOME_UNKNOWN，沿原关联查询或人工核对。系统 MUST NOT 自动换 requestId、callId 或目标尝试重发，也不得因本地失败推断平台未发布。

#### Scenario: 平台接受后调用方超时
- **WHEN** 系统不能确认该尝试的最终结果
- **THEN** 保留未知状态及原关联并提供核对入口，不自动创建第二条发布

### Requirement: 效果数据需要真实来源且可幂等修正

曝光、点击、线索等观察 SHALL 包含渠道来源、原事件身份/版本、统计窗口、采集时间和数据模式。重复同步不得重复累计，迟到修正依来源版本生效；真实来源缺字段 MUST 返回未知，不以模拟算法补齐。

#### Scenario: 同一渠道观察重复或修正到达
- **WHEN** 相同观察事件重复送达，随后到达更高来源版本
- **THEN** 系统只保留一次当前有效观察及其修订历史，不将重复项累加为新增曝光或线索

### Requirement: ROI 使用可归属收入成本和明确口径

真实 ROI SHALL 只有在存在可归属收入、成本及定义版本时才计算，数据不足返回未知。系统 MUST NOT 将线索数乘固定金额作为真实收入；模拟效果必须在查询、聚合和导出中与真实数据分别表达。

#### Scenario: 只有线索数量与推广成本
- **WHEN** 没有可核验归属收入
- **THEN** 系统可返回定义清晰的线索成本，真实 ROI 返回未知，不生成估造收入

### Requirement: 迁移保留历史身份和单一写入责任

合并迁移 SHALL 保留原来源 ID、外部发布 ID、状态及幂等关联，并在切换期间确保单一有效写入责任。历史存储和未知尝试核对材料不得因模拟数据说明而删除；回退不得重放已完成发布。

#### Scenario: 两个旧库记录 ID 相同
- **WHEN** 迁移对象的旧数值 ID 与营销库已有 ID 冲突
- **THEN** 系统通过来源映射保持不同业务身份，并核对历史引用，不覆盖已有内容或发布记录

### Requirement: 营销来源消费与其它领域实现解耦

营销域需要房源事实时 SHALL 使用 domain-parallel-v1 的公开来源契约，显式模拟模式允许固定来源及渠道回执替身，业务生命周期与本地记录必须实际执行。真实模式缺少房源来源、provider、身份或当前权限 MUST 按明确错误拒绝，不得依赖另外一个 change 的未发布实现或静默回退模拟。

#### Scenario: 房源合并尚未完成
- **WHEN** 营销域显式采用固定 v1 模拟房源和平台来源，房源、知识、经营新增实现均未交付
- **THEN** 仍能完成本域准备、确认、部分结果核对和效果查询，并保留 SIMULATED，不要求先取得其它领域新的 Java 制品

#### Scenario: 模拟验收后切换真实模式
- **WHEN** 配置改为真实渠道但真实 provider 或当前账号授权缺失
- **THEN** 真实发布明确失败，不能因模拟生命周期曾通过而继续使用测试身份、模拟回执或模拟效果

### Requirement: 合并后的营销域保留内容与推广全生命周期

统一营销域 SHALL 保留原有内容创建、渠道版本创建、审核/发布状态维护、按房源列表和多条件检索，以及推广发布、效果同步/查询、ROI 报告、品牌素材创建/列表等业务结果。原 REST、Dubbo、Agent、MCP 和 Supervisor 调用 MUST 映射到新契约并记录迁移；发布状态 MUST 由受控流程和真实 provider 回执推进，不得由任意状态更新伪造。

#### Scenario: 创建并检索多渠道营销内容
- **WHEN** 营销人员为一套获准房源创建内容及渠道版本，并通过房源条件检索
- **THEN** 新域能返回内容、各渠道版本及当前审核/发布状态，旧内容引用不会因合并丢失

#### Scenario: 查询效果和品牌素材
- **WHEN** 当前获准用户同步或查询一套房源的推广效果、查看 ROI，并创建或列出品牌素材
- **THEN** 新域保留这些业务结果及来源/窗口/模式信息；缺少可归属收入时真实 ROI 为未知，不估造收入

### Requirement: 旧单次发布入口迁移后仍可完成发布业务

旧 `/promotions/publish` 和 Dubbo `PromotionRpcService.publish` 的调用方 SHALL 迁移到内容选择/创建、审核、计划准备、明确确认及回执/核对组成的统一流程。prepare 只表示计划已就绪，不得对外报告发布完成；迁移 MUST 覆盖已知 HTTP、RPC、A2A/ReAct、MCP 和 Supervisor 调用方。

#### Scenario: 旧调用方完成一次批准发布
- **WHEN** 已迁移的调用方按新流程提交内容并取得明确批准
- **THEN** 系统通过原 attempt 执行发布并按 provider 回执返回结果，调用方可查询 receipt 或核对 UNKNOWN，不重复创建发布尝试

#### Scenario: 仅准备了发布计划
- **WHEN** 调用方只调用 prepare 或用户尚未批准 confirm
- **THEN** 内容保持未发布，系统不返回发布成功或通知已发送的结果

### Requirement: 原模拟渠道能力继续可验收且不冒充真实发布

既有 BEIKE、DOUYIN、XIAOHONGSHU 目标在本 change 中 SHALL 继续通过显式 fixture provider 覆盖发布结果；模拟回执 MUST 保留 SIMULATED。没有对应真实 provider 和授权配置时，生产模式 MUST 明确不可用且不得使用 fixture 成功回退。

#### Scenario: 模拟渠道闭环
- **WHEN** 用户在显式 fixture 模式选择任一既有模拟渠道并完成获批发布
- **THEN** 内容、attempt、回执和后续效果均保留模拟模式，并能沿原 attempt 查询

#### Scenario: 生产渠道 provider 缺失
- **WHEN** 生产模式选择尚未接入真实 provider 的渠道
- **THEN** 系统明确报告不可用，不创建模拟成功回执或真实发布状态
