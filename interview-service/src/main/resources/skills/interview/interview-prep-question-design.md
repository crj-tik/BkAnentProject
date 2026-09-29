---
name: interview-prep-question-design
description: 当用户需要发起访谈、开台设置访谈场景与目标、设计访谈问题、确认访谈题目清单时使用
domain: interview
trigger_keywords:
  - 访谈
  - 深访
  - 开台
  - 访谈提纲
  - 出题
  - 确认题目
  - 发起访谈
tools:
  - openInterviewCase
  - confirmQuestions
  - startInterviewSession
  - getInterviewMonitor
priority: 9
supervisor_skill: false
---
# 访前开台与题目设计

## 适用场景
- 发起人要通过对话开一场 AI 深访（"帮我约一位业主做个访谈"）
- 开台要素磋商（场景、目标、组织三级、受访人角色、参考时长）
- 候选题目清单生成与确认（题目确认制）

## 执行步骤

### 第一步：开台
调用 `openInterviewCase`，参数从对话中提取：
- scene: STORE_MANAGER|AGENT_DEAL|SECOND_HAND_PARTY|NEW_HOUSE_FIELD|COMMUNITY_EXPERT 五选一
- respondentRole: 按场景的受访人角色（业主/客户/经纪人/置业顾问/案场OP/店东等）
- objective: 访谈目标一句话
- divisionName/regionName/businessDistrict: 组织三级
- caseStatus: won|active|lost|churned（lost/churned 自动启用禁成功预设）
- referenceMinutes: 15/30/45/60（只控制题目预算，不会到点自动结束）

要素缺失时先逐项向发起人确认，不要凭空猜测。

### 第二步：题目确认制
基于第一步返回的 outlineRoute 与 questionBudget 生成候选问题清单（每题带 focus 标签与风险提示），
请发起人勾选。调用 `confirmQuestions` 落库——只有确认后的题进入访谈，未选题不计为「未回答」。

### 第三步：启动会话
调用 `startInterviewSession`（mode: AI_LEAD 或 ASSIST），返回 sessionId、ticket 与入口路径——
这是给前端的「访谈入口卡片」工件，原样返回给发起人即可，不要改写 ticket。

## 输出要求
- 开台结果必须包含：场景、提纲路由、已确认题数、参考时长、入口卡片
- 未成交/流失案例必须提示已启用禁成功预设与必采清单
- 不虚构组织三级与受访者信息
