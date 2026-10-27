---
name: listing-compare-marketing-draft
description: 按真实找房结果进行房源对比，再形成营销草稿；仅接受本次请求显式指定
domain: supervisor
owner: supervisor
version: "1"
explicit_only: true
supervisor_skill: false
capabilities:
  policy: allowlist
  refs:
    - a2a:listing-agent
    - a2a:marketing-agent
---
# 找房、对比与营销草稿

先理解原始请求中的地区、预算、户型、营销平台和选择偏好。有冲突先说明；缺少关键条件时单独调用 request_input，不编造地址、房源 ID、平台或用户选择。技能是模型执行指引，平台不校验本文的业务顺序。

通过 a2a:listing-agent 查询候选房源。instruction 写清自然语言条件，context 保留已确认的结构化条件；需要其本地找房流程时，通过实际 Card 发布映射显式选择 listing-search（版本以目录为准）。父技能名称不能当成子技能名称。准备比较时再显式选择 listing-comparison，并保留实际响应中的稳定 listingRef、revision、contentHash 与 dataMode；不能把旧 Long ID 或模型推测当作跨来源身份。

至少取得两套候选及其完整来源版本后，通过 a2a:listing-agent 的 listing-comparison 技能创建比较。只传递实际返回的稳定引用和预期 revision；等待来源当前版本复核成功后再决定要介绍哪套房源。`SIMULATED` 或 `UNKNOWN` 数据必须照实标注；候选不足、授权缺失、来源过期或依赖不可用时停止或向用户补充条件，不生成虚构比较，也不重发未确认的旧 Compare 调用。

通过 a2a:marketing-agent 形成草稿，显式选择其 Card 发布的 marketing-draft 技能；context 传入已确认的平台、真实选中房源及比较结果，instruction 要求输出 contentType=copy_draft、draftText、platform、listingId 和事实来源。此子技能只提供读取既有内容的能力；不创建发布任务，也不调用 publishContent。缺少营销平台或待推广房源时先通过 request_input 询问用户。

最终给出候选、比较依据和草稿，区分真实结果与文案建议；状态必须表示草稿，不能声称已发布。正文中的依赖由模型遵循，平台仅校验实际工具范围、参数、身份、权限、预算和审批。
