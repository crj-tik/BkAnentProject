---
name: platform-operations-kpi-report
description: 汇总指定月份或日期的经营指标、员工工作量、门店概览、房源流转和排行榜
domain: analytics
trigger_keywords:
  - KPI
  - 经营分析
  - 月度指标
  - 员工工作量
  - 门店经营
  - 房源流转
  - 经营排名
tools:
  - queryMonthlyKpis
  - queryListingTurnover
  - getStoreDashboard
  - calculateKpiAssessments
  - queryRankings
  - queryDailyWorkloads
priority: 10
supervisor_skill: false
owner: analytics
version: 1.0.0
explicit_only: true
---
# 平台经营分析

## 执行规则

- 先确认用户要求的月份、日期、门店或排名范围，再调用对应工具获取数据。
- 仅使用工具返回的字段和统计口径；当前能力不提供季度汇总、同比或环比，不得自行推算或编造。
- 数据为空时明确说明没有匹配记录；`UNKNOWN`、模拟或混合来源不得描述为已核实的真实业绩。
- 门店或员工范围必须来自已验证授权上下文。用户输入的门店名只可作为筛选条件，不能建立身份或授权。
- 将指标事实与建议分开，说明时间范围和数据缺口。
