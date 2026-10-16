# Agent Experience Memory

## Purpose

为 Agent 提供可追溯且受授权约束的软经验记忆，使真正结果和有效反馈经异步评估形成可复核经验，并在事项相关性、证据支持、使用优先级、时间影响及上下文预算之间进行明确选择。划定硬规则不可被学习或衰减改写的边界，区分私有经验与获批脱敏共享经验，保障版本、负面反馈、停用、修正、撤回和删除的生命周期，同时防止检索被误当确认或经验演变为业务执行流程。

## ADDED Requirements

### Requirement: Hard rules remain separate and authoritative

系统 SHALL 将来自当前受管政策、代码或配置的硬规则与可学习、可衰减的软经验分别识别，并保持硬规则的来源和有效版本。硬规则 MUST 只能由其既定受授权入口修改或停用，不得由时间衰减、模型学习、反馈评分或经验选择改写；软经验 MUST 不得被提升为硬规则，也不得因未被选入上下文而使硬规则失效。

#### Scenario: A successful experience conflicts with a hard rule
- **WHEN** 某经验具有正面结果或较高评分，但建议的行为违反当前硬规则
- **THEN** 系统遵守硬规则，排除或限制该经验的使用，不将成功记录作为放宽政策的授权

#### Scenario: Time passes without new validation
- **WHEN** 硬规则或相关软经验长期没有新增确认
- **THEN** 时间影响只能改变软经验的选择权重，不能使硬规则自动降级、修改或停用

#### Scenario: A hard rule is legitimately revised
- **WHEN** 受授权入口发布新的有效政策版本
- **THEN** 系统依据新政策约束经验使用，不由模型或经验归并反向修改政策内容

### Requirement: Experience is private unless explicitly approved for sharing

系统 SHALL 将经验默认限制于可信 owner、domain 和 agent 的私有边界。跨用户的域共享经验 MUST 是经过人工或受授权领域治理批准的脱敏归纳，并明确发布范围、目的、来源可核验关系、版本及有效状态；相同 owner、检索命中、任务关联或模型建议 MUST 不得自动授权跨域、跨 Agent 或跨用户共享。共享内容不得包含未获准的个人细节或可还原私有信息的引用。

#### Scenario: An experience derives from a personal interaction
- **WHEN** 个人事项产生可复用经验，但没有共享批准
- **THEN** 系统仅在该 owner 的获准域和 Agent 范围内保留和使用，不自动发布为域共享或跨用户规则

#### Scenario: A sanitized generalization is approved
- **WHEN** 受授权治理方批准已脱敏归纳及具体共享范围和目的
- **THEN** 系统仅在获准范围内发布该版本的归纳，不随归纳公开私有原文、个人证据或可还原身份的来源引用

#### Scenario: A sharing candidate still reveals a private subject
- **WHEN** 拟发布归纳或其引用能够揭露未获准的个人信息
- **THEN** 系统拒绝将该候选作为已批准的域共享经验，不以模型声称已脱敏代替授权审核

### Requirement: Learning requires genuine outcome or valid feedback evidence

系统 SHALL 仅依据可验证的真实结果或有效反馈评估经验，并保留来源、事项、发生或观察时间、适用条件、结果状态和证据引用。用户反馈、业务结果、部分结果、未知结果和模型自评 MUST 明确区分；模型声称成功、工具返回受理、任务执行终态、检索命中或经验被使用 MUST 不得单独视为业务结果已达成。无法验证的候选不得冒称为已验证经验。

#### Scenario: A model reports success without outcome evidence
- **WHEN** 模型自评成功或某调用已受理，但没有真实结果或有效反馈
- **THEN** 系统保留结果未知或未验证状态，不增加经验的成功证据或刷新 lastValidatedAt

#### Scenario: An authorized user provides attributable feedback
- **WHEN** 获准反馈者针对可关联事项提供真实反馈
- **THEN** 系统以反馈证据评估经验，记录其正负性质和适用范围，不将反馈意见自动等同于独立验证的业务结果

#### Scenario: Only part of a matter has a verified result
- **WHEN** 可核验结果仅覆盖事项的一部分
- **THEN** 系统仅对被证据覆盖的条件和结论进行评估，保留其他结论的未知状态，不将部分完成泛化为全部成功

### Requirement: Outcome assessment is asynchronous and recoverable

系统 SHALL 对已接受的结果和反馈提供可靠可恢复的处理状态、稳定事件身份及幂等评估，先确认来源有效性、正负含义、适用条件和不确定性，再形成或修正经验。异步评估、经验归并和检索准备 MUST 不阻塞当前会话及实时内容交付；重试、重复交付和处理完成先后 MUST 不得导致证据重复或以陈旧评估覆盖较新的有效反馈，无法可靠解释的冲突必须显式保留。

#### Scenario: Feedback is delivered more than once
- **WHEN** 同一真实反馈事件因重试或恢复被重复交付
- **THEN** 系统仅计入一次有效证据，保留稳定来源关系，不重复提高或降低 confidence

#### Scenario: Assessment is interrupted or unavailable
- **WHEN** 已可靠接收的结果或反馈尚未评估完成时发生中断或依赖不可用
- **THEN** 系统保留可恢复的待处理状态并继续当前可执行请求，不虚报经验已验证或已归并

#### Scenario: An older assessment finishes after newer feedback
- **WHEN** 较早结果的评估在较新的有效反馈处理后才完成
- **THEN** 系统按来源关系、可靠顺序和适用条件归并，不以后台完成先后覆盖最新结论，未决冲突保持可辨识

### Requirement: Experience revisions preserve evidence and decisions

系统 SHALL 为软经验及其适用条件、结论、证据支持、反馈、启用状态和评分策略关联提供稳定身份和明确版本。修正、停用、重新确认及共享发布 MUST 保留可授权复核的变更关系和原因；后续选择不得混合不同版本的结论与证据，也不得将旧版本默认为当前版本。时间衰减不得删除原事实，明确获准的隐私删除另受删除契约约束。

#### Scenario: Negative evidence narrows an experience
- **WHEN** 新的有效反例表明某经验只在较窄条件下成立
- **THEN** 系统形成明确的新版本或未决状态，保留旧结论与反例的修正关系，不静默覆盖原经验或隐藏不利证据

#### Scenario: A consumer holds an older version
- **WHEN** 已装配经验在后续使用前被修正、停用或受新的政策版本约束
- **THEN** 系统校验当前有效状态，刷新或排除受影响投影，不把旧结论和新版本证据拼成一个经验

### Requirement: Versioned scoring separates evidence from use

系统 SHALL 使用明确版本的评分规则，分别表达 relevance、confidence、activation 和 time weight 的来源与含义，按当前事项目标、有效证据及明确评估时点计算选择结果。confidence MUST 反映证据支持，activation MUST 反映使用优先级，relevance MUST 反映事项适配，时间影响 MUST 基于有效观察或确认而非最近读取。检索、读取、注入及使用 MUST 不自动增加证据、confidence 或 lastValidatedAt，也不得刷新 lastObserved。

#### Scenario: The same experience is reused repeatedly
- **WHEN** 某经验被多次检索、读取或用于推理，但没有新的真实结果或有效反馈
- **THEN** 证据、confidence、lastObserved 和 lastValidatedAt 不因使用次数改变，相关性和时间权重可按当前事项及评估时点独立变化

#### Scenario: Selection is reviewed under another scoring revision
- **WHEN** 受授权评分规则版本发生变化
- **THEN** 系统明确所用评分版本及其输入语义，能够区分评分变化与证据变化，不将重评分报告为新验证

#### Scenario: Old experience loses selection priority
- **WHEN** 没有新的有效确认且时间权重降低
- **THEN** 系统可降低软经验被选择的优先级，但保留事实和反馈历史，不改变硬规则或伪造新的观察时间

### Requirement: Matter relevant selection respects a token budget

系统 SHALL 在新事项开始时按目标、主体、适用条件、可信授权、有效版本及政策选择软经验，在同事项内复用仍有效的选择，并对候选去重后遵守当前上下文 token 预算。预算 MUST 用于取舍软经验及其必要解释和证据，不得无界注入全部历程、重复片段或牺牲硬规则；范围、授权、政策、版本变化和停用必须引起相关经验刷新或排除。

#### Scenario: More eligible experience exists than fits the budget
- **WHEN** 多项获准相关经验超过当前软经验上下文预算
- **THEN** 系统依据版本化评分选择去重后的有界内容，保留必要适用条件和证据关系，不为容纳全部经验省略硬规则

#### Scenario: Turns continue the same matter
- **WHEN** 当前事项及经验的授权、版本和政策仍有效
- **THEN** 系统复用所选经验，不在每个模型轮次无条件重新装配，也不将复用计为确认

#### Scenario: A new matter or changed scope begins
- **WHEN** 事项目标、主体或获准范围发生变化
- **THEN** 系统重新选择适用经验，排除原事项特有条件，不将旧选择变成新事项的固定执行安排

### Requirement: Retrieval is a candidate signal rather than confirmation

系统 SHALL 将检索结果视为待校验候选，使用当前授权和真源版本确认经验的存在、适用性、启用状态及证据后才允许装配。语义相似 MUST 不得证明同一事项、真实结果或结论正确；检索命中与没有匹配、记忆暂不可用、内容已撤回或已删除 MUST 明确区分，系统不得从不可用推断经验不存在或虚构确认。

#### Scenario: A highly similar candidate lacks applicable evidence
- **WHEN** 检索命中语义相似的经验，但其条件或证据不覆盖当前事项
- **THEN** 系统不将该命中报告为适用性确认，也不增加该经验的验证记录

#### Scenario: A stale projection returns an inactive revision
- **WHEN** 检索候选指向已停用、已撤回或已修正的经验版本
- **THEN** 系统依据当前真源状态排除或刷新该候选，不因高检索评分继续使用旧版本

#### Scenario: Optional experience memory cannot be read
- **WHEN** 经验记忆暂不可用且当前请求能够依据明确输入及硬规则继续
- **THEN** 系统可不使用软经验继续，并真实标明未装配或不可用，不猜测候选也不宣称已完成验证

### Requirement: Feedback and deactivation have an explicit lifecycle

系统 SHALL 接受具有可信来源和适用关联的正面及负面反馈，并在评估后记录其证据、影响、决定和版本。停用 MUST 有明确状态及原因，生效后必须排除该经验的新检索使用、装配及当前 working context 复用；停用或撤回不得由读取次数、评分提升或模型自评自动逆转。重新启用必须具有可复核的有效依据并服从当前政策和授权。

#### Scenario: Valid negative feedback contradicts a recommendation
- **WHEN** 获准反馈揭示经验建议在某条件下失败或有害
- **THEN** 系统保留并评估反例，对置信支持、适用范围或停用作出可追溯决定，不只保留正面反馈

#### Scenario: An experience is deactivated during a matter
- **WHEN** 当前已装配经验的停用状态生效
- **THEN** 系统排除其后续使用和输出，不等待下一事项，也不因曾被选中而继续复用

#### Scenario: A deactivated experience is encountered again
- **WHEN** 检索、模型自评或高评分再次指向已停用经验
- **THEN** 系统保持停用，只有具备有效依据且满足当前政策与授权的明确重新启用决定才能形成新的有效状态

### Requirement: Policy has precedence and experience does not define a business DAG

系统 SHALL 将经验作为可解释的建议数据，服从当前硬规则、授权及明确请求。经验、事项标签、历史步骤文字或推荐顺序 MUST 不得成为强制业务 DAG、自动调用触发器或绕过审批的依据，也不得自行触发重复执行；当前业务调用选择和既有执行事实不得被经验版本或评分接管。

#### Scenario: An experience contains a sequence of past steps
- **WHEN** 某经验描述历史上成功的步骤或下一步建议
- **THEN** 系统仅将其作为受当前请求和政策约束的参考，不将文字转换为强制流程或自动调用安排

#### Scenario: An experience recommends bypassing an approval
- **WHEN** 经验建议与当前审批要求或调用授权冲突
- **THEN** 系统保留审批及授权边界，不因建议来自学习、反馈或域共享而执行越权操作

#### Scenario: Reuse suggests repeating an already completed action
- **WHEN** 经验中的历史做法包含当前事项已完成的有副作用操作
- **THEN** 系统不因装配该经验而自动重复执行，仍依据当前请求、政策及既有执行事实判断是否允许新调用

### Requirement: Authorization and revocation cover all experience use

系统 SHALL 对私有经验、获批共享归纳、证据详情、反馈、搜索、版本读取、导出和后台评估分别验证当前可信 owner、domain、agent、purpose 及有效授权。后台评估 MUST 仅限具体获准结果和反馈，不得获得通用个人历史搜索权；引用和分类不得授予权限。共享撤回生效后 MUST 排除受影响接收方的新读取及已有 working context 使用，并阻止迟到评估重新发布；共享撤回不自动删除原 owner 仍有权持有的私有记录。

#### Scenario: A shared generalization is read across users
- **WHEN** 接收方有权读取已批准域共享经验，但无权读取其私有来源
- **THEN** 系统仅提供获准脱敏归纳及允许公开的证据说明，拒绝私有原文和个人证据详情

#### Scenario: A grant is revoked while assessment is running
- **WHEN** 结果或反馈使用授权在异步评估提交前被撤回
- **THEN** 系统不提交依赖该已撤回证据的经验更新或共享发布，不借后台身份继续扩大处理范围

#### Scenario: A shared experience is withdrawn after assembly
- **WHEN** 获批共享经验的发布或接收授权被撤回，而接收方已装配该内容
- **THEN** 系统停止接收方后续读取、推理和输出使用，并使相关投影失效，不因缓存或稳定引用继续共享

### Requirement: Privacy deletion is distinct from decay and deactivation

系统 SHALL 将时间衰减、停用、共享撤回和获准隐私删除区分为不同生命周期决定。获准删除 MUST 清除目标私有经验及可还原其内容的证据副本、版本历史、检索投影、共享副本和缓存，并真实报告完成状态；未经独立脱敏与保留授权的派生经验不得在删除后继续暴露或依赖已删除内容。旧任务、重复事件、旧引用及陈旧投影 MUST 不得复活已删除经验；允许保留的独立获批脱敏归纳不得再还原已删除私有来源，也不得借此承诺原证据可读。

#### Scenario: Time decay lowers a score
- **WHEN** 经验仅因时间权重降低而不再被选中
- **THEN** 系统保留获准事实和反馈历史，不将低分等同于隐私删除或停用

#### Scenario: Private evidence is deleted beneath a shared derivative
- **WHEN** 私有来源收到获准删除请求，而某共享经验依赖该来源
- **THEN** 系统清除可还原私有来源的内容和引用；仅在归纳独立脱敏且仍具备明确保留授权时保留归纳，否则撤回并清除受影响派生内容

#### Scenario: A delayed event targets deleted experience
- **WHEN** 旧反馈、重试评估或陈旧检索结果再次引用已删除经验
- **THEN** 系统拒绝恢复或重新发布该内容，明确删除或不可获取状态，不重新增加证据或启用经验

#### Scenario: Some deletion targets remain unconfirmed
- **WHEN** 删除已接受但相关副本或投影尚未全部确认清除
- **THEN** 系统禁止继续使用目标内容并报告删除未完成，不把暂时不可检索或接受请求视为全部删除成功
