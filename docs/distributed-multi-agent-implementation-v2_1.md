# 分布式多 Agent 实施路线图 V2.1

## 1. 文档定位

本文档是 V2 的细化版，目标是把“分布式多 Agent + A2A + 主 Agent Graph + 通用审批子图”继续落到更接近代码设计的层面。

本文档重点补四部分：

1. 每个服务的输入输出协议建议
2. `supervisor-agent-service` 的 Graph 节点清单
3. `A2A / Approval / Artifact` DTO 草案
4. 每个阶段的风险清单和验收标准

配套主文档：

- [distributed-multi-agent-implementation-v2.md](/D:/project/BkAnentProject/BkAnentProject/docs/distributed-multi-agent-implementation-v2.md:1)
- [distributed-multi-agent-a2a-plan.md](/D:/project/BkAnentProject/BkAnentProject/docs/distributed-multi-agent-a2a-plan.md:1)

## 2. 服务边界与协议原则

### 2.1 总原则

所有 Agent 服务都尽量遵循同一组协议原则：

- 输入以结构化 DTO 为主，不直接传完整对话历史
- 输出统一返回 `structuredOutput + artifactIds + nextHints`
- 大对象不直接内嵌在 A2A 返回里，改用 `artifactId` 引用
- 所有调用都带 `sessionId`、`taskId`、`traceId`
- 所有异步任务都支持状态查询与幂等恢复

### 2.2 主 Agent 与子 Agent 的调用关系

推荐关系固定为：

- `supervisor-agent-service` -> `A2A` -> 各子 Agent
- 各子 Agent -> `MCP` -> 工具或业务能力
- `supervisor-agent-service` / 子 Agent -> `memory-service` -> artifact 与记忆

主 Agent 不直接跨过子 Agent 去操作它的领域工具。

## 3. 服务输入输出协议建议

### 3.1 Supervisor Agent 对外入口

推荐对外统一一个任务入口，而不是暴露一堆业务特化入口。

```java
public record SupervisorTaskRequest(
    String sessionId,
    String userId,
    String requestId,
    String traceId,
    String userMessage,
    Map<String, Object> context,
    String channel,
    Boolean stream
) {}
```

```java
public record SupervisorTaskResponse(
    String sessionId,
    String taskId,
    String status,
    String finalAnswer,
    List<String> artifactIds,
    String traceId
) {}
```

### 3.2 A2A 通用请求

推荐所有子 Agent 都接受同一种主请求结构：

```java
public record AgentTaskInvokeRequest(
    String sessionId,
    String taskId,
    String parentTaskId,
    String traceId,
    String sourceAgentId,
    String targetAgentId,
    String intent,
    String domain,
    String instruction,
    Map<String, Object> structuredContext,
    List<String> artifactIds,
    List<String> constraints,
    String expectedOutput,
    Boolean stream
) {}
```

说明：

- `instruction` 是当前子任务目标
- `structuredContext` 放轻量上下文
- `artifactIds` 放大对象引用
- `expectedOutput` 明确要求子 Agent 交付格式

### 3.3 A2A 通用返回

```java
public record AgentTaskInvokeResponse(
    String sessionId,
    String taskId,
    String agentId,
    String status,
    Map<String, Object> structuredOutput,
    List<String> artifactIds,
    List<String> nextHints,
    String summary,
    String traceId
) {}
```

推荐约束：

- `structuredOutput` 尽量稳定，不要塞一堆自由文本
- `artifactIds` 承载正文、长报告、媒体任务等大对象
- `nextHints` 用于提示主 Agent 后续可选动作

### 3.4 Listing Agent 协议

推荐意图范围：

- `listing.search`
- `listing.recommend`
- `listing.summary`
- `listing.answer`

推荐输出：

```java
public record ListingAgentOutput(
    String resultType,
    List<Map<String, Object>> listings,
    String summary,
    List<String> evidenceArtifactIds
) {}
```

### 3.5 Marketing Agent 协议

推荐意图范围：

- `marketing.generate_copy`
- `marketing.adapt_content`
- `marketing.publish_prepare`

推荐输出：

```java
public record MarketingAgentOutput(
    String resultType,
    String contentType,
    String draftText,
    Map<String, Object> platformVariants,
    List<String> artifactIds
) {}
```

### 3.6 Media Agent 协议

推荐意图范围：

- `media.generate_script`
- `media.generate_cover`
- `media.generate_video_task`

推荐输出：

```java
public record MediaAgentOutput(
    String resultType,
    String mediaTaskId,
    String scriptText,
    List<String> artifactIds,
    String mediaStatus
) {}
```

### 3.7 Trade Agent 协议

推荐意图范围：

- `trade.feasibility_analysis`
- `trade.process_judgement`
- `trade.risk_reasoning`

推荐输出：

```java
public record TradeAgentOutput(
    String resultType,
    String decision,
    List<String> reasons,
    Map<String, Object> structuredAssessment,
    List<String> artifactIds
) {}
```

### 3.8 Contract Agent 协议

推荐意图范围：

- `contract.parse`
- `contract.risk_review`
- `contract.summary`

推荐输出：

```java
public record ContractAgentOutput(
    String resultType,
    String summary,
    List<Map<String, Object>> clauses,
    List<Map<String, Object>> risks,
    List<String> artifactIds
) {}
```

## 4. Supervisor Graph 节点清单

### 4.1 固定骨架节点

推荐主图至少保留以下固定节点：

1. `LoadSessionNode`
2. `LoadArtifactsNode`
3. `ParseIntentNode`
4. `PlanTaskNode`
5. `SelectAgentNode`
6. `BuildInvokeRequestNode`
7. `InvokeAgentNode`
8. `MergeAgentResultNode`
9. `BuildApprovalRequestNode`
10. `ApprovalSubgraphNode`
11. `PersistArtifactsNode`
12. `PersistSessionNode`
13. `SummarizeNode`
14. `EmitStreamNode`
15. `FinishNode`

### 4.2 节点职责说明

#### `LoadSessionNode`

职责：

- 从 `memory-service` 加载 `session shared memory`
- 读取用户偏好、已确认约束、稳定事实

输入：

- `sessionId`

输出：

- `sharedContext`

#### `LoadArtifactsNode`

职责：

- 读取当前任务已存在的产物
- 恢复上一次审批前或 handoff 后的 artifact 引用

输入：

- `taskId`
- `artifactIds`

输出：

- `artifacts`

#### `ParseIntentNode`

职责：

- 识别用户意图
- 判断任务类型、领域标签、是否需要审批链路

输出：

- `intent`
- `domain`
- `workflowType`

#### `PlanTaskNode`

职责：

- 生成主流程计划
- 决定是否需要单 Agent、并行 Agent、或审批链路

输出：

- `plan`
- `requireParallel`
- `requireApproval`

#### `SelectAgentNode`

职责：

- 根据 `AgentRegistry`、`intent`、`domain`、`skill` 动态选目标 Agent

输出：

- `selectedAgentId`

#### `BuildInvokeRequestNode`

职责：

- 组装 `AgentTaskInvokeRequest`
- 把当前上下文压缩成可交付的 A2A 载荷

#### `InvokeAgentNode`

职责：

- 通过 A2A 调用子 Agent
- 接受同步返回或长任务引用

#### `MergeAgentResultNode`

职责：

- 把子 Agent 返回写入主图状态
- 提取 `structuredOutput`
- 合并 `artifactIds`

#### `BuildApprovalRequestNode`

职责：

- 判断当前结果是否需要用户确认
- 基于产物组装 `ApprovalRequest`

适用场景：

- 文案审批
- 视频审批
- 合同确认
- 报价确认

#### `ApprovalSubgraphNode`

职责：

- 运行通用审批子图
- 负责中断、恢复和出口分支

#### `PersistArtifactsNode`

职责：

- 将大文本、报告、草稿、脚本、媒体任务引用写入 `memory-service`

#### `PersistSessionNode`

职责：

- 更新本轮会话共享记忆
- 记录已确认事实和约束

#### `SummarizeNode`

职责：

- 把多 Agent / 多阶段结果汇总成统一用户答复

#### `EmitStreamNode`

职责：

- 发统一 session 事件

#### `FinishNode`

职责：

- 输出最终任务结果

### 4.3 推荐边关系

推荐主链路：

```text
LoadSession
-> LoadArtifacts
-> ParseIntent
-> PlanTask
-> SelectAgent
-> BuildInvokeRequest
-> InvokeAgent
-> MergeAgentResult
-> BuildApprovalRequest
-> ApprovalSubgraph
-> PersistArtifacts
-> PersistSession
-> Summarize
-> EmitStream
-> Finish
```

推荐扩展边：

- `PlanTask -> SelectAgent`
- `PlanTask -> ParallelInvokeBranch`
- `ApprovalSubgraph -> BuildInvokeRequest`
- `ApprovalSubgraph -> Summarize`
- `MergeAgentResult -> BuildInvokeRequest`

## 5. A2A DTO 草案

### 5.1 Handoff Packet

```java
public record AgentHandoffPacket(
    String sessionId,
    String taskId,
    String parentTaskId,
    String traceId,
    String fromAgent,
    String toAgent,
    String reason,
    String userGoal,
    Map<String, Object> structuredContext,
    List<String> artifactIds,
    List<String> constraints,
    String expectedOutput
) {}
```

### 5.2 子任务状态

```java
public record AgentTaskStatusResponse(
    String taskId,
    String agentId,
    String status,
    Integer progressPercent,
    String message,
    List<String> artifactIds,
    String traceId
) {}
```

### 5.3 A2A 错误结构

```java
public record AgentErrorResponse(
    String taskId,
    String agentId,
    String errorCode,
    String errorMessage,
    Boolean retryable,
    String traceId
) {}
```

推荐统一错误码前缀：

- `A2A_TIMEOUT`
- `A2A_BAD_REQUEST`
- `A2A_AGENT_UNAVAILABLE`
- `A2A_RESULT_INVALID`
- `A2A_TASK_DUPLICATED`

## 6. Approval DTO 草案

### 6.1 ApprovalRequest

```java
public record ApprovalRequest(
    String approvalId,
    String taskId,
    String sessionId,
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
    Integer maxRetryCount,
    String traceId
) {}
```

### 6.2 ApprovalCallbackRequest

```java
public record ApprovalCallbackRequest(
    String approvalId,
    String taskId,
    String sessionId,
    ApprovalStatus status,
    String reviewerId,
    String feedback,
    String traceId
) {}
```

### 6.3 ApprovalDecision

```java
public record ApprovalDecision(
    String approvalId,
    ApprovalStatus status,
    String reviewerId,
    String feedback,
    LocalDateTime reviewedAt,
    String traceId
) {}
```

### 6.4 ApprovalEvent

```java
public record ApprovalEvent(
    String approvalId,
    String taskId,
    String sessionId,
    String approvalType,
    String subjectType,
    String subjectId,
    Integer subjectVersion,
    String eventType,
    String feedback,
    String traceId
) {}
```

## 7. Artifact DTO 草案

### 7.1 ArtifactMeta

```java
public record ArtifactMeta(
    String artifactId,
    String taskId,
    String sessionId,
    String agentId,
    String artifactType,
    Integer version,
    String contentRef,
    Map<String, Object> metadata,
    String traceId
) {}
```

### 7.2 ArtifactCreateRequest

```java
public record ArtifactCreateRequest(
    String taskId,
    String sessionId,
    String agentId,
    String artifactType,
    Integer version,
    Object content,
    Map<String, Object> metadata,
    String traceId
) {}
```

### 7.3 ArtifactQueryResponse

```java
public record ArtifactQueryResponse(
    ArtifactMeta meta,
    Object content
) {}
```

推荐 `artifactType` 枚举先收窄为：

- `copy_draft`
- `video_script`
- `video_task`
- `listing_summary`
- `contract_summary`
- `risk_report`
- `publish_payload`
- `final_answer`

## 8. Session 事件流 DTO 草案

```java
public record SessionStreamEvent(
    String sessionId,
    String taskId,
    String agentId,
    String eventType,
    String content,
    Map<String, Object> metadata,
    String traceId,
    Long timestamp
) {}
```

推荐事件类型：

- `session.started`
- `task.started`
- `agent.started`
- `agent.delta`
- `tool.started`
- `tool.finished`
- `handoff.started`
- `handoff.completed`
- `task.waiting_approval`
- `task.approval_received`
- `task.completed`
- `task.failed`
- `session.completed`

## 9. 分阶段风险清单

### 9.1 第一阶段：主 Agent 底座

风险：

- Graph 状态和 Memory 状态混淆
- 主 Agent 过早承担领域逻辑
- A2A 协议未冻结导致后续返工

建议：

- 在第一阶段只做骨架，不做太多业务定制
- 先冻结 DTO 与事件契约

验收标准：

- `supervisor-agent-service` 可独立运行
- Graph 可跑通单链路
- session 事件流可输出
- 审批子图可中断和恢复

### 9.2 第二阶段：Listing Agent

风险：

- 主 Agent 与 Listing Agent 数据结构不统一
- 结果过大直接塞回 A2A 返回

建议：

- 大结果全部走 artifact
- 输出字段先做窄接口

验收标准：

- `supervisor -> listing` A2A 调用跑通
- `ListingAgentOutput` 稳定
- artifact 可落库并回查

### 9.3 第三阶段：Memory Service

风险：

- artifact 与 session 写入不一致
- 读取延迟导致恢复失败

建议：

- 先只做最小能力
- 先支持 `session shared memory + task artifact memory`

验收标准：

- artifact 可写入、查询、版本化
- session 共享上下文可回读
- 主 Agent 恢复执行不依赖本地内存

### 9.4 第四阶段：Marketing Agent

风险：

- 文案输出格式漂移
- 审批驳回后重生成协议不统一

建议：

- 固定 `MarketingAgentOutput`
- 固定 `ApprovalRequest.payload` 结构

验收标准：

- 生成文案可进入审批子图
- 驳回后可重生成
- 通过后可继续下游

### 9.5 第五阶段：Media Agent

风险：

- 视频任务通常是异步长任务
- 媒体任务状态和主任务状态不一致

建议：

- 把 `mediaTaskId` 统一归档到 artifact
- 子任务状态统一通过 `AgentTaskStatusResponse` 查询

验收标准：

- 媒体任务创建成功
- 视频审批链路跑通
- 双审批中断恢复稳定

### 9.6 第六阶段：Trade Agent

风险：

- 推理结果容易自由发挥
- 并行汇总时结构不稳定

建议：

- 输出必须结构化
- 并行结果先 merge 再 summarize

验收标准：

- 并行调用与汇聚跑通
- trade 输出可被主 Agent 消费

### 9.7 第七阶段：Contract Agent

风险：

- OCR/附件输入复杂
- 多模态结果稳定性差

建议：

- 先把 OCR 和风险总结解耦
- 多模态产物统一走 artifact

验收标准：

- 合同解析结果结构化可落库
- 风险报告可进入审批或汇总流程

## 10. 推荐验收清单

### 10.1 协议验收

- DTO 已冻结
- 事件类型已冻结
- 状态机已冻结
- 幂等键机制已落地

### 10.2 架构验收

- 主 Agent 不直接持有领域工具
- 子 Agent 不保存审批等待状态
- Checkpoint 与 Memory Service 分工清晰

### 10.3 功能验收

- 单 Agent 任务跑通
- handoff 跑通
- artifact 引用跑通
- 审批中断恢复跑通
- session 事件流跑通

### 10.4 运行验收

- traceId 全链路可追踪
- 子 Agent 超时可感知
- 重复回调不会造成重复执行
- 部分节点失败后可恢复或重试

## 11. 最终建议

V2.1 的核心建议可以压缩成五点：

1. 先冻结协议，再拆服务。
2. 主 Agent 先落 Graph 骨架和审批子图。
3. 子 Agent 统一吃 A2A DTO，统一吐 `structuredOutput + artifactIds`。
4. 大对象统一走 artifact，不在 A2A 里硬传。
5. 先 `listing`，再 `marketing`，再 `media`，再 `trade`，最后 `contract`。

一句话总结：

**先把主控面做稳，再逐个接入领域执行面。**
