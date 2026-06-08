---
# ============================================================
# YAML 前置元数据（必填字段标注了 [必填]）
# ============================================================

# [必填] 技能唯一标识，建议格式：{domain}-{场景}，如 trade-kpi-report
name: your-skill-name

# [必填] 一句话描述技能的用途，用于匹配和技能目录展示
description: 简要描述这个技能做什么

# [必填] 所属领域，决定该技能归属于哪个 Agent
# 可选值: supervisor | trade | compare | marketing | contract | settlement | notification | media | listing
domain: trade

# [可选] 触发关键词列表，用于快速匹配用户意图
# 写得越全，匹配越准。留空则该 skill 只能通过语义匹配或 Fallback 触发。
trigger_keywords:
  - 关键词1
  - 关键词2
  - 关键词3

# [可选] 匹配后只加载这些 tool（填写 @Tool 方法名）
# 留空 = 不限制，加载该 Agent 的全部 tool
# 工具名必须与对应 Agent 的 @Tool 方法名完全一致
tools:
  - toolMethodName1
  - toolMethodName2

# [可选] 优先级（1-10），多 skill 同时命中时选最高的。默认 5
priority: 5

# [可选] 是否为 Supervisor 知识技能（仅注入知识，不加载工具）
# true = Supervisor 知识技能（domain 应为 supervisor，tools 应留空）
# false = 子 Agent 操作技能（默认）
supervisor_skill: false
---
# ============================================================
# System Prompt（--- 之后的 Markdown 正文）
# 这部分会作为 LLM 的 system prompt，指导它如何执行任务
# ============================================================

# 技能标题（与 name 对应的人性化名称）

## 触发条件
<!-- 描述什么情况下应该触发这个技能，帮助 LLM 在 fallback 模式下自主判断 -->

- 用户询问关于 XXX 的内容时触发
- 用户消息中包含 XXX 关键词时触发

## 执行步骤
<!-- 按顺序列出工具调用步骤，每一步说明调用哪个 tool、传什么参数 -->

### 第一步：获取基础数据
调用 `toolMethodName1`：
- param1: 从用户输入中提取的 XXX
- param2: 默认值或从上下文获取的 XXX

### 第二步：分析处理
调用 `toolMethodName2`，传入第一步的结果数据。

## 输出格式
<!-- 定义期望的输出结构，帮助 LLM 组织最终回复 -->

```json
{
  "field1": "说明",
  "field2": "说明"
}
```

## 注意事项
<!-- 边界条件、常见错误、特殊处理逻辑 -->

- 如果用户未指定 XXX，默认使用 XXX
- 当 XXX 为空时，跳过第 N 步
- 错误处理：如果 XXX 调用失败，提示用户 XXX
