## MODIFIED Requirements

### Requirement: 认证与延迟观测不进入记忆正文

认证材料 SHALL 仅经专用传输使用，包含token、版本化授权证明、A2A请求级签名上下文和访谈ticket；MUST NOT 出现在prompt、普通metadata、工具业务参数/响应、记忆、checkpoint、日志、URL或SSE。A2A send/stream/get/cancel/resubscribe 使用已绑定 Auth context、当前 freshness 与绑定实际 JSON-RPC body 的 Agent 请求证明；接收端在官方 handler 触及 TaskStore/queue 前本地验签与核操作/owner，稳定 run 内不逐请求向 Auth 验证，权限刷新只在 Supervisor 规划边界联系 Auth。A2A 工具和流事件复用本地 lease，不逐事件增加 Auth/Redis调用；硬截止、已知撤回、Memory 来源/grant 和业务资源失效仍阻止新动作或未获准输出。访谈授权前端通过独立受鉴权领取取得票据，业务A2A只交付非凭据入口引用，不靠LLM转述或泛化sanitizer放行。验收分别记录首事件、终态、成果可读、共享记忆、索引、Studio和必要I/O，不将某一阶段完成视为全部完成，不要求改造前延迟分布或前后性能对比。Studio实例未就绪如实标记未验。

#### Scenario: 专用认证信标与共享引用同时存在
- **WHEN** A2A携有效身份和获准共享引用
- **THEN** 身份只用于专用校验，前端/记忆仅见业务内容和非凭据引用，不能凭引用取身份

#### Scenario: 分阶段故障验收
- **WHEN** 记忆或索引延迟而A2A输出及成果链正常
- **THEN** 各阶段状态分别展示，不移除认证、来源或成果持久边界来降低延迟

#### Scenario: Interview entry remains usable without a ticket in artifacts
- **WHEN** 合法访谈启动结果进入A2A进度、终态、Supervisor模型与归档
- **THEN** 所有路径无ticket原文，前端仍可用受鉴权入口引用领取自己的票据，不因过滤删票而失去正式交付能力

#### Scenario: Repeated Agent-to-Agent calls reuse a bound context
- **WHEN** 一个有效 Supervisor run 多次调用子 Agent，且授权尚未到 Graph 规划刷新边界
- **THEN** 每个 A2A JSON-RPC 请求由 Agent 使用同一 Auth 签名 actor/run/session context 产生新的短期请求证明，接收端在 Store/Queue 前本地核验精确 body、目标、操作、owner 与期限；这些调用和 SSE 项不新增 Auth RPC，模型正文里的 userId/role/admin 字段不能改变 actor

#### Scenario: Authorization refresh is required at a planning boundary
- **WHEN** Agent 的规划版本检查发现 context DIRTY 或 freshness 到期
- **THEN** 由已绑定的 Supervisor/Auth 刷新边界处理；刷新成功前不产生新的 A2A 请求证明，子 Agent 不自行用 userId 或旧 context 换发授权
