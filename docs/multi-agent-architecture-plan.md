# 多 Agent 架构改造方案

## 1. 背景

当前 `agent-service` 本质上还是一个单 Agent 运行时：

- 一个对话入口
- 一条模型调用链
- 一个合并后的工具回调提供器
- 一套 MCP 访问方式
- 一条共享的 RAG 访问路径

这套方案对于简单问答和工具调用已经够用，但当平台能力从房源扩展到合同、交易、营销、结算等多个业务域后，单 Agent 方案会出现明显问题：

- 工具越来越多，模型每次都要看到一大堆无关能力
- 领域边界越来越模糊，`agent-service` 容易反向依赖所有业务
- 记忆无法隔离，多个任务容易互相污染
- 前端流式输出只能表达“一个 Agent 在回答”，很难表达多角色协作过程

本方案的目标，是在**不破坏现有 MCP 调用模式**、**不引入模拟 MCP 的中间层**、**不牺牲 RAG 通用性**的前提下，把当前单 Agent 架构演进成多 Agent 架构。

## 2. 设计目标

目标架构需要满足以下约束：

1. 第一阶段仍然以 `agent-service` 作为 AI 编排主服务。
2. 保留当前基于真实 MCP Client 和 Tool Callback 的调用方式。
3. 保持 RAG 是通用能力，而不是房源专属能力。
4. 避免每个请求都加载所有技能、所有工具、所有 MCP Server。
5. 支持多 Agent 协调、任务接力、流式输出。
6. 支持按会话、任务、Agent、知识域隔离记忆。
7. 支持未来业务域继续扩展，而不是把 `agent-service` 变成新的大单体。

## 3. 核心策略

推荐策略是：**单服务、多 Agent 运行时**。

这意味着：

- 第一阶段不要先拆成多个独立 Spring Boot Agent 服务
- 不要为每个 Agent 复制一套模型基础设施
- 不要为了多 Agent 重新造一层假的 MCP 协议

而是：

- 继续保留一个 `agent-service`
- 在服务内部引入多 Agent 运行时模型
- 由一个协调器决定当前该由哪个 Agent 执行
- 让每个 Agent 只加载自己需要的 skill、tool、MCP 和 RAG 范围

## 4. 目标架构

`agent-service` 内部建议分成四个逻辑层。

### 4.1 Agent Runtime 层

这一层负责执行单个 Agent 任务。

职责：

- 构建 system prompt 和上下文
- 挂载对应 skill
- 挂载对应本地工具
- 挂载对应 MCP Tool Callback
- 挂载对应 RAG namespace
- 调用模型
- 输出结构化执行结果和事件流

建议核心抽象：

```java
public record AgentDefinition(
    String agentId,
    String displayName,
    String model,
    List<String> skillIds,
    List<String> toolGroupIds,
    List<String> mcpServerNames,
    List<String> ragNamespaces,
    AgentPolicy policy
) {}
```

```java
public interface AgentRuntime {
    AgentExecutionResult execute(AgentTask task);
}
```

### 4.2 Agent Coordination 层

这一层负责多 Agent 协作。

职责：

- 判断当前 Agent 是否可以直接回答
- 路由领域任务
- 委派子任务
- handoff 后恢复原始 Agent
- 聚合多个 Agent 的输出，形成最终用户响应

建议核心抽象：

```java
public interface AgentCoordinatorService {
    AgentExecutionResult execute(AgentTask task);
    AgentExecutionResult handoff(AgentHandoffPacket packet);
}
```

```java
public record AgentExecutionResult(
    String agentId,
    String taskId,
    boolean completed,
    boolean requiresHandoff,
    String handoffTarget,
    AgentHandoffPacket handoffPacket,
    String finalText,
    Map<String, Object> structuredOutput
) {}
```

### 4.3 Memory and Context 层

这一层负责记忆隔离与受控传递。

职责：

- 会话共享记忆
- Agent 私有工作记忆
- 任务产物记忆
- 长期知识记忆

### 4.4 Capability Registry 层

这一层决定每个 Agent 可以访问什么能力。

职责：

- 注册 skill
- 注册 tool group
- 注册 MCP server 绑定关系
- 注册 RAG namespace 绑定关系
- 生成按请求装配的能力集合

这一层是避免“每次都全量加载所有工具”的关键。

## 5. 推荐的 Agent 类型

第一阶段建议引入三类 Agent。

### 5.1 SupervisorAgent

职责：

- 理解用户意图
- 判断是否需要直接回答
- 决定是否 handoff
- 汇总领域 Agent 输出
- 控制最终返回结构

约束：

- 不应该默认加载所有领域工具
- 更偏向路由、汇总、协调能力

### 5.2 DomainAgent

例如：

- `ListingAgent`
- `ContractAgent`
- `TradeAgent`
- `MarketingAgent`

职责：

- 只处理所属领域任务
- 只加载该领域的 skill、MCP tool 和 RAG namespace
- 输出结构化领域结果供 `SupervisorAgent` 汇总

### 5.3 ExecutionAgent

例如：

- `CompareAgent`
- `PublishAgent`
- `SearchAgent`

职责：

- 专门执行某类工具密集型流程
- 更关注任务完成，不强调广义推理

这类 Agent 在第一阶段不是必须，但对于高频、强工具依赖场景很有价值。

## 6. 任务模型

当前系统建议扩展出两个执行概念。

### 6.1 ConversationSession

这是前端可见的会话。

建议字段：

```java
public record ConversationSession(
    String sessionId,
    Long userId,
    String currentTaskId,
    Map<String, Object> sharedContext
) {}
```

### 6.2 AgentTask

这是内部执行单元。

建议字段：

```java
public record AgentTask(
    String taskId,
    String sessionId,
    String agentId,
    String parentTaskId,
    String handoffFromTaskId,
    Map<String, Object> inputContext,
    String userMessage,
    AgentTaskStatus status
) {}
```

这个模型允许一个用户会话下挂多个 Agent 执行节点，并保留完整可追踪的任务图。

## 7. 记忆隔离与传递

记忆不能继续被当成一个全局上下文缓冲区。

推荐拆成四层记忆。

### 7.1 Session Shared Memory

整个会话共享。

存储内容：

- 用户偏好
- 已确认约束
- 当前任务目标
- 已经稳定确认的事实

例如：

- 预算上限
- 目标城市
- 希望输出成 markdown

### 7.2 Agent Private Working Memory

当前 Agent 私有。

存储内容：

- 临时推理草稿
- tool 执行过程中间数据
- 排序过程、中间判断结果

这层默认不共享。

### 7.3 Task Artifact Memory

用于跨 Agent 传递。

存储内容：

- handoff 摘要
- 结构化输出
- tool 结果摘要
- RAG 命中摘要

这是推荐的 handoff 载体。

### 7.4 Long-Term Knowledge Memory

用于长期知识检索。

底层依赖：

- Milvus
- ES
- 业务侧产出的知识文档

这层必须保持通用能力和 namespace 化访问。

### 7.5 记忆范围抽象

```java
public enum MemoryScope {
    SESSION_SHARED,
    AGENT_PRIVATE,
    TASK_ARTIFACT,
    LONG_TERM_KNOWLEDGE
}
```

## 8. 主 Agent Graph 编排骨架

这一节描述的是单服务阶段的过渡性编排骨架；如果系统继续演进为分布式多 Agent，最终应以 [`distributed-multi-agent-a2a-plan.md`](/D:/project/BkAnentProject/BkAnentProject/docs/distributed-multi-agent-a2a-plan.md:759) 中 `supervisor-agent-service` 的 Graph 编排方案为准。

如果主 Agent 采用 Graph 框架编排多步骤任务，必须把“审批/判断”抽象成**通用审批骨架子图**，而不是为文案、视频、合同、报价分别维护一套专用审批节点。

对于“查询数据 -> 生成文案 -> 生成视频 -> 生成适配 -> 发布广告”这类链路，推荐主图只负责编排、状态迁移和暂停恢复，具体生成能力由对应 DomainAgent 或 ExecutionAgent 完成，审批则统一走复用的 `approval_subgraph`。

### 8.1 推荐主流程

推荐把主流程拆成下面这些节点：

1. `load_task_context`
2. `query_listing_data`
3. `generate_copy`
4. `build_copy_approval_request`
5. `approval_subgraph`
6. `generate_video`
7. `build_video_approval_request`
8. `approval_subgraph`
9. `generate_adaptation`
10. `publish_ad`
11. `finish_task`

其中：

- `generate_copy` 和 `generate_video` 是生成节点
- `build_*_approval_request` 是业务侧组装审批请求的节点
- `approval_subgraph` 是通用审批子图，不关心当前审批对象是文案、视频、合同还是其他业务产物
- 通用审批子图必须支持 `通过`、`驳回重生成`、`终止任务` 三种分支

工程实现上不要保留文案审批、视频审批这类专用审批节点名称，而是统一由业务节点产出 `ApprovalRequest` 后进入同一个审批子图。

### 8.2 为什么审批必须抽成通用子图

如果继续为每个业务单独加审批节点，会有四个问题：

1. 不同业务会复制相同的持久化、事件发送、中断恢复逻辑。
2. 多个审批节点的状态结构会逐步分叉，后续很难统一前端和任务中心。
3. 通过/驳回/终止的语义相同，却被拆成多个近似实现，维护成本高。
4. Spring AI Alibaba Graph 已经支持子图嵌套、检查点和中断恢复，没有必要把审批实现成大量专用节点。

所以更合理的做法是：

- 业务图负责“生成什么”
- 通用审批子图负责“怎么等用户、怎么恢复、怎么分支”

### 8.3 Graph State 建议

主 Agent 的 graph state 不应绑定在文案、视频这类专用字段上，而应改成通用结构：

```java
public record WorkflowState(
    String taskId,
    String sessionId,
    String currentNode,
    WorkflowStatus status,
    ApprovalRequest pendingApproval,
    ApprovalDecision latestApprovalDecision,
    Map<String, Artifact> artifacts,
    Map<String, Object> context,
    List<String> auditTrail
) {}
```

```java
public record Artifact(
    String artifactId,
    String artifactType,
    Integer version,
    Object content,
    Map<String, Object> metadata
) {}
```

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

```java
public enum WorkflowStatus {
    RUNNING,
    WAITING_USER_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELED
}
```

```java
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    TERMINATED
}
```

这里有几个要求：

- `artifacts` 必须支持不同业务对象统一存储，不能只适配文案和视频。
- `pendingApproval` 必须是当前唯一待审对象，便于前端和任务中心统一展示。
- `feedback` 必须进入状态，下一轮重生成要显式消费。
- `subjectVersion` 和 `retryCount` 必须受控，防止无限重生成。

### 8.4 通用审批子图骨架

推荐把审批实现成可复用子图，而不是单个业务节点：

```text
prepare_approval -> persist_and_interrupt -> await_resume -> route_by_decision
```

子图职责：

- 接收上游业务节点组装好的 `ApprovalRequest`
- 持久化 `pendingApproval`
- 将任务状态置为 `WAITING_USER_APPROVAL`
- 发出待审批事件
- 中断 graph 执行
- 接收外部 `APPROVED / REJECTED / TERMINATED`
- 根据 `ApprovalRequest` 内的下一跳配置返回出口

这个子图本身不关心审批对象是什么，只关心审批协议。

### 8.5 业务节点职责

#### `query_listing_data`

职责：

- 查询房源、门店、品牌、渠道等发布所需数据
- 统一归档为结构化上下文
- 生成后续节点可直接消费的规范输入

约束：

- 失败时可以重试
- 成功后不能再因为审批驳回而重复查询，除非上游输入变化

#### `generate_copy`

职责：

- 基于查询结果、品牌约束、渠道约束生成文案
- 把产物写入 `artifacts`
- 每次重生成都递增版本号

#### `build_copy_approval_request`

职责：

- 读取文案产物
- 组装通用 `ApprovalRequest`
- 指定：
  - `subjectType=copy`
  - `approveNextNode=generate_video`
  - `rejectNextNode=generate_copy`
  - `terminateNextNode=cancel_task`

#### `generate_video`

职责：

- 基于已通过的文案、房源素材、品牌模板生成视频脚本或视频任务
- 把视频产物写入 `artifacts`
- 如果底层是异步视频生成，还要保存媒体任务 id

#### `build_video_approval_request`

职责：

- 读取视频产物
- 组装通用 `ApprovalRequest`
- 指定：
  - `subjectType=video`
  - `approveNextNode=generate_adaptation`
  - `rejectNextNode=generate_video`
  - `terminateNextNode=cancel_task`

#### `generate_adaptation`

职责：

- 基于已通过的文案和视频，生成各渠道适配内容
- 例如抖音标题、小红书正文、贝壳摘要、封面文案等

约束：

- 不应在这个节点再修改已通过的核心语义
- 如果渠道约束冲突，应返回结构化冲突信息，由主 Agent 决定是否追加人工确认

#### `publish_ad`

职责：

- 调用推广/营销发布能力
- 记录每个平台的发布结果
- 返回可查询的发布流水

### 8.6 Graph 条件分支

建议把审批后的跳转统一交给通用路由器，而不是散落在业务节点里：

```java
public interface ApprovalRouter {
    String route(WorkflowState state, ApprovalDecision decision);
}
```

推荐分支规则：

```text
approval_subgraph:
- APPROVED -> pendingApproval.approveNextNode
- REJECTED and retryCount < maxRetryCount -> pendingApproval.rejectNextNode
- REJECTED and retryCount >= maxRetryCount -> failed_or_manual_intervention
- TERMINATED -> pendingApproval.terminateNextNode
```

这里要特别注意：

- “用户驳回”不是异常，不应直接算系统失败。
- “超过最大重试次数”才应该转成失败或人工介入。
- “审批通过”与“生成成功”是两个不同状态，不能混用。

### 8.7 中断与恢复机制

主 Agent graph 必须支持暂停恢复，而不是在审批逻辑里同步等待。

推荐模型：

1. 图执行到 `approval_subgraph`
2. 持久化完整 `WorkflowState` 和当前 node
3. 发出 `task.waiting_approval` 事件
4. 结束本次执行
5. 用户在前端点击通过、驳回或终止
6. 外部接口写入审批结果
7. 主 Agent 根据 `taskId` 重新加载 state
8. 从审批子图出口后的条件分支继续执行

建议抽象：

```java
public interface GraphCheckpointStore {
    void save(WorkflowState state);
    Optional<WorkflowState> load(String taskId);
}
```

```java
public interface ApprovalCommandService {
    void approve(String taskId, String reviewerId);
    void reject(String taskId, String reviewerId, String feedback);
    void terminate(String taskId, String reviewerId, String reason);
}
```

这个模型的关键是：

- graph 负责流程推进
- DB/Redis 负责状态持久化
- 前端负责用户交互
- 审批接口负责写回状态并触发 resume

不要让 Agent 自己“记住正在等用户”，这会把编排逻辑和对话逻辑混在一起。

### 8.8 用户驳回后的重生成规则

用户点“不通过”后，不能简单再次调用同一个生成 prompt，而应该走“带反馈的重生成”。

建议重生成输入统一结构化：

```java
public record RegenerationCommand(
    String taskId,
    String artifactId,
    String artifactType,
    Integer previousVersion,
    Object previousContent,
    String userFeedback,
    List<String> immutableConstraints,
    Integer retryCount
) {}
```

通用原则是：

- 上一版产物必须带入
- 用户反馈必须带入
- 已确认不可变约束必须带入
- 当前重试次数必须带入

文案、视频、合同摘要、定价建议都遵循同一套重生成协议，只是业务 prompt 不同。

### 8.9 审批事件模型

如果前端要清晰展示流程，建议把审批统一发成标准事件：

- `task.waiting_approval`
- `task.approval_received`
- `task.approval_rejected`
- `task.approval_approved`
- `task.approval_terminated`
- `task.regeneration_started`

建议事件载荷至少包含：

```java
public record ApprovalEvent(
    String taskId,
    String sessionId,
    String approvalType,
    String subjectType,
    String subjectId,
    Integer subjectVersion,
    String eventType,
    String feedback
) {}
```

这样前端、通知中心、运营后台都能统一消费。

### 8.10 与多 Agent 协作的关系

审批子图通常应该放在主 Agent graph 中，而不是下沉到子 Agent 内部。

原因是：

1. 审批是任务编排控制点，不只是内容生成细节。
2. 审批通过后是否继续下游，取决于主流程，不取决于单个生成 Agent。
3. 主 Agent 更适合统一处理重试上限、人工介入、任务取消、事件流。

推荐职责边界：

- `MarketingAgent` 负责生成文案
- `MediaAgent` 负责生成视频
- `SupervisorAgent` 或主编排 graph 负责审批子图、状态持久化和恢复

### 8.11 推荐伪代码

下面这类骨架比“一个大 while 循环 + if 判断”更稳定：

```java
public class MarketingWorkflowGraph {

    public WorkflowState run(WorkflowState state) {
        while (state.status() == WorkflowStatus.RUNNING) {
            state = switch (state.currentNode()) {
                case "load_task_context" -> loadTaskContext(state);
                case "query_listing_data" -> queryListingData(state);
                case "generate_copy" -> generateCopy(state);
                case "build_copy_approval_request" -> buildCopyApprovalRequest(state);
                case "approval_subgraph" -> runApprovalSubgraph(state);
                case "generate_video" -> generateVideo(state);
                case "build_video_approval_request" -> buildVideoApprovalRequest(state);
                case "generate_adaptation" -> generateAdaptation(state);
                case "publish_ad" -> publishAd(state);
                case "finish_task" -> finishTask(state);
                default -> fail(state, "unknown node: " + state.currentNode());
            };
        }
        return state;
    }
}
```

其中审批子图要显式返回暂停态：

```java
private WorkflowState runApprovalSubgraph(WorkflowState state) {
    checkpointStore.save(
        state.withStatus(WorkflowStatus.WAITING_USER_APPROVAL)
    );
    eventPublisher.publishWaitingApproval(state.taskId(), state.pendingApproval());
    return state.withStatus(WorkflowStatus.WAITING_USER_APPROVAL);
}
```

恢复执行时不重新跑整张图，而是从 checkpoint 继续：

```java
public WorkflowState resumeAfterApproval(
    String taskId,
    ApprovalStatus approvalStatus,
    String feedback
) {
    WorkflowState state = checkpointStore.load(taskId)
        .orElseThrow();
    WorkflowState updated = state.withLatestApprovalDecision(
        new ApprovalDecision(approvalStatus, feedback, null, LocalDateTime.now())
    ).withStatus(WorkflowStatus.RUNNING);
    return run(routeAfterApproval(updated));
}
```

如果使用 Spring AI Alibaba Graph，推荐进一步把这段能力抽成可复用子图工厂：

```java
public interface ApprovalSubgraphFactory {
    StateGraph<WorkflowState> create();
}
```

业务图只需要：

1. 生成业务产物
2. 组装 `ApprovalRequest`
3. 调用 `approval_subgraph`
4. 消费审批结果后的出口

这个骨架说明了三件事：

- 审批是 graph 的显式中断点
- 审批实现应该是可复用子图，而不是业务专用节点
- 恢复是从状态继续，不是重新发起一次全新会话

## 9. Handoff 模型

Handoff 必须是显式的、结构化的、受控的。

### 9.1 支持的 Handoff 类型

#### Route Handoff

协调器把任务路由给领域 Agent。

例如：

- 房源推荐 -> `ListingAgent`
- 合同条款解析 -> `ContractAgent`

#### Escalation Handoff

当前 Agent 无法完成任务，升级给更高能力 Agent。

例如：

- 局部推荐升级给 `SupervisorAgent` 做跨域整合
- 普通房源咨询升级给交易 Agent 做成交可行性分析

#### Delegate Handoff

当前 Agent 委派一个子任务，等待结果后继续。

例如：

- 营销 Agent 委派房源卖点提炼
- `SupervisorAgent` 委派业务 KPI 查询

### 9.2 Handoff Packet

Handoff 不应该传完整原始对话 history。

应该传紧凑的结构化载荷：

```java
public record AgentHandoffPacket(
    String fromAgent,
    String toAgent,
    String reason,
    String userGoal,
    Map<String, Object> structuredContext,
    List<KnowledgeSnippet> evidence,
    List<String> constraints,
    String expectedOutput
) {}
```

这个对象将成为跨 Agent 交接的标准协议。

## 10. 多 Agent 下的流式输出

前端不应该收到多个 Agent 各自乱序的流。

正确模型应该是：

- 一个 session 流
- 流里包含多个事件类型

建议事件类型：

- `session.started`
- `agent.started`
- `agent.delta`
- `tool.started`
- `tool.finished`
- `handoff.started`
- `handoff.finished`
- `agent.completed`
- `session.completed`

建议事件模型：

```java
public record AgentStreamEvent(
    String sessionId,
    String taskId,
    String agentId,
    String eventType,
    String content,
    Map<String, Object> metadata
) {}
```

这样前端可以：

- 只展示最终统一回答
- 展示透明的多 Agent 协作轨迹
- 逐步展示工具调用与 handoff 过程

重点是：  
即使发生 handoff，前端流也不应该断。

## 11. Skill 机制

Skill 不应该只是一个全局 prompt 片段。

一个 skill 应该是一个能力包，包含：

- 指令内容
- 关联 tool group
- 允许访问的 MCP server
- 允许访问的 RAG namespace

建议抽象：

```java
public record SkillDefinition(
    String skillId,
    String name,
    String description,
    String promptTemplate,
    List<String> toolGroupIds,
    List<String> allowedMcpServers,
    List<String> ragNamespaces
) {}
```

### 11.1 Agent 与 Skill 的绑定

每个 Agent 必须显式声明自己可以使用哪些 skill。

例如：

- `ListingAgent` -> `listing-search`, `listing-recommendation`
- `ContractAgent` -> `contract-review`
- `MarketingAgent` -> `marketing-copy`

### 11.2 skill.md 的可见性控制

当前要求是：指定 Agent 只能获取自己的 `skill.md`。

推荐实现方式：

1. 在 `SkillRegistry` 中注册所有 skill
2. 通过配置或代码把 skill 绑定给 Agent
3. 在运行时只把该 Agent 对应的 skill 内容装入本次请求上下文

也就是说：

- `ListingAgent` 看不到 `contract-review.md`
- `ContractAgent` 看不到 `marketing-copy.md`

这个控制必须发生在模型调用前，而不是只靠 prompt 中的约束文字。

## 12. Tool 加载策略

当前单 Agent 模式天然会走向一个全局合并的 Tool Callback Provider。

这个模式适合早期原型，但不适合多 Agent 生产场景。

多 Agent 设计建议改成：

- 全局 MCP Client 池
- 按请求动态装配 Tool Callback
- 按 Agent 做能力裁剪

### 12.1 Tool Group

```java
public record ToolGroupDefinition(
    String groupId,
    List<String> localToolBeanNames,
    List<String> mcpServerNames
) {}
```

### 12.2 运行时装配链路

运行时能力解析链路建议是：

`AgentDefinition -> Skills -> ToolGroups -> ToolCallbacks`

这样可以保证：

- `ListingAgent` 只加载房源相关工具
- `MarketingAgent` 只加载营销相关工具
- `ContractAgent` 不会看到房源或营销工具，除非明确授权

### 12.3 为什么这一步关键

这一步直接解决两个问题：

1. 工具列表太大，导致 prompt 膨胀
2. 模型能看到太多无关工具，导致 function calling 选错工具

## 13. MCP 模型

本架构必须保留现有 MCP 模式。

这意味着：

- 继续使用真实 MCP Server
- 继续使用真实 `McpSyncClient`
- 继续使用 Spring AI 的 `SyncMcpToolCallbackProvider`
- 不引入模拟层，不重写 MCP 协议

真正变化的只是**挂载策略**，不是**调用协议**。

推荐模型：

- 全局维护一组 `NamedMcpSyncClient`
- 每个 Agent 运行时只取其允许访问的子集
- 用该子集构建当前请求范围内的 `SyncMcpToolCallbackProvider`

这样既保留当前 MCP 投资，又实现了按 Agent 隔离工具可见性。

## 14. RAG 的通用性

RAG 必须继续作为平台通用能力，而不是绑定某个 Agent。

上层建议逐步抽象成统一检索契约：

```java
public record KnowledgeQuery(
    String bizType,
    String namespace,
    String query,
    Map<String, Object> filters,
    Integer topK
) {}
```

```java
public interface KnowledgeRetrievalService {
    List<KnowledgeHit> search(KnowledgeQuery query);
}
```

每个 Agent 只声明自己允许访问哪些 namespace。

例如：

- `ListingAgent` -> `listing/*`
- `ContractAgent` -> `contract/*`
- `MarketingAgent` -> `listing/summary`, `marketing/*`

这样既保住 RAG 的通用能力，又不会让每个 Agent 默认拿到全库权限。

## 15. 推荐的改造顺序

这套架构应该分阶段演进，不建议一次性全改。

### 第一阶段：能力建模

新增：

- `AgentDefinition`
- `SkillDefinition`
- `ToolGroupDefinition`
- `CapabilityRegistry`

这一阶段系统仍然可以继续以单逻辑 Agent 运行。

### 第二阶段：按请求挂载 Tool

把当前全局一次性挂载所有工具的方式，改成按请求动态解析。

这是多 Agent 真正的基础。

### 第三阶段：任务、Graph 与 Handoff 协议

新增：

- `ConversationSession`
- `AgentTask`
- `WorkflowState`
- `AgentExecutionResult`
- `AgentHandoffPacket`
- `GraphCheckpointStore`
- `ApprovalCommandService`

让 handoff、审批节点、暂停恢复都成为一等执行行为。

### 第四阶段：最小多 Agent + 审批闭环

先实现：

- `SupervisorAgent`
- `ListingAgent`
- `MarketingAgent`
- `MediaAgent`

优先验证：

- 路由
- handoff
- 记忆传递
- 流式输出连续性
- 文案审批驳回回路
- 视频审批驳回回路
- checkpoint 恢复正确性

### 第五阶段：记忆分层

拆分：

- 会话共享记忆
- Agent 私有记忆
- 任务产物记忆

长期知识记忆暂时可以继续依赖现有 Milvus 和 ES 基础。

### 第六阶段：Session 事件流

把前端流式输出从“一个原始回答流”升级为“一个 session 事件流”。

这样可以透明表达多 Agent 执行过程，同时不破坏前端协议稳定性。

### 第七阶段：领域扩展

在前面基础稳定后，再逐步增加：

- `ContractAgent`
- `TradeAgent`
- `MarketingAgent`

## 16. 与当前项目的映射关系

本方案明确是建立在当前仓库基础上的。

### 16.1 应该保留的部分

- 当前 MCP 连接模式
- 当前 Milvus / RAG 基础实现
- 当前业务侧知识文档生产模式
- 当前 Spring AI Tool Callback 模型

### 16.2 需要演进的部分

- `AgentOrchestratorService`
  - 从单 Agent 聊天入口演进为协调入口

- `AgentChatService`
  - 演进为可复用的单 Agent Runtime 级模型调用组件

- `AgentServiceConfiguration`
  - 从静态合并 callback 改成按请求装配能力

- 当前 tool 加载方式
  - 从全局挂载改成 skill 和 Agent 维度的裁剪挂载

### 16.3 建议新增的包结构

建议未来增加这些包：

```text
com.bkanent.agent.runtime
com.bkanent.agent.coordinator
com.bkanent.agent.memory
com.bkanent.agent.skill
com.bkanent.agent.capability
com.bkanent.agent.stream
com.bkanent.agent.task
```

## 17. 最终建议

这个项目走向多 Agent，正确方向不是“再起很多个 agent-service”。

正确方向是：

- 继续保留一个 `agent-service`
- 在内部引入多 Agent Runtime 模型
- 以 `Supervisor + DomainAgent` 作为基础协作模式
- 以 `skill -> tool group -> MCP/RAG namespace` 作为能力隔离模型
- 以 `handoff packet + task artifact memory` 作为跨 Agent 传递模型
- 以统一 `session event stream` 作为前端流式输出模型
- 保持 MCP 和 RAG 基础设施真实且通用

这样既不会推翻当前在 Spring AI、MCP、RAG 上的投入，也能让系统具备演进成真正多 Agent 平台的能力。
