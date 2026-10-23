## Why

RAG 已独立，但 Agent 的会话工作集、压缩、偏好评估与跨域协作记忆尚未形成统一生命周期；已有 memory_json/summary 字段、偏好 CRUD 和 Graph checkpoint 不能代表这条链路已经实现。本变更复用 Spring AI / Alibaba 原生扩展点，补齐域隔离、可回溯压缩与事项级记忆装配，同时保持 A2A 到前端 SSE 的实时正文链不串行依赖 memory 回取。

## What Changes

- 各 Agent 自己管理本域活跃上下文；memory 管理可持久化原文、摘要版本、实体/事项索引、偏好历程、经验与授权检索。RAG 只执行检索投影，不成为原文唯一存储；领域业务真源和执行 checkpoint 不迁入通用消息库。
- 在已使用 ReactAgent 的 SubAgent 上接入 AgentHook/ModelHook/Interceptor，结合 Alibaba SummarizationHook 的 token 触发；在 Supervisor 通用 StateGraph 使用同一生命周期策略，在确实使用 ChatClient 的入口提供 ChatMemory/Repository 适配。不得给未使用的 ChatClient bean 加 Advisor 后宣称主链已接通。
- 会话活跃工作集本地复用，冷启动/恢复/版本失效时加载；消息先可靠增量记录，再由 token 压力或用户确认事项结束触发压缩，保存覆盖游标、证据与可重开事项摘要，完整工具调用组不被拆开。
- 用户偏好完全异步评估后归并：仅按本人 owner、私有 domain+agent 范围和稳定偏好维度维护逻辑块，保存过去到现在的完整时间历程、当前有效值和临时例外；明确修正立即影响当前上下文，不等待后台抽取。跨域共享内容仍走独立显式导出授权，不共享或合并偏好块。
- 新事项开始时按事项装配相关偏好和经验，同事项复用；范围变化、明确纠正或撤回触发局部刷新。压缩后历史指代缺口先查本地实体索引，再通过 memory 做本域本会话增强检索，返回简短解释与证据，不把任意新词视为必须检索。
- 硬规则与可衰减经验分开；经验按相关性、有效反馈与时间权重在 token 预算内选择，检索/注入本身不构成新证据。
- A2A 继续直接返回获准展示内容、进度和本次结构化协作结果；Supervisor 可直接用于当前推理及 SSE。memory 保存长期可复用的共享记录，成功后关联稳定引用；不要求逐 delta 先存、回取或等待索引。
- 用户 owner-only 叠加可信 domain/agent/事项共享授权；引用、namespace、category、A2A contextId 不授予权限。新增能力逐域启用、版本化迁移，不在授权不明时自动公开历史内容。

## Capabilities

### New Capabilities

- `agent-memory-runtime`: 原生框架接入、本地工作集、身份映射、事项级装配、跨模型轮次复用及并发恢复。
- `memory-compaction`: 可靠消息档案、token/事项双触发压缩、摘要覆盖游标、实体索引与历史补忆。
- `preference-history-memory`: 异步先评估再归并、单维度偏好块、时间历程与新事项使用策略。
- `agent-experience-memory`: 硬规则与软经验分离、领域经验评估、权重选择和生命周期。
- `a2a-memory-coordination`: A2A/SSE 实时直传与共享记忆耐久引用双轨、域授权、恢复及完成阶段区分。

### Modified Capabilities

- `domain-rag-consumption`: 扩展会话/偏好历程/经验/协作记忆的域授权、真源最终校验及稳定引用读取，不允许 Agent 绕过 memory 搜索私有记忆。
- `rag-index-lifecycle`: 扩展新记忆类型的版本化投影、偏好逻辑块物理分片和共享导出撤回，不覆盖既有单值偏好/约束空间语义。

## Impact

- 主要模块：common、common-a2a、agent-service、memory-service、rag-service；现有 SubAgent 的原生 A2A 接入统一扩展，interview 治理面与 marketing 作为代表性运行验收。interview 高频 REST/SSE 运行面、业务状态机与脱敏真源保留。
- 规划统一薄适配层 common-memory（独立模块以实际依赖面验证为准，不能向 common DTO 引入框架类型）；新增 memory v2 受管原生 Dubbo RPC，覆盖工作集、可靠消息交付、摘要提交、偏好/经验及协作引用读取；保留受鉴权 v1 REST/RPC 兼容。新增版本化 SQL/初始化清单、受管 RAG 空间/schema、配置开关及运维观测，不引入新的内网 HTTP 客户端或放宽请求地址限制。
- SSE 现有 eventType/content/result、eventId/sequence/Last-Event-ID 继续兼容；共享引用与持久化状态为增量字段，认证信标永不进入普通 metadata、记忆、日志或前端事件。既有 artifact.created 仅在成果保存成功后发布，workflow 完成与 Studio 成果投影时序不被异步记忆加工破坏。
- 配置和访问控制须延续 LR-3/6/17/18/22/23/31/33/38/40/41/53；实施同提交更新 logic-rationale/known-issues，但本次仅生成规划，不修改主规格、代码、SQL 或运行环境。
- 不宣称已实现或性能已达标，不同时修复无关 Studio 领域功能、访谈凭据问题或全仓 SSE 重构；涉及本链路的过滤/可见性/成果时序问题由回归与独立任务明确处理。
