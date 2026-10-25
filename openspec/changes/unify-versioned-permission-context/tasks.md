## 1. 权限节点、现状与迁移清单

- [x] 1.1 复核LR-1/3/5/6/8/33/40/41/63/89/98及KI-1/2/55/59/79/80/128/151/157，形成操作许可、资源关系、状态、服务身份、来源撤回的逐节点清单；不把历史OPEN或fixture成功当当前部署证据。
- [x] 1.2 盘点全部真实权限调用与REST/RPC/A2A/MCP消费者，登记稳定操作码、资源真源、actor来源和能力是否真实落地；输出未知关系和应停用操作。
- [x] 1.3 固定82项访谈及Supervisor/Memory相关回归基线，记录实际Auth/Redis/Memory调用计数采样点；不要求改造前延迟分布或性能对比。

## 2. 显式角色权限与MySQL真源

- [x] 2.1 增量创建权限目录、role_permission、user_role有效期和business_admin字段迁移；ADMIN/BROKER受控映射，fresh/upgrade/repeat不删除用户或未知角色数据。
- [x] 2.2 实现显式权限计算：多角色去重并集、有效期、停用/删除、管理员所有登记有效权限、未知码拒绝；增加任意前后缀及自提权反例。
- [x] 2.3 实现受鉴权的角色组合/用户授予/管理员维护与审计API，普通用户拒绝管理写；真实操作目录只通过受管登记维护。
- [x] 2.4 为用户加入permission_revision/credential_epoch与授权outbox；账号停用、角色关联/权限/管理员/目录变化同事务递增版本并写待发布事件。
- [x] 2.5 实现授予到期扫描与版本发布；logout按现有token撤销并产生用户credential_epoch失效事件，不宣称精准设备会话撤销。

## 3. Redis快照、发布与通知

- [x] 3.1 实现Auth唯一writer的用户级不可变snapshot与head结构，签名hash、有效期、同用户hash-slot及Lua单调CAS；消费者不得按run复制集合或从旧内存回写。
- [x] 3.2 实现outbox有界领取epoch、事务外最新版本装配、snapshot先写/head后切/通知最后发的顺序和失败恢复；乱序、重复、缺snapshot、迟到事件均不能回退版本。
- [ ] 3.3 实现Redis权限namespace与channel ACL及受保护连接：Auth可写并发布指定channel，消费者只读精确key并订阅指定channel；写管理员/权限/TTL、PUBLISH/SPUBLISH及跨channel均拒绝，覆盖订阅成功和非法发布反例，缺配置不得启用。
- [x] 3.4 实现Auth每5秒MySQL/outbox核验及独立签名FreshnessAttestation，绑定user/revision/epoch/status/hash/真实checkedAt/notAfter；发布前重核MySQL和head CAS，迟到核验/更新竞争不能以完成时刻续窗，缓存GET不能续期。
- [ ] 3.5 接入权限版本Pub/Sub通知和消费者去重失效，消息不含token/正文/权限全集；单飞刷新和版本比较拒绝旧通知覆盖新状态。
- [ ] 3.6 实现活跃用户10秒批量对账、30秒权威新鲜度上限和硬到期；验证通知丢失、Redis失联、Auth发布阻塞时旧权限不会无限延长。

## 4. 共享可信授权协议与入口

- [x] 4.1 在common定义框架无关snapshot/context/delegation/status/error DTO、规范hash和协议域Ed25519签名；上下文包含actor/version/credential epoch/受众/请求政策与期限，序列化无框架凭据泄漏。
- [x] 4.2 新增原生Dubbo resolveRequestAuthorization、validateDelegation和独立refreshAuthorization接口/实现，注册各自caller-operation ACL；刷新携真实access和原context，保持actor/run/session/audience/policy/原scope及hard期限、当前credentialEpoch，覆盖跨操作proof、nonce/body/身份篡改/版本竞争及provider→本地refresh闭环，未知key/算法拒绝。
- [ ] 4.3 Gateway对实际转发原始body计算有界hash，绑定method/original-target path/query/audience/requestId与授权签名，下游解析前复算；覆盖路径rewrite/重复query/空body/MCP实际JSON篡改，拒超限或不支持流式body；清除伪造头，精确公开path+method及POST refresh回归。
- [ ] 4.4 Agent filter/principal消费有效入口证明，移除同请求四次resolve重复；直接业务HTTP无可信入口证明时独立认证，不信固定marker。
- [x] 4.5 分离稳定权限snapshot与新鲜证明/签发context，300秒runHardDeadline固定不被同版重建/刷新重置，子context≤父期限/run截止/授予期限、请求证明≤60秒；覆盖同版缓存回收、子委托和到期反例，重启原owner重认证。
- [x] 4.6 login/refresh/me与UserManagement迁移可信businessAdmin+多角色集合，旧roleCode只兼容展示；无角色管理员正常认证/管理，logout仅提交token吊销+全用户旧run重认证（非全设备退出），覆盖其他有效token重入和重复注销幂等。

## 5. Supervisor本地权限与资源守卫

- [ ] 5.1 实现按actor+revision的有界不可变本地cache与run引用/原policy；同用户多会话共享权限但不共享任务/消息，线程异步捕获清理不串actor。
- [ ] 5.2 业务Model重规划仅查head版本与authorityCheckedAt，同版不下载/计算权限；变更单飞刷新目录，首次Model复用入口结果，不为摘要模型重复获取。
- [ ] 5.3 替换目录的首domain/generic fallback/统一chat本地工具权限为精确映射；保留AUTO新增能力、技能版本/schema/政策交集，注册未知动作不能放行管理员。
- [ ] 5.4 Graph节点、worker、tool callback、runner和输出使用本地许可/期限/dirty检查，移除重复Auth用户RPC而保留审批hash、资源、Memory来源、run lease/cancel与预算。
- [ ] 5.5 统一chat/sync/async/续接/审批/query/cancel/SSE的持久session/run owner校验；修复同名session事件和跨会话requestId不同输入冲突，错误前无副作用。
- [ ] 5.6 审计普通用户owner范围与管理员业务诊断接口分开；治理限流/灰度读写要求独立管理权限并记录actor/version，拒绝普通登录全局修改。
- [ ] 5.7 验证原审批动作恢复、已完成结果复用、未知副作用对账、权限变化不扩原政策或重置预算；新增transition同步KI-2调用点回归。

## 6. A2A、MCP与Memory接入

- [ ] 6.1 真实A2A transport/guard接版本化context与委托：新send/stream/control边界一次Auth验证，仍在官方Store/Queue前核actor/caller/context/body/nonce，内部工具/流回调本地验证。
- [ ] 6.2 任务身份持久关联与本地执行上下文分离，get/cancel/resubscribe重认证原owner，跨实例/重启不凭taskId生成身份；保留原在途预留/条件释放。
- [ ] 6.3 Memory新边界验证一次域context，内部请求本地验证签名与nonce；移除hasPermission/resolve重复并保留owner/domain/agent、来源/grant、writer fence、permit和最终结果检查。
- [ ] 6.4 子Agent模型/工具/输出边界复核当前Memory来源，来源撤回不等待Auth通知；基础Memory预检失败仍零模型/零业务工具，SERVICE只精确补交付。
- [ ] 6.5 MCP补正式工具provider注册和服务端受限委托入口，客户端用模型不可见专用传输；不支持用户委托服务只准入无私有数据能力，MCP admin连接和资源权限分开。
- [ ] 6.6 RAG principal/scope与真实消费者同步版本迁移businessAdmin、多角色有效操作和显式businessWide/activeOnly，保留独立HMAC；覆盖无角色管理员管理/普通用户ON_SALE/个人Memory拒绝，未知范围/旧弱fallback拒绝。
- [ ] 6.7 核验本次新增服务端URL请求只允许http/https、请求前精确host检查且拒localhost/环回/私有/保留地址，阻止redirect/DNS换址；权限RPC不得用新增任意URL回调替代。

## 7. 访谈主体关系与状态动作

- [ ] 7.1 增量建立created_by_user_id和CREATE/RESPOND场次关系、平台用户选择/受访确认流程；工号仅metadata，旧工号未知映射隔离供受权治理，不猜姓名、不删记录。
- [ ] 7.2 实现发起interview.initiate及普通用户创建/受访范围查询，受访无发起许可仍可本人读取/答题；管理员业务全读与Memory self-only反例同时通过。
- [ ] 7.3 对REST/A2A题单创建/确认先验操作/关系/允许状态，非法ID原子拒绝不先更新；运行后题单不可修改，保留原状态机。
- [ ] 7.4 分离start、mode变更和重签，transition幂等也先核actor合法；协助指令仅创建关系/管理权限且IN_PROGRESS，保存并消费actor/版本，不新增导演Auth角色。
- [ ] 7.5 主动结束走原运行受权指令而非治理工具冒用RUNTIME，受访道别/收尾锁/原固定决策保持；为真实转换调用新增权限表单测。

## 8. 访谈凭据与前端交付

- [ ] 8.1 新建绑定user/session/purpose/hash/expiry/revoked/version的短期参与票；默认15分钟且不代替登录，独立重签/撤销不作废其他用户资格。
- [ ] 8.2 A2A/REST开台和启动仅返回安全导航/领取引用，新增认证POST credentials领取及no-store响应；票只专用请求头，不经模型转述。
- [ ] 8.3 monitor/turns/replies/协助与stream核可信actor+purpose，删除query-ticket入口并公告迁移；浏览器fetch流携headers，票到期/撤销后拒绝后续访问。
- [ ] 8.4 在工具入模和归档入口彻底排除ticket/token/授权证明，保留共享sanitizer不放行票；验证真实ReactAgent工具后prompt、Memory、progress、终态artifact和前端领取链。
- [ ] 8.5 扫描存量消息/摘要/副本的凭据形状只输出计数，受权撤销并清理可恢复票副本，处理未完成状态不宣称全删；不日志回显秘密。

## 9. 访谈私有资料、公开投影与后台任务

- [ ] 9.1 建立PRIVATE/PUBLISHED、发布revision/审核与脱敏投影，默认私有；L2/L3仅质量条件，明确发布后才在公共MCP显示，上传只新增且核case关联。
- [ ] 9.2 区分公共投影的PUBLISHED/L2/L3源许可与本人/管理员私有读取及获准私有报告生成；均补case/asset/session关联、源hash/revision和模板版本幂等，测试私有结果可读但不公开，未知ID不建任务。
- [ ] 9.3 上传真正Sanitizer脱敏，冷历史/归集/报告缺脱敏字段不回退raw，原文仅域内受权保存；重评级核真实metadata/证据，不固定true提升公开资格。
- [ ] 9.4 报告worker获取lease epoch后事务外模型执行，提交/失败用epoch+source版本围栏和唯一报告键；源撤回/降级/删除阻止公开派生结果，用户离线不取消精确已接受任务。
- [ ] 9.5 归集可恢复状态和完整话轮保留，防COLLECT_PENDING失败后丢恢复及并发重复asset；不为补记忆/报告重执已完成业务动作。
- [ ] 9.6 对齐三技能允许工具与真实候选创建/确认/任务查询路径，MCP六工具注册/写动作权限准确，LR-8公开语义不变成私有数据通行证。

## 10. 其余域逐操作准入

- [ ] 10.1 Listing与Customer登记读写操作和可信broker/customer关系；保留房源角色状态范围，写权限不能只用reader，跨协议反例通过后启用。
- [ ] 10.2 Business与Settlement核员工/门店/合同/收款主体映射，报表/计算/确认/分账独立操作；未定义关系或真实支付保持拒绝，SERVICE不继承用户无限读取。
- [ ] 10.3 Contract与Marketing核合同/附件/内容owner及办理/编辑/审批动作；签章与真实发布未接通不授权mock成功，原业务状态不由权限替代。
- [ ] 10.4 Compare迁移已退役无凭据Listing读取，分享/PDF绑定owner/公开范围/源版本；Media绑定task/material owner及精确后台许可，内存ID不当身份。
- [ ] 10.5 Notification本人消息读取由actor注入、发送recipient关系独立授权，修正KI-151及caller已登记的错误兜底说明；角色许可/Memory接线不能放行他人消息。
- [ ] 10.6 每域记录实际通过的入口/资源/恢复矩阵；任何未完成节点保持关闭并列出原因，不把统一DTO或A2A wiring当全域业务验收。

## 11. 故障、兼容与交付验收

- [ ] 11.1 使用真实MySQL/Redis测试原子发布、重建、版本并发、角色批变更、授予到期、重复/乱序/丢通知、dispatcher延迟和权限Redis非法写；超过30秒权威新鲜度必须停新动作。
- [ ] 11.2 使用Graph/正式ReactAgent/MCP fixture统计：Gateway->Supervisor入口Auth一次；同版Model返回只核版本；新域入口一次；N工具/M流项新增逐节点Auth/Redis为零，Memory资源检查独立计数。
- [ ] 11.3 覆盖伪造actor/admin/metadata、错误audience/body/task/nonce、跨用户与多会话、管理员业务全读/个人Memory拒绝、权限新增不越原审批和请求政策。
- [ ] 11.4 在开发栈做真实send/stream/get/cancel/resubscribe、访谈开台领取答题回执/报告及撤权故障验收；未知/未运行业务域和Studio未就绪浏览器如实标未验，不启动无关真实外部副作用。
- [ ] 11.5 使用仓库settings运行相关模块及全量compile/test；失败与跳过有具体证据，新增transition均有防回归；发布配置只用受管服务身份，不回显秘密。
- [ ] 11.6 验证fresh/upgrade/repeat、权限格式及票交付兼容、同等安全制品成组回滚、outbox/permit/原任务保留；不能回滚到审计旁路、匿名或票入模策略。
- [ ] 11.7 同实现提交更新LR/KI及协议/部署/操作清单，严格OpenSpec验证，中文commit并push当前对应远程分支；仅真实验证后勾任务，实施完成后另行归档。
