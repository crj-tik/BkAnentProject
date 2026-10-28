---
name: marketing-copy-generation
description: 根据房源信息生成小红书/抖音风格的营销文案
domain: marketing
trigger_keywords:
  - 文案
  - 营销
  - 推广
  - 广告
  - 小红书
  - 抖音
  - 朋友圈
  - 宣传
  - 海报
  - 卖点
  - 房源描述
  - 标题
  - 短视频
  - 脚本
tools:
  - createMarketingContent
  - searchContents
  - prepareMarketingCampaign
priority: 10
supervisor_skill: false
---
# 营销文案生成

## 触发条件
用户要求生成小红书/抖音/朋友圈等平台的房产营销文案、短视频脚本、推广内容时触发。

## 执行步骤

### 第一步：搜索参考内容
调用 `searchContents` 查找历史优秀文案作为参考：
- keyword: 用户提供的核心卖点关键词
- platform: 目标平台（xiaohongshu/douyin/wechat）

### 第二步：生成营销草稿
调用 `createMarketingContent`：
- 根据房源特点（户型、面积、价格、区位优势）生成平台适配文案
- 小红書风格：活泼生动，多用emoji，标签化
- 抖音风格：短句有力，前三秒抓眼球，引导互动
- 朋友圈风格：专业可信，突出性价比

该工具只创建 DRAFT 内容，不代表审核通过，也不会调用平台。

### 第三步：按需准备发布计划
只有用户明确要求发布、且内容已由有权限的审核人通过后，才调用 `prepareMarketingCampaign`。
准备结果会固定内容、素材、房源快照和受权账号。向用户展示平台、账号和 `snapshotHash`，明确说明当前仅为待确认计划。

本技能只允许搜索内容、创建 DRAFT 和准备计划。**不得调用发布确认、通知或分发能力；不得把模型输出、工具受理或计划准备说成已发布。** 后续发布由 Supervisor 的 `marketing-campaign-publish` 技能单独展示计划，并在用户批准后调用确认工具；内容、素材、账号或房源版本变化时必须重新准备。

## 输出格式
```json
{
  "platform": "xiaohongshu",
  "title": "标题",
  "body": "正文内容",
  "hashtags": ["#好房推荐", "#刚需上车"],
  "callToAction": "引导语"
}
```

## 平台文案规范
- **小红書**：标题≤20字，正文200-500字，3-5个标签，必加emoji
- **抖音**：口播脚本格式，前3秒金句，正文口语化，末尾引导评论/点赞
- **朋友圈**：≤140字，配合房源图片，突出1-2个核心卖点
