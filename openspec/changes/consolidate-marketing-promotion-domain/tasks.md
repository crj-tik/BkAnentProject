# Tasks

## 0. 独立会话与契约基线

- [x] 0.1 按并行总览记录独立worktree/分支、共同base SHA、自身.m2repo和测试资源，验证未依赖其它三个实现提交或共享运行栈。
- [x] 0.2 固定marketing/promotion和common/marketing/campaign修改范围及编号段，用差异清单验证无Auth实现、其它领域任务或公共框架改造。
- [x] 0.3 为所需房源、平台、媒体/通知及身份依赖固定本地契约fixture，按domain-parallel-v1验证房源消费字段及受管模式，保证独立验收无需其它会话提供新制品。营销当前不直接调用媒体或通知服务，继续消费已保存的媒体 URL。

## 1. 数据与内容修订

- [x] 1.1 固化营销/推广调用方、数据表、ID 冲突和在途发布清单，交付可复核的映射/备份清单而非直接删除原库。
- [x] 1.2 将品牌素材、发布及效果业务迁入营销 campaign 包，编译模块并用服务测试验证基础读取与既有媒体 Worker bridge wiring。
- [x] 1.3 增加内容修订/计划/尝试/效果/回执SQL与check，在自身隔离库验证重复升级和旧ID冲突，交付唯一版本与bk_marketing的manifest/runner allowlist差异，不执行共享迁移。

## 2. 受控发布与核对

- [x] 2.1 实现内容/素材/受权账号/规则版本绑定的 prepare/read/confirm 契约，用正文或账号变化、范围拒绝和过期确认测试验证不可沿用旧批准。
- [x] 2.2 实现发布请求与每目标 attempt 的幂等回执，用丢响应、同号异内容和部分成功测试验证不重复建目标尝试。
- [x] 2.3 将外部调用移出本地事务并增加可靠派发/条件回写，用受理后进程中断、迟到回执和重复消费测试验证原 attempt 可恢复。
- [x] 2.4 实现真实 provider 缺失的前置失败与显式 fixture 模式，用反例验证真实模式零模拟回退、回执/事件保留 SIMULATED。
- [x] 2.5 增加 UNKNOWN 原尝试核对和原回执查询，用平台接受后超时测试验证不换 ID 重发且不会影响已成功目标。

## 3. 效果与真实收益口径

- [x] 3.1 将现有按记录 ID 生成统计的逻辑移入 fixture provider，用显式模式测试验证真实效果读取不会执行样例算法。
- [x] 3.2 实现渠道观察身份/版本/窗口及数据模式记录，用重复同步、迟到修正和缺字段测试验证不重复累计及未知状态。
- [x] 3.3 定义真实 ROI 与线索成本等指标的证据要求，用仅线索和成本、无归属收入及混合来源反例验证不估造收入或真实 ROI。

## 4. 合并调用与旧部署退役

- [x] 4.1 更新 marketing-agent 技能、工具及旧 /promotions 受权适配，用调用契约测试验证同域状态更新和必需 Memory 语义。
- [x] 4.2 提供 Studio 可消费的领域发布快照及回执契约，用边界测试验证不复制 Studio work/command/审批存储，也不勾选其 task 4.1/4.2/4.4。
- [x] 4.3 在本分支实现旧新接收围栏、调用方切换及本域reactor/配置最小差异，用隔离在途/迁移测试验证单writer和历史保留；共享环境停旧与登记由协调执行，不等待其它change。

## 5. 验收与交付

- [x] 5.1 在共同基线及本change独立运行营销/推广和必要调用方测试/编译，使用自身Maven仓库并验证无其它change的Java类型/制品依赖，覆盖授权/幂等/未知/模式/历史完整路径。
- [x] 5.2 用 fixture 平台执行准备、确认、部分成功、UNKNOWN 核对和效果读取闭环，验收记录明确模拟边界及真实渠道尚未接入状态。
- [x] 5.3 交付部署回退/效果口径/本域LR与KI-167证据，严格校验和diff检查后中文commit/push本会话分支，返回head SHA和共享差异；真实平台/登记联机单列，不能阻塞已约定的本域fixture验收或冒充真实ROI。

## 6. Supervisor、A2A、ReAct 与 MCP 发布闭环

- [x] 6.1 收紧营销服务内 ReAct copy-generation 技能：允许搜索、创建 DRAFT 和准备计划，不暴露确认发布/通知动作；用工具授权及伪造 PUBLISHED 输出反例验证只有真实 provider receipt 能改变发布状态。
- [x] 6.2 新增营销专属 Supervisor 发布技能并接入动态目录，保留 A2A marketing-draft 只读草稿边界；用活动技能快照验证发布流程不会改写房源 change 所有的找房/对比技能文件。
- [x] 6.3 将 MCP 草稿返回改为包含 contentId、platform、status 的结构化结果，并补齐 receipt 查询与原 attempt reconcile 工具；用 MCP 契约测试验证 ID 可传给 prepare、未知结果沿原 attempt 查询。
- [x] 6.4 在隔离 Supervisor profile 配置实际目录返回的 confirmMarketingCampaign capabilityId 到 agent.supervisor.orchestration.approval-capabilities，交付独立配置差异并标明合并时取并集；用 Supervisor 审批测试验证 prepare 不触发外部副作用、confirm 等待批准且计划参数变更后旧批准失效。
- [x] 6.5 通过实际 Supervisor 目录、A2A/ReAct 和 MCP fixture 完成草稿、内容审核、计划预览、确认、receipt 查询及 UNKNOWN 核对闭环；覆盖拒绝/待批准不发布、confirm 后不重发、通知与发布状态必须有真实回执。
- [x] 6.6 盘点旧 /promotions/** 调用方并迁移已知客户端到当前 DTO；为旧请求体增加明确的契约不支持响应及契约测试，不将路径级过渡映射描述为旧 DTO 兼容。

## 7. 原营销功能完整性与退役门槛

- [x] 7.1 建立内容及推广源功能矩阵，覆盖内容/渠道版本/审核状态/列表检索、发布、效果同步/查询、ROI、品牌素材、各模拟渠道以及 REST/Dubbo/A2A/ReAct/MCP/Supervisor 入口，逐项登记统一域目标能力、调用方与验收证据。
- [x] 7.2 盘点并迁移旧 `/promotions/publish`、`PromotionRpcService.publish` 和全部已知 REST/RPC/Agent/客户端调用方到内容审核、计划预览、批准确认和回执/核对闭环；验证 prepare 不冒充发布成功。
- [x] 7.3 端到端验证内容创建到渠道发布回执、UNKNOWN 核对、效果同步/读取、ROI 查询及品牌素材创建/列表；证明旧业务结果在合并后均有目标入口。
- [x] 7.4 对 BEIKE、DOUYIN、XIAOHONGSHU fixture 分别验证发布和结果查询的 SIMULATED 传递，并验证无真实 provider 时生产模式失败关闭、不回退模拟成功。
- [x] 7.5 只有功能矩阵零未映射项、已知调用方均有迁移记录且端到端证据通过后，才允许退役旧服务接收或删除旧部署配置；真实平台联机另列，不以模拟通过标记为已接通。
- [ ] 7.6 完成 Marketing 对 Auth v2 的逐入口准入：登记内容/campaign/channel 精确权限码，确定新旧内容 owner 与经纪人/业务资源关系，并为 REST、Dubbo、Agent、A2A、MCP、Supervisor 及回放/后台恢复接入可信版本化身份/受限委托；验证伪造 actor、无委托服务身份、owner 错配、未知权限、撤权和审批门分离。与 `unify-versioned-permission-context` task 10.3 协同；未完成前 campaign/content 真实用户操作不得标记为 Auth v2 已接通。
- [x] 7.7 核验 Memory v2 的营销边界：Marketing A2A 注入共享必需 hook，按认证 owner/domain/agent/context 隔离，Memory/授权失败在模型前拒绝，归档失败不报成功；确认无会话 REST/MCP 草稿接口是无记忆单次命令，不用 Memory 推断权限或共享个人记忆。证据：营销 `ExplicitSkillAgentWiringTest` 与 common-a2a `A2aMemoryAgentHookTest`。
