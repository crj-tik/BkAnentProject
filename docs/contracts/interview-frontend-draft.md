# 访谈接口请求参数（源码核对稿）

统一入口见[前后端接口契约 v1](README.md)。本文按当前 `InterviewController`、`InterviewAssetController` 的 Gateway 路径和请求模型整理；响应完整 schema、全部异常与目标环境运行情况仍需验收。访问受保护入口时携带 Auth access token；场次级票据的领取、用途和保存规则见[浏览器凭据契约](../interview-credential-client-contract.md)。

所有 `sessionId`、`credentialId`、`taskId`、`assetId` 路径参数均为 int64；`reqId` 为字符串。下表的 `MANAGE`、`RESPOND`、`READ` 指 `X-Ticket` 的用途，另需对应用户的操作权限和场次关系。

## 场次、参与人和票据

| 方法和路径 | 请求参数 |
| --- | --- |
| `GET /interviews` | 可选 query `status:string`；结果最多 100 条，按当前用户可信可见范围过滤 |
| `POST /interviews` | 必填 JSON body `OpenCaseRequest` |
| `GET /interviews/participants/invitations` | 无参数；读取当前用户的待确认邀请 |
| `POST /interviews/{sessionId}/participants/respond/invite` | 必填 body `{userId:int64}`；`userId` 须为正数且是被邀请目标 |
| `POST /interviews/{sessionId}/participants/respond/confirm` | 无 body；由受邀用户确认自己的关系 |
| `POST /interviews/{sessionId}/credentials` | 必填 body `{purpose:string}`，值为 `MANAGE`、`RESPOND` 或 `READ` |
| `DELETE /interviews/{sessionId}/credentials/{credentialId}` | 两个路径参数；无 body |
| `GET /interviews/{sessionId}` | `X-Ticket: <READ 凭据>` |
| `POST /interviews/{sessionId}/start` | `X-Ticket: <MANAGE 凭据>`；无 body |

`OpenCaseRequest={scene:string,respondentRole:string,objective:string,divisionName:string,regionName:string,businessDistrict:string,caseStatus:string,referenceMinutes:int32,creatorWorkNo:string,respondentUserId:int64}`。`scene` 必须为 `STORE_MANAGER`、`AGENT_DEAL`、`SECOND_HAND_PARTY`、`NEW_HOUSE_FIELD` 或 `COMMUNITY_EXPERT`。`SECOND_HAND_PARTY` 的 `respondentRole` 必须为 `OWNER`、`OWNER_REVISIT`、`CUSTOMER` 或 `CUSTOMER_DECIDING`；`NEW_HOUSE_FIELD` 必须为 `MARKETING_DIRECTOR`、`FIELD_OP`、`CONSULTANT` 或 `CUSTOMER`。`respondentUserId` 可省略，提供时必须为正数并表示邀请目标；`creatorWorkNo` 是业务元数据，不建立调用者身份。`caseStatus` 为 null 时保存为 `active`；`referenceMinutes` 为 null 时题目预算按 30 分钟计算。

## 题目和话轮

| 方法和路径 | 请求参数 |
| --- | --- |
| `POST /interviews/{sessionId}/questions` | `X-Ticket: <MANAGE 凭据>`；必填 body `{questions:QuestionDraftItem[]}`，数组不能为空且元素不能为 null |
| `POST /interviews/{sessionId}/questions/confirm` | `X-Ticket: <MANAGE 凭据>`；必填 body `{questionIds:int64[]}`，数组非空、不能重复且各题属于本场案例 |
| `POST /interviews/{sessionId}/turns` | `X-Ticket: <RESPOND 凭据>`；可选 header `X-Req-Id:string`；必填 body `{content:string,idempotencyKey:string}`；响应为 `text/event-stream` |
| `GET /interviews/{sessionId}/replies/{reqId}` | `X-Ticket: <READ 凭据>`；`reqId:string` 是原话轮请求编号 |
| `GET /interviews/{sessionId}/stream` | `X-Ticket: <READ 凭据>`；响应为 `text/event-stream` |
| `POST /interviews/{sessionId}/director-commands` | `X-Ticket: <MANAGE 凭据>`；必填 body `{command:string,pinnedQuestion:string}` |

`QuestionDraftItem={content:string,focusLabel:string,riskHint:string,core:boolean}`；`core` 为 null 或省略时按 `false` 处理。导演命令 `command` 为 `WRAP_UP`、`NEXT_QUESTION` 或 `PINNED_QUESTION`；最后一种要求 `pinnedQuestion` 非空白，且内容不得含凭据形态。

话轮使用非空白 `X-Req-Id` 优先，其次使用 body 中非空白 `idempotencyKey`；均未提供时由服务端生成 UUID。前端需要断线后按原请求查询回执时，应自行提供并保存原编号；缺失回执时不能随意换新编号重发。请求编号不得包含凭据形态。回执持久化及断线限制见 KI-12。

## 报告与访谈资料

| 方法和路径 | 请求参数 |
| --- | --- |
| `GET /interviews/reports/tasks` | 可选 query `creatorWorkNo:string`、`status:string`、`page:int32`、`pageSize:int32` |
| `GET /interviews/reports/tasks/{taskId}` | `taskId:int64` |
| `GET /interviews/reports/search` | 可选 query `keyword:string`、`creatorWorkNo:string`、`page:int32`、`pageSize:int32` |
| `POST /interviews/assets` | 必填 JSON body `{caseId:int64,title:string,content:string}` |
| `POST /interviews/assets/{assetId}/publish` | `assetId:int64`，无 body |
| `POST /interviews/assets/{assetId}/revoke-publication` | `assetId:int64`，无 body |
| `POST /interviews/assets/{assetId}/regrade` | `assetId:int64`，无 body |

报告列表和搜索默认 `page=1`、`pageSize=20`，服务端将页码限制为至少 1、页大小限制为 1–100；搜索 `keyword` 为空白时返回空列表。报告范围由可信场次关系或已授权的业务范围决定，`creatorWorkNo` 只是筛选条件。提交逐字稿时 `caseId` 必须对应当前调用者获准的案例，`content` 非空白且最多 1,000,000 字符；`title` 可省略，为空时使用“外部提交逐字稿”。发布、撤回和评级均需相应授权与资源状态。
