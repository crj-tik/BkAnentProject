# Interview 浏览器凭据领取与请求契约

本文是 Interview REST/SSE 客户端集成约定。请求经 Gateway 到达 `interview-service`；Gateway 负责验证用户登录并为实际请求签署授权上下文，Interview 在本地验签。前端不得发送自称的 `userId`、角色、管理员标记或权限列表来声明身份。

## 领取场次凭据

```http
POST /interviews/{sessionId}/credentials
Authorization: Bearer <当前登录用户访问令牌>
Content-Type: application/json
```

请求体中的 `purpose` 必须是 `MANAGE`、`RESPOND` 或 `READ`。服务按已签名 actor 和当前访谈关系/操作授权确认领取资格。成功响应为 `ApiResponse` 包装，数据字段包括 `credentialId`、`credential`、`purpose`、`expiresAt` 和 `headerName`（值为 `X-Ticket`）；响应带 `Cache-Control: no-store` 与 `Pragma: no-cache`。服务只保存 256 位随机凭据的 SHA-256 摘要，默认有效期 15 分钟且配置不能延长超过 15 分钟。

开台和 A2A 工具只提供场次导航及领取路径，不返回凭据原文。页面刷新时，仍登录的用户重新调用领取接口获取新凭据。浏览器应只把凭据保存在当前页面内存中，不写入 URL、localStorage、日志、埋点或持久化状态。

## 访问会话资源

每个受保护会话请求都继续携带登录访问令牌，并把场次凭据放在 `X-Ticket` 请求头。服务按可信 actor、session 和精确用途检查凭据；数据库中的所有者、参与关系、操作码及业务状态仍是最终资源授权依据。

| 用途 | 典型入口 |
|---|---|
| `MANAGE` | 创建/确认题单、启动会话、协助指令 |
| `RESPOND` | `POST /interviews/{sessionId}/turns` |
| `READ` | 会话监控、回复领取和会话 SSE |

`X-Ticket` 不接受在 query string、path、prompt 或普通业务参数中传递。包含 `ticket`、`session_ticket`、`participant_ticket`、`access_token`、`refresh_token`、`authorization`、`authz`、`proof`、`request_proof` 或 `credential` 等凭据键的 query 请求会被拒绝。凭据无效、身份/用途/场次不匹配或授权关闭时，访问失败；调用方应在仍有登录态时重新领取，不要重试旧凭据。

## SSE 客户端

浏览器原生 `EventSource` 无法设置 `Authorization` 和 `X-Ticket` 自定义请求头，因此应使用 `fetch` 与 `ReadableStream` 读取流，并在请求头中同时传入登录访问令牌和 `X-Ticket`。`POST /interviews/{sessionId}/turns` 的事件流和 `GET /interviews/{sessionId}/stream` 都遵守同一凭据规则。长连接会在凭据撤销或到期后停止；客户端应关闭 reader 并要求用户重新领取。

本仓库没有 Interview 浏览器 UI，故此文描述服务端已实现的调用契约；真实页面的领取、内存态保存、fetch 流解析及到期恢复仍需由前端仓库完成并验收。
