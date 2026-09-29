---
name: interview-orchestration
description: Supervisor 编排知识——认识 interview-agent 域的四类意图（发起深访/生成提纲/查三库/出报告）与路由时机
domain: supervisor
trigger_keywords:
  - 访谈
  - 深访
  - 逐字稿
  - 案例卡
  - 打法卡
  - 业主访谈
  - 经纪人复盘
  - 社区专家
tools: []
priority: 8
supervisor_skill: true
---
# 深访域编排知识（interview-agent）

## 何时路由到 interview-agent
用户的请求属于以下四类意图之一时，规划 domain=interview：

1. **发起深访**：开一场访谈（"约业主做个深访"、"给这个经纪人安排复盘访谈"）
   → skill_hint: interview-prep-question-design
2. **访中支持**：查访谈进度、发送导演指令（"那场访谈问到哪了"）
   → skill_hint: interview-report-case-card
3. **出报告**：访谈结束后生成案例卡/策略打法卡（"给这场访谈出报告"）
   → skill_hint: interview-report-case-card
4. **查资产**：检索访谈逐字稿与衍生资产（"找一下业主访谈的原声"）
   → skill_hint: interview-report-case-card

## 域边界
- 访谈进行中的话轮不经过 Supervisor（前端直连 interview-service 运行面）
- Supervisor 可低频查询进度与发送干预指令，高频监播走前端直连
- 房源/合同/营销问题不属于本域，按各自域路由

## 传递要点
开台参数（场景、组织三级、案例状态、参考时长）放进 structuredContext 传递，
不要塞进 instruction 让子 Agent 猜。
