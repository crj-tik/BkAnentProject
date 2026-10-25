## MODIFIED Requirements

### Requirement: 认证与延迟观测不进入记忆正文

认证材料 SHALL 仅经专用传输使用，包含token、版本化授权证明、A2A信标和访谈ticket；MUST NOT 出现在prompt、普通metadata、工具业务参数/响应、记忆、checkpoint、日志、URL或SSE。访谈授权前端通过独立受鉴权领取取得票据，业务A2A只交付非凭据入口引用，不靠LLM转述或泛化sanitizer放行。流事件使用本地有效授权，不逐delta增加Auth/Redis调用；来源使用和失效边界仍受领域保护。验收分别记录首事件、终态、成果可读、共享记忆、索引、Studio和必要I/O，不将某一阶段完成视为全部完成，不要求改造前延迟分布或前后性能对比。Studio实例未就绪如实标记未验。

#### Scenario: 专用认证信标与共享引用同时存在
- **WHEN** A2A携有效身份和获准共享引用
- **THEN** 身份只用于专用校验，前端/记忆仅见业务内容和非凭据引用，不能凭引用取身份

#### Scenario: 分阶段故障验收
- **WHEN** 记忆或索引延迟而A2A输出及成果链正常
- **THEN** 各阶段状态分别展示，不移除认证、来源或成果持久边界来降低延迟

#### Scenario: Interview entry remains usable without a ticket in artifacts
- **WHEN** 合法访谈启动结果进入A2A进度、终态、Supervisor模型与归档
- **THEN** 所有路径无ticket原文，前端仍可用受鉴权入口引用领取自己的票据，不因过滤删票而失去正式交付能力
