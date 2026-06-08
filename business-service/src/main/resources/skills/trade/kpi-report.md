---
name: trade-kpi-report
description: 生成月度/季度KPI绩效报告，包含关键指标分析、排名和同比环比变化
domain: trade
trigger_keywords:
  - KPI
  - 绩效
  - 月度报告
  - 季度报告
  - 业绩数据
  - 关键指标
  - 成交量
  - 成交额
  - 转化率
  - 带看量
tools:
  - queryMonthlyKpis
  - calculateKpiAssessments
  - queryRankings
priority: 10
supervisor_skill: false
---
# KPI 绩效报告生成

## 触发条件
用户要求查看月度/季度/年度绩效数据、KPI指标、业务员业绩排名时触发。

## 执行步骤

### 第一步：获取KPI基础数据
调用 `queryMonthlyKpis`，参数从用户输入中提取：
- month: 月份，格式 YYYY-MM
- dept: 部门名称（如未指定，使用当前用户所属部门）

### 第二步：计算评估分析
调用 `calculateKpiAssessments`，传入第一步的结果数据进行评估：
- 完成率计算
- 同比/环比变化
- 异常指标标记（低于阈值或高于阈值）

### 第三步：获取排名（可选）
如果用户要求排名，调用 `queryRankings`：
- 支持按成交量、成交额、转化率等维度排名

## 输出格式
返回结构化的KPI报告，包含：

```json
{
  "period": "2025-06",
  "summary": {
    "totalVolume": "成交量总计",
    "totalAmount": "成交额总计",
    "avgConversionRate": "平均转化率"
  },
  "comparison": {
    "monthOverMonth": "+5.2%",
    "yearOverYear": "+12.1%"
  },
  "rankings": [
    {"rank": 1, "name": "张三", "volume": 12, "amount": 5800000}
  ],
  "anomalies": [
    {"metric": "带看转化率", "value": "2.1%", "threshold": "5%", "level": "warning"}
  ],
  "recommendations": ["建议增加周末带看排班", "A组转化率低于平均水平，建议分析原因"]
}
```
