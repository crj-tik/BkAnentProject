# Preference History Memory

## Purpose

建立 owner-self 私有偏好记忆行为契约，使每个 domain+agent 私有范围内的稳定偏好维度具有唯一逻辑身份，完整表达过去到现在的变化、否定、纠正及临时例外。明确异步评估与当前会话指令的边界、事项级使用策略、证据与使用权重的独立语义，并保障长历程读取、隐私隔离和删除不被检索或后台处理绕过。其他主体偏好及共享偏好块不属于本变更；跨域交接仍由独立显式共享导出授权处理，不能据此读取偏好块或历程。

## ADDED Requirements

### Requirement: Trusted identity and unique preference block

系统 SHALL 依据可信 owner、固定为 owner 的 subject、domain+agent 私有 scope 和稳定 dimension 确定偏好逻辑块的唯一身份。此变更中的 subjectRef MUST 等于 ownerId，scope MUST 为包含可信 domainId 与 agentId 的 DOMAIN_PRIVATE；系统 MUST NOT 将偏好块写入或合并到 TASK_SHARED/SESSION_SHARED，也 MUST NOT 因共享导出 grant 扩大偏好块读取范围。其他主体偏好和共享偏好块不属于本变更。系统 MUST 将同一身份下的表达归入同一逻辑块，不得以逐句新建维度或合并所有维度代替稳定维度识别，也不得将模型提供的主体、范围、引用或分类视为授权。跨域交接仅通过独立显式 MemorySharedExport/grant 处理，其授权不覆盖偏好历程。

#### Scenario: Multiple expressions concern the same preference dimension
- **WHEN** 同一可信 owner 对同一主体、适用范围和稳定维度提供多次有效表达
- **THEN** 系统维护同一逻辑块及其历程，而非生成多个相互竞争的偏好块或覆盖为无历史的单值

#### Scenario: A candidate names another subject or shared scope
- **WHEN** 请求或模型提出将偏好写入其他 subject 或 TASK_SHARED/SESSION_SHARED scope
- **THEN** 系统拒绝该偏好写入，不猜测主体、不合并共享块，也不扩大当前读取范围

#### Scenario: Explicit export does not share the preference block
- **WHEN** 用户通过独立 MemorySharedExport/grant 显式导出一段获准内容
- **THEN** 接收方仅获得该导出契约所允许的内容，不获得底层偏好块、完整历程或新的 preference block 身份

### Requirement: Asynchronous assessment precedes consolidation

系统 SHALL 在异步归并前评估表达是否为偏好、事项约束、问题或其他内容，并识别肯定、否定、长期修正、临时例外、适用条件及证据来源。未通过评估的候选 MUST 不得作为已确认的长期偏好；评估、归并及检索投影的等待或失败 MUST 不成为当前会话继续响应或实时内容交付的前置条件。

#### Scenario: A question resembles a preference
- **WHEN** 用户询问某项选择是否可行，但没有表达自身倾向
- **THEN** 系统将其与偏好证据区分，不仅因出现维度关键词就更新长期偏好

#### Scenario: A temporary exception qualifies an existing preference
- **WHEN** 用户在特定事项或有效期间内明确接受与长期偏好不同的选择
- **THEN** 系统先识别其条件和临时性质，再以例外关联原偏好，而非将长期默认值无条件替换

#### Scenario: Background assessment is unavailable
- **WHEN** 偏好评估或归并暂不可用，而当前请求能够依据用户明确输入继续
- **THEN** 当前会话及实时内容交付不等待偏好加工，系统不宣称候选已成为持久有效偏好

### Requirement: Reliable evidence and idempotent processing

系统 SHALL 对已接受用于偏好评估的证据提供可恢复的交付状态、稳定事件身份和可追溯来源；重复交付、重试或重复评估 MUST 不得产生重复历程挂点或增加证据数量。系统 MUST 区分证据已可靠接收、评估完成、归并完成和检索可用，不得将其中任一状态冒称为其他状态，也不得把仅暂存且不可恢复的输入报告为已可靠接收。

#### Scenario: The same evidence is delivered repeatedly
- **WHEN** 同一来源事件被重复交付或在中断后重新评估
- **THEN** 系统识别其稳定身份，仅保留一次有效观察及对应变化，不重复增加置信支持

#### Scenario: Processing is interrupted after reliable acceptance
- **WHEN** 证据已被可靠接受但评估或归并尚未完成时发生中断
- **THEN** 未完成证据仍可恢复处理，状态保持真实，恢复不丢失已接受的来源也不重复生效

### Requirement: Source ordered and conditional current state

系统 SHALL 根据明确表达、修正关系、适用范围、有效期间及可靠的来源顺序确定当前有效状态；发生时间、观察时间、有效起止与来源顺序 MUST 保持各自语义。后台处理完成先后 MUST 不得决定偏好新旧；不同 conversation 的局部 sequence MUST 不得直接比较以判定跨会话新旧，跨会话修正必须依据可信因果关系、被修正证据和有效时间。无法可靠排序或解释的冲突 MUST 显式保留为未决，不得依据处理完成时间或未经验证的时间戳静默覆盖。

#### Scenario: Older evidence finishes processing after a correction
- **WHEN** 较早的来源事件在较新的明确修正归并后才完成评估
- **THEN** 当前值仍服从来源顺序和修正关系，较早事件补入其历史位置而不覆盖较新的有效状态

#### Scenario: A scoped exception expires
- **WHEN** 临时例外的有效期间结束，且没有新的长期修正
- **THEN** 系统不再将该例外用作当前默认值，并依据仍有效的长期偏好确定当前状态，同时保留例外历程

#### Scenario: Conflicting sources have no reliable precedence
- **WHEN** 两项互斥表达之间没有可靠来源顺序、适用条件或明确修正关系
- **THEN** 系统保留冲突及各自证据，不将最后完成处理的一项报告为已确定的当前偏好

### Requirement: Complete historical evolution

系统 SHALL 保存并向获准读取者提供逻辑块从过去到现在的完整可追溯历程，包括旧值、否定、修正、临时例外、未决冲突、适用时间、来源顺序和证据引用，以及单独可辨识的当前有效状态。摘要、当前值、检索片段或上下文预算 MUST 不得替代完整历程；无匹配、暂不可读、部分返回及完整返回 MUST 明确区分。完整性声明仅适用于明确的获准范围及所选版本，不得暗示已删除或无权读取的内容仍然可获取。

#### Scenario: An authorized reader requests preference evolution
- **WHEN** 获准读取者请求某逻辑块的变化历程
- **THEN** 系统提供该范围和版本下从过去到现在的变化及证据，明确区分当前状态与历史值，而非仅返回最近值或摘要

#### Scenario: A response cannot include the full history
- **WHEN** 一次响应只能容纳部分获准历程，或部分证据暂不可获取
- **THEN** 系统明确返回部分完整度及可继续读取的范围或不可获取状态，不将截断或缺失报告为完整历程或无匹配

### Requirement: Stable versioned pagination for long histories

系统 SHALL 使长历程的所有片段及分页隶属同一稳定 blockId 和 version，并提供可继续读取的游标、已覆盖范围、是否还有后续及完整度。获准读取者 MUST 能在同一版本下按明确顺序读完全部获准历程；版本变化、游标失效或授权变化 MUST 显式报告，系统不得混合版本，也不得把物理片段当作独立偏好块。

#### Scenario: Retrieval matches one fragment of a long block
- **WHEN** 检索只命中长偏好块的一个片段
- **THEN** 系统将命中解析为同一逻辑块的当前状态和版本化历程入口，声明返回的覆盖范围并提供后续读取，而非声称片段就是完整偏好

#### Scenario: A history changes between page requests
- **WHEN** 读取者使用某版本的游标继续请求，而逻辑块已有新版本
- **THEN** 系统继续提供获准的原版本视图，或明确报告需重新开始读取，不以新版本页面静默拼接旧版本页面

#### Scenario: The final authorized page is reached
- **WHEN** 读取者已取得同一 blockId 和 version 下的全部获准页面
- **THEN** 系统标明该版本和授权范围内历程已完整及无后续页面，不以页面大小或截断长度推断完整性

### Requirement: Explicit corrections immediately govern working context

系统 SHALL 在当前明确表达被识别为纠正后立即使其影响当前 working context，不等待异步评估或持久偏好追平；纠正的主体、维度、范围及临时或长期性质 MUST 保持明确，未涉及的偏好不得被整体替换。尚未追平的旧持久默认值 MUST 不得重新覆盖该纠正，当前纠正也不得被冒称为已经持久归并。

#### Scenario: The user corrects a stale default during a matter
- **WHEN** 用户明确纠正当前事项所使用的偏好，而持久记忆仍包含旧默认值
- **THEN** 当前及后续同事项推理立即采用获准范围内的纠正，并保留其待归并状态，不等待后台完成

#### Scenario: Only one dimension is corrected
- **WHEN** 用户纠正一个维度或仅提出当前事项例外
- **THEN** 系统局部更新相关 working context，保留其他适用偏好和该例外之外的长期默认值

### Requirement: Matter relevant assembly and reuse

系统 SHALL 在新事项开始时按事项目标、主体、范围及当前授权检索和装配相关偏好，在同事项内复用仍有效的装配结果；主体或范围变化、明确纠正、撤回、授权失效或版本失效 MUST 引起相关投影的局部刷新或排除。事项标签 MUST 仅决定上下文适用性，不得充当业务流程或工具调用顺序的控制权。

#### Scenario: Multiple turns continue the same matter
- **WHEN** 后续轮次仍属于同一事项，且相关版本、范围和授权仍有效
- **THEN** 系统复用适用偏好，不为每个模型轮次重复装配，也不因复用增加偏好证据

#### Scenario: A new matter has a different goal
- **WHEN** 用户开始目标不同的新事项
- **THEN** 系统按新事项重新检索相关偏好，不将上一事项的临时例外或无关偏好无条件沿用

#### Scenario: The subject or authorization changes within a matter
- **WHEN** 当前主体、适用范围或授权发生变化
- **THEN** 系统刷新或移除受影响投影，不借助同事项缓存继续使用原主体或原范围内容

### Requirement: Independent evidence confidence and selection weights

系统 SHALL 将 confidence 解释为证据支持程度，将 activation 解释为使用优先级，将 relevance 解释为当前事项适配程度，将 time weight 解释为给定评估时点下的时间影响，分别表达且可追溯。时间权重 MUST 基于有效观察或确认及明确的评估时点；读取、检索、注入、复述和摘要 MUST 不得增加证据或 confidence，也不得刷新 lastObserved。选择权重降低 MUST 不得改写原始观察或删除历史事实。

#### Scenario: An old preference is retrieved repeatedly
- **WHEN** 同一偏好被多次检索、读取或注入当前上下文，而没有新的有效表达或确认
- **THEN** 原证据数量、confidence 和 lastObserved 保持不变，relevance 与时间权重可按当前事项及评估时点独立计算

#### Scenario: New independent evidence confirms a preference
- **WHEN** 获准来源提供可验证的新表达或明确确认
- **THEN** 系统按该来源更新观察及证据支持，保留 activation、relevance 和时间权重的独立含义，不把一次确认计为多项证据

### Requirement: Current use is bounded and subordinate to policy

系统 SHALL 在当前事项的上下文预算内使用获准且相关的当前值、关键例外与必要证据，提供完整历程的读取入口而非无条件注入全部历史。偏好 MUST 作为可解释的数据使用，不得成为系统指令；当前明确请求、有效授权及硬规则与偏好冲突时 MUST 优先于偏好，系统不得因预算或个性化选择绕过硬规则。

#### Scenario: A long history exceeds the working context budget
- **WHEN** 完整偏好历程无法全部纳入当前上下文
- **THEN** 系统只装配相关当前值、关键例外和必要证据，保留完整历程读取能力，不因上下文裁剪删除历史或冒称已注入全部历程

#### Scenario: A remembered preference conflicts with a hard rule
- **WHEN** 记忆中的偏好要求违反当前政策或授权边界的行为
- **THEN** 系统遵守硬规则和授权，不将偏好内容提升为指令或作为放宽规则的依据

### Requirement: Authorization applies to every preference access

系统 SHALL 对历程、当前状态、证据、搜索、分页及后台评估校验可信 owner 与 subject=owner、domain、agent、purpose 及来源权限；同 owner 不代表自动跨域或跨 Agent 可见。偏好块不接受共享 grant 作为读取授权；若内容另行进入共享导出，导出访问独立遵守 MemorySharedExport/grant 契约。后台处理 MUST 仅使用具体获准证据，不得获得通用用户搜索权；检索候选、稳定引用、分类和上下文标识 MUST 不得自行授予权限，索引或缓存命中必须服从当前真源版本及授权状态。

#### Scenario: A reference is forwarded outside its grant
- **WHEN** 未获准的域或 Agent 获得某偏好块引用或检索片段
- **THEN** 系统拒绝其详情、历程及证据读取，不因持有引用或共享 owner 而授予访问

#### Scenario: A background assessment outlives a grant
- **WHEN** 后台评估开始后其证据使用授权被撤回
- **THEN** 系统不得继续使用该证据或提交依赖已撤回证据的偏好归并，也不得通过后台身份扩大读取范围

### Requirement: Revocation and deletion prevent stale reuse

系统 SHALL 在偏好来源撤回或删除后阻止对应块的读取、装配和输出，并排除已有 working context 投影。若用户另行授权共享某段内容，撤回该 MemorySharedExport/grant 只控制独立导出及其副本，不授权接收方读取偏好块，也不自动删除 owner 仍获准持有的私有历程。获准删除 MUST 覆盖目标偏好及可还原内容的历程、证据副本、检索投影、共享副本和缓存，并真实报告完成或未完成状态；陈旧引用、旧页面、重试及迟到事件 MUST 不得使已删除内容重新出现，删除后不得宣称完整历程仍可读取。

#### Scenario: An exported copy is revoked while a matter remains active
- **WHEN** 单独的共享导出授权被撤回，而接收方仍持有该导出的已装配投影
- **THEN** 接收方后续读取、推理和输出排除导出内容；该授权从未使接收方获得原 owner 的偏好块或历程读取权

#### Scenario: Deleted evidence is replayed after deletion
- **WHEN** 已获准删除的偏好或证据被旧任务、迟到事件、旧检索片段或旧分页请求再次引用
- **THEN** 系统拒绝读取或重新归并该已删除内容，不让重试恢复其当前值或历史，并明确删除或不可获取状态

#### Scenario: Deletion has not reached every retained projection
- **WHEN** 删除请求已接受，但仍有目标副本或投影尚未确认清除
- **THEN** 系统立即禁止继续使用目标内容，报告删除尚未完成，不将接受请求或暂时不可检索冒称为全部删除成功
