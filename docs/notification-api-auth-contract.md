# Notification API 鉴权契约

## 身份来源与启动边界

- 受保护的 REST 请求必须由 `notification.authorization.enabled=true` 的 ingress filter 建立并验证 Auth 请求上下文。该开关默认关闭；没有已验证上下文时，业务服务返回 `AUTHENTICATION_REQUIRED`。
- Notification MCP 使用逐请求已验证的 Auth context、Agent proof、精确 tool operation 与实际 JSON-RPC body。`mcp.authorization.server.enabled` 默认关闭，启用前必须配置每个工具的精确 operation 映射；MCP 管理连接本身不授予用户消息权限。
- 用户 actor 只从已验证上下文取得。REST 的旧 `userId` 输入仅作可选一致性断言；工具读取不再暴露 `userId` 参数。

## REST 路由

| 路由 | 操作权限 | 资源与目标规则 |
| --- | --- | --- |
| `GET /notifications` | `notification.message.read.own` | 查询始终按 Auth actor 过滤。可选 `userId` 若提供必须等于 actor。 |
| `GET /notifications/unread-count` | `notification.message.read.own` | 统计始终按 Auth actor 过滤。可选 `userId` 若提供必须等于 actor。 |
| `POST /notifications/{id}/read` | `notification.message.mark-read.own` | 可选 body `userId` 只作 actor 一致性断言；消息必须是站内消息且 SQL recipient 必须等于 actor。 |
| `POST /notifications/station` | `notification.message.send.own` 或 `notification.message.send.all` | 自己收件时允许 `.send.own`；发给其他用户要求 `.send.all`。目标 `userId` 不是当前 actor。 |
| `POST /notifications/email` | `notification.email.send.all` | 邮件地址与目标用户是发送目标；当前服务只允许显式 local simulation 模式。 |
| `POST /notifications/robot` | `notification.robot.send.all` | webhook 与渠道是发送目标；当前服务只允许显式 local simulation 模式。 |
| `GET /notifications/workflow-events` | `notification.workflow.events.read.all` | 管理查询全局消费记录，按权限过滤而非使用请求声明的管理员身份。 |

没有通过用户 REST API 暴露工作流事件写入。内部 RocketMQ 工作流事件只接受登记的事件类型、有效任务/会话和服务端注入的正整数 actor；若事件带 `runId`，它必须与 `taskId` 相同。模型 tool 参数不构成此内部事件来源。

## Agent 与 MCP 工具

| 工具 | 操作权限 | 规则 |
| --- | --- | --- |
| `sendMyStationMessage(title, content)` | `notification.message.send.own` | 接收人由 Auth actor 注入。 |
| `sendStationMessage(userId, title, content)` | `notification.message.send.all` | 目标收件人是独立的业务对象。 |
| `sendEmailMessage(userId, title, content, receiverAddress)` | `notification.email.send.all` | 需要精确邮件发送操作。 |
| `sendRobotMessage(channel, webhookUrl, title, content)` | `notification.robot.send.all` | 需要精确机器人发送操作。 |
| `listUserMessages(channel, readStatus)` | `notification.message.read.own` | 方法没有 `userId` 参数，始终查询当前 actor。 |
| `countUnreadMessages()` | `notification.message.read.own` | 方法没有 `userId` 参数，始终统计当前 actor。 |

每个工具调用只在已绑定执行上下文上本地验证短期请求证明、body、nonce、操作与硬截止；内部 Graph 调度和流事件不逐次调用 Auth。权限版本或 freshness 到刷新边界时由 Supervisor/Auth 刷新。

## 兼容与错误

- 未提供有效 Auth 请求上下文：`AUTHENTICATION_REQUIRED`。
- 已认证但没有精确操作权限、声明的 `userId` 与 actor 不同、或目标消息归属不符：`PERMISSION_DENIED`。
- 旧 Dubbo `NotificationRpcService` 三个发送方法没有请求级用户/收件人证明，目前统一返回 `AUTHENTICATION_REQUIRED`。调用方必须迁移到受保护入口；合同/客户定时提醒在可信 recipient 绑定建立前跳过发送，见 KI-206。
- Auth migration 登记操作码但不自动授予角色。管理员可获得已登记操作，普通用户需经受管角色/用户授权流程授予。
- `memory-service` 源配置已登记 `notification-service` caller 与 `notification-agent` 域，但配置登记不代表公钥运行值有效，也不授予 Notification 他人消息权限；真实运行链仍须单独验收。
