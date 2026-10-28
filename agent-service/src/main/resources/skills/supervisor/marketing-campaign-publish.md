---
name: marketing-campaign-publish
description: 在内容审核和 Supervisor 明确批准下完成营销活动发布与回执核对
domain: supervisor
owner: supervisor
version: "1"
explicit_only: true
supervisor_skill: false
capabilities:
  policy: allowlist
  refs:
    - a2a:marketing-agent
    - mcp:marketing-mcp-server:createMarketingContentDraft
    - mcp:marketing-mcp-server:prepareMarketingCampaign
    - mcp:marketing-mcp-server:confirmMarketingCampaign
    - mcp:marketing-mcp-server:getMarketingCampaignReceipt
    - mcp:marketing-mcp-server:reconcileMarketingCampaignAttempt
---
# 营销活动发布

先确认用户指定的房源、渠道、目标账号和发布参数。缺少内容、平台、账号或参数时，通过 `request_input` 询问，不猜测授权账号或发布范围。

需要生成文案时，通过 `a2a:marketing-agent` 显式选择其 `marketing-draft` 技能。该 A2A 技能只读取既有内容并生成未保存的草稿，不创建发布任务，也不发布。使用 `createMarketingContentDraft` 保存为营销内容草稿，并记录每个返回项的 `contentId`、`platform` 和 `status`。若响应没有内容 ID 或状态不是 DRAFT，停止并报告问题。

发布前必须由有权限的内容审核人通过现有内容审核入口将对应内容审核为 APPROVED。草稿保存或模型生成不代表审核通过；本技能没有审核权限，不得伪造审核结果。无法确认审核状态时停止并请求审核人完成审核。

仅对已审核内容调用 `prepareMarketingCampaign`。向用户展示返回的内容、目标渠道和账号、参数、数据模式、计划版本、快照摘要及过期时间。准备计划没有外部发布副作用。等待用户明确确认这一份具体计划后，才可调用 `confirmMarketingCampaign`，并把确认请求 ID、计划版本和快照摘要原样传入。

`confirmMarketingCampaign` 受 Supervisor `approval-capabilities` 治理；工具调用必须等待 Supervisor 批准。拒绝、等待批准、计划版本变化、快照不匹配或过期都意味着不得发布。不能通过重建工具调用、换参数或改用其它入口绕过审批。

确认成功后，使用返回的 receipt ID 调用 `getMarketingCampaignReceipt` 查询各目标状态。若某目标状态为 `OUTCOME_UNKNOWN`，只对该原 attempt 调用 `reconcileMarketingCampaignAttempt`；禁止创建新确认请求或重发该目标。发布状态与成功只能以 provider receipt 为证据。除非存在真实渠道回执，本技能不得声称已发布，也不得声称已发送通知。
