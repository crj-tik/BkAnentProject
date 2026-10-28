---
name: listing-comparison
description: 当用户需要基于真实房源来源版本比较两套或更多房源，并查看受权历史报告时使用
domain: listing
trigger_keywords:
  - 对比房源
  - 比较房源
  - 哪套更合适
  - 性价比对比
tools:
  - searchBusinessListings
  - createListingComparison
  - getListingComparisonReport
priority: 10
supervisor_skill: false
---
# 房源版本比较

## 执行步骤

1. 使用 `searchBusinessListings` 从当前显式配置的来源查询候选。保留每条结果的完整 `listingRef`、`revision`、`contentHash` 和 `dataMode`。
2. 只有取得至少两套真实来源结果且用户条件足够时才能创建报告。向 `createListingComparison` 传入原样复制的 `ListingSourceVersionRef` JSON 数组，包含每套房源的 `listingRef` 和 `expectedRevision`。
3. 等待来源批量读取、当前版本核验和授权复核均成功后，再引用报告中的事实、模式标记和规则版本。来源过期、无权或依赖不可用时停止，不使用旧报告、旧 ID 或模拟数据替代。
4. 历史报告只能由所有者通过 `getListingComparisonReport` 读取；来源或权限改变后按返回状态停止使用。

## 事实边界

- `SIMULATED` 必须明确称为模拟数据；`UNKNOWN` 表示来源性质未获确认；多种模式合并时按 `MIXED` 呈现。
- 金额使用显式 `CNY/YUAN`，面积使用平方米。不得重复进行万元到元换算。
- `unknownFields` 中的字段应说明未知，不得让模型补造事实。
- 比较结论只能根据报告事实计算。未经来源接口确认，不宣称价格、可售状态或核验状态已被更新。
- 报告为私有，不生成匿名分享链接，不把旧分享码当作读取授权。
