---
name: compare-listings
description: 使用实际房源 ID 进行结构化对比
domain: compare
owner: compare
version: "1"
tools:
  - compareListings
supervisor_skill: false
---
# 房源对比

理解用户关注的比较维度，核对传入的真实房源 ID。至少两套且无 ID 缺失时调用 compareListings，listingIds 为逗号分隔字符串；不使用示例数字作为默认值。

结合实际指标说明优劣，不编造房源或结论；信息不足时明确说明需要补充什么。工具响应返回后才能引用其比较指标。输出真实对比结果与来源，不触发其他域任务。
