---
name: interview-orchestration
description: Supervisor 背景知识——认识访谈治理能力与运行面边界，由模型根据真实 Card 判断委托
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

## 何时考虑委托 interview-agent
以下场景是理解需求的背景，模型应结合用户意图与真实能力目录判断是否调用 a2a:interview-agent；不用 domain/intent 计划预选目标。下游技能只能使用其 Card 发布的本地名称与版本，不能猜名称或将本背景技能当作子技能。

1. **发起深访**：开一场访谈（"约业主做个深访"、"给这个经纪人安排复盘访谈"）
   → 结合实际 Card 的开台与提纲设计能力。
2. **访中支持**：查访谈进度、发送导演指令（"那场访谈问到哪了"）
   → 结合实际 Card 的治理能力，不将运行面话轮接入 Supervisor。
3. **出报告**：访谈结束后生成案例卡/策略打法卡（"给这场访谈出报告"）
   → 结合实际 Card 的报告与案例卡能力。
4. **查资产**：检索访谈逐字稿与衍生资产（"找一下业主访谈的原声"）
   → 结合实际 Card 的授权资产检索能力。

## 域边界
- 访谈进行中的话轮不经过 Supervisor（前端直连 interview-service 运行面）
- Supervisor 可低频查询进度与发送干预指令，高频监播走前端直连
- 房源/合同/营销问题使用真实目录中合适的能力，由模型判断，不由本知识触发固定调用

## 传递要点
开台参数（场景、组织三级、案例状态、参考时长）放进 A2A 工具 context 传递，
不要塞进 instruction 让子 Agent 猜。
