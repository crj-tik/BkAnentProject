# Auth 权限体系清单与架构图

本文把当前仓库中的 Auth v2 权限模型、Agent 请求身份传递、业务域授权边界和验收状态放在一处，供后续实现与前端需求复用。它描述当前设计和代码证据，不代表所有业务服务已在生产启用。

状态口径以 `openspec/changes/unify-versioned-permission-context/tasks.md` 的当前勾选和 `implementation-progress.md` 最后追加的 2026-10-27 开发栈验收为准；历史章节可能保留当时的状态。

## 1. 设计结论

权限模型采用 **身份认证 + 受限操作权限码 + 资源关系/状态检查**：Auth 认证真实用户并签发请求级上下文；权限码只标记需要明确授权的受限拦截点，不要求给每个 endpoint、CRUD 或 Graph 节点配码；业务服务依据自己的数据真源检查资源归属和业务状态。

一次业务动作只有同时满足以下条件才允许执行：

```text
允许 = 有效用户/受限委托
    AND 签名上下文、受众、请求绑定、期限和授权版本有效
    AND (命中受限拦截点时：对应精确权限码已授予)
    AND (涉及目标资源时：owner/关系成立)
    AND (涉及状态转换时：业务状态允许该操作)
```

关键约束：

- **Auth 是身份与操作权限的权威来源；业务服务是资源归属和业务状态的权威来源。** Auth 不替业务服务判断某条客户、房源、合同或内容属于谁。
- **基础功能面向所有有效登录用户时，不要求权限码。** “无权限码”不等于匿名访问：仍须验证用户和签名上下文；涉及资源或状态转换时，继续检查 owner/参与关系及业务状态。
- 权限码目录、角色授予、用户角色关联只管理受限拦截点。受限操作引用的权限码未登记、未授予或已停用时拒绝；不根据角色名称、模型输出、内网来源或资源 ID 推断权限。
- 普通用户对受限拦截点的权限来自当前有效角色的显式授予；用户角色有生效/失效时间。`businessAdmin` 是 Auth 保存的可信标记，可获得已登记的有效受限操作，但不能绕过个人 Memory owner、业务资源关系或状态机。
- LLM 可以提出业务目标和参数，不能决定 `actor/userId`、`role`、`businessAdmin`、`brokerId`、`employeeId`、授权范围或请求所有者。
- 同一个 Graph run 内的多个工具/subAgent 调用复用已绑定身份和权限范围。每个传输边界验证签名上下文和请求证明；不对 Graph 中的每个节点重复远程查询 Auth。
- 授权通过不代表业务动作已经实现。签章、付款、外部营销发布等真实副作用仍由对应业务流程和状态机决定。

## 2. 权限职责清单

| 层 | 权威事实 | 负责判断 | 不负责判断 |
|---|---|---|---|
| Auth | 用户状态、真实登录凭据、角色、受限操作权限目录、有效授予、`businessAdmin`、业务身份映射、权限版本和认证 epoch | 谁是 actor；受限拦截点是否获授权；签发/刷新/撤销受限上下文 | 某条业务记录是否真的属于 actor；业务状态是否允许发布/签章/付款 |
| Gateway | Bearer、真实请求方法/path/query/body、路由目标 | 清理客户端伪造身份头；为外部请求建立一次请求级 Auth 上下文；绑定转发请求 | 从请求字段或模型文本推导 actor/admin |
| Supervisor | 持久 run/session owner、原请求范围、审批与续接状态 | 持久执行身份确定后请求 Auth 绑定；把原范围带入 Graph 和子调用；控制刷新/恢复 | 扩大 Auth 签名范围，或让模型改写 run/session owner |
| Agent/A2A/MCP 边界 | Auth 签名上下文、服务调用方证明、实际请求正文、nonce | 本地验签、验 audience/期限/请求 hash/nonce；复用上下文；拒绝重放和篡改 | 用固定 caller、服务身份或内网来源代替用户授权 |
| 业务域服务 | 本域 SQL 记录、owner/关联表、审核/发布/状态机 | 受限拦截点检查精确操作码；所有操作都检查适用的 owner/关系和业务状态；在副作用前拒绝 | 重新相信模型给出的 actor/owner；把 Auth 权限当作状态转换成功 |
| MySQL/Auth Outbox | 角色/权限/映射/版本的持久权威数据 | 事务化写权限事实、审计和待发布事件 | 作为每个 Graph 节点的远程实时授权 API |
| Redis 授权 head/通知 | 已签快照的版本、状态与有界 freshness | 加速读、通知消费者版本变化、约束失效窗口 | 取代 MySQL 真源或自行增加权限 |

## 3. 权限对象与授权数据

| 对象 | 当前用途 | 设计检查点 |
|---|---|---|
| 用户与认证凭据 | 登录、refresh、me、logout；用户必须存在、启用且未删除 | 请求体中的 `userId` 不能代替真实凭据；撤销凭据会更新认证 epoch |
| 角色 | 将一组明确权限组织成可管理的业务角色 | 角色代码不是权限；`ADMIN`/`BROKER` 字符串本身不赋权 |
| 权限目录 | 保存受限操作的 `permission_code → domain + operation + active + version` | 只为需要明确授权的拦截点登记精确码；该拦截点引用未知码时拒绝；停用/目录变化推动权限版本变化 |
| 角色权限 | 角色到受限目录操作的显式关联 | 不用 `*`、domain 前缀或工具名称泛化；权限登记不等于角色授予；基础功能不靠批量角色授予“假装免鉴权” |
| 用户角色授予 | 用户与角色的关联，含 active、`valid_from`、`valid_until` | 只计算当前有效授予；开始/到期边界须推动版本刷新 |
| 业务身份映射 | Auth 维护 `userId → business identity`，当前已实现 BROKER 映射 | 服务端签入上下文；模型字段和偶然相同的数字 ID 不构成映射 |
| `permissionRevision` | 权限/角色/映射变更版本 | 消费者发现变化后停止旧范围，并在合法边界刷新 |
| `credentialEpoch` | 用户凭据和账号失效版本 | 注销、停用等使旧执行上下文失效 |
| Outbox 与审计 | 将 Auth 数据变更与版本通知可靠关联 | 事务提交后发布；失败或状态不新鲜时不续用旧 head |
| Nonce/执行绑定 | 约束授权 RPC 重放和 Supervisor run/session 的持久绑定 | 耐久 nonce/owner 放在 Auth 或实际 provider 的持久库；不能仅靠进程内 Map |

## 4. Auth v2 上下文清单

Auth v2 不是把权限名放进普通请求头。下游使用经过签名、绑定特定执行/请求的授权材料，并在本地校验。

| 字段组 | 主要内容 | 校验目的 |
|---|---|---|
| actor 与权限 | `actorId`、`allowedOperations`（仅受限操作）、`permissionHash`、`permissionRevision`、`credentialEpoch` | 确认可信主体和受限操作授权范围未被替换或回放成旧版本；基础操作不要求出现在权限集合中 |
| 业务身份 | Auth 签入的 `businessIdentities`，如 `BROKER → brokerId` | 服务端从可信映射取得 broker，不从模型/请求正文取值 |
| 请求关联 | `audience`、`requestId`、run/session、`policyDigest`、请求 hash、nonce | 将授权限定到目标服务、本次执行、请求内容和调用策略 |
| 时间边界 | 签发、过期时间、run 硬截止；另有 freshness 的 `checkedAt/notAfter` | 硬截止不能由续租延长；过期、失效或 freshness 超窗时拒绝新动作 |
| 签名信息 | 算法、key id、签名 | 验证 Auth 对上下文的签发；服务间请求仍需验证对应 caller proof |
| 执行绑定 | 持久 run/session owner、原入口请求 hash、允许操作集合 | Supervisor 在真实 run/session 确定后由 Auth 建立执行关联，不能改写原 actor 或扩大原范围 |

`AuthorizationSnapshot` 是稳定的用户授权事实；`AuthorizationContext` 是限定受众、请求和执行的短期授权；`AuthzFreshness` 表明这些事实近期由权威来源核验。三者必须彼此匹配，不能单独拿其中一个当长期凭据。

## 5. 请求与权限检查体系图

```mermaid
flowchart LR
    Client[前端或 API 客户端] -->|Bearer + 业务参数| Gateway[Gateway 外部入口]
    Gateway -->|一次认证请求：token + method/path/query/body 绑定| Auth[Auth 身份与权限决策]
    Auth <--> AuthDB[(Auth MySQL：用户、角色、权限、授予、映射、版本)]
    Auth -->|签名 Snapshot + Context + Freshness| Gateway
    Gateway --> Supervisor[Supervisor]
    Supervisor -->|run/session 持久化后绑定一次| Auth
    Supervisor --> Graph[Graph 执行]

    Graph -->|请求级 Context + caller proof| A2A[subAgent / A2A]
    Graph -->|签名 tools/call proof| MCP[MCP 工具入口]
    Gateway -->|签名请求上下文| Domain[业务域服务入口]
    A2A --> Domain
    MCP --> Domain

    Domain --> LocalVerify[本地验签：audience、请求 hash、nonce、期限、freshness]
    LocalVerify --> GateDecision{是否命中受限拦截点}
    GateDecision -->|是| OpCheck[精确权限码检查]
    GateDecision -->|否| OwnerCheck[适用时检查资源 owner/关系]
    OpCheck -->|通过| OwnerCheck
    OpCheck -->|拒绝| Reject[拒绝请求]
    OwnerCheck --> StateCheck[适用时检查业务状态]
    StateCheck -->|允许后才执行| DomainDB[(本域 MySQL/业务真源)]

    AuthDB --> Outbox[事务 Outbox]
    Outbox --> Redis[(Redis 授权版本 head / 通知)]
    Redis -.版本变化或过期时刷新/停止新动作.-> LocalVerify
    LLM[LLM 生成的参数/目标 ID] -.不可信输入，不能决定 actor 或权限.-> Graph
```

### 一次用户请求的步骤

1. 用户经登录得到 access/refresh 凭据；前端后续只向 Gateway 发送凭据和业务输入。
2. Gateway 清除客户端提交的身份/授权伪造头，按真实请求调用 Auth 一次。Auth 校验用户状态、有效角色授予、受限操作授予、业务身份映射和当前版本；普遍开放的基础操作不要求逐项命中权限码。
3. Auth 签发限定 audience、方法、路径、query、body hash、policy、请求 ID、期限和操作集合的授权上下文。Gateway 转发时不把模型参数当 actor。
4. Supervisor 创建或恢复持久 run/session 后，在执行关联边界让 Auth 绑定这份上下文。actor、原允许操作、原 run 硬截止不能被子 Agent 改写。
5. Graph 内部多个工具/subAgent 复用绑定上下文；每个跨服务请求带独立请求证明和唯一 nonce，由接收服务本地验证。普通节点不逐次远程调用 Auth。
6. 命中受限拦截点时，业务方法检查对应精确 operation；所有操作再以本域数据库按 actor 的可信业务映射核 owner/关系，并核业务状态。只有所有适用检查通过才允许查询或副作用。
7. 权限/角色/身份映射变化由 Auth 更新版本、审计与 Outbox；Redis head/通知促使消费者停止旧权限或刷新。硬过期、已知撤权或 freshness 不足时关闭新动作。

## 6. 权限目录分域清单

下表是当前操作与受限能力的分域速查，不是自动授予清单。表内 `.*`、`own/all` 是便于阅读的分组简写，**不是可授予的通配权限码**；当前迁移中逐条登记的精确权限码见 6.1 节。现有目录包含一些可能属于基础功能的细粒度码，清单表示当前迁移事实，不表示这些码都应作为目标拦截点保留。

| 域 | 操作类别 / 权限码示例 | 权威资源与额外边界 | 当前状态摘要 |
|---|---|---|---|
| Auth 管理 | `auth.users.manage`、`auth.roles.manage`、`auth.business-identity.manage` | 账号、角色和授权管理；管理动作本身也需真实管理员授权 | Auth→Gateway→Agent 开发链路已验；生产环境启用与发布仍须按 rollout 门禁执行 |
| Agent/Supervisor | `agent.chat.use`、`agent.workflow.read`、`agent.workflow.artifact.read`、`agent.workflow.invoke.*`、`agent.audit.*`、`agent.governance.*` | 持久 run/session/task owner；审批、取消、恢复和 artifact 仍校验原 owner 与状态 | Auth→Gateway→Agent 的真实开发 API 流程已验；多节点 Auth 调用数有本地回归 |
| RAG | `agent.rag.*`、`agent.vector-store.*`、`agent.rag.memory.search`、`agent.rag.listing.query`、`rag.space.manage` | Space/corpus/grant/source owner 和版本；候选授权不等于源数据可见 | 逐域实现；部署密钥、数据源和具体 RAG 路径按域验收 |
| Memory | `memory.read/write/search/share/revoke/delete`、`memory.constraint.*`、`memory.experience.governance.publish` | 个人数据始终按 owner 隔离；业务管理员不自动获得他人个人 Memory | 有请求级来源授权；真实 DB/撤权场景仍按任务验收 |
| Listing | `listing.read`、`listing.read.all`、`listing.create`、`listing.update.own/all`、`listing.delete.own/all`、`listing.status.update.*`、`listing.assets.bind.*`、`listing.ocr.*`、`listing.verify.*`、`listing.index.manage` | SQL `brokerId/status/revision`；私有详情和写入按 Auth 的 user→broker 映射核 owner | 源码与定向回归完成；真实 Listing provider 的跨服务端到端仍待验 |
| Customer | `customer.read`、`customer.read.all`、`customer.create`、`customer.update/delete.own/all`、`customer.follow.read/write.own/all`、`customer.entrust.read/write.own/all`、`customer.favorite.read/write.own/all`、`customer.match.own/all`、`customer.entrust.expiring.read`、`customer.reminder.send` | 客户、跟进、委托和收藏关联表为归属真源；请求体 brokerId 只能作一致性断言 | 源码与定向回归完成；无可信归属时拒绝 |
| Business | `business.kpi.read.all`、`business.workload.read.all`、assessment/ranking/turnover/store-dashboard/dashboard 操作 | 实际域为 Trade；目前缺 user→employee/store 映射，服务不信模型员工/门店 ID | 本地源码和 fixture 通过；未定义映射的访问保持关闭 |
| Settlement | `settlement.rule.*`、`settlement.calculate.all`、`settlement.record.*`、`settlement.split.*`、`settlement.generate.all`、`settlement.batch.*`、`settlement.commission.*` | 缺 user→employee/store/payee 关系、Contract 委托或银行签名回执的副作用拒绝 | 精确操作守卫已做；支付、批次等未证明的能力仍关闭 |
| Contract | `contract.template.*`、`contract.create.own`、`contract.read.*`、`contract.status.update.*`、`contract.attachment.ocr.*`、`contract.archive.*`、`contract.seal.*`、`contract.risk.*` | 合同 broker owner；附件先授权父合同；签章需真实签章服务和状态门 | 源码/定向回归完成；mock 签章不能标记 SEALED |
| Marketing/Promotion | `marketing.content.create.own`；`content.read.own/all`；`content.variant.create.own/all`；`assets.bind.own/all`；`publish.status.update.own/all`；`publish.execute.own/all`；`promotion.effect.read.all/sync.all`；`promotion.roi.read.all`；`brand-assets.read.all/write.all`（均带 `marketing.` 前缀） | 新内容 owner 从 Auth broker 映射写入；历史 owner 必须有可靠证据；发布状态仍受审批与外部渠道约束 | 目录 migration 有 16 个营销权限码，但该 migration 不自动授予 BROKER；实际角色授予需单独检查。新内容可限制在 DRAFT，真实发布未接通时拒绝 SUCCESS |
| Compare | `compare.report.create`、`compare.report.read.own`、`compare.report.share.public` | 报告 owner、固定来源快照 hash；公开分享需单独权限且只开放已发布快照 | 本地 owner/source 检查已实现；持久恢复与部署路径按域验收 |
| Media | `media.task.submit.*`、`media.task.read.*`、`media.generate.background` | taskId 只是定位符；后台 permit 绑定 actor、resource、请求 hash 与硬截止 | 许可守卫已实现；当前内存 TaskStore 不保证跨重启恢复，不能当生产耐久任务 |
| Notification | `notification.message.read.own`、`notification.message.mark-read.own`、`notification.message.send.own/all`、`notification.email.send.all`、`notification.robot.send.all`、`notification.workflow.events.read.all` | actor 与 recipient 分离；消息 SQL recipient 关系为真源；旧无 proof Dubbo 发送拒绝 | 源码与定向回归完成；Contract/Customer 提醒缺 recipient 映射，保持关闭 |
| Interview | `interview.initiate/read/respond/assist/end/report.generate/asset.submit/asset.publish/asset.regrade`、`interview.read.all` | session owner、participant relation、purpose ticket、报告/附件状态；短票不替代登录 | 服务端已加目的和 owner 检查；浏览器 fetch/SSE 和真实 ReactAgent 归档链冻结待前端接入 |

### 6.1 当前迁移中登记的精确权限码（现状，不是目标必留清单）

以下按权限目录迁移中的精确字符串列出。Auth 表同时保存 `domain` 与 `operation`；代码匹配使用完整 `permission_code`，不能把下面的分组标题或省略符当权限。当前迁移登记约 147 个精确码，其中部分本人范围的基础操作应按 6.2 节复核是否撤出权限码门控。此表不授权删除运行中的守卫；须先确认所有调用入口与本域 owner/state 检查，再通过单独迁移调整目录、角色授予和消费者。

**Auth、Agent 与 RAG**

```text
auth.users.manage
auth.roles.manage
auth.business-identity.manage
agent.chat.use
agent.workflow.read
agent.workflow.artifact.read
agent.workflow.invoke.interview
agent.workflow.invoke.memory
agent.governance.read
agent.governance.manage
agent.audit.read
agent.audit.manage
agent.rag.read
agent.rag.search
agent.rag.manage
agent.vector-store.read
agent.vector-store.manage
agent.rag.memory.search
agent.rag.listing.query
rag.space.manage
```

**Listing**

```text
listing.read
listing.read.all
listing.create
listing.update.own
listing.update.all
listing.delete.own
listing.delete.all
listing.status.update.own
listing.status.update.all
listing.assets.bind.own
listing.assets.bind.all
listing.ocr.own
listing.ocr.all
listing.verify.own
listing.verify.all
listing.index.manage
```

**Customer**

```text
customer.read
customer.read.all
customer.create
customer.update.own
customer.update.all
customer.delete.own
customer.delete.all
customer.follow.read.own
customer.follow.read.all
customer.follow.write.own
customer.follow.write.all
customer.entrust.read.own
customer.entrust.read.all
customer.entrust.write.own
customer.entrust.write.all
customer.favorite.read.own
customer.favorite.read.all
customer.favorite.write.own
customer.favorite.write.all
customer.match.own
customer.match.all
customer.entrust.expiring.read
customer.reminder.send
```

**Business 与 Settlement**

```text
business.kpi.read.all
business.workload.read.all
business.assessment.calculate.all
business.ranking.read.all
business.ranking.rebuild.all
business.turnover.read.all
business.store-dashboard.read.all
business.dashboard.read.all
settlement.rule.read.all
settlement.rule.write.all
settlement.calculate.all
settlement.record.create.all
settlement.split.calculate.all
settlement.generate.all
settlement.record.read.all
settlement.split.read.all
settlement.payout-status.update.all
settlement.summary.read.all
settlement.summary.rebuild.all
settlement.batch.create.all
settlement.commission.read.all
```

**Contract**

```text
contract.template.read.all
contract.template.write.all
contract.create.own
contract.read.own
contract.read.all
contract.status.update.own
contract.status.update.all
contract.attachment.ocr.own
contract.attachment.ocr.all
contract.archive.own
contract.archive.all
contract.seal.own
contract.seal.all
contract.risk.read.own
contract.risk.read.all
contract.settlement.read.all
contract.reminder.send.all
```

**Marketing 与 Promotion**

```text
marketing.content.create.own
marketing.content.read.own
marketing.content.read.all
marketing.content.variant.create.own
marketing.content.variant.create.all
marketing.assets.bind.own
marketing.assets.bind.all
marketing.publish.status.update.own
marketing.publish.status.update.all
marketing.publish.execute.own
marketing.publish.execute.all
marketing.promotion.effect.read.all
marketing.promotion.effect.sync.all
marketing.promotion.roi.read.all
marketing.brand-assets.read.all
marketing.brand-assets.write.all
```

**Compare 与 Media**

```text
compare.report.create
compare.report.read.own
compare.report.share.public
media.task.submit.own
media.task.submit.all
media.task.read.own
media.task.read.all
media.generate.background
```

**Notification**

```text
notification.message.read.own
notification.message.mark-read.own
notification.message.send.own
notification.message.send.all
notification.email.send.all
notification.robot.send.all
notification.workflow.events.read.all
```

**Interview 与 Memory**

```text
interview.initiate
interview.read
interview.read.all
interview.respond
interview.assist
interview.end
interview.report.generate
interview.asset.submit
interview.asset.publish
interview.asset.regrade
memory.read
memory.write
memory.search
memory.share
memory.revoke
memory.delete
memory.constraint.read
memory.constraint.manage
memory.experience.governance.publish
```

### 6.2 目标权限码边界：只保护受限拦截点

权限码不是接口清单，而是对“默认不能做、必须显式授予才能做”的业务能力设置的拦截门。一个受限能力可由多个 REST、Dubbo、A2A 或 MCP 入口共同执行同一权限检查；不要因为入口、HTTP 方法、Graph 节点或 subAgent 不同而重复造码。

| 类型 | 是否要求权限码 | 判断规则与示例 |
|---|---|---|
| 普遍适用的基础功能 | 否 | 任何有效登录用户均可使用的普通查询/写入、标准 Agent 对话、本人任务或通知读取等；仍按真实 actor、owner/参与关系、数据敏感级别和业务状态检查 |
| 本人范围内的普通业务操作 | 默认否，按产品规则确认 | 若对该类有效用户普遍开放，可只做登录身份、可信业务身份映射及 owner/关系检查；例如本人范围的常规列表、客户跟进或草稿操作，不因存在 endpoint 就强制配码 |
| 明确限制的能力 | 是 | 跨 owner/全量数据、敏感数据导出、用户/角色/身份映射管理、审批、签章、付款、外部发布/发送、公开分享、批量重建、审计治理等；必须有精确登记码和显式授予 |
| 高风险本人范围操作 | 视业务风险可要求 | `.own` 不代表天然免码。即使资源属于本人，签章、公开发布、不可逆删除等仍可设为受限拦截点 |

现有细粒度码中，可优先评估是否移出权限码门控的候选包括 `agent.chat.use`、`listing.read/create/update.own`、`customer.create/update.own`、`customer.follow.read.own/write.own`、`compare.report.create/read.own`、`media.task.submit.own/read.own`、`notification.message.read.own/mark-read.own`、`interview.initiate/read/respond/assist/end`、`memory.read/write/search`。这些是待按产品规则和数据敏感性确认的候选，不是本次直接删除清单；若某项并非普遍开放，保留其受限门控并说明限制理由。

目标保留的门控以明确的限制边界为准：例如 `*.all` 跨 owner 操作、`auth.*.manage`、`contract.seal.own`、`marketing.publish.execute.own`、`settlement.batch.create.all`、`notification.email.send.all`、`compare.report.share.public`。权限码授予始终不能取代资源 owner/关系、参与者资格、审核及状态机检查。

### 6.3 权限码的命名和授予语义

权限码约定为 `domain.operation`；operation 可以继续分层，例如 `resource.action.scope`。例如：

| 权限码 | Auth 保存的 domain / operation | 含义 |
|---|---|---|
| `customer.follow.write.own` | `customer` / `follow.write.own` | 执行客户跟进写操作，并由 Customer 服务限制为当前 actor 可管理的客户 |
| `marketing.content.read.all` | `marketing` / `content.read.all` | 请求 Marketing 内容的业务宽范围读取；仍受本域数据状态和服务实现约束 |
| `agent.workflow.invoke.interview` | `agent` / `workflow.invoke.interview` | 允许 Supervisor 发起 Interview 域工作流，不代表拥有任一 Interview session |
| `interview.respond` | `interview` / `respond` | 允许执行答题动作；仍需参与者关系、session owner/purpose 和状态检查 |

现有码中的 `.own` 表示本人范围，必须映射到已签名的 actor/business identity 并由目标数据核 owner；`.all` 表示显式登记的宽业务范围操作，不是全局通配，也不能绕过该域状态机。后缀不决定是否需要权限码：普遍开放的本人操作可以没有权限码；高风险本人操作仍可以有 `.own` 码。即使没有权限码，不能用请求体中的 `userId` 或 `brokerId` 替代 owner 检查。

当前 SQL 仍显式给 `BROKER` 授予一组 Agent、Interview、Memory、Listing 和 Customer 操作；这是现有实现，不是最终目录规模。后续应按 6.2 节把普遍基础操作移出码门控及其角色授予，只对受限能力保留显式授予。Marketing/Promotion 迁移登记目录码但不自动授予 `BROKER`。Auth 管理员使用独立可信 `businessAdmin` 标记，不依赖 `ADMIN` 角色通配权限；有效业务管理员可取得已登记的受限操作，但本域 owner、个人 Memory 和业务状态约束仍然存在。

### 目录发布与授予检查

- [ ] 每个新操作先判断是否真是受限拦截点。普遍适用的基础操作不加权限码；受限能力才定义稳定操作码，并记录调用入口、目标资源、owner 关系、限制原因和状态转换。
- [ ] 分别审查 `auth_permission`（目录）和 `auth_role_permission`/`auth_user_role`（实际授予）；不因目录出现就自动放行角色。
- [ ] 对保留的 `.own` 和 `.all` 码写清授予对象；`.all` 要求显式业务范围，不从管理员角色名称或请求参数推导；本人范围高风险操作注明为何仍需门控。
- [ ] 逐项复核现有目录码及其消费者；确认基础操作的 owner/state 检查后，再撤销冗余守卫和角色授予，避免只删 Auth 授予导致现有调用失败。
- [ ] 迁移需有 schema check、幂等 repeat 证明、权限版本/outbox 影响和受控回滚方案；授权事实迁移按 forward-only 处理。
- [ ] 用户/角色/权限/业务映射变化需检查审计、revision/epoch、Redis 发布及旧上下文失效行为。

## 7. 前端与 LLM 身份边界

前端目前不在仓库。本节列出以后搭建前端时要进入需求单的能力；前端只能改善交互，不能替代服务端鉴权。

- 登录、refresh、logout、`/auth/me`；显示 Auth 返回的真实角色、多角色、businessAdmin 和权限版本，不用 UI 本地角色推导权限。
- 所有受保护 Agent API 经过 Gateway；凭据不放 query string，不由用户输入框或对话内容构造身份字段。
- 用户界面可以提供资源目标和业务参数；`userId`、`role`、`businessAdmin`、broker/employee owner 由服务端绑定。
- 不把 token、ticket、Auth context、caller proof 放进 prompt、Memory、进度事件、artifact、URL、analytics 或普通日志。
- Interview 需要认证 POST 领取短期 purpose ticket；浏览器以 fetch + ReadableStream 携带 Bearer 与专用票据请求 SSE，处理到期/撤销、重新领取和恢复。
- 前端展示“可操作”仅作提示；每次 API 请求仍由服务端核 operation、resource owner 和状态。

## 8. 当前验收与生产准入清单

### 已有证据

- [x] Auth 用户/角色/权限目录、签名授权上下文、版本状态、业务身份映射和执行绑定有源码及定向回归。
- [x] 逐域源码清单覆盖 Auth、Agent、RAG、Memory、Listing、Customer、Business、Settlement、Contract、Marketing、Compare、Media、Notification、Interview。
- [x] Auth→Gateway→Agent 的开发 API 验收记录包含登录、me、refresh、refresh 重放、伪造身份拒绝、logout 和撤销 token；秘密值不进入本文。
- [x] 最新开发栈记录显示既有数据库的 42 项 upgrade migration 及 schema check 已执行并重复通过；这不是 fresh DB 或生产回滚验收。
- [x] `implementation-progress.md` 历史记录为 58/63；本次依据目标权限模型新增 1.4（现有码分类）和 2.6（基础操作退出权限码门控）两项未完成工作，因此当前任务清单为 58/65，另有原先未完成项 8.3、8.4、11.1、11.4、11.6。历史进度不代表本次设计调整已落到运行时代码。

### 上线前仍需逐域完成

- [ ] 真实 MySQL/Redis 的并发写、原子版本发布、outbox 延迟/乱序/丢失、授予到期、账号撤销和 freshness 超窗故障矩阵（11.1）。
- [ ] Auth、Gateway、Supervisor、common 协议和要启用的业务服务成组发布；确认每个服务的签名 key、audience、trusted caller、服务路由及数据库迁移版本一致。
- [ ] 按启用域真实验证 REST、Dubbo、A2A、MCP 入口都到达同一服务级权限/owner 守卫；覆盖跨用户、管理员范围、模型伪造 actor、请求正文重放、过期与撤权。
- [ ] 对 Marketing 明确普通 BROKER 的具体角色授予；核实历史内容 owner 来源。不能可靠回填的记录保持不可见/隔离。
- [ ] 对 Marketing 外部发布实现真实审核和渠道回执之前，不开放“已发布成功”；权限码不构成外部渠道上线证据。
- [ ] 完成开发栈真实 A2A 生命周期及 Interview 端到端（11.4）；未知或未运行的业务域保持未验状态。
- [ ] 完成 fresh/upgrade/repeat 和同等安全制品组回滚演练（11.6）；数据库鉴权迁移不降级、不删除授权事实。
- [ ] 前端建立后完成 8.3/8.4 浏览器领取、fetch/SSE、票据恢复，以及真实 ReactAgent 输出归档链；在此之前维持冻结标记。

## 9. 参考源

- [Auth 基线与 Gateway 入口规范](../openspec/changes/unify-versioned-permission-context/specs/authentication-baseline/spec.md)
- [业务域准入与跨协议授权规范](../openspec/changes/unify-versioned-permission-context/specs/domain-authorization-adoption/spec.md)
- [逐域实现与资源/状态矩阵](../openspec/changes/unify-versioned-permission-context/implementation-inventory.md)
- [当前实施进度与验收证据](../openspec/changes/unify-versioned-permission-context/implementation-progress.md)
- [逐任务状态](../openspec/changes/unify-versioned-permission-context/tasks.md)
- [版本化鉴权发布与回滚](authz-versioned-rollout-rollback.md)
- [Auth 基础权限迁移](../sql/migrations/20261023_versioned_authorization.sql)
- [业务域权限目录迁移](../sql/migrations/20261026_auth_business_identity_mapping.sql)
- [Marketing/Contract 权限目录迁移](../sql/migrations/20261026_contract_marketing_authorization.sql)
- [Marketing 内容归属迁移](../sql/migrations/20261026_marketing_content_owner.sql)
