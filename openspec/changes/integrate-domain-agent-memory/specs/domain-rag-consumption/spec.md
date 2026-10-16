## ADDED Requirements

### Requirement: 新记忆检索与详情必须经过记忆域授权

Agent 消费会话档案、摘要、偏好历程、经验或协作导出 SHALL 经过 memory 域服务，语义召回 SHALL 只由 RAG 执行；按稳定 ID 读取 SHALL 由 memory 真源授权，不要求索引先就绪。Agent MUST NOT 直接向 RAG 搜索私有记忆以绕过 memory 最终检查，也 MUST NOT 在 RAG 故障时自行恢复旧关键词/向量执行链。

#### Scenario: 新偏好逻辑块语义命中
- **WHEN** 一个 Agent 以当前获准事项搜索相关偏好
- **THEN** memory 代理 RAG 取得候选并最终核验真源，返回单个逻辑块的当前状态、版本和历程读取信息，而不是让 Agent 直接消费未核验分片

#### Scenario: 新共享记录尚未索引
- **WHEN** 共享导出已持久化且授权调用者按稳定 ID 请求详情，但索引仍待应用
- **THEN** memory 在当前权限与真源版本核验后返回详情，不把索引未就绪等同于正文不存在

### Requirement: 私有记忆具备可信域与参与者范围

新记忆读取 SHALL 在 owner-only 基础上施加可信调用服务、domain/agent、conversation/matter 和显式共享 grant 的授权交集；个人偏好包括 ADMIN 仍 self-only。范围 SHALL 来自可验证服务身份和授权关联，MUST NOT 依赖模型填写的 userId/domain、namespace、category、普通 metadata 或引用取得读取权限。授权过滤 SHALL 在双路召回与重排前生效；候选正文实际送入重排前 SHALL 依据记忆域当前权威状态核验其记录版本、删除、grant和派生来源失效状态，无法核验 MUST 失败关闭，并在最终输出前再次核验当前身份与范围。

#### Scenario: 同用户不同私有域
- **WHEN** 营销 Agent 与访谈 Agent 使用同一用户身份，而营销 Agent 未取得访谈私有记录共享授权
- **THEN** 营销召回、重排、详情及诊断均不接触访谈私有正文，不因 owner 相同自动共享

#### Scenario: 伪造共享 grant 或后台身份搜索
- **WHEN** 模型或后台 worker 提交未被可信服务授予的 domain/grant 或尝试通用用户记忆搜索
- **THEN** 操作在未授权正文读取前被拒绝，后台加工身份不能继承用户通用检索权

### Requirement: 真源核验与领域选择适用于新记忆类型

memory SHALL 在返回所有新记忆匹配、正文、证据和诊断前检查当前 owner、域授权、删除/撤回状态、逻辑版本和内容哈希。相关度、偏好置信度、当前活跃权重及时间衰减 SHALL 分开解释并由域执行最终选择，RAG MUST NOT 代替域应用记忆衰减。补取 SHALL 保持既有至多一次及累计预算限制，结果不足如实返回；依赖不可用 MUST NOT 伪装成无匹配。

#### Scenario: 旧历程分片或协作授权已失效
- **WHEN** RAG 返回旧偏好 revision 分片或已撤回的共享导出候选
- **THEN** memory 根据最新真源排除无效候选及关联证据，不能拼成跨版本逻辑块，也不能在诊断中泄漏被排除正文

#### Scenario: 高相关旧经验参与排序
- **WHEN** 获准经验候选的相关度较高但有效确认较旧
- **THEN** memory 依据版本化领域规则分别计算相关度与时间权重，读取不会成为新确认，RAG 原始分数不被误当领域最终分数

### Requirement: 记忆正文只作为有界数据使用

注入 Agent 的记忆 SHALL 携带来源和版本，保留相关事实、决定、否定或临时例外，并在明确 token 预算内装配。检索正文 MUST NOT 被解释为更高优先级政策、认证材料、工具批准或隐式业务计划；脱敏映射、凭据及未经许可的领域原文 MUST NOT 因可检索而进入模型或前端。

#### Scenario: 经验文本包含指令性内容
- **WHEN** 检索经验中包含“忽略权限”或要求自动调用其他 Agent 的文字
- **THEN** 该文本没有改变授权、审批或调用选择权的效力，运行仍遵循当前硬规则与模型工具治理

#### Scenario: 历史补忆返回有证据解释
- **WHEN** 压缩后缺失对象的授权检索命中当前有效证据
- **THEN** Agent 取得有界事实解释及来源引用，不能以语义相似为由编造历史或扩大私有会话范围
