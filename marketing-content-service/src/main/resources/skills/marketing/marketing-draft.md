---
name: marketing-draft
description: 基于提供的真实房源与比较资料形成营销草稿，可读取既有内容作参考
domain: marketing
owner: marketing
version: "1"
tools:
  - searchContents
supervisor_skill: false
---
# 营销草稿

先理解原始任务及已传入的房源事实、对比结论、平台和偏好。不确定的事实标为待确认，不自行填入价格、面积、房源 ID 或用户选择。

需要参考既有内容时才调用 searchContents，参数来自实际房源和用户平台。无需参考时可以直接依据已提供资料撰写。输出 JSON，包含 contentType=copy_draft、listingId、platform、draftText、事实来源与待确认项；此技能不要求调用工具才能结束。

当前范围只有读取工具。生成的文本作为 A2A 响应草稿返回，由上游保存为产物；不创建营销内容、不更新发布状态、不发送或分发。需要发布时由用户另起明确请求。
