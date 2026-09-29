---
name: notification-sending
description: 当用户需要发送通知、发站内信、发邮件提醒、给经纪人或客户推送消息，或查询某人未读消息时使用
domain: notification
trigger_keywords:
  - 通知
  - 站内信
  - 邮件
  - 提醒
  - 发消息
  - 未读
tools:
  - sendStationMessage
  - sendEmailMessage
priority: 9
supervisor_skill: false
---
# 消息通知发送

## 适用场景
用户提出以下类型请求时适用本技能：
- 给指定用户发送站内信（"通知经纪人 9001 明天上午带看"）
- 给指定用户发送邮件通知（需要对方邮箱）
- 查询某用户的消息列表或未读数

## 执行步骤

### 第一步：确认要素
发送前确认三要素：接收人 userId、标题、正文内容。用户没有明确给出时先补齐，不要代拟关键业务内容后直接发送。

### 第二步：选择通道发送
- 站内信：调用 `sendStationMessage`（userId、title、content）
- 邮件：调用 `sendEmailMessage`（userId、title、content、receiverAddress，邮箱必须由用户提供）

### 第三步：结果反馈
发送成功时返回消息 ID；用户询问消息情况时按需查询列表或未读数。

## 输出要求
- 发送类动作执行前必须复述收件人与内容要点
- 通知失败时如实说明（如邮箱缺失），并给出补救建议
- 不向无关第三人透露消息正文
