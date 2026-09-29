---
name: media-generation
description: 当用户需要为房源出视频、出图片、制作宣传素材、生成封面或查询素材制作进度时使用
domain: media
trigger_keywords:
  - 视频
  - 出图
  - 素材
  - 封面
  - 宣传物料
  - 制作进度
tools:
  - submitMediaTask
  - getTaskResult
priority: 9
supervisor_skill: false
---
# 媒体素材生成

## 适用场景
用户提出以下类型请求时适用本技能：
- 为某套房源制作宣传视频或图片素材
- 生成房源封面图、短视频脚本镜头素材
- 查询已提交素材制作任务的进度与结果

## 执行步骤

### 第一步：提交生成任务
调用 `submitMediaTask`：
- listingId: 目标房源 ID（用户未指明时先确认）
- prompt: 描述期望的输出（风格、镜头、重点展示的空间），基于用户诉求具体化，不要原样照抄

### 第二步：反馈任务与跟踪
任务提交后向用户返回任务 ID；用户询问进度时调用 `getTaskResult`：
- taskId: 第一步返回的任务 ID

## 输出要求
- 提交成功时明确告知"任务已提交"并给出任务 ID，说明结果需稍后查询
- 查询结果时如实转述任务状态；失败时说明原因与建议（如调整 prompt 重试）
- 不承诺即时出片：媒体生成为异步任务
