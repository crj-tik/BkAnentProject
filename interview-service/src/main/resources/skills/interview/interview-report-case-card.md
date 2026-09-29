---
name: interview-report-case-card
description: 当用户需要生成访谈报告、查看策略案例卡、查询访谈进度、检索访谈逐字稿或触发报告任务时使用
domain: interview
trigger_keywords:
  - 访谈报告
  - 案例卡
  - 打法卡
  - 访谈进度
  - 逐字稿
  - 访谈结果
  - 核心发现
tools:
  - getInterviewMonitor
  - generateCaseReport
  - searchAssets
  - regradeAsset
  - finalizeInterview
  - sendDirectorCommand
priority: 8
supervisor_skill: false
---
# 访谈报告与资产检索

## 适用场景
- 查询访谈进度（"那场业主访谈怎么样了"）
- 访谈结束后生成案例卡报告（"给这场访谈出个报告"）
- 检索已归档逐字稿（"找一下上个月朝阳大区的业主访谈"）
- 导演指令（收束/下一问/加问）

## 执行步骤

### 查进度
调用 `getInterviewMonitor`（sessionId），返回状态、已答/已确认题数、当前题。
对 A2A 开台与页面开台的会话一视同仁。

### 出报告
调用 `generateCaseReport`（caseId, assetId）。任务幂等：相同输入复用同一任务，
已有成功报告直接返回。返回的 taskId/status 供轮询——不要向用户虚构报告内容，
报告以任务完成后的结果为准。

### 检索资产
调用 `searchAssets`（keyword, scene, creatorWorkNo），仅返回 L2/L3 已归档资产。

### 导演指令（会话进行中）
调用 `sendDirectorCommand`（sessionId, command, pinnedQuestion）：
WRAP_UP 收束当前题 / NEXT_QUESTION 下一问 / PINNED_QUESTION 加问置顶（原样问）。

## 输出要求
- 报告未完成时如实报告任务状态，不编造报告内容
- 引用受访者原声时逐字引用，不改写
- 可复制性等级与质量分以工具返回为准（L2 以上需外部证据与失效条件闭环）
