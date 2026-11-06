# 买方咨询对比卡片：前端契约

> 对比卡片是对比报告的确定性投影（与报告行/指标同源同版本），前端只负责呈现，不做任何计算。本文是唯一数据契约；UI 设计（布局、样式、交互）完全由前端决定，数据不含任何 UI 语义。

## 1. 数据结构

对比报告读取响应（`GET /listings/comparisons/{reportId}` 与 agent 对比工具结果）在既有字段（reportId、rulesVersion、createdAt、dataMode、sources、rows、metrics、comparisonTableMarkdown、conclusion、pdfDownloadPath）之上新增：

```jsonc
{
  "reportId": "…",                  // 32 位十六进制；关联咨询与历史回看的句柄
  "rulesVersion": "listing-comparison-v1",
  "dataMode": "UNKNOWN",            // REAL / SIMULATED / MIXED / UNKNOWN，照实标注

  "cards": [                        // ★ 每套被对比房源一张卡（2–6 张）
    {
      "listingRef": { "sourceSystem": "legacy-local", "sourceId": "101" },
      "revision": "…",              // 本卡基于的房源版本
      "contentHash": "…",
      "title": "测试房源 101",
      "totalPrice": { "amount": "3200000", "currency": "CNY", "unit": "YUAN" },  // null=未知
      "areaSquareMetres": "100",    // null=未知
      "unitPriceCnyPerSquareMetre": "32000.00",  // null=缺面积或总价，不可计算
      "attributes": { "layout": "2室1厅", "status": "ON_SALE" },  // 快照属性，有什么显示什么
      "unknownFields": ["schoolZone"],  // ★ 显式未知：渲染"未知"，禁止补值
      "highlights": ["lowestTotalPriceCny", "largestAreaSquareMetres"]  // 命中的确定性徽标
    }
  ],

  "metricWinners": [                // ★ 跨卡确定性指标归属（并列时每个胜出者一条）
    { "metric": "lowestTotalPriceCny", "listingRef": {…}, "value": "3100000" },
    { "metric": "largestAreaSquareMetres", "listingRef": {…}, "value": "100" }
  ],

  "conclusion": "共比较 3 套房源。总价最低：…；面积最大：…",  // 规则生成，非模型生成
  "pdfDownloadPath": "/listings/comparisons/{reportId}/pdf"
}
```

### 指标键（highlights 与 metricWinners.metric 取值）

| 键 | 含义 |
|---|---|
| `lowestTotalPriceCny` / `highestTotalPriceCny` | 总价最低 / 最高（CNY 元） |
| `lowestUnitPriceCnyPerSquareMetre` / `highestUnitPriceCnyPerSquareMetre` | 单价最低 / 最高（元/㎡；缺面积或总价的房源不参与） |
| `largestAreaSquareMetres` | 面积最大（与报告结论同源） |

### 渲染约定

- **一张卡一套房源**：`cards` 数组每项一张卡并排呈现；徽标（highlights）样式由前端设计。
- **未知呈现**：`unknownFields` 列出的字段与 null 值渲染"未知"，不得估算或用其他房源数据填充。
- **单位显式**：金额带币种与单位（CNY/YUAN）；面积为平方米；单价为元/㎡。不需要前端换算。
- **agent 观点不在数据里**：卡片与指标只有确定性事实；agent 的选房理由只出现在对话文字中。
- **版本标识**：卡片携带 revision/contentHash，可标注"基于某版本"；详情页跳转用 `listingRef`。

## 2. 两种传输方式（同一契约）

| 方式 | 内容 | 适用 |
|---|---|---|
| **A. 回答内嵌结构块** | agent 回答携带 `{type: "listing-comparison-cards", reportId, cards, metricWinners, conclusion}` 结构块 | 即时对话渲染（取决于聊天协议对结构化内容块的支持） |
| **B. reportId 句柄** | 回答/咨询历史携带 reportId，前端调 `GET /listings/comparisons/{reportId}` 获取上述完整响应 | 历史回看、需要重新鉴权与版本复验的场景（推荐保底） |

方式 B 的读取沿用现有语义：owner 校验、逐套房源读取权、当前版本复验——报告过时（STALE）或不可读会如实返回，前端据此提示"报告已过时，可重新生成"。

## 3. 咨询留痕（历史回看）

- 关联：`POST /customers/inquiries/{inquiryId}/comparison-reports` `{reportId}`（经纪人权限；报告 owner 须为当前经纪人；幂等；一次咨询可关联多份）。
- 咨询列表/详情返回 `comparisonReports: [{reportId, createdAt}]`（按时间排序）；正文与卡片不冗余存储，前端按 reportId 经方式 B 按需拉取。
- Agent 技能（`consultation-listing-comparison`）在回答中输出 reportId 与咨询 ID，由调用方执行关联；agent 不直接写客户域。

## 4. 边界

- 卡片数据由服务端从报告来源快照确定性投影（读取时计算），前端与 agent 均不得修改。
- 存量历史报告自动获得卡片视图，无需迁移。
- 对比计算维度以报告 `metrics` 为准，前端不自行推导新指标。
