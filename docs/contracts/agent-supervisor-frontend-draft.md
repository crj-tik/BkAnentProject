# Agent 对话与 Supervisor 请求参数（源码核对稿）

统一入口见[前后端接口契约 v1](README.md)，Studio 参数另见 [Studio API](../studio-v2-api.md)。本文覆盖 `AgentController` 的对话、任务、工作流、审批及事件流入口；MCP、RAG、诊断和治理接口的完整参数与授权尚需继续核对。所有路径相对于 Gateway，访问受保护接口须携带 Auth access token。

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
