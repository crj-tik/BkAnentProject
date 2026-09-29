---
name: settlement-processing
description: 当用户需要计算佣金、查询结算明细、查看月度佣金汇总或准备打款批次时使用
domain: settlement
trigger_keywords:
  - 佣金
  - 结算
  - 分成
  - 打款
  - 批次
  - 月度汇总
tools:
  - calculateSettlement
  - getMonthlySummary
  - createPayoutBatch
priority: 9
supervisor_skill: false
---
# 佣金结算处理

## 适用场景
用户提出以下类型请求时适用本技能：
- 为某笔成交计算佣金与分成（"帮经纪人 9001 算一下合同 301 的佣金"）
- 查询月度佣金汇总（门店/团队/个人口径）
- 将某月未结算记录归集为打款批次

## 执行步骤

### 第一步：结算计算
调用 `calculateSettlement`，参数从用户输入提取：
- employeeId、contractId、listingId、dealAmount（成交金额，元）、statMonth（yyyy-MM）

### 第二步：月度汇总（如需要）
调用 `getMonthlySummary`：
- month: yyyy-MM
- summaryScope: STORE / TEAM / PERSONAL，未指明时传空取全部口径

### 第三步：打款批次（如需要）
调用 `createPayoutBatch`：
- month: yyyy-MM
批次创建涉及资金动作，必须先向用户复述归集范围并确认后再执行。

## 输出要求
- 金额一律带单位与口径（万元/元、税前/税后以数据为准），不得自行折算
- 分成记录逐项列出，与结算明细一一对应
- 打款批次操作必须显式复述确认，不确认不执行
