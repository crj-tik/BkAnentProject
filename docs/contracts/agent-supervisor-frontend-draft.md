# Agent 对话与 Supervisor 请求参数（源码核对稿）

统一入口见[前后端接口契约 v1](README.md)，Studio 参数另见 [Studio API](../studio-v2-api.md)。本文覆盖 `AgentController` 的对话、任务、工作流、审批、事件流、MCP 和 RAG 请求参数；诊断和治理接口、完整响应及运行行为尚需继续核对。所有路径相对于 Gateway，访问受保护接口须携带 Auth access token。

`userId` 在请求中只是与当前登录主体的一致性断言，不能授予身份或资源访问权。下列 query 参数 `userId` 可以省略；提供非空值时必须与当前登录主体一致，见 KI-255。

## 对话、任务和工作流

| 方法和路径 | 请求参数 |
| --- | --- |
| `POST /agent/chat` | 必填 JSON body `AgentChatRequest` |
| `POST /agent/supervisor/tasks`、`/agent/supervisor/tasks/async` | 必填 JSON body `SupervisorTaskRequest` |
| `POST /agent/supervisor/workflows`、`/agent/supervisor/workflows/async` | 必填 JSON body `SupervisorTaskRequest` |
| `GET /agent/supervisor/tasks/async/status`、`/agent/supervisor/tasks/async/stream` | 必填 query `asyncTaskId:string`，可选 `userId:string`；stream 返回 SSE |
| `GET /agent/supervisor/workflows/async/status` | 必填 query `asyncWorkflowId:string`，可选 `userId:string` |
| `GET /agent/supervisor/workflows/async/stream` | 必填 query `asyncWorkflowId:string`；可选 query `userId:string`、`afterEventId:string`、`afterSequence:int64` 和 header `Last-Event-ID:string`；返回 SSE |
| `POST /agent/supervisor/workflows/async/cancel`、`/agent/supervisor/workflows/async/retry` | 必填 query `asyncWorkflowId:string`，可选 `userId:string`；无 body |
| `POST /agent/supervisor/runs/cancel`、`/agent/supervisor/runs/reconcile` | 必填 query `runId:string`，可选 query `userId:string`；无 body |
| `GET /agent/supervisor/workflows/state`、`/agent/supervisor/workflows/artifacts` | 必填 query `taskId:string`，可选 `userId:string` |
| `GET /agent/supervisor/stream` | 必填 query `sessionId:string`；可选 query `taskId:string`、`afterEventId:string`、`afterSequence:int64` 和 header `Last-Event-ID:string`；返回 SSE |

`AgentChatRequest={userId:string,message:string,collectionName:string,topK:int32,allowMcp:boolean,skill:SkillSelection,continueRunId:string,sessionId:string,requestId:string}`。`userId` 可省略；提供时与已认证主体不一致会被拒绝。其余字段的具体场景与运行校验仍以服务实现为准。

`SupervisorTaskRequest={sessionId:string,userId:string,requestId:string,traceId:string,userMessage:string,context:object,channel:string,stream:boolean,skill:SkillSelection,continueRunId:string,allowMcp:boolean,matterIntent:MemoryMatterIntent}`。`userMessage` 须非空白；`userId` 可省略且提供时须匹配已认证主体。`sessionId` 和 `requestId` 缺省时由服务端生成；续接会校验原执行的 owner 和 session。`context.userId` 由服务端覆盖为可信主体。

`SkillSelection={name:string,version:string,contentHash:string,owner:string}`，`name` 须非空白。`MemoryMatterIntent={action,matterId,expectedRevision,goal}`：`action` 为 `START`、`CONTINUE`、`CLOSE`、`REOPEN`；`START` 要求 `matterId=null`、`expectedRevision=0` 和非空 `goal`（最多 2000 字符）；其他动作要求 `matterId`、正数 `expectedRevision` 和 `goal=null`。

两种可续订的事件流均优先使用非空白 `afterEventId`，否则使用 `Last-Event-ID`；`afterSequence` 是独立的可选序号。当前版本化授权上下文下，部分取消、重试及恢复控制因缺少固定原始执行绑定而拒绝，不能仅凭接口映射宣称可执行。

## 审批

| 方法和路径 | 请求参数 |
| --- | --- |
| `GET /agent/supervisor/approvals/pending` | 可选 query `userId:string`、`page:int32`、`pageSize:int32`；默认 1 和 20，服务端将页码限制为至少 1、页大小限制为 1–100 |
| `POST /agent/supervisor/approvals/callback` | 必填 JSON body `ApprovalCallbackRequest` |

`ApprovalCallbackRequest={approvalId:string,taskId:string,sessionId:string,status:ApprovalStatus,reviewerId:string,feedback:string,traceId:string,approvalVersion:int32}`。`approvalId`、`taskId` 须非空白；`status` 须为 `APPROVED`、`REJECTED` 或 `TERMINATED`，不能为 `PENDING` 或 null。`reviewerId` 提供时须与已认证主体一致；任务归属、审批版本和状态由服务端复核。

## MCP 与 RAG

这些入口中的 query `userId` 均可省略；提供非空值须与认证主体一致。表中权限码是当前控制器的操作权限检查，还需满足下游范围和运行配置；列出映射不表示目标环境已启用。

| 方法和路径 | 请求参数及控制器权限 |
| --- | --- |
| `GET /agent/mcp/tools` | 可选 query `userId:string`；`agent.mcp.tools.read`，返回工具还按调用权限过滤 |
| `GET /agent/mcp/tools/catalog` | 可选 query `userId:string`；`agent.mcp.catalog.read`，返回工具还按调用权限过滤 |
| `GET /agent/mcp/tools/discovered`、`/agent/mcp/tools/registered` | 可选 query `userId:string`；`agent.mcp.debug.read`，返回工具还按调用权限过滤 |
| `GET /agent/mcp/servers/status` | 可选 query `userId:string`；`agent.mcp.debug.read`，返回服务还按读取权限过滤 |
| `GET /agent/mcp/dynamic/connections` | 可选 query `userId:string`；`agent.mcp.debug.read` |
| `POST /agent/mcp/dynamic/connect` | 可选 query `userId:string`，必填 JSON body `DynamicMcpConnectionConfig`；`agent.mcp.admin.manage` |
| `DELETE /agent/mcp/dynamic/connect/{name}` | 必填 path `name:string`，可选 query `userId:string`；`agent.mcp.admin.manage` |
| `GET /agent/vector-store/milvus/search`、`/agent/mcp/milvus/search` | 必填 query `query:string`，可选 `userId:string`；`agent.rag.memory.search` |
| `POST /agent/vector-store/milvus/collections/init`、`/agent/mcp/milvus/collections/init` | 可选 query `userId:string`，必填 JSON body `MilvusCollectionInitRequest`；`agent.rag.memory.init` |
| `POST /agent/rag/listings/index` | 必填 JSON body `ListingIndexRequest`；`agent.rag.listing.index` |
| `POST /agent/rag/listings/query` | 必填 JSON body `ListingRagQueryRequest`；`agent.rag.listing.query` |

`DynamicMcpConnectionConfig={name:string,type:string,command:string,args:string[],env:object<string,string>,url:string}`。`name` 必须非空白、前后无空白、不含控制字符且长度不超过 100，并且不能与现有动态连接或保留的静态连接同名；`type` 为 `STDIO` 或 `STREAMABLE`，大小写不敏感。`STDIO` 必须提供非空白 `command`，`args`、`env` 可省略；`STREAMABLE` 必须提供带主机名的 HTTP 或 HTTPS `url`。建立连接需要实际初始化服务，注册成功不等于永久可用。

`MilvusCollectionInitRequest={collectionName:string,dimension:int32}`。`dimension` 必须为正整数且与下游空间维度一致；`collectionName` 省略或空白时使用 `supervisor.knowledge`，也可传兼容别名 `agent_knowledge`。当前实现还识别 `listing_knowledge`、`user_preference_knowledge`、`system_constraint_knowledge` 及其逻辑空间名；是否允许使用由下游授权和配置决定。搜索入口不接收 `collectionName` 或 `topK` 参数，固定使用默认空间与 5 条上限；`query` 须非空白且不超过 8192 字符。

`ListingIndexRequest={userId:string,listingId:int64,collectionName:string}`：`userId` 可省略或须与主体一致；`listingId` 必须为正整数。`collectionName` 省略或空白时使用 `listing.knowledge`，提供时只允许 `listing.knowledge` 或兼容别名 `listing_knowledge`。

`ListingRagQueryRequest={userId:string,query:string,topK:int32,keywordTopK:int32,vectorTopK:int32,collectionName:string,region:string,minTotalPrice:decimal,maxTotalPrice:decimal,layout:string,minArea:decimal,maxArea:decimal}`。`userId`、`collectionName` 的规则同上；`query` 须非空白且不超过 8192 字符。`topK` 范围 1–200，`keywordTopK` 和 `vectorTopK` 范围均为 1–500，两者之和不得小于 `topK`；省略时使用服务配置，代码默认分别为 10、20、20，部署配置可覆盖。其他筛选字段可省略；具体检索结果和访问范围由房源域校验。
