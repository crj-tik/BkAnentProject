## Why

当前 Auth 按权限码前后缀及单一角色判定，Supervisor/A2A/Memory 在节点、工具与输出阶段重复远程复验，却未统一业务资源归属与可信操作者；访谈的 ticket 又进入模型与私有记忆，并在最终输出被过滤，身份、授权和交付边界不一致。需要以已落地的 Supervisor、Interviewer、Memory 为首批样本，建立 MySQL 真源、Redis 用户级版本快照、运行内存引用和边界验证，减少重复鉴权而不放宽资源安全。

## What Changes

- 建立显式权限目录、用户角色多对多授权与业务管理员标记：管理员不依赖角色取得所有已登记操作权限；未知权限码拒绝，管理员不豁免业务状态、审批、参数和幂等，也不跨 owner 读取个人 Memory。
- Auth 唯一维护 MySQL 授权真源与 Redis 用户级不可变权限快照；采用单调版本、事务 outbox、原子当前版本发布、版本通知与活跃用户批量对账，不为每个 run 复制 Redis 权限集合。
- 新请求进入 Supervisor 时一次取得完整有效权限与可信身份；运行内本地校验，Model 重新规划只核版本，同版不重新下载或计算权限；收到变更后调用独立受限刷新接口，验证真实凭据和原context，保留原运行身份、范围及硬截止；Supervisor解析持久执行关联后调用独立bindExecution，绑定成功前不执行业务，原入口不能复用于不同执行。接受有界通知传播窗口，不无限使用过期、撤回或未知版本。
- A2A/MCP/直接业务入口验证调用服务、用户、请求及受限委托。A2A 在已绑定 run 上传递 Auth 签名上下文和 Agent 服务请求证明，由接收端在 Store/Queue 前本地验签；Auth 仅在入口授权及权限刷新边界调用，不因每个子 Agent、控制请求或流事件重复远程校验。Memory/RAG 等尚未迁移的边界保持原有强校验，直到其本地验证协议验收完成。领域资源、Memory 来源与共享 grant、执行租约和审批事实仍由权威服务核查。
- **BREAKING**：以明确权限契约替代权限名前后缀推导；普通用户的新权限集仅来自角色映射，旧一般 `hasPermission(userId, code)` 不再是新授权链的身份依据。版本化并迁移真实消费者，不提供匿名或旧策略放行旁路。
- **BREAKING**：访谈建立可信创建用户与受访用户的场次关系，而非新增“发起人/受访者/导演”系统角色。拥有 `interview.initiate` 才能发起；普通用户只读自己创建或接受的访谈及相关结果，业务管理员可读全量业务访谈；发起人的协助动作按场次关系和状态授权。
- **BREAKING**：访谈 ticket 仅通过认证前端领取、服务端传输使用，不进入 LLM/Memory/checkpoint/URL/SSE。修复 A2A 开台交付与过滤冲突，分离场次参与资格和角色权限；公开资产/报告使用独立发布与脱敏投影，不将评级或持有 ID 当作公开授权。
- 修复 Supervisor 各入口 session/run/请求幂等绑定、审计 owner 范围和治理写权限；保留模型选择权、原调用审批、已完成结果复用及未知副作用对账。
- 其余业务域分批登记真实能力、建立主体关系与资源执行点；未落地或未通过授权验收的能力保持不可用，禁止因登记服务公钥或工具描述而自动开放。

## Capabilities

### New Capabilities

- `versioned-authorization-context`: 权限目录与角色授予、Redis/本地版本快照、通知和对账、受限跨服务委托、失败语义及安全迁移。
- `interview-authorization`: 访谈场次关系、三协议面一致授权、场次凭据交付、私有/公开资产与报告权限及后台任务围栏。现有主 specs 无 interview capability，本能力新增，不复制归档 runtime 全部规范。
- `domain-authorization-adoption`: 其他业务域按真实业务主体、资源和操作接入统一上下文的准入、映射与回归门禁。

### Modified Capabilities

- `authentication-baseline`: 在真实登录认证后提供可信版本化授权，管理员与普通角色分开，撤销采用明确有界失效而非逐节点远程复验。
- `supervisor-tool-orchestration`: 目录/工具使用完整有效权限本地交集，重规划版本核对，补齐入口/审计/治理资源范围，保持恢复与审批。
- `agent-memory-runtime`: 账号与粗权限使用受限有效上下文；私有 owner/domain/agent、来源与 grant 的当前权威核查和强制 Memory 接入不变，凭据不得归档。
- `domain-rag-consumption`: 以版本化受限授权替换 A2A 热路径重复远程用户校验，保留独立 RAG 签名协议、领域范围与输出复验。
- `isolated-rag-retrieval`: 将硬编码 ADMIN/BROKER 单角色准入迁至可信管理员标记、显式操作许可和签名资源范围；保留房源业务全读与个人记忆 self-only 差异。
- `a2a-memory-coordination`: 认证材料（包含访谈 ticket）与业务正文分轨，流事件不增加 Auth/Redis 往返，失效和资源来源仍本地/领域核查。

## Impact

- 核心模块：auth-service、gateway、agent-service、interview-service、memory-service、common、common-a2a、common-memory、common-skill；RAG 与真实领域消费者使用版本化适配，不能破坏既有独立签名合约。
- 数据与配置：MySQL 权限/角色关联、授权版本/outbox、访谈参与关系/票据记录及任务来源围栏迁移；Auth/消费者 Redis ACL、签名公钥登记、缓存上限与期限；使用现有 Redis 与服务 Ed25519 原语，不引入通用策略引擎。
- 协议与前端：Auth 新授权 RPC、专用内部上下文、认证访谈凭据领取与前端 ticket 使用契约；原 REST 路径和结果字段尽可能兼容，权限拒绝及凭据交付变化必须公告。Studio 未运行部分如实标记未验。
- 既有约束：实施时同步修订 LR-1/3/5/6/8/33/40/41/63/89/98 和相关 KI 的实际状态；区分 Auth 失效窗口与 Memory 来源/共享撤回。主规范不在提案阶段修改。
- 首批完成 Supervisor、Interviewer、Memory 及共享鉴权链；其他域按门禁迁移操作与资源授权，不包含电子签章、支付、外部发布、视频生成等未落地业务功能实现，也不进行无关全仓重构。
