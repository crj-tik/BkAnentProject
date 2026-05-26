# 分布式多 Agent 实施路线图 V2

## 1. 文档定位

本文档是对现有分布式多 Agent 方案的落地版补充，重点回答三个问题：

1. 后续分布式多 Agent 的推荐目标结构是什么。
2. 每个 Agent 服务推荐使用什么模型，以及如何做模型分层。
3. 实施步骤应该整体推进还是按模块推进，推荐的拆分顺序是什么。

这份文档默认以下前提已经成立：

- Agent 与 Tool 之间继续使用 `MCP`
- Agent 与 Agent 之间使用 `A2A`
- 主 Agent 内部使用 `Spring AI Alibaba Graph`
- 人工审批统一建模为主 Agent 内部的通用 `ApprovalSubgraph`
- `memory-service` 独立承载业务记忆与任务产物

## 2. 总体结论

推荐路线不是“一次性整体重构”，也不是“每个模块各自先拆再说”。

正确路线是两层推进：

1. 先统一全局骨架
2. 再按模块逐个迁移

也就是说：

- 全局协议、任务模型、Graph 骨架、审批子图、事件流、Memory Service 这些基础设施要先定下来
- 真正的业务 Agent 服务拆分和迁移，要按模块一块一块推进

一句话总结：

**先统一编排底座，再逐个迁移领域 Agent。**

## 3. 推荐目标架构

推荐的目标结构如下：

- `supervisor-agent-service`
  - 前端统一入口
  - 会话管理
  - 主任务 Graph 编排
  - 通用 `ApprovalSubgraph`
  - A2A 调度与汇总
  - 统一 session 事件流输出

- `listing-agent-service`
  - 房源检索
  - 房源问答
  - 房源推荐
  - 房源结构化摘要

- `marketing-agent-service`
  - 文案生成
  - 渠道适配
  - 营销素材组织
  - 广告发布请求组装

- `media-agent-service`
  - 视频脚本生成
  - 图像/封面生成
  - 媒体生成任务编排

- `trade-agent-service`
  - 成交可行性分析
  - 交易流程判断
  - 规则推理

- `contract-agent-service`
  - 合同条款解析
  - 风险提示
  - OCR/附件解析结果整合

- `memory-service`
  - `session shared memory`
  - `task artifact memory`
  - `agent private memory`
  - artifact 查询与权限控制

- 基础设施
  - `Nacos`
  - `A2A`
  - `MCP`
  - `Event Bus`
  - `Milvus / ES`

## 4. 主 Agent 的定位

`supervisor-agent-service` 是整个分布式多 Agent 的控制平面。

它不应该承担所有领域能力，但必须承担以下职责：

- 接收用户请求
- 维护 `sessionId`、`taskId`、`traceId`
- 加载共享记忆与任务产物
- 通过 Graph 决定当前编排路径
- 通过 A2A 调用子 Agent
- 统一做审批中断与恢复
- 统一汇总多 Agent 输出
- 统一向前端发流式事件

主 Agent 里推荐固定保留这些 Graph 骨架能力：

- `LoadMemory`
- `ParseIntent`
- `Plan`
- `SelectAgent`
- `BuildApprovalRequest`
- `ApprovalSubgraph`
- `InvokeAgent`
- `MergeResult`
- `PersistMemory`
- `EmitStream`

重点原则：

- Graph 不按“一个 Agent 一个固定节点”建模
- Graph 按“稳定骨架 + 动态路由 + 通用调用节点”建模

## 5. 通用审批子图

审批统一放在 `supervisor-agent-service` 内部，不放到任何领域 Agent 中。

### 5.1 为什么必须统一

因为审批本质是主流程控制点，而不是领域生成逻辑的一部分。

如果让各子 Agent 自己等待审批，会带来问题：

- 审批状态散落在多个服务
- 中断恢复逻辑重复实现
- 前端无法统一展示审批状态
- 审批后继续执行的流程不一致

### 5.2 推荐审批子图骨架

```text
prepare_approval -> persist_and_interrupt -> await_resume -> route_by_decision
```

### 5.3 推荐统一对象

```java
public record ApprovalRequest(
    String approvalType,
    String subjectType,
    String subjectId,
    Integer subjectVersion,
    String title,
    String summary,
    Map<String, Object> payload,
    String approveNextNode,
    String rejectNextNode,
    String terminateNextNode,
    Integer retryCount,
    Integer maxRetryCount
) {}
```

```java
public record ApprovalDecision(
    ApprovalStatus status,
    String feedback,
    String reviewerId,
    LocalDateTime reviewedAt
) {}
```

### 5.4 推荐统一事件

- `task.waiting_approval`
- `task.approval_received`
- `task.approval_approved`
- `task.approval_rejected`
- `task.approval_terminated`
- `task.regeneration_started`

## 6. 统一技术约束

在开始拆服务前，推荐先冻结下面这些统一约束。

### 6.1 统一 ID 体系

每次请求至少携带：

- `sessionId`
- `taskId`
- `traceId`
- `idempotencyKey`

### 6.2 统一任务状态

推荐主任务与子任务共享一套状态语义：

- `submitted`
- `working`
- `handoff_pending`
- `handed_off`
- `waiting_input`
- `completed`
- `failed`
- `canceled`

### 6.3 统一子 Agent 返回契约

每个子 Agent 至少统一返回：

```java
public record AgentResult(
    String taskId,
    String agentId,
    String status,
    Map<String, Object> structuredOutput,
    List<String> artifactIds,
    List<String> nextHints,
    String traceId
) {}
```

### 6.4 统一幂等要求

以下动作必须具备幂等性：

- A2A 子任务创建
- 审批回调
- MQ 事件消费
- 广告发布
- artifact 持久化

## 7. 推荐模型策略

不要给每个服务只绑定一个固定模型，推荐每个服务配置三档模型：

- `primary`
- `fallback`
- `cheap`

然后按节点类型进行模型路由。

### 7.1 主 Agent 模型

`supervisor-agent-service`

- `primary`: `qwen-max-latest`
- `fallback`: `qwen-plus-latest`
- `cheap`: `qwen-turbo-latest`

适用节点：

- `ParseIntent`
- `Plan`
- `SupervisorSummarize`
- `ApprovalSubgraph` 中的复杂分支判断

### 7.2 Listing Agent 模型

`listing-agent-service`

- `primary`: `qwen-plus-latest`
- `fallback`: `qwen-turbo-latest`
- `cheap`: `qwen-turbo-latest`

适用场景：

- 房源问答
- 房源摘要
- 检索结果解释
- 结构化抽取

### 7.3 Marketing Agent 模型

`marketing-agent-service`

- `primary`: `qwen-plus-latest`
- `fallback`: `qwen-turbo-latest`
- `cheap`: `qwen-turbo-latest`

适用场景：

- 文案生成
- 多平台适配
- 标题与正文组合

### 7.4 Media Agent 模型

`media-agent-service`

- 文本脚本 `primary`: `qwen-plus-latest`
- 图像生成 `primary`: `qwen-image` 或 `qwen-image-plus`
- `fallback`: `qwen-turbo-latest`

说明：

- 媒体服务建议把“脚本生成”和“图像/视频生成”拆开看
- 视频生成具体模型要在落地前单独核一遍当前百炼可用清单，不建议现在在架构文档里写死

### 7.5 Trade Agent 模型

`trade-agent-service`

- `primary`: `qwen-plus-latest`
- `fallback`: `qwen-turbo-latest`
- 复杂规则推理可预留更强推理模型位

适用场景：

- 流程推理
- 可成交性判断
- 风险路径分析

### 7.6 Contract Agent 模型

`contract-agent-service`

- 文本解析 `primary`: `qwen-plus-latest`
- 多模态解析 `primary`: `qwen-vl-plus` 或 `qwen-vl-max`
- `fallback`: `qwen-turbo-latest`

适用场景：

- 条款解析
- OCR 后语义抽取
- 合同风险提示

### 7.7 模型路由原则

推荐按节点类别路由模型，而不是按服务整体路由。

推荐规则：

- `cheap`
  - 标签分类
  - 基础抽取
  - 简单格式化

- `primary`
  - 领域生成
  - 中等复杂推理
  - 结构化总结

- `max`
  - 主 Agent 规划
  - 多 Agent 汇总
  - 审批分支与复杂任务判断

## 8. 推荐实施方式

推荐方式不是“所有架构一起一次改完”。

正确方式是：

### 8.1 先做全局骨架

这一层必须先一起落：

- `AgentCard`
- `AgentRegistry`
- `A2A` 协议
- `AgentTask`
- `AgentHandoffPacket`
- `AgentResult`
- `memory-service` 最小能力
- 主 Agent Graph 骨架
- 通用 `ApprovalSubgraph`
- 统一 session 事件流

这一步完成后，哪怕只拆出一个领域 Agent，系统也已经具备标准化扩展能力。

### 8.2 再按模块迁移

领域 Agent 迁移必须逐个模块推进，不建议同时拆很多个。

## 9. 推荐模块迁移顺序

### 第一阶段：主 Agent 底座先行

目标：

- 建立 `supervisor-agent-service`
- 跑通 Graph 骨架
- 跑通 `ApprovalSubgraph`
- 跑通 `A2A client`
- 跑通 session 事件流

产出：

- 前端统一入口稳定
- 主任务可跟踪
- 审批中断恢复可用

### 第二阶段：Listing Agent 单独落地

目标：

- 独立 `listing-agent-service`
- 主 Agent 通过 A2A 调用 listing
- 输出统一 `AgentResult`

原因：

- 房源域最成熟
- 输入输出比较稳定
- 适合做第一个样板

### 第三阶段：Memory Service 最小闭环

目标：

- 落 `session shared memory`
- 落 `task artifact memory`
- 支持 artifact 查询

原因：

- 后续审批、handoff、回放都依赖 artifact

### 第四阶段：Marketing Agent 落地

目标：

- 文案生成通过 `marketing-agent-service`
- 主 Agent 走 `BuildApprovalRequest + ApprovalSubgraph`
- 跑通“生成 -> 审批 -> 重生成 -> 继续”

这是第一个真正验证审批子图价值的阶段。

### 第五阶段：Media Agent 落地

目标：

- 媒体脚本或封面生成独立为 `media-agent-service`
- 跑通“文案审批 -> 视频生成 -> 视频审批 -> 继续”

这是第一个验证“多次审批中断恢复”的阶段。

### 第六阶段：Trade Agent 落地

目标：

- 跑通规则推理与 cross-agent handoff
- 验证并行查询与结果合并

### 第七阶段：Contract Agent 落地

目标：

- 处理 OCR、附件、多模态输入
- 验证复杂输入场景下的 Agent 边界

原因：

- 合同链路复杂度更高
- 放在后面更稳

### 第八阶段：其余辅助 Agent

例如：

- settlement
- notification
- ops assistant

这些建议在主链稳定后再加。

## 10. 推荐里程碑

### M0

- 文档冻结
- 协议冻结
- 状态机冻结

### M1

- `supervisor-agent-service` 可运行
- Graph 骨架可运行
- `listing-agent-service` 可通过 A2A 调用

### M2

- `memory-service` 上线
- session 事件流上线
- artifact 持久化上线

### M3

- `marketing-agent-service` 上线
- `ApprovalSubgraph` 跑通
- 审批回调与 resume 跑通

### M4

- `media-agent-service` 上线
- 双审批链路跑通

### M5

- `trade-agent-service` 上线
- 并行与 handoff 跑通

### M6

- `contract-agent-service` 上线
- 多模态复杂输入跑通

### M7

- 权限、限流、灰度、压测、观测补齐

## 11. 风险与补充建议

### 11.1 不建议过早拆太多服务

如果在 A2A、artifact、事件流、审批恢复都没稳定前就同时拆多个 Agent，联调成本会迅速失控。

### 11.2 不建议把审批放回子 Agent

审批一旦回到子 Agent，本方案里最核心的主流程控制点就会再次分散。

### 11.3 不建议把 Graph Checkpoint 当业务 Memory

Checkpoint 只负责主图恢复，不负责跨任务、跨 Agent 的业务记忆。

### 11.4 必须尽早补可观测性

至少要统一：

- `traceId`
- `sessionId`
- `taskId`
- `agentId`
- `approvalId`
- `artifactId`

否则后面问题会很难追。

### 11.5 先把协议做窄

前期不要在 A2A、Approval、Artifact 协议里一次塞太多字段。

建议先做：

- 最小必需字段
- 明确版本号
- 允许后续扩展

## 12. 最终建议

后续分布式多 Agent 推荐按下面原则推进：

1. 先统一全局骨架，不要先拆一堆服务。
2. 主 Agent 先成型，再逐个迁移领域 Agent。
3. 审批统一放在主 Agent Graph 的 `ApprovalSubgraph`。
4. 先 `listing`，再 `marketing`，再 `media`，再 `trade`，最后 `contract`。
5. 模型采用分层路由，不要一服务一模型写死。

一句话总结：

**整体先定协议和骨架，业务再按模块逐个迁移。**
