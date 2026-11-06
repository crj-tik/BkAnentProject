---
name: consultation-listing-comparison
description: 买方咨询中匹配多套房源并对比，以卡片结构返回结果并留痕到咨询；仅接受本次请求显式指定
domain: supervisor
owner: supervisor
version: "1"
explicit_only: true
supervisor_skill: false
capabilities:
  policy: allowlist
  refs:
    - a2a:listing-agent
---
# 买方咨询房源对比

先理解买方咨询上下文：优先使用已确认的客户档案偏好（区域、户型、预算）或本次咨询记录中的条件；无档案时按经纪人当场描述执行，不虚构偏好。条件缺失或冲突时先说明，或单独调用 request_input 补充，不编造地址、房源 ID 或用户选择。

通过 a2a:listing-agent 查询候选房源：instruction 写清自然语言条件，context 保留已确认的结构化条件；需要其本地找房流程时，通过实际 Card 发布映射显式选择 listing-search（版本以目录为准）。保留实际返回的稳定 listingRef、revision、contentHash 与 dataMode；不能把旧 Long ID 或模型推测当作房源身份。

从真实候选中选择 2–6 套进入对比：按买方偏好匹配度优先；候选超过 6 套时向用户确认取舍，不擅自删减。候选不足两套时如实说明并补充条件，不生成虚构比较。

准备比较时再显式选择 a2a:listing-agent 的 listing-comparison 技能创建比较：只传递实际返回的稳定引用与预期 revision；等待来源当前版本复核成功后再呈现。版本冲突（搜索与比较之间房源变化）时重新搜索一次；仍冲突则向用户如实说明，不使用过期数据完成比较。

呈现对比结果：卡片数据原样透传（cards、metricWinners 与报告结论），不得增删改任何事实字段；文字部分只做简短引导（如"为您对比了 3 套，总价最低的是……"）。agent 的选房理由与推荐语只写在文字里，不写入卡片结构；报告标注的未知字段如实转述，不补值。`SIMULATED` 或 `UNKNOWN` 数据必须照实标注。

留痕：回答中给出对比报告 reportId 与本次咨询的咨询 ID（若存在），由调用方经客户域关联接口（POST /customers/inquiries/{咨询ID}/comparison-reports）将报告关联到咨询；agent 不直接写客户域。无咨询记录且用户未要求留痕时跳过此步。

最终回答包含：候选卡片对比、确定性结论、reportId 与文字引导。候选不足、授权缺失、来源过期或依赖不可用时停止或向用户补充条件，不降级、不使用模拟数据替代。正文中的依赖由模型遵循，平台仅校验实际工具范围、参数、身份、权限、预算和审批。
