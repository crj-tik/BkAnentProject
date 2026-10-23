# a2a-memory-coordination Specification

## Purpose
本能力将 Agent 间实时协作输出与长期可复用记忆分开管理，在不增加前端 SSE 串行记忆读取的前提下，使协作记录具有可信参与者授权、稳定版本和可核验就绪状态，避免实时展示权限被误当作全域私有历史访问权。

## Requirements

### Requirement: 实时内容与持久记忆采用双轨交付

A2A Agent 只有在请求入口完成必需的 Memory 身份、工作集和授权/版本预检后才可执行；预检失败 MUST 在模型或业务工具执行前拒绝该任务，不允许绕过 Memory。预检通过后，A2A SHALL 继续直接交付获准展示的进度、正文及本次结构化结果，Supervisor SHALL 能直接将已收到且获准使用的协作内容纳入当前推理及前端 SSE。长期记忆记录 SHALL 独立持久化；实时事件链 MUST NOT 要求先持久化每个 delta、回取 memory 正文或等待语义索引，MUST NOT 为已具备的本次必要结果重复读取记忆。可选索引或经验加工延迟不得阻塞实时事件链，也不得关闭基础 Memory 生命周期。

#### Scenario: Memory preflight failure blocks the Agent
- **WHEN** 正式 A2A 入口收到任务，但 Memory 工作集、当前身份或授权版本无法核验
- **THEN** 任务在调用模型或业务工具前失败，且不通过直接调用 ReactAgent 或无记忆路径继续执行

#### Scenario: 可选记忆加工慢但实时结果仍到达
- **WHEN** Memory 预检已通过、SubAgent 正在产生获准显示的文本，但异步索引或经验加工延迟或不可用
- **THEN** 文本仍由 A2A 进入原 SSE 事件链，该 delta 路径新增记忆读写和模型加工调用次数为零；附加加工状态独立记录，基础 Memory 生命周期仍保持接入

#### Scenario: 当前协作不需要回取已有正文
- **WHEN** Supervisor 已收到本次协作所需的结构化结论与必要正文
- **THEN** 当前推理直接使用该获准内容，不串行检索同一内容后才继续或发送 SSE

### Requirement: 展示协作与长期导出分别授权

系统 SHALL 分别校验 display、collaboration、durable export 的接收范围、目的和内容白名单，并保持有界正文大小。共享 SHALL 以真实 owner、来源 domain/agent、conversation/matter、接收者、purpose、授权版本和有效期为依据；同 owner 不授予跨域私有记忆读取权。获准接收一段结果 MUST NOT 自动获得来源 Agent 的完整历史、偏好、逐字稿或内部推理。

#### Scenario: 进度可见但私有历史不可读
- **WHEN** Supervisor 可以接收访谈 Agent 的一段进度，但没有其私有档案授权
- **THEN** SSE 仅包含获准进度，Supervisor 请求其他私有历史时被拒绝，不能用相同 owner 或 A2A contextId 扩权

#### Scenario: 协作内容转交其他 Agent
- **WHEN** 结果接收者希望将内容转交一个未在原授权范围内的 Agent
- **THEN** 系统重新验证转交授权，不将先前的可展示或可协作权限解释为无限传播权

### Requirement: 共享记录由来源域版本化发布

协作共享记录 SHALL 保留稳定 exportId、来源调用关联、版本、内容哈希及来源证据，由来源域发布不可变版本；接收 Agent SHALL 仅在当前授权范围内读取。修改建议 SHALL 回到来源域形成新版本，MUST NOT 让多个 Agent 覆盖同一个无版本共享对象。撤回、删除或接收权限失效 SHALL 立即在最终读取中排除记录，并使运行中的获准投影失效。

#### Scenario: 接收方提出修正
- **WHEN** 一个接收 Agent 对共享结论提出修正
- **THEN** 原记录保持可追溯，来源域验证并发布新版本后才成为当前共享结论，接收方不能直接覆盖来源正文

#### Scenario: 旧引用在授权撤回后读取
- **WHEN** 共享权限撤回而接收者持有先前合法 exportId
- **THEN** 当前详情与检索拒绝访问该记录，缓存与旧引用不绕过当前授权

### Requirement: 引用就绪与索引就绪如实区分

共享引用 SHALL 是定位数据而不是认证凭据。系统 SHALL 区分交付待处理、持久可读、索引待应用和索引可检索状态；持久确认前 MUST NOT 宣称引用可读，索引接受 MUST NOT 宣称检索已就绪。A2A 增量字段 SHALL 保留现有正文和结果兼容；异步失败 SHALL 保留可重试状态和来源身份，不伪造 COMMITTED 或删除业务执行事实。

#### Scenario: 持久化已完成而索引尚未追平
- **WHEN** memory 已确认共享记录持久可读，但关键词或向量投影仍未应用
- **THEN** 获准调用者可以按稳定引用读取真源，状态明确为索引未就绪，不依赖 RAG 查询才能获得详情

#### Scenario: 只取得待处理标识
- **WHEN** A2A 结果携带一个待归档的 sourceExportId，而 memory 尚未确认保存
- **THEN** 结果明确标记待处理，不将该标识伪装成已提交可读引用；后续确认使用同一来源身份关联

### Requirement: 既有成果与前端事件时序保持兼容

生成成果的可靠保存 SHALL 保持既有完成边界，artifact.created SHALL 仅在成果保存成功后发布，tool/workflow 完成 MUST NOT 被异步偏好或索引加工改成先完成后保存成果。SSE SHALL 保留 eventType/content/result、eventId/sequence、父子关联、visibility 和重连水位语义，MUST NOT 将私有记忆引用或内部协作正文投递到无授权订阅。Studio 人工修订 SHALL 保持其独立版本来源，不被生成记忆回填覆盖。

#### Scenario: 生成成果保存失败
- **WHEN** 一个工具已经返回结果但其应持久化成果未保存成功
- **THEN** 不发布虚假的 artifact.created 或成果已保存状态，错误与结果事实分别记录，不能借记忆后台加工掩盖失败

#### Scenario: 父任务订阅和重连
- **WHEN** 授权前端通过正式事件入口订阅父任务并在断线后携带重连水位
- **THEN** 其可见子事件沿可信父子关联到达并去重回放，其他 owner/task 的私有事件不出现，已有终态与成果时序不被新增记忆通知改写

### Requirement: 认证与延迟观测不进入记忆正文

认证材料 SHALL 只沿受支持专用传输流转，MUST NOT 进入 prompt、普通 metadata、记忆记录、checkpoint、日志或 SSE。验收 SHALL 在相同 fixture 下分别验证首个可见事件、A2A 终态展示、成果持久可读、共享记忆可读、索引可检索和 Studio 可读性，并记录功能结果及必要 I/O；不能把其中一项完成误报为其他项完成。验收 MUST NOT 要求记录延迟分布、改造前性能基线或前后性能对比。Studio 前端仍在设计、当前无可执行实例时 SHALL 明确标记浏览器验收为未测，并在可用后补验。

#### Scenario: 专用认证信标与共享引用同时存在
- **WHEN** 一个 A2A 调用携带有效专用身份及获准共享引用
- **THEN** 认证只用于传输校验，前端/记忆仅见获准业务内容与非凭据引用，不能凭引用绕过认证

#### Scenario: 分阶段故障验收
- **WHEN** 故障注入使记忆持久化或索引延迟，但 A2A 输出和既有成果链正常
- **THEN** 报告分别展示各阶段真实状态及延迟，不宣称端到端记忆已完成，也不移除现有认证校验来降低 SSE 延迟
