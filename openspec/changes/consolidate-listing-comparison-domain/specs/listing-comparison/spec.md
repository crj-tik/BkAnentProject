# 房源域对比与报告契约

## Purpose

将客观房源对比和报告纳入同一房源经营能力，统一来源授权、量纲与版本校验，并约束缓存、分享和文件导出的使用范围。合并后保留旧结果和执行身份的可追踪性，使服务退役不会放宽权限或重复执行已发生的调用。

## ADDED Requirements

### Requirement: Supervisor 通过房源域执行对比

Supervisor 的显式找房对比技能 SHALL 通过 `listing-agent` 当前发布的比较技能执行，并通过活动能力目录验证实际调用目标。新调用 MUST NOT 使用旧 compare MCP 能力或无凭据 RPC；能力缺失时明确停止，不得回退。对比是只读能力，不加入写操作审批清单。

#### Scenario: 显式技能通过房源 Agent 比较
- **WHEN** 用户选择找房对比技能且已取得获准房源
- **THEN** Supervisor 通过 `listing-agent` 已发布的比较技能完成调用，记录实际能力 ID 和返回的来源版本，不调用旧 compare MCP/RPC

#### Scenario: 房源比较技能暂不可用
- **WHEN** 动态目录中没有可用的 `listing-agent` 比较能力
- **THEN** Supervisor 明确报告比较能力不可用并停止，不回退到旧 compare MCP、旧 RPC 或无凭据路径

### Requirement: 对比属于房源域内的获准能力

系统 SHALL 通过 `listing-agent` 的 `listing-comparison` 技能提供对比能力，接受 2 至 6 套不同的稳定 `listingRef={sourceSystem,sourceId}` 及预期来源版本，使用同次获准来源快照计算事实指标。创建对比 MUST 要求当前 Auth 签名上下文包含 `compare.report.create` 操作，并对每套来源分别满足适用的 `listing.read` 或 `listing.read.all` 操作与资源边界。结果 MUST 绑定每套房源的来源引用、revision/contentHash、数据模式和计算规则版本。新请求 MUST 不依赖独立 compare Agent 或 Long ID-only 比较契约；迁移期旧 Long ID 入口只可解析为 `legacy-local`。

#### Scenario: 通过房源能力发起比较
- **WHEN** 当前用户通过 `listing-comparison` 提交 3 套获准房源引用及期望来源版本
- **THEN** 系统在房源域生成比较结果，并记录实际房源引用、版本、数据模式和规则版本，不再次委托旧 compare Agent

#### Scenario: 旧 Long ID 兼容入口收到外部来源房源
- **WHEN** 迁移期旧 `/compare/**` 入口收到不能唯一映射到 `legacy-local` 的 Long ID
- **THEN** 系统拒绝该请求并要求调用方迁移到稳定来源引用，不从其他来源猜测或匹配房源

#### Scenario: 比较创建操作未包含在签名上下文
- **WHEN** 当前用户能够读取房源，但已验签 Auth 上下文不含 `compare.report.create` 操作
- **THEN** 系统拒绝创建报告，不把 `listing.read`、Agent 可调用资格或管理员展示字段当作比较创建操作

### Requirement: 比较操作目录归属随业务域合并

Auth 目录 SHALL 将比较创建和本人报告读取动作归入 `listing` domain，并分别登记为 `comparison.report.create` 与 `comparison.report.read.own`。稳定操作标识 `compare.report.create` 与 `compare.report.read.own` MUST 保持不变并继续出现在已验签上下文中；它们属于基线动作而非角色权限。与基线权限退役保持一致，旧目录行 MUST 保持停用且角色关联 MUST 清空。Listing 目录迁移只重分类目录元数据，不得重新激活或新增授权；元数据变化时 MUST 递增受影响的授权修订号并写入 Auth outbox。

#### Scenario: 基线退役已完成后应用 Listing 目录迁移
- **WHEN** Auth 数据库已应用基线权限退役，再应用 Listing 比较目录迁移
- **THEN** 两个稳定操作码的目录 domain/operation 更新为 `listing` / `comparison.report.*`，目录行保持停用且无角色关联，元数据变化通过修订号和 outbox 通知消费者

#### Scenario: 基线动作不依赖角色授予
- **WHEN** 已认证用户没有比较报告角色授予，但 Auth 为其签发包含基线操作的签名上下文
- **THEN** 操作标识可用于创建请求；系统仍须核验每套来源读取权、资源状态及报告 owner，不把 Agent 调用资格当作来源授权

### Requirement: 结果明确事实与生成解释


比较 SHALL 区分确定性指标、未知字段和模型解释，并将结果绑定到当前获准的来源引用、来源版本、数据模式与规则版本。模型解释 MUST 只依据当前获准事实，不得将最低单价自动解释为对所有客户最优，也不得创造外部事实。

#### Scenario: 其中一套房源缺少面积
- **WHEN** 对比房源缺少有效面积
- **THEN** 该房源单价显示未知，结论说明资料缺口，不根据其他房源或模型估算填入数值


### Requirement: 报告缓存和历史查看复核当前来源

缓存命中和历史详情 SHALL 在返回报告正文前核验当前身份、签名上下文中的 `compare.report.read.own` 操作、报告 owner、逐套来源读取权与来源版本。缓存中保存的来源指纹只用于识别报告绑定的快照，不代替对当前来源状态的核验。期望版本改变 MUST 返回明确冲突或将旧报告标为 STALE；来源不可核验时不得把旧报告作为当前事实返回。

#### Scenario: 价格改变后查看旧报告
- **WHEN** 某套来源房源价格版本与历史报告绑定版本不同
- **THEN** 系统明确报告已过时，并允许受权用户另建新版本，不静默改写原历史内容

#### Scenario: 缓存命中时来源读取权已撤回
- **WHEN** 当前用户请求一份缓存报告，但至少一套房源的当前读取权已撤回或来源状态无法核验
- **THEN** 系统不返回缓存的报告正文、指标或 PDF，并返回可辨识的拒绝或不可用结果

#### Scenario: 报告读取操作或 owner 校验失败
- **WHEN** 请求者不是报告 owner，或已验签 Auth 上下文不含 `compare.report.read.own` 操作
- **THEN** 系统拒绝返回报告正文、指标和 PDF，不因报告 ID、分享码或旧缓存命中而放行

### Requirement: 报告分享及 PDF 使用同等授权


报告详情、分享和 PDF 导出 SHALL 执行相同当前用户与来源访问检查。报告 ID 或分享码只可定位报告，MUST NOT 单独授予访问权；系统 MUST NOT 创建或继续接受匿名公开分享。旧文件缺少可核验归属、或带有旧公开分享标记时，未经当前授权 SHALL 明确拒绝或返回退役语义，并保留原存储供受控迁移。

#### Scenario: 仅持有旧分享码
- **WHEN** 请求者持有旧报告分享码但没有当前可验证的读取权
- **THEN** 系统不返回报告正文、下载地址或 PDF 字节

#### Scenario: 旧报告曾被显式发布为公开分享
- **WHEN** 请求者仅持有一份 `publicShare=true` 的旧报告分享码，且没有当前可验证的读取权
- **THEN** 系统按退役或私有报告处理，不因旧公开标记返回报告正文、下载地址或 PDF 字节；原文件只保留给受控迁移核对

#### Scenario: 合并后保留获准接收人的分享和 PDF 结果
- **WHEN** 报告所有者通过有效分享流程将报告交给当前仍获准读取全部来源房源的接收人
- **THEN** 接收人仍可读取相应报告并导出 PDF，系统记录分享范围和报告来源版本

#### Scenario: 分享后接收人失去来源读取权
- **WHEN** 接收人持有分享链接但已失去报告所涉房源的当前读取权
- **THEN** 系统拒绝报告正文和 PDF 字节，分享链接本身不得绕过撤权

### Requirement: 合并保留原执行和私有记忆身份

退役独立比较能力 SHALL 停止接收新委托，保留已接受调用的原任务关联用于对账/排空及历史查询。系统 MUST NOT 将 compare 未决调用换成新的 listing 调用，也不得重写旧 Memory owner/domain/agent 来获得读取权。

#### Scenario: 停服前有结果未知的比较任务
- **WHEN** 某旧 compare 任务已经被接受但结果尚未确认
- **THEN** 系统沿原任务查询或登记未知事实，不在 listing Agent 下创建替代任务，历史私有内容仍按原授权读取

### Requirement: 新比较回合使用 Listing Agent 的必需 Memory 生命周期

经 `listing-agent` 执行的比较 SHALL 使用该 Agent 当前已登记的私有 Memory 身份完成回合前读取、来源复核及成功后的归档。Memory 查询成功但无历史内容是合法空结果；Memory 缺失、授权失败或不可用 MUST 在模型和业务工具执行前阻断本轮。新比较不得读取或迁移旧 `compare/compare-agent` 私有记忆。

#### Scenario: Listing Memory 不可用
- **WHEN** 比较请求开始时必需的 Listing Memory 读取、授权或来源复核失败
- **THEN** 系统在调用模型、房源来源或报告写入工具前拒绝本轮，不绕过 Memory 重试另一条比较路径

#### Scenario: 旧 Compare 记忆不属于新 Listing 身份
- **WHEN** 新 Listing 比较请求需要历史上下文
- **THEN** 系统只读取当前用户获准的 Listing Agent Memory，不因服务合并读取旧 `compare/compare-agent` 空间
