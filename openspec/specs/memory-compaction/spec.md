# memory-compaction Specification

## Purpose
定义获准会话原文的可靠增量记录、token 压力与事项结束双触发压缩、安全工具调用边界、可回溯摘要和并发替换契约；确保失败及重启不丢旧窗口或执行事实，历史补忆始终有界、基于当前授权证据，并遵守领域真源与隐私敏感材料边界。

## Requirements

### Requirement: Reliable incremental archive precedes compaction

系统 SHALL 在完整获准输入或消息、工具结果形成时可靠增量记录其内容、来源身份、顺序、事项关联和完整性证据，且记录不得仅依赖内存队列、正常执行返回或压缩触发。原文 MUST 在对应范围被压缩替换或从活跃窗口移除前获得持久覆盖确认；仅确认短期交付记录而长期档案仍未确认时不得裁剪该范围。流式碎片和事件审计不得作为完整记忆档案的唯一来源；记忆交付失败不得改写既有业务调用结果，记录失败亦不得虚报已归档。

#### Scenario: Crash before any compaction trigger preserves recorded input
- **WHEN** 用户输入已可靠记录，但执行在首次压缩或正常返回前崩溃
- **THEN** 原输入和其来源顺序仍可恢复并继续交付，不因尚未压缩而丢失历史

#### Scenario: Delivery buffering is not archive coverage confirmation
- **WHEN** 待压缩消息仅已可靠进入交付缓冲，长期记忆档案尚未确认覆盖
- **THEN** 系统不移除对应原文，不将缓冲成功表示为摘要已覆盖可回查档案

#### Scenario: Tool result delivery failure does not change execution facts
- **WHEN** 有副作用调用的完成事实已按原业务规则持久化，但其记忆交付失败
- **THEN** 系统保留原完成事实并明确记忆尚未确认，不将调用改判为结果未知或为了记忆归档重执行业务工具

#### Scenario: Reliable recording failure is not successful memory persistence
- **WHEN** 完整消息形成后，其可靠增量记录失败
- **THEN** 系统明确报告未记录状态，保留仍可恢复的材料并禁止压缩移除该消息，不声称已成功归档

### Requirement: Idempotent ordered archive independent of active windows

系统 SHALL 以稳定消息及来源事件身份进行幂等追加，保留会话内单调顺序、内容完整性证据和可追溯来源；相同来源的重复交付不得生成重复历史或新证据，相同身份携带不同内容 MUST 显式冲突而不能静默覆盖。活跃窗口截断、替换或摘要生成 MUST 不删除对应原文档案，且不将摘要、检索注入或 assistant 复述重新记作用户原始观察。档案读取仍 SHALL 遵守当前授权、撤回、删除和受管保留规则，不因原文可追溯而绕过这些规则。

#### Scenario: Retry after missing receipt does not duplicate history
- **WHEN** 同一完整消息因交付回执丢失被再次提交
- **THEN** 系统识别同一来源并返回对应持久事实，不新增消息、顺序位置或证据观察次数

#### Scenario: Conflicting content for one source identity is rejected
- **WHEN** 同一稳定来源身份再次提交但内容完整性证据与已记录内容不一致
- **THEN** 系统显式报告冲突，保留已确认原文，不以重试为由覆盖

#### Scenario: Window replacement does not replace the archive
- **WHEN** 活跃消息窗口被压缩为摘要及近期消息
- **THEN** 已覆盖原文仍可在当前授权及保留规则下按原来源读取，不被窗口替换或截断操作删除

### Requirement: Token pressure and verified matter closure are independent triggers

系统 SHALL 同时支持可配置软 token 阈值触发和事项结束触发，并使用一致的安全性、版本与覆盖约束。软阈值与硬上下文预算 MUST 分别表达；token 估算 SHALL 标识所采用的估算规则或版本，不得将粗略字符换算冒称精确 tokenizer 结果。事项结束 MUST 有用户明确确认或可核验且足以证明该事项完成的实际结果作为证据；模型提议、单个子任务结束或不相关完成事件不得单独关闭事项。任何触发均不得为了缩短窗口误删开放事项的关键材料。

#### Scenario: Token pressure triggers compaction without matter closure
- **WHEN** 估算的上下文 token 已达到配置软阈值，但当前事项仍开放
- **THEN** 系统可请求安全压缩，保留开放事项关键材料和未决事实，不将 token 压力当作事项完成证据

#### Scenario: Confirmed matter closure triggers compaction below the soft threshold
- **WHEN** 用户明确确认事项已结束，且当前上下文尚未达到软 token 阈值
- **THEN** 系统记录结束证据并可触发该事项的安全压缩，无需等待 token 压力

#### Scenario: Verifiable outcome closes only the matter it proves complete
- **WHEN** 实际完成结果可核验且足以证明当前事项完成
- **THEN** 系统仅记录该事项的结束证据，不推断其他事项或领域业务流程已结束

#### Scenario: Agent completion or model suggestion is insufficient
- **WHEN** 模型提出结束候选，或 A2A 完成的只是创建访谈场次而非访谈本身
- **THEN** 系统不据此关闭未完成事项，也不裁剪其仍需使用的关键材料

### Requirement: Complete tool groups and structured response data

系统 MUST 将包含工具调用的 assistant 消息、全部关联工具响应及其调用身份视为不可拆分的完整组；并行多调用消息不得仅保留部分响应，未完成组不得进入会破坏组完整性的裁剪边界。获准工具结果 MUST 保留语义完整的结构化 responseData、来源和执行状态，不能仅以可见摘要文本代替；审批待办、未执行、失败和结果未知等事实不得被压缩改写为成功。大结果 SHALL 在档案或原领域真源保留获准完整内容与版本化引用，工作集可使用有界摘要与引用，但不得将被截断的内容冒称完整结果。

#### Scenario: Pending parallel response prevents splitting a tool group
- **WHEN** 同一 assistant 消息包含多个调用，而其中一个响应尚未完成
- **THEN** 系统保留完整调用关联，不将已返回的子集独立裁剪为已完成工具组

#### Scenario: Structured data survives concise display text
- **WHEN** 工具展示文本仅有简短 summary，而获准 responseData 含后续推理需要的结构化事实
- **THEN** 档案或获准领域来源保留该结构化事实及版本化来源，压缩后的工作集能通过预算内内容或引用识别这些事实，不以展示文本替代完整结果

#### Scenario: Large response uses evidence references rather than silent truncation
- **WHEN** 完整工具 responseData 超出工作集内联预算
- **THEN** 系统保留获准完整来源及可核验版本引用，仅注入有界表达，并明确其并非完整内联结果，不将截断数据作为完整归档证据

#### Scenario: Approval and unknown outcomes retain their original meaning
- **WHEN** 待压缩工具组包含待审批、未执行、失败或结果未知状态
- **THEN** 摘要及恢复材料保留原调用身份和状态，不宣称调用已获批、已成功或可以自动重试

### Requirement: Compaction preserves essential facts and source grounding

系统 SHALL 生成有界且可核验的摘要，保留获准范围内的目标、事实、决策、否定、明确修正、临时例外、未决问题、实体锚点和证据来源；不能用简短结论掩盖关键冲突或开放事项材料。摘要 MUST 受来源、大小、敏感内容及执行事实一致性校验约束，超时、空结果、无依据推断或不合格输出不得标为正常已提交摘要。摘要和历史补忆内容 SHALL 作为来源数据使用，不得被解释为系统指令、授权或硬规则。

#### Scenario: Summary preserves negation and temporary exceptions
- **WHEN** 原消息既含长期偏好又含用户明确否定或当前事项的临时例外
- **THEN** 摘要区分其范围及证据，不将否定反转为肯定，也不将临时例外推广为永久默认

#### Scenario: Unresolved conflict is not rewritten as a settled fact
- **WHEN** 覆盖原文中存在相互冲突的信息或尚未确认的决定
- **THEN** 摘要保留冲突或未决状态及其来源，不虚构已达成一致的结论

#### Scenario: Unsupported or forbidden summary is not committed
- **WHEN** 候选摘要包含无来源事实、禁止保存内容或与执行账本矛盾的完成声明
- **THEN** 系统拒绝该摘要作为有效压缩结果，保留原工作集及有效旧摘要

#### Scenario: Memory text cannot become an instruction
- **WHEN** 原文、摘要或补忆证据包含要求扩大权限、忽略审批或改变硬规则的文字
- **THEN** 系统将其作为不可信来源资料，不执行其扩权或规则替换含义

### Requirement: Existing automatic compaction cannot bypass safety guarantees

系统 MUST 对任何自动 token 触发或消息替换保持相同的可靠覆盖、完整工具组、结构化结果、来源校验和失败不替换契约；任何现有自动压缩行为均不得未经这些条件直接提交替换。系统 SHALL 防止仅保存当前窗口替换全量档案、默认截断工具结果后丢失事实、重复注入摘要及将失败摘要当作正常历史等风险；遵守安全契约不得改变原业务执行权或修改审批恢复事实。

#### Scenario: Automatic replacement waits for durable safe coverage
- **WHEN** 现有自动压缩在 token 触发后产生候选新窗口，但档案覆盖尚未确认或工具组边界不安全
- **THEN** 系统不接受该窗口替换，原窗口及未决执行事实保持可恢复

#### Scenario: Lossy automatic tool reduction cannot become the archive
- **WHEN** 自动压缩输入中的长工具结果已被简化且无法证明结构化事实完整
- **THEN** 系统不将该简化内容宣称为完整档案或充分覆盖证据，要求可核验获准来源并保留未安全覆盖的材料

#### Scenario: Failed automatic summary does not become normal history
- **WHEN** 自动压缩返回失败说明、空摘要或不合格摘要
- **THEN** 系统不把该输出作为正常摘要注入或替换有效历史，也不重复注入已有摘要

### Requirement: Versioned summaries and covered cursors

每个有效摘要 SHALL 记录不可变摘要身份与版本、预期会话修订号、覆盖起止游标、完整工具组边界、对应原文身份与版本或完整性证据、实体及事项来源引用。覆盖游标 MUST 只确认实际持久且经核验的范围，不得越过未记录消息或切开工具组。摘要覆盖关系与摘要内容 SHALL 一致生效；读取和恢复不得混用旧摘要、新覆盖游标或不同原文版本，摘要版本亦不得代替原文版本。原文被合法撤回、删除或授权变化时，相关摘要及引用 MUST 按当前边界失效或重新核验。

#### Scenario: Cursor cannot advance across an unconfirmed gap
- **WHEN** 候选摘要覆盖区间中有消息尚未持久确认或工具响应组未完整
- **THEN** 系统不将覆盖游标推进到该缺口之后，也不宣称区间已完整压缩

#### Scenario: Restored summary and raw tail use matching coverage
- **WHEN** 系统根据摘要和近期原文恢复工作集
- **THEN** 系统使用同一有效摘要版本及其覆盖关系，保留覆盖范围之后的原文，不遗漏或重复注入已覆盖消息

#### Scenario: Changed source invalidates stale summary evidence
- **WHEN** 摘要引用的原文版本或完整性证据不再匹配，或来源已撤回、删除或不可访问
- **THEN** 系统不继续将原摘要内容当作当前可信可用证据，按受影响范围使其失效或重新核验

### Requirement: Compare and swap replacement under concurrent triggers

系统 SHALL 仅在会话修订号、原文覆盖范围、当前摘要版本及授权仍满足预期条件时，以比较并替换（CAS）语义使新摘要与对应工作集替换生效。token 和事项触发 MUST 服从同一版本及覆盖契约，不能由两个压缩结果无条件互相覆盖。压缩期间的新输入、较新摘要或失效更新权 SHALL 使旧候选不能覆盖当前窗口；过期候选不得被宣称为当前摘要，新消息及原档案不得因此丢失。

#### Scenario: New input invalidates a stale compaction commit
- **WHEN** 摘要生成期间有新输入推进会话修订号
- **THEN** 旧候选不能按原预期版本替换当前窗口，新输入保留且旧候选不被标为当前有效摘要

#### Scenario: Summary commit succeeds but runtime replacement fails
- **WHEN** 摘要及覆盖关系已持久提交，但运行工作集替换失败或进程随后崩溃
- **THEN** 恢复重新核验摘要覆盖证据和当前授权，合并所有未覆盖原文尾部，不遗漏或重复消息，不重执工具或改变原审批与调用身份

#### Scenario: Token and closure candidates cannot overwrite each other blindly
- **WHEN** token 触发与事项结束触发基于相同旧版本生成两个候选，其中一个已成功提交
- **THEN** 另一个候选必须重新核验预期条件，不能整体覆盖已提交摘要及覆盖关系

#### Scenario: Late completion after takeover is rejected
- **WHEN** 旧压缩执行失去更新权后才返回结果，而新执行已建立较新有效窗口
- **THEN** 系统拒绝旧结果替换，不覆盖新窗口、摘要版本或授权状态

### Requirement: Failed compaction retains old windows and enforces hard budgets

系统 MUST 在摘要失败、超时、校验拒绝、持久确认缺失或版本冲突时保留有效旧窗口、旧摘要及未覆盖原文，不进行破坏性替换。硬上下文预算 SHALL 是当前模型输入的硬限制；没有安全且预算内的新工作集时，系统 MUST 在有界等待或重试后返回 CONTEXT_BUDGET_EXCEEDED 并保留恢复材料，不无限增长、无限等待、静默截断工具组或提交超预算输入。后台压缩不得阻塞逐 delta 或 progress 交付；只有实际模型输入执行边界需要为硬预算进行有界等待。

#### Scenario: Summary timeout preserves the previous working set
- **WHEN** 压缩生成超时或失败，且旧工作集仍在当前硬预算内
- **THEN** 系统保留有效旧窗口及旧摘要，不用错误消息或空内容替换历史，可按受管策略继续获准执行

#### Scenario: Hard budget stops unsafe model input
- **WHEN** 旧工作集已超出当前硬预算，而有界处理后仍无安全且预算内的替代工作集
- **THEN** 系统返回 CONTEXT_BUDGET_EXCEEDED，保留原文、有效摘要和执行恢复材料，不发送超预算输入或静默丢弃关键内容

#### Scenario: Compaction does not block stream callbacks
- **WHEN** 后台压缩仍在处理中而获准 delta 或 progress 已可交付
- **THEN** 系统继续原实时交付，不在回调路径等待摘要；若下一次模型输入触及硬预算，仅在该执行边界进行有界处理

### Requirement: Closed matter compaction and authorized reopening

系统 SHALL 在事项转移时仅裁剪已获结束证据且安全覆盖的关闭事项材料；开放事项的关键约束、未决信息及执行事实 MUST 保持可用。重开事项 SHALL 在当前身份和授权下从可回溯摘要、近期消息及必要来源恢复获准上下文，不把上下文重开当作业务状态回退、新执行授权或新调用身份。

#### Scenario: Closing one matter preserves another open matter
- **WHEN** 用户转入新事项，而会话中另一个事项仍有未决决定或待审批调用
- **THEN** 系统仅压缩安全覆盖的关闭事项，保留开放事项关键材料及原待审批事实

#### Scenario: Reopened matter resumes from grounded authorized context
- **WHEN** 用户明确重开已压缩事项且当前授权仍成立
- **THEN** 系统恢复该事项的目标、决定、例外、未决项及来源证据，不虚构被压缩掉的细节，也不重执行业务调用

#### Scenario: Reopening does not revive withdrawn material
- **WHEN** 已关闭事项的部分来源在重开前被撤回或不再获准
- **THEN** 系统不恢复失效内容，并说明影响当前请求的必要证据缺口，而非以旧摘要绕过授权

### Requirement: Bounded evidence based entity history recall

系统 SHALL 先利用本地近期消息及有范围的实体、别名和事项锚点处理历史指代；仅在存在实际历史指代或事实缺口时发起记忆补忆，不因任意新词自动检索。补忆 MUST 默认限定当前 owner、domain、agent 及获准 conversation/matter，只有明确请求与当前授权同时成立才扩大到本域跨会话或获准共享记录；各次候选、扩展、返回证据和注入 SHALL 共用有界预算。返回 SHALL 包含简短解释、相关事实或决定及否定、来源引用和版本；语义相似不得证明实体相同，无匹配或证据不足不得编造。任何候选在使用前 MUST 核验真源当前版本、删除及授权状态，引用或检索结果不得自行授予读取权。

#### Scenario: Local anchor resolves a historical alias without remote recall
- **WHEN** 当前获准本地锚点和近期消息已能明确解释用户的历史别名或指代
- **THEN** 系统复用现有证据，不为了同一指代重复远程检索

#### Scenario: Unfamiliar new word is not automatically a history query
- **WHEN** 用户首次提出新对象或新术语且没有历史指代或事实缺口
- **THEN** 系统不会仅因该词陌生而查询私人历史，也不将相似历史对象直接当作该新对象

#### Scenario: Historical gap returns bounded versioned evidence
- **WHEN** 压缩后的历史指代确有缺口，且范围内存在当前获准证据
- **THEN** 系统在累计预算内返回简短解释、相关事实或决定及否定、来源与版本，仅注入解决缺口所需的材料，不装配全会话原文

#### Scenario: Wider history requires both explicit intent and authorization
- **WHEN** 当前会话未找到充分证据，而用户明确要求查询本域其他会话或某份共享记录
- **THEN** 系统仅在扩展范围当前获准时进行预算内补忆，否则不扩大搜索或泄漏该范围内容

#### Scenario: Similarity or stale candidates cannot establish a fact
- **WHEN** 检索仅返回语义相似对象、版本不匹配或已删除、撤回、未获准的来源
- **THEN** 系统不将其当作原实体或有效事实，说明证据不足或无法获取，并在必要时请求用户澄清

### Requirement: Privacy and domain source boundaries apply to every compaction stage

系统 MUST 在原文记录、摘要、实体锚点、工具数据、历史补忆、共享投影及后台加工中执行相同的最小授权和敏感材料边界。用户凭据、认证信标、脱敏映射及禁止暴露的内部材料不得进入通用记忆、模型上下文、普通 metadata、日志或前端事件。领域逐字稿、业务状态、完整营销正文、人工修订及执行账本 SHALL 保留原领域真源，记忆仅保存获准的有界投影与引用，不无限复制或另建权威副本；需要保留的获准工具事实不得成为复制禁止材料的理由。后台加工 MUST 限定已授权来源事件和精确 owner/domain 范围，并在提交前检查当前删除或撤回，不持久化用户凭据或获得通用用户搜索权。

#### Scenario: Sensitive tool fields do not enter generic archives
- **WHEN** 工具结果同时含有获准事实、用户凭据、认证信标或脱敏映射
- **THEN** 系统仅按当前许可保留必要事实和引用，禁止材料不进入档案、摘要、模型上下文、日志或普通 metadata

#### Scenario: Domain transcript remains a bounded authorized projection
- **WHEN** 补忆需要访谈逐字稿中的一个事实
- **THEN** 系统仅读取和注入获准的有界脱敏证据及原领域来源引用，不复制全量逐字稿、还原脱敏映射或改变领域证据语义

#### Scenario: Generated output and manual revision retain separate source identities
- **WHEN** 摘要或补忆同时引用业务生成成果和后续人工修订
- **THEN** 系统保留其各自不可变来源与版本，不将人工修订覆盖为通用记忆原文，也不把同标题内容合并为一个事实源

#### Scenario: Background processing cannot outlive revoked source permission
- **WHEN** 后台摘要或锚点加工读取来源后，该来源被删除或撤回授权
- **THEN** 系统在提交时拒绝失效内容生效，不通过后台权限保留用户已禁止使用的结果，也不借加工身份扩大读取范围
