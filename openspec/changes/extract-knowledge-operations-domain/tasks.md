# Tasks

## 0. 独立会话与契约基线

- [ ] 0.1 按并行总览记录独立worktree/分支、共同base SHA、.m2repo及测试资源，验证没有引入房源、经营、营销实现提交。
- [ ] 0.2 固定knowledge服务、Interview来源/授权适配、两个专属common wire命名空间及agent-service最小audience/capability/config改动；通过差异清单确认不改Auth、Memory和其他业务实现，并按知识保留LR/KI编号维护记录。
- [ ] 0.3 固化domain-parallel-v1三条POST契约、Auth精确操作登记交接（knowledge lifecycle/action matrix见design，另含`agent.workflow.invoke.knowledge`及三项Interview用途授权操作）、独立Knowledge服务身份以及来源/Auth/Memory测试登记；验证缺少真实登记时失败关闭。

## 1. 逐字稿来源边界

- [ ] 1.1 定义包含assetId、snapshot/content hash、content/sanitization revision、grantRef、purpose、consumer、目标知识scope、Auth context/freshness、服务proof和dataMode的Interview wire契约；验证DTO序列化及完整请求绑定。
- [ ] 1.2 在interview-service增加独立来源授权表、签发/撤回及只读来源provider；签发和撤回分别核验精确Auth操作、当前签名context/freshness及现有case关系，读取必须先验knowledge-service proof与匹配grant，再读脱敏content并复核快照；验证grant缺失/撤回/范围不符均在读正文前拒绝，不改访谈状态机。
- [ ] 1.3 实现知识域来源适配与来源引用核验，存储grantId、精确快照和可复核quote hash/定位符；用快照冲突、撤回、失联、脱敏引用和未知/模拟模式测试验证失效行为。AUTO/UPLOAD或L0-L3不得推断dataMode；不得修改访谈运行面transition。

## 2. 独立知识模块与可靠业务记录

- [ ] 2.1 新增 knowledge-service、最小受管配置和构建/部署入口，编译模块并验证依赖不反向引用 interview 实现包。
- [ ] 2.2 增加知识对象/修订/依赖/审核/回执SQL与check，并独立增加Interview来源授权表migration/check；分别在隔离`bk_knowledge`/`bk_interview`验证新建及重复升级，交付唯一版本和两库manifest/runner allowlist差异；不改已应用hash或运行共享迁移。
- [ ] 2.3 实现knowledge-service自有持久生成任务和输入/规则版本身份，不复用Interview report task表；用重启、丢回执、同号异内容和已完成结果复用验证不重复调用模型。
- [ ] 2.4 实现单案例和打法候选生成，保留适用条件、未核实项、结果证据级别、grant/source依赖及稳定引用定位符；用精确原声/错引/无依据有效性断言验证输出规则。
- [ ] 2.5 盘点访谈域旧playbook_card/commonality_report和原报告，交付旧来源到新知识身份的映射方案；验证旧表、ID、置信度及报告任务留存，不读取报告正文、不假设空表或重放生成。

## 3. 运营审核发布与公开读取

- [ ] 3.1 实现草稿编辑、提交、审核、发布和撤回的版本流转，逐动作校验design矩阵中的精确Auth操作与own/store资源范围；用单权限缺失、并发expectedVersion、审核策略和回执重试验证越权动作不生效且决定唯一。
- [ ] 3.2 实现知识发布范围与公开读取适配，范围取Auth可信身份/操作与全部有效来源grant目标scope交集；用跨用户/门店、伪造scope、权限撤回及缺失STORE映射验证拒绝且不读取正文，不以测试identity代替生产范围。
- [ ] 3.3 实现来源依赖复核及发布后失效处理，每次search/detail/export/Agent输出和check前复核grant与当前来源；用授权撤回、源hash冲突和缓存/导出反例验证旧正文不能继续入模或输出。
- [ ] 3.4 提供场景/标签/关键词检索及精确 revision 读取契约，用无匹配、依赖故障和撤回版本测试验证区分；首期不以新向量实现替代 RAG。

## 4. Agent 与消费契约

- [ ] 4.1 实现knowledge-service独立身份接线，交付精确caller/domain/agent配置；在`OfficialA2aAgentClient.audienceFor`加入`knowledge-agent -> knowledge-service`，在`AgentAuthorizationProperties.capabilityPermissions`/受管配置映射`a2a:knowledge-agent -> agent.workflow.invoke.knowledge`，并在`agent.distributed.agents`提供最小knowledge-agent注册块。通过测试registry验证装配/签名/ACL、未登记拒绝、无身份复用；Auth操作/密钥和Memory caller全局登记由协调合并。
- [ ] 4.2 发布案例提炼、打法运营和知识检索技能，接入现有common-a2a/common-skill和动态发现；确认知识Agent复用必需Memory生命周期、没有Supervisor Graph专属分支，并验证版本、范围和真实调用审批。
- [ ] 4.3 实现冻结v1的published/search、batch-read和check HTTP处理器，用真实本域处理器的契约测试验证来源链/revision/可见性及不自动生成Memory grant，无需运行门店消费者。

## 5. 验收与交付

- [ ] 5.1 在共同基线及本change的worktree独立运行知识、访谈窄适配的聚焦测试和编译，使用自身Maven仓库，验证原运行面契约且无其它change制品依赖。
- [ ] 5.2 在显式SIMULATED访谈夹具上执行授权签发→来源读取→提炼→人工审核→发布→授权使用→来源授权撤回闭环；验证撤回后最终输出不含派生正文、无映射秘密、引用绑定快照且持续保留模拟标记。
- [ ] 5.3 交付新服务/双schema/独立身份及Auth操作交接片段的部署回退手册、本域LR/KI-15引用及独立验收证据，严格校验和diff检查后中文commit/push本会话分支，返回head SHA/共享差异；真实Auth/Memory登记、STORE映射、下游互通和索引联机另列未执行项，不阻塞本期独立完成。
