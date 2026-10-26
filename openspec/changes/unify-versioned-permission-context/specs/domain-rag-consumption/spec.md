## MODIFIED Requirements

### Requirement: 领域最终检查和排序不可被通用重排替代

域 SHALL 取得有界足量候选及scoreKind/scoringVersion并按领域决定topK，不混用分数、硬过滤和软衰减。返回正文、匹配与诊断前 MUST 检查有效版本授权及当前资源状态、范围、投影和条件；账号/角色采用统一通知和最多30秒兼容窗口，硬到期或已知失效拒绝，不逐次解析原token。可信businessAdmin全读未删除房源含非ON_SALE，普通获准用户默认仅ON_SALE；旧ADMIN/BROKER仅受控迁移映射，不是新角色白名单。范围来自签名操作许可与业务scope，个人偏好包括业务管理员绝对self-only。业务资源删除、状态变化、偏好/来源/grant失效即时按领域真源排除，不受Auth通知窗放宽。补取至多一次且累计有界，不足如实返回。

#### Scenario: 真源变化或用户失效
- **WHEN** 返回前资源删除/状态变化、来源撤回，或本地已获知用户/权限失效、授权硬到期
- **THEN** 无效正文不进入任何字段；账号失效通知未达的兼容使用受30秒上限，不把资源撤回延迟到Auth通知

#### Scenario: 领域时间衰减
- **WHEN** 较旧记忆候选高度相关且软时间衰减启用
- **THEN** 时间只影响排序，不自动删除，明确时间硬过滤另算

### Requirement: A2A 认证使用专用可验证信标与真实传输

A2A SHALL 经真实受支持transport携带可验证受限授权，绑定服务、用户、请求正文、操作、audience、任务及期限。已绑定 Agent run 的 send/stream/get/cancel/resubscribe 使用 Auth 签名context及 Agent 每请求签名 proof，由接收端在 Store/Queue 前本地验证；不逐子 Agent/控制请求向 Auth 验证，权限变化仅在已绑定 Supervisor 规划刷新边界联系 Auth。内部节点、工具、SSE只本地核验许可/期限/已知失效与请求完整性，不逐事件重查Auth。控制请求仍核对原任务owner和调用服务。凭据不得进入模型metadata、参数、普通structuredContext、URL、日志或checkpoint；重启或硬到期需原owner重认证，不按userId换发。未迁移的 RAG/Memory 消费者继续使用各自当前受保护签名/资源检查，不因 A2A 本地验证而降低旧RAG授权；新协议保留算法域隔离，不因缺桥转匿名或旧弱策略。

#### Scenario: 真实房源Agent读取RAG
- **WHEN** 有效用户经Supervisor受限委托房源Agent
- **THEN** 新域入口验证可信身份和范围，内部读取复用上下文并做领域核验，模型和持久快照无凭据

#### Scenario: 伪造metadata或恢复旧凭据
- **WHEN** 模型伪造userId/auth metadata或重启只有旧标识
- **THEN** 不能建立授权，业务/RAG零执行并要求认证

### Requirement: 真实旧REST保持受鉴权纯代理兼容

六组旧GET搜索、POST集合初始化及房源index/query的路径、方法和成功字段 SHALL 在真实消费者兼容期保持纯代理；身份、显式操作许可、可信业务管理员管理与个人偏好self-only不可绕过。管理员不依赖ADMIN角色字面值，普通用户不因任意role名获管理权限。collectionName仅固定别名转换，维度严格匹配，任意物理目标拒绝；新授权失败不得回退旧前后缀角色策略，退役另行公告。

#### Scenario: 旧客户端数量和别名
- **WHEN** 获准旧房源查询缺省数量或提交受支持工具topK
- **THEN** 保留旧20+20/最终10或工具最终topK语义并生成合法重排池，非法数量/集合/维度明确拒绝

#### Scenario: Administrator without a role manages a legacy endpoint
- **WHEN** 有效businessAdmin无任何角色但有已登记空间管理权限调用旧管理REST
- **THEN** 按可信管理许可准入，不因缺ADMIN roleCode拒绝；个人Memory跨owner仍拒绝

## ADDED Requirements

### Requirement: A2A output events reuse valid local authorization

同一有效A2A执行的每项progress SHALL 本地检查授权和输出许可，不重复查询Auth或Redis；到期、已知撤回和领域来源失效阻止未获准输出。

#### Scenario: A2A output contains many events
- **WHEN** 有效已接受任务连续发送progress/SSE
- **THEN** 每项仅本地授权和输出检查，新增Auth用户RPC及Redis读取为零；到期或已知撤回后停止未获准输出
