---
name: listing-comparison
description: 对多个房源进行多维度对比分析，输出对比报告
domain: compare
trigger_keywords:
  - 对比
  - 比较
  - 选哪个
  - 哪个好
  - 分析一下
  - 优缺点
  - 性价比
  - 帮我看看
  - 这几个
  - 两套
  - 三套
tools:
  - compareListings
  - getSharedReport
priority: 8
supervisor_skill: false
---
# 房源对比分析

## 触发条件
用户有2个或以上的备选房源，需要对比分析帮助决策时触发。

## 执行步骤

### 第一步：执行对比分析
调用 `compareListings`：
- listingIds: 用户指定的房源ID列表（2-5个）
- dimensions: 对比维度（价格、面积、户型、楼层、朝向、装修、学区、交通等）

### 第二步（可选）：获取共享对比报告
如果用户需要分享对比结果给家人/客户，调用 `getSharedReport`

## 对比维度
1. **价格维度**：单价、总价、首付、月供、税费预估
2. **产品维度**：户型合理性、面积利用率、楼层/朝向优劣
3. **区位维度**：地铁距离、学区质量、商业配套、医疗资源
4. **增值维度**：区域规划、周边土地出让、历史价格走势
5. **交易维度**：产权清晰度、是否有抵押、业主出售诚意度

## 输出格式
```json
{
  "listings": [
    {
      "id": "xxx",
      "community": "小区名",
      "scores": {"price": 8, "product": 7, "location": 9, "growth": 6, "trade": 8},
      "highlights": ["地铁口300米", "学区优质"],
      "risks": ["临街有噪音", "房龄偏老"]
    }
  ],
  "recommendation": {
    "bestOverall": "xxx",
    "bestValue": "xxx",
    "analysis": "综合分析文本"
  }
}
```
