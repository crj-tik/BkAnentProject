# Memory v1 / v2 能力差异与兼容边界

> 状态：v2 可靠归档及 Supervisor Graph 工作集接线已实现并部署，Agent 与原生 provider 的 V2 开关均默认关闭；其他领域消费者尚未接入。v1 保持原接口。防回归：`MemoryV1V2CompatibilityTest`；证据见 `docs/acceptance/domain-agent-memory-archive-20261019.md` 和 `docs/acceptance/domain-agent-memory-supervisor-20261020.md`。

## 1. v1 真实消费者面（2026-10-18 源码核实）

| v1 通道 | 端点/接口 | 真实消费者 | 状态 |
| --- | --- | --- | --- |
| 会话记忆 REST | `POST /internal/memory/sessions/upsert`、`GET /internal/memory/sessions` | agent-service `SupervisorSessionOwnershipService`（claim/assertOwned，会话归属核验） | 活跃 |
| 工件 REST | `POST /internal/memory/artifacts`、`GET .../artifacts/by-task|by-id` | agent-service `RemoteTaskArtifactStore`（`@Primary` 成果存储）、`SupervisorWorkflowQueryService` | 活跃 |
| 交接 REST | `GET /internal/memory/handoffs/by-task|by-session` | agent-service `RemoteTaskArtifactStore`（历史列读） | 活跃 |
| 工作流历史 REST | `GET /internal/memory/workflow/{taskId}/history` | agent-service `WorkflowHistoryService` | 活跃 |
| 系统约束 REST | `GET /internal/memory/system-constraints[/search]` | agent-service 启动期 `SystemConstraintBootstrap`（读硬规则） | 活跃 |
| 检索 RPC（Dubbo v1） | `MemoryRagRpcService.search` | RAG 服务（`memory.*` 私有搜索经 memory 真源） | 活跃 |
| 用户偏好 REST | `POST /internal/memory/user-preferences/upsert`、`GET .../{userId}`、`.../decay` | **无业务调用点**（`MemoryStoreClient` 接口有方法、agent 无 caller；已被 RAG 偏好投影链路替代） | 保留但闲置 |
| 会话快照 REST | `POST/GET /internal/memory/sessions/snapshot...` | 存量能力，未见新增消费者 | 保留 |

**兼容承诺**：上述活跃通道的请求/响应形状（`SessionMemoryUpsertRequest`、`ArtifactCreateRequest`、`HandoffRelation*`、`UserPreferenceRecord` 等）在 v2 落地期间**不改名、不改字段、不改语义**；v1 端点鉴权与 owner-only 语义不变（LR-40/43）。

## 2. v1 明确不提供的能力（不得误声明为完整记忆链）

- **无完整历程**：`SessionSharedMemoryEntity` 是 `summary` 整体覆盖 + `memory_json` 平铺 Map——没有 revision/OCC，没有时间挂点，写两次就覆盖。v1 偏好是单值 `preferenceKey→preferenceValue`，无 PreferenceBlock、无 transition 历程、无 authorization revision。
- **无域/Agent 隔离身份**：v1 只有 `userId+sessionId`，没有 `(ownerId, domainId, agentId, conversationId)` 四重身份；不区分 conversation/run/task/domainSession 关联。
- **无可靠交付语义**：无接收许可/交付许可/invocationId 绑定；`upsert` 是"写了就当成功"，无精确事件身份。
- **无压缩契约**：summary 无 covered range、无来源依赖、无两阶段确认（v1 的 summary 字段只是最后一次写入的文本）。
- **无共享授权**：handoff 是平铺记录，无 grant/purpose/有效期/参与者投影。

结论：**"接入了 v1" ≠ "已有域隔离记忆"**。v1 的 memory_json/summary/单值偏好是上述受限形状，任何把它们描述为"新记忆链已接通"的说法都违背本表。

## 3. v2 能力与启用状态（实现按任务组推进）

十四操作（工作集与档案十二操作，另加已评估偏好归并和固定版本历程详情）+ 四重身份 + 构造期版本/操作拒绝 + 请求 hash 身份。已实现当前用户与 SERVICE 分离、持久 nonce、精确事件/受理结果许可、消息单调追加、摘要覆盖/OCC、实体与事项分页、当前来源复核和撤回。开发库清单 110–170 均已应用并核验，140/150 分别对应写入 fence 与 Supervisor 交付 journal，160 是偏好历程与首次接收时间，170 是偏好异步评估回执。原生 provider 已通过进程内 Dubbo 调用测试，Supervisor 已接真实 Graph 节点生命周期并通过受控 RPC fixture 回归；Agent 与 Memory v2 入口默认关闭。偏好后台消费者已实现但默认关闭，其他域消费者、其他加工/索引 worker、v2 RAG 尚未启用。偏好/经验搜索在当前阶段明确返回 `MEMORY_UNAVAILABLE`，不会伪装成无匹配。

首次启用前的 KI-129 契约修正：`MemoryGrantRequest` 独立传递接收 contextKey、来源 exportRef、scope/matter/purpose，`MemoryShareGrant.Participant` 绑定接收 conversationId。此前仅由来源引用推导调用者身份的 v2 测试客户端须随当前源码更新；v1 形状及消费者不受影响。共享引用不是认证凭据，展示/协作许可不兑换 durable export，当前 RPC 详情路径只返回一个明确请求且获准的导出版本。

本次首次启用前的 v2 wire 变更：proof 增加签名覆盖的 USER/SERVICE actor；permit 请求增加 `resultAdmissions` 与 writer authority；消息增加来源依赖；摘要请求增加 writer authority；工具 response 增加 `RETURNED`（只证明 callback 返回）；接口增加 `withdrawSource`、`manageWriter`、`checkBoundary`，加上偏好归并/详情后，十四个方法均声明安全异常。所有 v2 caller 必须一起更新，不能混用先前未启用的 v2 测试制品。事件用途固定为 `conversation-archive`；结果许可仅接受已入库 TOOL/ACCEPTED 事实，模型 ASSISTANT 提案与回调 JSON 中的状态文本无效。来源工具事实必须由真实执行器构造，本批没有开放模型自行签名入口。

## 4. 防互污染规则（测试锁定）

2026-10-20 偏好历程模块新增 `submitPreferenceAssessment` / `readPreferenceHistory`。前者要求当前 USER 断言、同一可信 source_caller、已归档 USER 来源与稳定注册维度，仅归并自身 DOMAIN_PRIVATE 范围；不作为后台 SERVICE 入口，不自动执行模型评估。后者用稳定 blockId + revision/authorization revision 游标读取完整历程，返回不可变快照与独立 asOf/matter 有效状态；检索接入后也须由同一 blockId 解析详情，物理片段不形成新偏好。`PreferenceCurrentState` 新增显式 polarity，有限 Double 增加独立规范 hash 类型标签；启用前 caller/provider 须统一当前 V2 制品。其他主体、共享偏好和 Agent 装配仍未接通。

随后接入 Memory 内部偏好异步消费者（7.2），无新增 RPC 或 USER/SERVICE proof 放宽。已可靠接收的精确事件经过当前 SERVICE 加工映射、来源及可信 caller 校验，事务外调用无工具模型，结果先可靠保存，再微批归并；重启不重复评估或观察。`MEMORY_V2_PREFERENCE_PROCESSING_ENABLED` 默认 false，模式默认 SHADOW；启用还要求 archive=true、模型和真实密钥，以及 Nacos/部署配置中的 `memory.v2.preference.processing.allowed-agents-by-domain` 精确映射。SHADOW 只存评估；PUBLISH 才创建私有偏好和 INDEX_PREFERENCE 待办。普通交付/逐 delta 路径不调用评估，索引可用状态仍独立。

2026-10-20 大结果/实体摘要模块增加摘要 `toolResults` 锚点列表（schema `summary.v2.1`）与实体自身内容 hash（`entity.v2.1`）。缺失 `toolResults` 的旧 JSON 按空列表读取，空列表的既有摘要内容 hash 保持不变；新增列表参与摘要 hash。V2 请求签名对完整 record 字段计算，因此所有尚未启用的 V2 caller/provider 必须随本批同步升级，不允许旧请求制品混用。v1 DTO 与端点不变，无新增 SQL 迁移。

Supervisor 仅在 V2 工作集成功接入时提供只读 `memory_read_result` 控制工具；它读取既有 RECEIVED checkpoint 来源，始终复核当前授权，不通过新增 RPC 或任意 URL 读取。`AGENT_MEMORY_V2_TOOL_INLINE_CHARS` 默认 4096、范围 256–16384；`AGENT_MEMORY_V2_RECALL_CHARS` 默认 8192、范围 2048–16384，限制整份补忆 JSON 响应，单页内容 limit 最大 4096 UTF-16 单元。完整档案 DTO 不被引用视图替换。开发启用状态与证据另见本轮压缩模块验收记录。

1. v2 operation 命名空间 `memory.*` 与 v1 REST 路径 `/internal/memory/...` 不相交（改 REST 路径或 operation 命名需先更新 `MemoryV1V2CompatibilityTest`）。
2. v2 请求/响应 DTO 不嵌任何 `com.bkanent.common.agent` v1 类型——v2 写路径结构上无法路由进 legacy 表，**不存在静默双写**。
3. v1 形状保持 v1 字段集（无 historyPage/coveredRange/grant 等 v2 语义字段混入）。
4. 数据层迁移属任务组 4（显式 manifest），不在 v2 契约层做任何 DDL。

## 5. 失败差异（发布语义）

| 场景 | v1 行为 | v2 行为（设计） |
| --- | --- | --- |
| 版本不兼容 | 无版本协商，形状漂移静默 | 构造期 `UNSUPPORTED_VERSION` 拒绝 |
| 授权失败 | REST 401/403 | `MemoryException(AUTHENTICATION_FAILED/FORBIDDEN)`，无 provider 文本 |
| 依赖不可用 | 503 空体（见 KI-126） | `MEMORY_UNAVAILABLE` 枚举，服务端日志留根因 |
| 压缩未确认 | 无此场景 | `ARCHIVE_NOT_CONFIRMED`，旧窗口保留 |
