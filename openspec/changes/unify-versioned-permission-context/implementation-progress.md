# 基础批次交付与协议阻塞

## 状态

本批以基线dbc13db实施，新增权限MySQL真源、签名DTO/请求关联、角色管理、授予时间边界、本地不可变缓存和独立运行状态。新Auth RPC/管理服务在auth.authorization.enabled=false（缺省）时不装配；Redis writer未自动装配、publisher/consumer/版本刷新尚未接线。不是新授权链上线，不允许据已有DTO去掉旧消费端安全守卫。

## 已完成任务与证据

- 1.1/1.2：implementation-inventory.md含共享链、三样本和九个其余域逐节点actor/操作/资源/状态/来源/未知启用清单，已读相关LR/KI。
- 1.3：baseline.md采样点和原Memory Auth计数assert；本轮实际定向日志按模块摘要合计413项（common40、common-memory2、common-a2a23、Auth59、Agent111、Memory96、Interview82），失败/错误/跳过均0。Auth新RPC测试断言入口tokens.parse一次且snapshot一次；Memory仍保留原当前source/输出/Auth计数。没有宣称目标热路径新增Auth/Redis=0，后者是11.2未来验收。
- 2.1：真实隔离MySQL8.4.11五次fresh/upgrade/repeat及结构检查、20谓词通过；unknown用户/角色保留，repeat不复活撤权/permission/role/grant。manifest250/check及runner仅增加upgrade:bk_auth，fresh/upgrade checksum dry-run均0。
- 2.2/2.3：多角色并集、登记权限和管理员无角色、未知码拒绝；真实token/current权限的角色CRUD/用户授予/admin独立管理，写版本/outbox/审计同事务；初版基础相关35项H2/纯/HTTP测试通过。
- 4.1：30项common授权基础/本地缓存/session回归通过，canonical类型分域Ed25519、origin/receiver独立证明、严格父scope/deadline与DTO序列化；另2项AuthzException固定安全Throwable映射回归。

## 其他已写但未整项完成

- 2.4仅事务Repository原语、管理路径已接版本；旧账号/UserManagement全路径未接。
- 2.5授予开始/到期版本+outbox扫描已实现；logout epoch接线未完成。
- 3.x仅Redis writer/Lua静态基础及真实隔离ACL23/Lua20规则通过；签名writer运行、TLS/生产ACL、outbox dispatcher、authority更新、Pub/Sub消费者和对账未完成，全部不勾。
- 4.2仅两RPC初次认证和委托签发/provider测试5项；没有保持原run限期/范围的版本刷新协议，见下。
- 4.5/5.1仅common硬期限/session/cache基础未接Supervisor。
- 5.5/5.6/5.7已有chat/session/requestId/audit/governance边界补丁及63项先期回归；完整集又补真实request-scope四入口16次重投/冲突测试，整体部署与新版本许可consumer仍未完成，不勾。

## 失败与修正记录

- 管理Service初轮H2 generatedKey包含timestamp多列，改指定id；Controller PathVariable缺显式名已补，不放宽权限。
- 首批Supervisor测试audit可选参数未显式名，补生产name；H2审计fixture按原handler填时间。
- 集中回归第一轮新RPC子binding用fixture建立时刻，晚于入口响应跨秒时反而早于parent签发，正确被拒；改使用已签parent时间，不放宽期限。
- 集中回归第二轮授予开始Instant纳秒经SQL timestamp舍入，固定测试秒级时刻，生产边界条件不改；第三轮413全过。
- review发现新增前置幂等在principal缺省session每次随机UUID时误拒合法重投，已补原owner持久session恢复及真实HTTP反例。
- 原生MemoryArchiveProviderTest输出Dubbo injvm deep-copy默认fastjson2 JSONB诊断但测试通过，现用构造器序列化契约与资源验证不放宽，不称真实部署无日志故障。
- 隔离MySQL初次health因Windows只读bind的option文件被mysql视为world-writable而忽略，只修新容器复制后chmod600；最终成功，不改dev凭据。
- manifest首dry-run65因closed target不含upgrade:bk_auth，只加这一合法目标后fresh/upgrade为0，保留历史失败。

## 必须暂停的协议问题（KI-167）

现两个RPC仅resolveRequestAuthorization(token,binding,proof)与validateDelegation(parent,binding,originProof,receiverProof)。入口每次新建now+300 hard deadline；委托要求parent revision/epoch当前有效。权限变化后旧parent不能委托，重新resolve又增加原run截止/受限child范围，被AuthorizationSession.refresh正确拒绝。consumer不能裁剪Auth签名自救。

需要修订design D3/D7、共享RPC和4.2/4.5任务：建议新增明确refreshAuthorization(token,originalContext,binding,callerProof)，认证原actor并验证原context签名、policy/身份关联，在原hard deadline内签新revision及旧scope与当前权限交集；不能把refresh变成裸userId续租或延长期限。不在本批擅自新增协议；用户确认修订后才继续。

## 安全与运行边界

acceptance-results.json记录MySQL20、RedisACL23、生产Lua20及临时资源清理；并非新Auth/RPC/签名/广播全链实测。只使用新tmpfs、唯一internal网、无端口，两个临时容器/网均清理、删除卷数0。没有devDB迁移/生产部署/真实数据与外部业务副作用，没有读取.env/.secrets，不回显凭据。

原工件63项目前仅7项完成。LR-99/100与KI-167/168同步说明实际实现和未验边界；其余不提前关闭。待本批验证和中文提交完成后暂停，不归档变更。
