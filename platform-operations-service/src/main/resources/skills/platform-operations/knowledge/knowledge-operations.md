---
name: platform-operations-knowledge-operations
description: 根据已发布的访谈资产生成知识候选，并查询生成任务状态
domain: knowledge
trigger_keywords:
  - 知识运营
  - 知识案例
  - 访谈资料提炼
  - 知识生成
tools:
  - startKnowledgeGeneration
  - getKnowledgeGenerationStatus
priority: 10
supervisor_skill: false
owner: knowledge
version: 1.0.0
explicit_only: true
---
# 平台经营知识运营

- 只通过已发布访谈资产 ID 启动知识候选生成；工具只返回任务状态，不返回逐字稿原文或引文。
- 查询状态时必须使用对应 Knowledge 案例 ID 和任务 ID；不得猜测或替换对象标识。
- 候选内容必须经过人工审核后才可发布。Agent 不执行审核、发布、撤回或来源授权操作；这些动作只能由用户通过受保护的业务 API 明确触发。
- 明确区分模拟、未知和真实来源，不得把模拟内容描述为已核实经验。
