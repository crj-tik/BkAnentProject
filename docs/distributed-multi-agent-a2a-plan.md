# 分布式多 Agent 与 A2A 改造方案

## 1. 文档目的

本文档定义的是**多 Agent 拆分到不同服务**时的目标架构、协议边界、记忆体系、注册发现、消息传递、流式输出、技能隔离、RAG 通用化，以及推荐的分阶段实施步骤。

这份方案是后续架构改造的长期依据，目标是：

- 让主 Agent 负责统一编排
- 让领域 Agent 以独立服务形式部署和演进
- 保留当前 MCP 调用模式
- 引入 A2A 作为 Agent 间标准通信协议
- 保持 RAG 仍然是通用知识增强能力
- 给后续代码改造提供稳定路线图

## 2. 当前问题

当前项目以单 `agent-service` 为中心，适合单 Agent 执行，但如果后续要升级成分布式多 Agent，会遇到以下问题：

1. 所有能力集中在一个服务中，领域边界越来越模糊。
2. MCP 适合 Agent 调工具，但不适合作为 Agent 间通信协议。
3. 多 Agent 协作时，记忆隔离和传递没有统一模型。
4. Agent 服务之间缺少能力发现机制。
5. 子 Agent 的结果、状态和流式输出缺少统一回传路径。
6. skill、tool、RAG 都容易继续沿着“全局加载”的方向失控。

## 3. 核心结论

如果多 Agent 拆分到不同服务，推荐采用以下组合：

- `主 Agent` 负责统一任务编排
- `子 Agent` 按领域拆为独立服务
- `MCP` 继续负责 Agent 到工具
- `A2A` 负责 Agent 到 Agent
- `Nacos` 负责服务注册发现
- `AgentCard` 负责 Agent 能力发现
- `Memory Service` 负责共享记忆、私有记忆和任务产物管理
- `Event Bus` 负责异步状态、长任务和事件流

一句话概括：

**A2A 负责 Agent 间协议，MCP 负责工具调用，Nacos 负责发现，Memory Service 负责上下文，Event Bus 负责异步协作。**

## 4. 总体架构

推荐的目标拓扑如下：

- `supervisor-agent-service`
  - 前端统一入口
  - 统一会话管理
  - 主任务编排
  - Graph 编排骨架
  - 通用审批子图
  - handoff 控制
  - 结果汇总
  - 流式输出聚合

- `listing-agent-service`
  - 房源问答
  - 房源推荐
  - 房源检索
  - 房源领域 RAG

- `contract-agent-service`
  - 合同解析
  - 条款审阅
  - 风险提示

- `trade-agent-service`
  - 交易分析
  - 流程判断
  - 可成交性分析

- `marketing-agent-service`
  - 营销文案
  - 平台内容发布
  - 推广策略建议

- `memory-service`
  - 会话共享记忆
  - Agent 私有记忆
  - 任务产物存储
  - handoff artifact 查询

- `knowledge infrastructure`
  - Milvus
  - ES
  - 业务侧产出的 KnowledgeDocument

- `event-bus`
  - Kafka / RocketMQ / Redis Stream

## 5. 协议边界

这是整个方案最关键的一部分。

### 5.1 MCP 的职责

MCP 负责：

- Agent 调工具
- Agent 调外部业务能力
- Agent 调业务系统工具接口

也就是说：

- `listing-agent-service` 可以通过 MCP 调房源查询工具
- `marketing-agent-service` 可以通过 MCP 调营销发布工具

### 5.2 A2A 的职责

A2A 负责：

- Agent 到 Agent 的协作
- Agent 能力发现
- Agent 任务创建、推进、查询、回调
- Agent 之间标准化消息交换

也就是说：

- `supervisor-agent-service` 调 `listing-agent-service`
- `listing-agent-service` 必要时再调 `trade-agent-service`

### 5.3 不建议的做法

不建议：

- 用 MCP 模拟 Agent 间通信
- 把子 Agent 暴露成一堆 function 再让主 Agent 全量加载
- 把 A2A 当成 memory 系统或注册中心替代品

## 6. Agent 注册与发现

分布式多 Agent 下，注册发现建议拆成两层。

### 6.1 第一层：Nacos 负责服务发现

继续使用当前已有的 `Nacos`。

职责：

- 服务实例注册
- 服务地址发现
- 健康状态检查

例如：

- `listing-agent-service`
- `contract-agent-service`
- `marketing-agent-service`

都作为普通微服务注册到 `Nacos`。

### 6.2 第二层：AgentCard 负责能力发现

每个 Agent 服务需要暴露自己的 `AgentCard`。

建议 `AgentCard` 包含：

- `agentId`
- `name`
- `description`
- `version`
- `supportedSkills`
- `supportedDomains`
- `supportsStreaming`
- `supportsAsyncTask`
- `a2aEndpoint`
- `inputModes`
- `outputModes`

例如：

```json
{
  "agentId": "listing-agent",
  "name": "Listing Agent",
  "description": "负责房源检索、推荐与分析",
  "version": "1.0.0",
  "supportedSkills": ["listing-search", "listing-recommendation"],
  "supportedDomains": ["listing"],
  "supportsStreaming": true,
  "supportsAsyncTask": true,
  "a2aEndpoint": "http://listing-agent-service/a2a"
}
```

### 6.3 主 Agent 内部的 AgentRegistry

主 Agent 不应该每次请求都临时查所有 Agent。

建议在主 Agent 服务中维护一个 `AgentRegistry`：

```java
public interface AgentRegistry {
    AgentDescriptor getByAgentId(String agentId);
    List<AgentDescriptor> findBySkill(String skillId);
    List<AgentDescriptor> findByDomain(String domain);
}
```

其中 `AgentDescriptor` 由：

- `Nacos` 服务元信息
- `AgentCard`

组合而成。

## 7. Agent 间调用模型

不建议只用同步 HTTP 调用。

推荐拆成两类通道。

### 7.1 同步调用通道

适用于：

- 短任务
- 需要立即返回
- 需要边执行边流式输出

这里建议直接使用：

- `A2A HTTP`
- `A2A Streaming`

这部分适合：

- 查询类任务
- 推荐类任务
- 简短分析类任务

### 7.2 异步调用通道

适用于：

- 长任务
- 可能失败重试的任务
- 需要异步回调的任务
- 需要状态恢复的任务

这里建议走：

- `A2A 任务创建`
- `Event Bus` 事件通知

推荐事件：

- `task.created`
- `task.started`
- `task.progress`
- `task.waiting_input`
- `task.completed`
- `task.failed`
- `task.canceled`

### 7.3 推荐原则

推荐组合：

- 控制面：`A2A`
- 事件面：`MQ / Event Bus`

不要试图让一次同步 HTTP 把所有任务生命周期都承载完。

## 8. Memory 架构

A2A 不负责 memory 体系，所以 memory 必须单独设计。

推荐拆成四层。

### 8.1 Session Shared Memory

整个用户会话共享。

存储：

- 用户偏好
- 已确认约束
- 当前主任务目标
- 已确认稳定事实

例如：

- 用户只接受浦东
- 预算不超过 800 万
- 最终输出需要中文 markdown

### 8.2 Agent Private Memory

每个 Agent 的私有工作记忆。

存储：

- 中间推理草稿
- 临时 tool 结果
- 领域内部中间状态

默认不共享。

### 8.3 Task Artifact Memory

这是跨 Agent 协作最关键的一层。

它不是“会话记忆”，而是“任务产物存储”。

存储：

- 子任务摘要
- 结构化输出
- RAG 证据摘要
- 工具调用结果摘要
- handoff 交接材料

主 Agent 与子 Agent 之间，不应直接传完整 history，而应主要传这层内容。

### 8.4 Long-Term Knowledge Memory

长期知识记忆继续使用现有知识底座：

- Milvus
- ES
- 业务服务产出的 KnowledgeDocument

这一层必须保持通用，并基于 `bizType` / `namespace` 访问。

## 9. Memory Service 的职责

推荐新增独立 `memory-service`。

它的职责不是替代 RAG，而是管理短中期上下文和任务产物。

建议职责：

- 维护 session shared memory
- 维护 agent private memory
- 存储 task artifact
- 提供 artifact 引用读取接口
- 提供 handoff packet 关联查询能力

建议存储分层：

- `Redis`
  - 会话共享记忆
  - 热数据
  - 短期状态

- `MySQL / PostgreSQL`
  - task metadata
  - handoff relation
  - artifact 索引

- `对象存储 / DB`
  - 大体积 artifact 内容

- `Milvus / ES`
  - 长期知识检索

## 10. Handoff 机制

在分布式场景下，handoff 必须是显式任务迁移，而不是一段 prompt。

### 10.1 Handoff 的本质

handoff 本质上是：

- 父任务挂起
- 子任务创建
- 子任务产物回收
- 父任务恢复

### 10.2 Handoff Packet

建议标准对象：

```java
public record AgentHandoffPacket(
    String taskId,
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

这里：

- `structuredContext` 放轻量上下文
- `artifactIds` 指向 `memory-service` 中的任务产物
- `constraints` 放明确边界条件
- `expectedOutput` 放对子 Agent 的交付要求

### 10.3 推荐任务状态机

建议任务状态：

- `submitted`
- `working`
- `handoff_pending`
- `handed_off`
- `waiting_input`
- `completed`
- `failed`
- `canceled`

### 10.4 Handoff 恢复流程

推荐流程：

1. 主 Agent 创建主任务
2. 主 Agent 决定把子任务 handoff 给某个领域 Agent
3. 生成 `AgentHandoffPacket`
4. 调用目标 Agent 的 A2A endpoint
5. 子 Agent 接收并创建子任务
6. 子任务完成后，把结果写入 `Task Artifact Memory`
7. 主 Agent 收到完成事件，恢复父任务
8. 主 Agent 汇总最终结果

## 11. 流式输出设计

分布式多 Agent 下，前端不应该分别连接每个子 Agent。

推荐模型是：

- 前端只连接 `supervisor-agent-service`
- 主 Agent 对外提供统一 session 事件流
- 子 Agent 的流式输出先回到主 Agent
- 主 Agent 统一转发给前端

### 11.1 统一事件模型

```java
public record SessionStreamEvent(
    String sessionId,
    String taskId,
    String agentId,
    String eventType,
    String content,
    Map<String, Object> metadata
) {}
```

### 11.2 建议事件类型

- `session.started`
- `agent.started`
- `agent.delta`
- `tool.started`
- `tool.finished`
- `handoff.started`
- `handoff.completed`
- `task.completed`
- `session.completed`

### 11.3 流式传递原则

如果子 Agent 支持 A2A streaming：

- 主 Agent 边收边转

如果子 Agent 是长任务：

- 主 Agent 按任务状态发事件

核心目标是：

- 前端流不断
- 事件统一
- 用户看到的是一个完整协作过程，而不是多个散乱响应

## 12. Skill 机制

分布式多 Agent 下，skill 建议跟 Agent 服务本地走，而不是全局共享给所有 Agent。

### 12.1 Skill 的归属

例如：

- `listing-agent-service` 持有自己的 `skill.md`
- `contract-agent-service` 持有自己的 `skill.md`
- `marketing-agent-service` 持有自己的 `skill.md`

### 12.2 主 Agent 是否需要读取所有 skill

不需要。

主 Agent 只需要知道：

- 某个 Agent 具备什么 skill
- 某个 Agent 可以处理什么类型任务

真正 skill 内容应只在对应 Agent 内部使用。

### 12.3 Skill 对外暴露什么

对外只暴露 capability 摘要，不暴露全部内部 skill 内容。

例如在 `AgentCard` 中声明：

- `supportedSkills`
- `supportedDomains`
- `expectedInput`
- `expectedOutput`

## 13. Tool 与 MCP 的边界

当前项目对 MCP 已经有投入，因此分布式多 Agent 改造时不应推倒重来。

建议做法：

- Agent 内部继续使用 MCP 调工具
- Agent 之间用 A2A 通信
- 不把子 Agent 模拟成 MCP tool

### 13.1 为什么不建议把子 Agent 伪装成 Tool

因为这样会带来问题：

- 失去 Agent 自治边界
- handoff 变成普通 function calling，难以管理任务状态
- 记忆和 artifact 很难独立建模
- 流式输出和异步状态不容易表达

### 13.2 正确关系

正确关系应该是：

- `A2A` 管 Agent 到 Agent
- `MCP` 管 Agent 到 Tool

## 14. RAG 的通用性要求

分布式多 Agent 之后，RAG 不能被拆成“每个 Agent 各玩各的私有检索”。

正确方向是：

- 知识底座共享
- 检索接口统一
- 访问 namespace 隔离

建议统一检索抽象：

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

各 Agent 只声明自己允许访问的 namespace。

例如：

- `listing-agent` -> `listing/*`
- `contract-agent` -> `contract/*`
- `marketing-agent` -> `listing/summary`, `marketing/*`

## 15. 安全与权限边界

分布式多 Agent 之后，权限必须明确。

建议控制点：

1. 主 Agent 不自动拥有所有领域知识访问权限。
2. 子 Agent 只能访问自己授权的 skill、tool、MCP server、RAG namespace。
3. handoff packet 只传必要信息，不传完整内部推理。
4. `memory-service` 对 artifact 访问做 task 级权限控制。
5. A2A 调用链需要带调用方身份和 trace id。

## 16. 推荐的技术选型

结合当前项目，推荐如下：

### 16.1 服务注册发现

- `Nacos`

### 16.2 Agent 间协议

- `A2A`

### 16.3 Agent 内部工具协议

- `MCP`

### 16.4 异步事件总线

三选一即可：

- Kafka
- RocketMQ
- Redis Stream

### 16.5 Memory Service 存储

- Redis
- MySQL / PostgreSQL
- 对象存储或数据库大字段

### 16.6 长期知识

- Milvus
- ES

## 17. 实施步骤

下面是推荐的落地顺序，按这个顺序改造最稳。

### 第一步：在文档层确认协议边界

明确以下原则：

- MCP 只负责 Agent 到 Tool
- A2A 只负责 Agent 到 Agent
- RAG 保持通用底座
- memory 独立建模

这是后续所有代码改造的前提。

### 第二步：抽象 AgentCard 和 AgentRegistry

先在当前单体思路上引入：

- `AgentCard`
- `AgentDescriptor`
- `AgentRegistry`

即使暂时还没有拆服务，也先把能力发现模型定下来。

### 第三步：抽象 HandoffPacket 与 Task 模型

新增统一对象：

- `ConversationSession`
- `AgentTask`
- `AgentHandoffPacket`
- `AgentExecutionResult`

让后续 handoff 和事件流有稳定载体。

### 第四步：先拆出 SupervisorAgent 的逻辑边界

即使现在还在一个服务里，也先在代码结构上分出：

- `SupervisorAgent`
- `ListingAgentRuntime`
- `SupervisorGraph`
- `ApprovalSubgraph`

先跑通逻辑上的主从协作。

### 第五步：把 ListingAgent 独立成服务

第一阶段不建议一口气拆很多 Agent。

建议先拆：

- `listing-agent-service`

因为当前系统中房源域能力最成熟，最适合做第一块样板。

### 第六步：引入 A2A 调 Supervisor -> Listing

这一阶段目标是：

- 主 Agent 不再直接调用 Listing Runtime
- 主 Agent 改为走 A2A 调用 `listing-agent-service`

同时：

- 继续保留当前 MCP 模式
- 继续保留当前 RAG 底座

### 第七步：引入 Memory Service

先支持：

- session shared memory
- task artifact memory

Agent private memory 可以先简化，后续再增强。

### 第八步：引入统一 Session 事件流

把前端流式输出统一到：

- `sessionId`
- `taskId`
- `agentId`
- `eventType`

并显式纳入：

- `task.waiting_approval`
- `task.approval_received`

前端仍然只连主 Agent。

### 第九步：再拆 ContractAgent / MarketingAgent / TradeAgent

在：

- 注册发现
- A2A 调用
- memory-service
- session event stream

都跑稳以后，再继续扩展更多领域 Agent。

### 第十步：收敛 skill、tool、RAG namespace 权限

最后集中做能力裁剪：

- skill 只在所属 Agent 内可见
- tool 按 Agent 范围加载
- MCP server 按 Agent 授权
- RAG namespace 按 Agent 隔离

## 18. 第一阶段最小可行目标

建议第一阶段只追求下面这个闭环：

1. 保留 `supervisor-agent-service` 作为前端统一入口
2. 新增 `listing-agent-service`
3. 主 Agent 通过 A2A 调 listing Agent
4. listing Agent 内部继续 MCP + RAG
5. 引入最小版 `memory-service`
6. 主 Agent 聚合 session 级流式输出

只要这个闭环跑通，后面加合同、交易、营销 Agent 就只是复制架构模式，而不是重新设计一遍。

## 19. 最终建议

对于这个项目，分布式多 Agent 的正确方向不是：

- 用 MCP 假装 Agent 协作
- 把所有子 Agent 暴露成全局 function calling
- 继续把所有能力集中堆进一个 `agent-service`

正确方向是：

- `Nacos` 负责服务发现
- `AgentCard` 负责能力发现
- `A2A` 负责 Agent 间标准通信
- `MCP` 负责 Agent 内工具调用
- `Memory Service` 负责共享记忆与任务产物
- `Event Bus` 负责异步状态与长任务事件
- `Supervisor Agent` 负责统一编排与前端输出

这样既能延续当前项目的技术积累，也能为后续真正的分布式多 Agent 平台打下稳定基础。

## 20. 主 Agent 使用 Spring AI Alibaba Graph 的方案

前面的方案默认主 Agent 可以先用普通编排服务实现，也就是：

- 主 Agent 接收入参
- 主 Agent 决定路由
- 主 Agent 调子 Agent
- 主 Agent 汇总结果

这种实现方式足够直接，但当主 Agent 的编排越来越复杂时，单纯靠普通 Service 代码会越来越难维护，尤其是在以下场景：

- 需要显式表达节点与分支
- 需要并行调用多个子 Agent
- 需要循环重试、补充上下文、继续 handoff
- 需要中断恢复
- 需要统一流式输出事件
- 需要持久化主 Agent 编排状态

在这种情况下，可以在前面分布式多 Agent 架构的基础上，引入 **Spring AI Alibaba Graph** 作为主 Agent 内部的任务编排引擎。

### 20.1 Graph 的定位

在这里，Graph 的定位不是替代 A2A，也不是替代 MCP。

Graph 的职责应该是：

- 作为主 Agent 服务内部的工作流/DAG 编排引擎
- 负责主任务状态流转
- 负责节点执行顺序
- 负责条件分支
- 负责并行与汇聚
- 负责流式事件产出
- 负责主 Agent 编排状态持久化

所以边界应该明确为：

- `A2A`：Agent 与 Agent 之间通信
- `MCP`：Agent 与 Tool 之间通信
- `Graph`：主 Agent 服务内部编排

### 20.2 Graph 版本下的总体结构

引入 Graph 后，推荐结构变成：

- `supervisor-agent-service`
  - `GraphCoordinator`
  - `StateGraph`
  - `IntentParseNode`
  - `RouteDecisionNode`
  - `MemoryLoadNode`
  - `ApprovalSubgraph`
  - `InvokeAgentNode`
  - `ParallelJoinNode`
  - `SupervisorSummarizeNode`
  - `MemoryPersistNode`
  - `StreamEmitNode`

- 各子 Agent 服务
  - `listing-agent-service`
  - `contract-agent-service`
  - `trade-agent-service`
  - `marketing-agent-service`

- 外围基础设施
  - `Nacos`
  - `A2A`
  - `MCP`
  - `Memory Service`
  - `Event Bus`
  - `Milvus / ES`

也就是说：

- 分布式多 Agent 的整体架构不变
- 只是把主 Agent 的内部编排逻辑升级成 Graph
- 审批/人工确认能力也并入主 Agent 的 Graph，而不是散落在各子 Agent 服务内部

### 20.3 Graph State 设计

如果主 Agent 使用 Graph，不建议直接在图里随意使用 `Map<String, Object>`。

更稳的做法是定义一层主编排状态对象，例如：

```java
public class SupervisorGraphState {
    private String sessionId;
    private String taskId;
    private String userMessage;
    private String intent;
    private String domain;
    private WorkflowStatus workflowStatus;
    private Map<String, Object> sharedContext;
    private List<String> artifactIds;
    private Map<String, Object> agentResults;
    private ApprovalRequest pendingApproval;
    private ApprovalDecision latestApprovalDecision;
    private String selectedAgent;
    private Boolean requireParallel;
    private String finalAnswer;
}
```

这样可以避免图状态无限膨胀，也便于后续持久化和恢复。

其中审批相关对象建议保持通用，而不是写成文案审批、视频审批这类专用字段：

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

### 20.3.1 Graph 与动态 Agent 注册的关系

这一点非常关键，必须单独强调：

**A2A 的动态注册发现能力，与 Graph 的主编排能力并不冲突。**

两者解决的是不同层次的问题：

- `A2A + Nacos + AgentCard`
  - 解决“当前系统里有哪些 Agent 可用”
  - 解决“Agent 的地址、能力、skill、输入输出约束是什么”

- `Graph`
  - 解决“主 Agent 应该按什么流程编排这些能力”
  - 解决“什么时候调用 Agent、是否并行、是否汇总、是否继续 handoff”

因此，动态发现的是 **Agent 资源池**，Graph 定义的是 **编排骨架**。

这意味着：

- 新增一个 Agent，并不一定意味着要改 Graph
- 只有当“编排策略本身发生变化”时，才需要改 Graph

例如：

- 新增 `settlement-agent-service`
  - 如果它遵循现有标准输入输出协议，只是可选的新领域执行者
  - 那么主 Agent 理论上不需要新增一个写死节点
  - 只需要通过 `Nacos + AgentCard` 动态发现它，并由路由逻辑决定是否调用

但是：

- 如果新增 `settlement-agent-service` 之后，业务要求“合同任务必须先经过结算评估，再回到合同分析”
  - 这改变的是编排流程本身
  - 这时才需要修改 Graph

所以，推荐原则不是：

- “一个 Agent 对应一个 Graph 节点写死”

而是：

- **稳定编排骨架 + 动态 AgentRegistry + 通用 InvokeAgentNode**

### 20.3.2 推荐的稳定骨架模式

为了同时满足：

- Agent 可动态扩展
- 主 Agent 编排保持稳定

推荐主 Graph 优先采用以下模式：

- 固定图骨架
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

- 动态 Agent 选择
  - `SelectAgent` 节点根据 `AgentRegistry`、`AgentCard`、当前任务意图、领域标签、skill 标签动态选择目标 Agent

- 通用 Agent 调用节点
  - `InvokeAgentNode` 不关心当前调用的是 `listing-agent`、`contract-agent` 还是 `trade-agent`
  - 它只负责：
    - 从 state 中读取 `selectedAgentId`
    - 去 `AgentRegistry` 查询 endpoint
    - 发起 A2A 请求
    - 把结果写回 state

如果某个任务不需要人工确认，`BuildApprovalRequest` 和 `ApprovalSubgraph` 可以被跳过。

如果某个任务需要文案确认、视频确认、合同确认或报价确认，也不应该新增多个专用审批节点，而应该统一走：

- 业务节点生成产物
- 业务节点组装 `ApprovalRequest`
- 主 Agent 执行 `ApprovalSubgraph`
- 根据审批结果从配置的出口继续编排

这样设计的好处是：

- 主 Graph 本身保持稳定
- 新增领域 Agent 时，只要遵循统一协议，就可以通过动态注册进入主编排体系
- 主 Agent 不需要频繁因为“多了一个 Agent”而改图
- 只有在“编排策略变化”时才调整 Graph

### 20.3.3 什么时候需要改 Graph

推荐明确区分两类变化：

#### 不需要改 Graph 的变化

以下情况通常不需要改 Graph：

- 新增一个领域 Agent
- 新增一个 skill
- 新增一个遵循标准输入输出的新执行者
- Agent 实例扩容或缩容
- Agent endpoint 变化

这些都属于 **资源池变化**，由：

- `Nacos`
- `AgentCard`
- `AgentRegistry`

负责承接。

#### 需要改 Graph 的变化

以下情况通常需要改 Graph：

- 新增一种固定编排阶段
- 新增一种强制前置审查流程
- 新增并行调用和汇聚逻辑
- 新增循环重试逻辑
- 新增一种通用审批子图出口策略
- 新增复杂任务分解策略

这些都属于 **编排策略变化**，应该由 Graph 承接。

### 20.3.4 这一原则的意义

这一原则的价值非常大：

- 保住了 A2A 带来的动态扩展能力
- 保住了主 Agent 的编排稳定性
- 避免出现“每增加一个 Agent 就要重写主图”的失控情况
- 把“资源扩展”和“流程演进”拆成两个独立维度

一句话总结：

**Graph 不应该默认按“一个 Agent 一个节点”建模，而应优先采用“稳定编排骨架 + 动态 AgentRegistry + 通用 InvokeAgentNode”的模式。**

### 20.4 推荐节点类型

主 Agent Graph 中建议至少具备以下节点：

- `IntentParseNode`
  - 识别用户意图、任务类型、领域归属

- `RouteDecisionNode`
  - 判断应该调用哪个子 Agent

- `MemoryLoadNode`
  - 从 `memory-service` 读取共享记忆和任务产物

- `BuildApprovalRequestNode`
  - 根据当前业务产物组装通用 `ApprovalRequest`

- `ApprovalSubgraphNode`
  - 负责持久化待审批状态
  - 负责中断与恢复
  - 负责输出 `APPROVED / REJECTED / TERMINATED`

- `InvokeAgentNode`
  - 通过 A2A 调用目标子 Agent
  - 不写死为某个具体领域 Agent 节点

- `ParallelJoinNode`
  - 汇总多个子 Agent 返回结果

- `SupervisorSummarizeNode`
  - 主 Agent 汇总生成最终用户答案

- `MemoryPersistNode`
  - 把本轮会话结果写回 `memory-service`

- `StreamEmitNode`
  - 发出统一的 session 事件流

### 20.5 Edge 设计

Graph 的真正价值不只在节点，更在边的表达能力。

建议支持以下几类边：

- 固定边
  - `MemoryLoadNode -> IntentParseNode`

- 条件边
  - `RouteDecisionNode -> BuildApprovalRequestNode`
  - `RouteDecisionNode -> InvokeAgentNode`
  - `ApprovalSubgraphNode -> InvokeAgentNode`
  - `ApprovalSubgraphNode -> SupervisorSummarizeNode`

- 并行边
  - 同时调用 `ListingAgent` 和 `TradeAgent`

- 汇聚边
  - 多个 Agent 结果汇总到 `ParallelJoinNode`

- 循环边
  - 子 Agent 结果不足时，再次补充上下文重新执行
  - 审批驳回后回到产物生成节点重新生成

推荐把审批子图建模成可复用骨架：

```text
prepare_approval -> persist_and_interrupt -> await_resume -> route_by_decision
```

这段逻辑应固定在 `supervisor-agent-service` 内，不下沉到子 Agent。

### 20.6 Graph 中如何调用子 Agent

Graph 节点里不应该把子 Agent 当成本地 method，也不应该当作普通 tool。

推荐在节点内部通过 A2A 调用子 Agent，例如：

```java
public class InvokeAgentNode implements NodeAction {
    @Override
    public Map<String, Object> apply(OverAllState state) {
        // 1. 构造 A2A 请求
        // 2. 根据 state.selectedAgentId 调用目标 agent-service
        // 3. 拿到 task / result / artifact
        // 4. 更新 graph state
    }
}
```

也就是说：

- Graph 只负责编排
- A2A 仍然负责跨服务协议

### 20.7 Graph 与 Memory 的关系

使用 Graph 后，记忆仍然建议分层，不能把 Graph Checkpoint 直接当成完整的 memory 体系。

推荐分工：

- `Graph Checkpoint`
  - 保存主 Agent 图执行状态
  - 保存当前节点位置
  - 保存图运行期上下文
  - 保存 `pendingApproval` 和等待恢复点

- `Memory Service`
  - 管理 session shared memory
  - 管理 task artifact memory
  - 管理 agent private memory
  - 管理跨会话或长期上下文

一句话：

- Graph Checkpoint 是编排状态
- Memory Service 是业务级记忆系统

### 20.8 Graph 下的 Handoff

如果使用 Graph，handoff 可以建模成一段显式节点链路，而不是普通 prompt 行为。

推荐链路：

- `RouteDecisionNode`
  - 决定 handoff 目标

- `HandoffPrepareNode`
  - 生成 `AgentHandoffPacket`
  - 关联 artifactIds

- `AgentInvokeNode`
  - 调目标 Agent

- `HandoffResultMergeNode`
  - 合并子 Agent 返回结果

- `ResumeSupervisorNode`
  - 恢复主 Agent 继续执行

这样 handoff 就成为图中的显式状态迁移，便于观察、重试和恢复。

### 20.9 Graph 下的审批子图

在分布式多 Agent 场景下，审批应由 `supervisor-agent-service` 的 Graph 统一承接，而不是让 `marketing-agent-service`、`contract-agent-service` 等子 Agent 自己等待用户操作。

推荐职责边界：

- 子 Agent 负责生成业务产物
- 主 Agent 负责组装 `ApprovalRequest`
- 主 Agent 负责执行 `ApprovalSubgraph`
- 审批结果决定是否重新调用原子 Agent，或继续后续 handoff

以“查询数据 -> 生成文案 -> 文案审批 -> 生成视频 -> 视频审批 -> 发布广告”为例，推荐链路是：

```text
InvokeAgent(marketing-agent:generate-copy)
-> BuildApprovalRequest(copy)
-> ApprovalSubgraph
-> InvokeAgent(media-agent:generate-video)
-> BuildApprovalRequest(video)
-> ApprovalSubgraph
-> InvokeAgent(marketing-agent:publish)
```

也就是说：

- 审批是主 Agent Graph 的控制点
- 子 Agent 不保存用户审批等待状态
- 用户审批结果通过主 Agent 的 resume 入口恢复图执行

### 20.10 Graph 下的流式输出

Graph 非常适合主 Agent 统一生成流式事件。

建议做法：

- 每个节点开始时发 `agent.started`
- 每个节点执行中发 `agent.delta` 或 `task.progress`
- 进入审批子图时发 `task.waiting_approval`
- 审批恢复时发 `task.approval_received`
- 节点调用子 Agent 时发 `handoff.started`
- 子 Agent 返回时发 `handoff.completed`
- 汇总完成后发 `session.completed`

前端仍然只连主 Agent。

### 20.11 Graph 下的 Skill 使用

引入 Graph 后，skill 的边界依旧不变。

推荐模型：

- 主 Agent 只加载协调型 skill
  - 路由 skill
  - 汇总 skill
  - 任务规划 skill

- 子 Agent 继续只加载各自领域 skill
  - listing skill
  - contract skill
  - marketing skill

Graph 节点负责流程控制，skill 负责节点里的 LLM 行为约束。

### 20.12 Graph 方案的价值

Graph 方案的主要价值在于：

- 编排结构更清晰
- 节点可观测性更强
- 分支与并行更自然
- 可恢复性更好
- 人工审批可以统一建模成通用子图
- 流式事件更容易统一建模
- handoff 更容易表达成正式状态流转

但它也会带来额外复杂度：

- 需要维护图定义
- 需要维护图状态
- 需要区分图状态和 memory 状态
- 需要更多治理和可观测性设计

## 21. 两个方案的区别与实施步骤

这里把两个方案明确区分：

- 方案一：分布式多 Agent + A2A
- 方案二：分布式多 Agent + A2A + 主 Agent 使用 Spring AI Alibaba Graph

### 21.1 两个方案的本质区别

方案一解决的是：

- 系统怎么拆
- Agent 服务怎么发现
- Agent 之间怎么通信
- memory 怎么分层
- 前端流式输出怎么统一

方案二解决的是：

- 主 Agent 内部怎么优雅编排
- 分支、并行、循环、恢复怎么表达
- handoff 怎么在主 Agent 内部以图的方式显式建模
- 通用审批子图怎么复用

一句话：

- 方案一是“系统边界方案”
- 方案二是“主编排引擎方案”

同时需要补充一点：

- 方案二中的 Graph，不应默认建模成“一个 Agent 一个固定节点”
- 方案二更推荐建模成“稳定骨架 + 动态路由 + 通用调用节点”
- 审批不应建模成“一个业务一个审批节点”，而应建模成“通用审批子图 + ApprovalRequest”

否则会削弱方案一中 A2A 动态扩展带来的优势。

### 21.2 方案一实施步骤

方案一建议按下面顺序实施：

#### 步骤 1：确认系统边界

明确：

- MCP 只负责 Agent 到 Tool
- A2A 只负责 Agent 到 Agent
- RAG 保持通用底座
- Memory Service 独立建模

#### 步骤 2：抽象注册发现模型

新增：

- `AgentCard`
- `AgentDescriptor`
- `AgentRegistry`

并接入 `Nacos`。

#### 步骤 3：抽象任务与 handoff 协议

新增：

- `ConversationSession`
- `AgentTask`
- `AgentHandoffPacket`
- `AgentExecutionResult`

#### 步骤 4：在现有单服务中先拆逻辑边界

先按逻辑区分：

- `SupervisorAgent`
- `ListingAgentRuntime`

即使暂时还在一个服务里，也先完成逻辑分层。

#### 步骤 5：优先拆出 `listing-agent-service`

先把房源 Agent 单独服务化，作为第一块样板。

#### 步骤 6：主 Agent 通过 A2A 调 listing Agent

把原来本地调用改成跨服务 A2A 调用。

#### 步骤 7：引入最小版 `memory-service`

先支持：

- session shared memory
- task artifact memory

#### 步骤 8：统一 session 事件流

前端仍然只连主 Agent，主 Agent 聚合所有事件。

#### 步骤 9：继续拆其他领域 Agent

例如：

- `contract-agent-service`
- `trade-agent-service`
- `marketing-agent-service`

#### 步骤 10：收敛 skill、tool、namespace 权限

做到：

- skill 按 Agent 隔离
- tool 按 Agent 加载
- MCP server 按 Agent 授权
- RAG namespace 按 Agent 隔离

### 21.3 方案二实施步骤

方案二不建议一开始就上。

更稳的顺序是：**先完成方案一，再升级方案二。**

推荐步骤如下：

#### 步骤 1：先完成方案一的核心闭环

至少做到：

- 主 Agent 独立编排
- 一个领域 Agent 服务化
- A2A 调用打通
- Memory Service 打通
- 统一 session 事件流打通

#### 步骤 2：在主 Agent 内引入 Graph State 模型

新增：

- `SupervisorGraphState`
- Graph 状态持久化策略
- 节点输入输出约束

#### 步骤 3：把主 Agent 编排拆成节点

建议先拆：

- `MemoryLoadNode`
- `IntentParseNode`
- `RouteDecisionNode`
- `ListingAgentInvokeNode`
- `SupervisorSummarizeNode`
- `MemoryPersistNode`

#### 步骤 4：把 handoff 建模成节点链路

新增：

- `HandoffPrepareNode`
- `HandoffResultMergeNode`
- `ResumeSupervisorNode`

#### 步骤 5：接入 Graph Checkpoint

让主 Agent 图具备：

- 中断恢复
- 长任务恢复
- 节点回溯

#### 步骤 6：增加并行与汇聚

新增：

- `ParallelJoinNode`
- 并行调用多个子 Agent 的分支

#### 步骤 7：统一 Graph 事件到前端 session stream

把图内节点事件映射成：

- `agent.started`
- `agent.delta`
- `handoff.started`
- `handoff.completed`
- `task.progress`

#### 步骤 8：逐步推广到更多复杂编排场景

例如：

- listing + trade 联合分析
- contract + settlement 联合审查
- marketing + listing 联合发布

### 21.4 推荐实施顺序

最终推荐顺序是：

1. 先做方案一
2. 方案一稳定后，再升级方案二

不要一开始就：

- 分布式拆服务
- 接 A2A
- 上 Memory Service
- 上 Event Bus
- 再同时上 Graph

那样复杂度会叠得过高，排障成本很大。

### 21.5 最终建议

如果从工程风险和演进成本看：

- 方案一更适合作为第一阶段架构升级
- 方案二更适合作为第二阶段编排能力升级

也就是说，推荐路线是：

- **第一阶段：先把多 Agent 分布式架构搭起来**
- **第二阶段：再把主 Agent 内部编排升级为 Graph**

这两套方案不是互斥关系，而是递进关系。
