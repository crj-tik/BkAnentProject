# 域 Agent 记忆接入：基础契约与启用前证据

日期：2026-10-16。变更：`integrate-domain-agent-memory`。本记录区分源码核查、离线制品测试与真实运行验收；规划完成不代表实施或启用完成。

## 不变边界

| 条目 | 本变更适用约束 |
| --- | --- |
| LR-3、LR-6、KI-2 | Interview 治理面可接记忆，REST/SSE 运行面保留代码决策、三段上下文、质量门、领域状态唯一权威及脱敏域；新增真实 transition 调用点必须有同步回归。 |
| LR-17、LR-18 | Supervisor 通用 Graph 保持业务调用唯一执行权；事项与经验不成为业务 DAG、关键词路由或 nextHints 自动交接。 |
| LR-22、LR-23、KI-21 | 保存原 run、输入幂等身份、技能、审批、预算、远端关联及未知结果；记忆恢复不能重执已完成调用，也不能把 checkpoint 当跨 run 会话档案。 |
| LR-31、LR-38 | 私有搜索经 memory 真源，RAG 执行召回与通用重排；领域最终版本/授权核验和同 asOf 的有界合池不移走。 |
| LR-33、LR-41、KI-59、KI-80 | 当前凭据只在专用传输与进程租约；A2A 精确信标不能重放到 memory，持久 owner 不能换发凭据。KI-59 的早期 SDK 限制须结合现行仓库 transport patch 核实，不能照旧文档重新引入旁路。 |
| LR-34、LR-45、KI-100 | 新 memory v2 用显式受管原生 Dubbo，不新增内网 HTTP、不借旧 transport 私网授权放宽新请求。HTTP discovery 存在不代表纯 RPC 可以选择 REST。 |
| LR-40 | 个人记忆 ADMIN 亦 self-only，并叠加可信 domain/agent 与明确共享范围；引用、namespace、会话关联都不是授权。 |
| LR-53、KI-24、KI-114 | 生成成果保存成功后发布成果事件，部分成功按稳定来源补存；Studio 人工修订不覆盖 memory 生成正文。本变更不顺手补齐尚未接通的 Studio 领域动作。 |

## 当前核查范围

- 源码九个 SubAgent、Supervisor 及真正 ChatClient 消费者由只读入口清单逐项核对；源码列表与目标环境实际运行/注册集合分别记录。
- 制品级测试使用实际解析的 Spring AI 1.1.2 / Alibaba 1.1.2.3，不修改框架版本、缓存或 `.m2`。
- memory v2 DTO 是纯 Java 数据契约。形状、版本及不可变性校验不等于可信身份、当前授权或可靠持久覆盖。
- 不在逐 delta/progress 回调增加 memory 读取、写入、embedding 或总结调用；A2A 获准正文/结构结果继续直传。

## 接入口、消费者与可靠事实源

九个 SubAgent 均由 `common-a2a/.../OfficialA2aAgentExecutor.java:148–262` 执行原生 ReactAgent，直接使用 ChatModel。九个 builder 都显式接认证工具、Supervisor 上下文、技能模型及技能工具四个 interceptor，当前无 memory Hook。执行器 `:316–326` 将 A2A taskId 映射为 Graph threadId，不是 conversationId。任务凭据必须来自 typed execution context 与当前认证，工具参数或普通 metadata 不授予 owner/domain/agent 权限。

| agent / domain / audience | 实际 builder（相对仓库根） | 既有事实源及缺口 |
| --- | --- | --- |
| listing-agent / listing / listing-master-service | `listing-master-service/src/main/java/com/bkanent/listing/a2a/ListingOfficialA2aAgent.java:48–58` | SQL 房源及 `ListingRagOutbox.java:47–62` 的 event/revision/hash/投影可引用；不是本次检索完整返回或域内工具调用档案。 |
| trade-agent / trade / business-service | `business-service/src/main/java/com/bkanent/business/a2a/TradeOfficialA2aAgent.java:40–49` | `BusinessAnalyticsServiceImpl.java:77–119` 读取统计/排名/最新快照；需固定 asOf/版本或当时返回引用，不能用当前值重建历史结果。 |
| contract-agent / contract / contract-service | `contract-service/src/main/java/com/bkanent/contract/a2a/ContractOfficialA2aAgent.java:43–53` | 合同/附件/签章业务行可引用但可变；`ContractAgentServiceImpl.java:105–120` 风险评审正文只返回，不能重跑模型补原结果。 |
| compare-agent / compare / compare-engine-service | `compare-engine-service/src/main/java/com/bkanent/compare/a2a/CompareOfficialA2aAgent.java:40–49` | `CompareReportCacheServiceImpl.java:48–53,65–97,122–129` 已有 JSON/PDF 磁盘缓存及启动加载；未证原子、跨实例耐久、owner/grant/不可变事件身份。不能称没有结果源。 |
| marketing-agent / marketing / marketing-content-service | `marketing-content-service/src/main/java/com/bkanent/marketing/a2a/MarketingOfficialA2aAgent.java:40–49` | `MarketingAssetServiceImpl.java:89–131` 保存正文与 contentId/version/parent；生成至 create 提交前仍有缺口，version 字段不证明所有变更不可变。模拟 publish ID 不是外平台回执。 |
| media-agent / media / media-worker-service | `media-worker-service/src/main/java/com/bkanent/media/a2a/MediaOfficialA2aAgent.java:46–56` | `MediaGenerationTaskServiceImpl.java:65–74` 内存 QUEUED 后发送 MQ；`MediaTaskStoreServiceImpl.java:20–89` 纯内存，不证明重启可恢复完成事实，不能换新 taskId 补记忆。 |
| notification-agent / notification / notification-service | `notification-service/src/main/java/com/bkanent/notification/a2a/NotificationOfficialA2aAgent.java:46–56` | 站内消息 SQL、消费去重/notificationId 可引用；Agent 直接调用未证接去重账本。recipientUserId 不等于 actor，模拟邮件 SENT 不是 provider 送达。 |
| settlement-agent / settlement / settlement-service | `settlement-service/src/main/java/com/bkanent/settlement/a2a/SettlementOfficialA2aAgent.java:46–56` | 结算/批次/支付 SQL ID 可引用但可变；未证 task/call/hash 接受关联，自造 BANK 字符串不作银行凭证。需规则/输入版本和原回调关联。 |
| interview-agent / interview / interview-service | `interview-service/src/main/java/com/bkanent/interview/a2a/InterviewOfficialA2aAgent.java:43–53` | 完成话轮、逐字稿 snapshotHash、报告 inputSnapshotHash 优先引用；WAIT/执行中/失败回执保留 KI-12 边界，创建场次终态不是访谈事项闭合。 |

上述事实源盘点不决定九域各建数据库或复制全量正文。现有业务真源优先稳定引用；只对缺失的输入接受、域内工具调用和原完成事实补最小 journal，具体事务/保留/版本授权仍属后续任务（KI-117）。业务 creatorWorkNo、employeeId、recipientUserId 等字段不能直接映射认证 owner。

### Supervisor 与 Interview 的独立调用路径

- `agent-service/.../SupervisorToolLoopGraph.java:138–149` PrepareContext 追加本 run 输入，没有跨 run 历史读取；`:343–455` 沿原调用 ledger 复用终态，未知副作用不自动重发；`:504–520` 先保存成果再发完成事件。
- `agent-service/.../OrchestrationStore.java:26–36,69–111` 保存原输入、调用参数 hash、结果及原远端关联；`StoredModelMessage.java:7–17` 和 DatabaseCheckpointSaver 保存已进入 checkpoint 的显式消息。它们不是独立完整逐模型轮/域内工具组档案，模型返回至 checkpoint 保存间仍有窗口。
- `SupervisorToolLoopRunner.java:225–245` 对账恢复从 Observe→Model 继续，绕 PrepareContext；新记忆生命周期必须覆盖该路径，保留原 run/审批/预算/task，不重复输入或执行工具。
- `RemoteTaskArtifactStore.java:11–39` 为实际 `@Primary`，调用 memory 保存生成成果；DbTaskArtifactStore 存在不表示自动异常 fallback。已保存 artifact 可以引用，不证明 v2 接收许可/grant/CAS。
- Interview 运行面 `InterviewRuntimeService.java:322–388` 从 SQL 重装 system/事实摘要/近期脱敏上下文，直接 ChatModel stream；`:270–274` 持久播出正文。治理 ReAct 接记忆不替代此运行面或唯一状态机。

### ChatClient 消费者不机械接会话记忆

八域旧 ChatClient `invoke()` 未发现生产 caller；interview 无 ChatClient 消费者。真实消费者是 `agent-service/.../AgentCapabilityServiceImpl.java:71–84,93–100,125–126`，上游为能力 Controller/RPC，经 `AgentChatService.java:27–38` 执行。这些方法仅传 prompt/kpis，没有可信 owner/session/conversation。正式 `/chat` 走 Orchestrator，非该 ChatClient 链。

因此本批不创建闲置 ChatMemory bean、不从 prompt 或进程认证猜 conversationId，也不把一次性营销生成/风险评审 ChatModel 当会话 Advisor 消费者。后续任务 5.9 仅在存在真正的会话 ChatClient 调用与可信身份映射时落实适配。

## 框架扩展签名核实

实际 JAR 的公开 `javap` 签名核实（不是由文档推测）：

- Spring AI `ChatMemory` 为 `add(String, Message/List<Message>)`、`get(String)`、`clear(String)`，没有 `get(id,lastN)`。Repository 为 `findConversationIds/findByConversationId/saveAll/deleteByConversationId`，没有 owner/domain、revision、CAS 或分页参数。
- `MessageChatMemoryAdvisor/PromptChatMemoryAdvisor.builder(ChatMemory)` 可配置 conversationId/order/scheduler。Advisor 注册在 `ChatClient.Builder.defaultAdvisors(...)` 或请求 `.advisors(...)`，不自动作用于 ChatModel 或 Graph。
- ReactAgent builder 有 `model/chatClient/hooks/interceptors/streamingInterceptors/saver/compileConfig`；其中 `chatClient(ChatClient)` 方法真实存在但已 Deprecated。不存在 `.advisors(...)` 或 `.store(...)`；advisorObservationConvention 不是注册入口。
- Store 真正路径为 `CompileConfig.builder().store(Store).build()` 再传入 Agent `compileConfig(...)`；namespace 不授予 ACL，不自动检索注入。
- ModelHook 的 beforeModel/afterModel 和 AgentHook 的 beforeAgent/afterAgent 接 `OverAllState,RunnableConfig`，返回 `CompletableFuture<Map<String,Object>>`。MessagesModelHook 直接实现 Hook（不继承 ModelHook），接 `List<Message>,RunnableConfig` 返回 AgentCommand，公开静态 action bridge 可转换为 state action。
- Hook 公开 `getHookPositions/getOrder/getKeyStrategys`（保留真实拼写）及工具/拦截器扩展。ModelInterceptor 同步返回 `ModelResponse interceptModel(ModelRequest,ModelCallHandler)`；ToolInterceptor 同步返回 `ToolCallResponse interceptToolCall(ToolCallRequest,ToolCallHandler)`，不能误写异步签名。

任务 1.2 的源码调用路径与实际制品签名已核实；不是生产 Controller/跨域运行验收，也不证明授权、事务或长期记忆已接通。

## 原生压缩制品级证据

`common-a2a/src/test/java/com/bkanent/a2a/memory/SummarizationHookContractTest.java` 的单模块最终执行为 25 次、0 failure/error/skipped。实际 ReactAgent 验证 BEFORE_MODEL hook 按 order 升序执行，after 反序；先生成摘要、完成提交桩，再进入主模型。`MessagesModelHook.beforeModelAction` 公开返回候选更新而不立即改 state；`AgentCommand` getter 包私有不阻断该公开桥。只注册外围 ModelHook 可拒绝候选，公开 KeyStrategy 可在真正 update 边界拒绝晚到旧版本。

已确认危险原生反例（KI-116）：模型异常被吞并生成错误 SystemMessage 后仍 REPLACE，异常 message 还进日志；字符/4逐字段向下取整不是 tokenizer；cutoff 五条邻域可能拆长间隔工具组，未完成并行响应也未被完整保护；总结 getText 完全丢 responseData 和工具身份；首 user 在保留尾部时可能重复；返回候选前检查版本加普通 REPLACE 不保护随后新到输入。

保护测试验证覆盖未确认、失败/空/无依据候选、版本竞争、提交失败/异常均不替换。update-time 策略还拒绝 gate 返回后的旧候选。所有覆盖、提交、revision 是本地 fixture，不是数据库事务/分布式租约/run CAS；被压缩前缀的工具内容保守拒绝，不是工具结构无损正向实现。生产压缩仍关闭，后续必须实现安全 responseData 投影、typed 错误装饰、事务覆盖、两阶段 CAS、恢复尾部、硬预算和撤回复验。

实际 CodeSource / pom.properties / SHA-256 均由测试核对：

| 坐标 | SHA-256 |
| --- | --- |
| `com.alibaba.cloud.ai:spring-ai-alibaba-agent-framework:1.1.2.3` | `2cc840e12070f2dffd015a0f2f0df803f46e7ebc83ac3d4f4c36b79c1dd03739` |
| `com.alibaba.cloud.ai:spring-ai-alibaba-graph-core:1.1.2.3` | `a74b180d531fdd5191538466f3720bc11109e7e19d9c6219cb7d8d40dd5ab4f2` |
| `org.springframework.ai:spring-ai-model:1.1.2` | `694d972dea31846bbb0b1f0a50cb72309681c3396a8e520da213323779a1406c` |
| `org.springframework.ai:spring-ai-client-chat:1.1.2` | `79661d5aacfdff6031bfc0c6eeb877feb471338483e0c996f6b24c74d5506fc7` |

## 目标环境只读核对

2026-10-16 06:37 UTC：Auth、Agent、Customer、Listing、Memory、RAG 的 release 容器为 `8bc352b0d45a79c860c65140ef829bbdd46415ee`，Marketing 为 `3c39145488241d3254aa7e5ad1f19fa8d2eec8d3b2c22a58f2a3227205901004`；七者 running/healthy，Marketing restart=1，其余=0。tag 与本次源码 HEAD `2c6c837` 分开，未核对运行 JAR 与源码映射。

九 SubAgent 仅 Listing、Marketing 有当前 Docker 运行证据；Interview、Trade、Contract、Compare、Media、Notification、Settlement 的旧 dev 容器 exited/137、OOMKilled=false。不能凭 137 推定 OOM，也不能据容器集合推定 Nacos Card 可调用集合。当前注册/版本/endpoint/技能快照未验，任务 1.1 保持未完成。

Gateway、业务 MinIO、mysql-mcp 退出。MySQL/Nacos/Redis/ES/Milvus/etcd/Milvus MinIO 健康，RocketMQ namesrv/broker 运行；这些状态不证明 memory 升级或三治理 fixture 可执行。

Marketing 标签指向 `D:/project/rag-dev-backups/42a55bf-20261014-release-prep/compose.release.yml`，其中 Agent/Listing/RAG 仍固定旧 `3c391454…`，六个新 release 容器没有 Compose project/service 标签。插值服务未读取凭据环境做 resolve；该文件不能当当前完整部署/回滚清单，记录 KI-109 新漂移，不执行 up/restart 或修正配置。

## 真实基线采样门

固定三类代表入口：Supervisor 正式会话 SSE、marketing 原生 A2A 流经 Supervisor 的正式 SSE、interview 治理面原生 A2A 流经 Supervisor 的正式 SSE。Interview 高频话轮 REST/SSE 不替换成治理面，也不混入这三类基线。

每类 fixture 使用隔离的 owner/conversation/run/task 和固定合成请求，保持同一模型、工具/技能版本、拓扑、负载、认证及审计配置；没有可信当前身份和隔离业务资源时不启动模型或工具请求。首可见事件、A2A 终态展示、成果持久可读、Studio 可读分别采样，不能从其中一个阶段推断其他阶段。改造前后各阶段 P50/P95 与 RPC/DB/模型次数只接受实际测量；缺少样本不填 0、不沿用历史 RAG 数字、不设置无依据固定毫秒门限。

现有 `scripts/verification/README.md` 的运行探针仅覆盖 RAG/A2A 专项，不自动覆盖正式 Supervisor SSE、成果/Studio 阶段或本变更的完整性能验收。SEND/STREAM 可能产生模型和业务副作用；历史 RAG 运行授权不是本次任意记忆 fixture 的授权。

### 当前基线阻断

正式源码入口为 `/agent/supervisor/stream`，参数保持 sessionId、可选 taskId、afterEventId/afterSequence/Last-Event-ID 并核对 owner；marketing/interview 治理面使用 `/a2a`。当前缺可信当前认证、原隔离 session/task 和获准业务 fixture；Gateway、Interview 已退出，未恢复。当前运行有效模型/温度/Nacos覆盖、认证/审计/限流及负载未固定。模板中的 gpt-5.6-terra 与历史 Qwen/Ollama 数据不构成同环境基线。

Studio 浏览器须经过 Gateway。`agent-service/.../StudioController.java:31` 的工作 GET 调 `artifacts.list`，`StudioArtifactsService.java:77` 的 list 调 syncLatest，可更新 studio_run 并插入系列/修订；不能仅因 HTTP GET 当纯只读 fixture。没有调用该链或运行模型/业务请求，没有读取运行凭据文件。四阶段 P50/P95 与 I/O 全部未测，任务 1.4 保持未完成；任务 11.3/11.4 不由容器 healthy 代签。

## 基础 DTO 契约及验证边界

新增 `common/src/main/java/com/bkanent/common/memory/` 共 36 个纯 Java17 文件，不添加框架依赖或服务调用。覆盖四重上下文身份、独立关联/版本、原消息/全部工具结构、事项/实体/摘要来源依赖、偏好逻辑块与固定版本历程页、私有经验与四维评分、grant/export 及加工/索引/装配状态。

JSON 采用封闭 type-tagged record 树（不含任意 Object），保留 null/数组/对象/十进制 lexeme，递归防变并限制深度/节点/总字符。大工具结果可表达完整版本来源引用，brief 不替代原 responseData；工具响应齐全不把审批/受理/未知状态当成功，不代表可裁剪。所有字段与构造器只提供形状验证，不验证服务身份、业务主体映射、来源当前授权或长期覆盖。

`MemoryContractTest` 有 27 项实质断言；`MemoryExceptionSerializationTest` 有 2 项官方严格序列化 fixture。前者覆盖 Jackson/JDK/现有 ctor-SPI 普通 DTO，后者只证明官方 fastjson2 的 throws 声明精确注册。没有导出 provider、reference.get、网络请求、包级 allowlist 或 add-opens。当前 ctor-SPI 的 MemoryException envelope 仍未实现，不宣称任务 2.2 的联合 wire、版本协商或 RPC 已完成。

首轮 Hook reactor 与随后主会 DTO compile 均因 `MemoryMatter.java` 缺最外层右括号失败，0 测试执行；主会已补齐。Hook 首个单模块 25 次有 3 个 fixture error（KeyStrategy 旧值为 Optional），解包后 25 次通过。DTO 首个可编译轮次 27 项通过，严格异常 fixture 2 项中一项未拒绝 ForeignFailure，保留失败并修正实际测试信任边界，不通过放宽 STRICT 或删断言解决。

### 最终定向结果

离线命令 `mvn -o -gs .mvn-settings.xml -s .mvn-settings.xml -pl common -Dtest=MemoryContractTest,MemoryExceptionSerializationTest test` 最终 BUILD SUCCESS：27+2=29 项，failure/error/skipped 均 0（2026-10-16 15:09:52，6.374s）。原未拒绝的 ForeignFailure 全名以已注册 MemoryException 名称开头，实证为官方 handler 前缀匹配，不是关闭 STRICT 或 scope 错误。测试保留该边界，并动态编译无允许名前缀的外部 Throwable 验证真正拒绝；第三方行为未修（KI-118）。本批 DTO 2.1 完成，RPC 2.2 未完成。

## 本地容器环境恢复与真实执行（2026-10-16 下午追加）

用户授权「按需重启docker，测试可直接使用本地docker容器」「完全允许」后，按此窗口执行。此前“容器退出/认证缺失”阻断中属于容器层的部分已解除；模型可用性与 fixture 身份仍按实测记录，不倒填。

### 环境依赖测试首次真实执行（6/7 通过，遗留行已清理）

以宿主机直连本地容器执行此前全仓回归中 assumption-skip 的七项测试：`BK_AGENT_NACOS_SERVER=127.0.0.1:8848`（gRPC 9848 已映射），`BK_AGENT_DB_*` 指向 dev MySQL（宿主映射 `10.117.102.54:3306/bk_agent`，root 凭据从根 `.env` 注入命令环境，不回显不入文件）。结果：

- `NacosAgentRegistryDockerTest`、`NacosRollingUpgradeDockerTest`、`ApprovalClaimDatabaseConcurrencyTest` 一次通过（3 项，11.000s）。
- `SupervisorDistributedAcceptanceTest`、`SupervisorDockerDatabaseTest` 通过（2 项，18.710s）。
- `LiveSkillAgentCardsDockerTest` 通过（1 项，含真实 Nacos Card 重发/删除，凭据取自 `.env` 注入）。
- `SupervisorRealModelEvaluationTest` 仍跳过：测试硬编码占位 apiKey（`local-evaluation`），只能指向接受任意 key 的本地 OpenAI 兼容端点；当前栈无此端点，外部 KE 网关需真实 key 不可注入（不读取运行凭据补测试）。该项保持未测，不伪造。

测试写入的 `db-%`/`mixed-%`/`session-mixed-%` 前缀行已按参数化 DELETE 清理并复核归零（agent_orchestration_run/tool_invocation/skill_snapshot/workflow_checkpoint/task_artifact/event_audit/approval_claim 全部为 0）；并发竞争测试自身已删除 `db-race-*`。真实 broker01 种子账号未动。

### Nacos 实际注册机制与启用集合实测（任务 1.1 目标环境证据）

只读查询（v1 naming + 控制台 v3 console API，bkagent 登录，凭据不回显）：

- A2A Card 走 SDK `A2aService.releaseAgentCard`（gRPC，NACOS_AUTH_ENABLE=false），端点走 naming 的 agent-endpoints 组；控制台 Card 注册表（`/v3/console/ai/a2a/list`）在 public 与 BK-AGENT 两命名空间均 totalCount=0——端点以 naming 实例可见，Card 注册表为空。
- public 命名空间（dev 容器，冻结 env `NACOS_NAMESPACE=`空）：12 项服务（gateway、business/contract/interview/media-worker/notification/settlement 六域 + 5 个 Dubbo `providers:com.bkanent.common.rpc.*`）+ 5 个 agent-endpoints。
- BK-AGENT 命名空间（release 容器）：marketing-content/customer/memory-service 各 2 实例 healthy、agent-service 0 实例、marketing-agent::1.0.0 端点、3 个 Dubbo providers。
- 九 SubAgent 实况：trade/contract/media/notification/settlement 端点已注册（public）；marketing 端点在 BK-AGENT（release 容器）；interview 服务实例注册但 `NACOS_A2A_AGENT_REGISTRY_ENABLED=false` 无 A2A 端点；compare 从未注册（见 KI-119）；listing 两命名空间均无端点条目。

### 容器恢复与四个新缺陷（KI-119/120/121/122，分两轮 docker 代理执行）

第一轮：gateway/interview/business/contract/media-worker/notification/settlement dev 容器与业务 MinIO 恢复运行并注册；8 JVM 并发 start 曾压垮 Docker Desktop 引擎（Engine API 500），代理按授权重启 Docker Desktop 引擎后凭 unless-stopped 自动拉回。历史批量 137/143 退出确认为宿主/WSL 停机事件（OOMKilled=false），非容器内 OOM。release agent 因 `DUBBO_IP_TO_BIND=172.24.0.4` 钉扎与引擎重启后实际 IP 不符崩溃循环（RestartCount≈740），已 stop；auth/listing/rag release 保持 exited（KI-120）。

第二轮（复刻重建，docker 代理）：根因升级为 **MySQL root 密码轮换 vs dev 容器冻结旧密码**（KI-121）——dev 全体容器（含当时"healthy"的 auth）DB 认证实际全断，网关 `/auth/login` 500 即 auth 断连。处理与结果：

| 容器 | 处置 | 最终状态 |
| --- | --- | --- |
| dev agent | 复刻重建（`bk-anent-services:dev` + 原 jar 注入，jar 逐字节一致 3ee81afb…，仅换 MYSQL_PASSWORD） | Up，readiness UP，Nacos public 172.24.0.19:9002+20882 |
| dev auth | 同上复刻重建 | Up，网关登录 HTTP 200/code 0 |
| dev listing | 同上（保留原 jar dd89ef…，dev 镜像内 listing jar 与原容器不同未采用） | Up，readiness UP，172.24.0.26:9004 |
| dev compare | 容器实际无 compare-report-data 卷（compose 声明与实况不符）；根因镜像内 `/app` root:root 755 且 `/app/runtime` 不存在，uid 10001 建目录被拒；`docker exec -u 0` 建目录 chown app（容器可写层，stop/start 保留） | Up，`compare-agent::1.0.0` 注册 public agent-endpoints |
| dev interview | 两段复刻重建：`NACOS_A2A_AGENT_REGISTRY_ENABLED=false→true` + 换新密码；NACOS_NAMESPACE 保持空值 | Up，`interview-agent::1.0.0` 注册 public agent-endpoints |
| dev marketing | 旧 jar（09-18 构建）`mcpSyncServer` 抛 `Tool 'publishMarketingContent' already registered`——现源码已不同名、release 镜像已修，属旧制品缺陷（KI-122）；改密码无效，需换制品 | 阻塞；新容器 `-new` 留存（已含新密码+原 jar），marketing-agent 由 release 容器在 BK-AGENT 提供 |
| business/contract/settlement/media-worker/notification | 同批复刻重建（第三轮收尾中） | 见下"最终清单" |
| release agent | stop 结束崩溃循环（IP 钉扎 start 层面无解，KI-120） | Exited，agent-service 角色由 dev 容器承担 |

Nacos public 最终（第二轮时点）：服务 20 项全 healthy（agent/auth/listing/interview/compare/gateway/business/contract/settlement/notification/media-worker + 9 Dubbo providers）；agent-endpoints 8 项（interview/contract/settlement/listing/media/compare/trade/notification 各 ::1.0.0），唯一缺席 marketing-agent（在 BK-AGENT 由 release 容器 172.24.0.2:9008 提供）。A2A Card 控制台注册表两命名空间仍 totalCount=0（端点均以 naming 实例呈现）。

第三轮（凭据对齐与版本缺口收尾）：business/contract/settlement/media-worker/notification 五容器按「bk-anent-services:dev + 原容器 jar 注入 + 仅换 MYSQL_PASSWORD」复刻重建，全部 healthy 无 Access denied；dev auth 的 AUTH_TOKEN_SECRET 亦为冻结旧值，同模式对齐后登录恢复 200。public 命名空间以 release marketing 镜像（3c391454）复刻 `bk-anent-dev-marketing-public-a2a`（NACOS_NAMESPACE 置空、A2A 基址指向自身容器名、补 public 版配置所需 ELASTICSEARCH_URIS、entrypoint 显式 -Duser.home=/app/runtime、无别名/无宿主端口），`marketing-agent::1.0.0` 注册 public——**九个 SubAgent 端点在 public 全部就绪**。release auth 钉扎 IP 172.24.0.8 已被 rocketmq-namesrv 占用（start 必失败），改以 release auth 镜像复刻 `bk-anent-release-auth-rpc`（去 DUBBO_IP 钉扎、BK-AGENT）恢复 `RagPrincipalRpcService` provider，memory→auth 验签链闭合。

supervisor 任务链修复链全记录（逐层剥离，每层均有实证）：① 网关登录 500 = dev auth 冻结旧密码（KI-121，复刻对齐后 200）；② 任务 503 = release memory→auth `RagPrincipalRpcService` 断供（release auth 复刻 replica 恢复）；③ 401 = 旧 dev agent jar 的 HttpMemoryStoreClient 无 Bearer/X-Memory-Owner 头（制品版本缺口，改以 release agent 镜像复刻 public supervisor `bk-anent-dev-agent-supervisor-public`：NACOS_NAMESPACE 空、DEEPSEEK key=.env 现值、去除三个 IP 钉扎含 SPRING_CLOUD_NACOS_DISCOVERY_IP）；④ 打招呼 COMPLETED、三意向正确路由（governanceMetadata.calls 为证）但子调用全 OUTCOME_UNKNOWN——真因一为 RocketMQ topic 默认名非法（KI-123，源码五处已修+防回归 3 项测试通过，运行容器以 AGENT_STREAM_ROCKETMQ_TOPIC=bk-agent-session-stream 覆盖后日志层 illegal characters/MQClientException 清零）；真因二为 public 无 `A2aBeaconRpcService` provider（dev auth 旧 jar 无该接口，A2A 调用死在 beacon 签发步从未出站——子容器日志仅注册元数据零任务提交），以 release auth 镜像在 public 复刻 auth 补齐（见下）。行为特性记录：memory 对不存在 session 返回 403 使 `claim` 首请求 500（预建会话后正常，实测 upsert→GET→任务 200）；`executeAuthenticatedCall` 异常走 store.unknown 静默降级（result_json NULL、无日志），排障时需结合 `agent_tool_invocation` 状态与 Nacos provider 清单。Docker 引擎两轮过载（8+ JVM 并发），均按授权重启 Desktop+WSL 恢复。

## 改造前基线实测（任务 1.4 部分达成，2026-10-16 晚）

三代表 fixture 中的两类经正式链路实测（broker01 真实登录 token → dev gateway `lb://` → supervisor → A2A → 子 Agent → memory → SSE），每轮先经 memory REST 预建会话（memory 对不存在 session 返回 403 使 `claim` 首请求 500，故预建是正式链路的必要前置，实测 upsert→GET→任务全通）。SSE 端点在首事件前不回送响应头（curl 8 秒无头实测），故脚本以订阅后预等 2 秒再发请求计时；结果以 POST 时刻为 t0。

| fixture（各 3 轮，全 COMPLETED） | 首可见事件 P50/P95 | 终态事件 P50/P95 | 事件数 | 备注 |
| --- | --- | --- | --- | --- |
| supervisor-listing（找浦东两居室） | 4775.7 / 5040.7 ms | 10184.2 / 11189.2 ms | 8–9 | `a2a:listing-agent COMPLETED`，artifact 每轮生成；子 Agent 真实答复"检索服务未授权，不编造列表"（release rag 容器 exited + 子容器冻结 RPC 凭据，业务检索在本混合环境不可用，链路本身完整） |
| supervisor-interview（带看访谈问题清单） | 5111.8 / 11769.1 ms | 38156.6 / 38577.8 ms | 9 | `a2a:interview-agent COMPLETED`，答复为完整可用的访谈问题清单，artifact 生成 |

事件序列典型形态：agent.started → agent.progress×N →（子工具失败事件）→ artifact.created → tool.completed → supervisor.completed → heartbeat。governanceMetadata.calls 逐轮记录 capabilityId 与结果负载（I/O 计数以事件数与 calls 为实测口径；未单独埋点 DB/RPC 次数）。模型：`gpt-5.6-terra`（public 命名空间 agent-service.yaml 当前生效模板值，经 KE 网关 + .env 现值 key，非模板历史数据）。认证/审计：真实 auth-service 登录 + agent_event_audit 全程落库（supervisor.completed/heartbeat 等 8–9 事件/轮）。

**marketing fixture 环境阻断未测**：A2A 任务到达 marketing 副本、技能激活后约 10 秒内 Docker 引擎整体无响应，2/2 可复现（本会话共 4 次引擎故障，2 次与 marketing 任务强相关；宿主/WSL 资源层，非 OOMKilled，见 KI-124）。按任务规则记为未测，不以其他 fixture 数字外推。**Studio 可读未通**：`GET /agent/studio/v1/works` 经网关持续 503（3/3），疑与 supervisor 启动期约束（marketing MCP 探测失败缓存）相关，因引擎过载无法取日志核实，如实记为未通。

### 2026-10-18 追加：环境清理与 Studio 503 深挖

用户授权清理后停止 11 个本项目失效容器（release auth/agent/listing/rag 崩溃循环 + dev 旧制品/旧密码容器，均只停不删），无关项目容器本就全部非运行态零负载。清理后引擎仍两度过载（累计第 5、6 次，KI-124）——26 容器稳态即在本机资源边缘，marketing 基线第三次重试未及执行即被引擎阻断，维持未测。

Studio 503 深挖结论（绕网关直打应用容器实证）：503 为应用自身返回 `STUDIO_DEPENDENCY_UNAVAILABLE`（通用 Exception 兜底），直打五连击呈"首发 ~991ms（超时特征）→ 61–331ms 快速失败"形态；auth 侧 ProfilerServerFilter 实测 `resolvePrincipal` 业务实现 1.16–2.07s（98% 在 biz impl），超消费端 1000ms Dubbo 超时；快速失败机制因处理器零日志无法取证（KI-126）。已修 `StudioExceptionHandler` 补 WARN 异常日志并加防回归（1 项测试通过）；运行中 release 制品不含修复，Studio 维持未通，待新制品后凭日志续查。

基线结果文件（会话外，不入库）：`D:\Personal\Temp\bk-baseline-result.json`；数字以本表为准。

### 基线窗口期间的回归修复（代码变更，随本批提交）

- **KI-123**：会话流 RocketMQ topic 默认名 `bk.agent.session.stream` 含点号非法——真实链路首次端到端暴露（此前全绿是因为分布式验收测试 mock 了 SessionStreamService）。五处默认值改 `bk-agent-session-stream`（`RocketMqSessionEventBus` 注解+@Value、两份 Nacos yaml、`NotificationWorkflowEventProperties`），新增 `RocketMqSessionEventBusDefaultsTest`（2 项）与 `NotificationWorkflowEventPropertiesTest`（1 项）防回归，均通过。
- **KI-125**：`SupervisorDistributedAcceptanceTest` fixture Agent 名与生产 agent id 冲突——dev 栈 marketing-agent 运行后其真实 Card 成为 Nacos latest，覆盖测试 fixture Card 使断言失败（此前通过属时序侥幸）。fixture 改随机短名 + mock SkillRegistry 提供 fixture allowlist，修复后该测试在真实 Nacos+MySQL 上通过（12.16s）。
- 修复后完整回归：`mvn -o -pl agent-service,notification-service test`（真实 Nacos+MySQL env）BUILD SUCCESS，两模块合计 **158 项测试，0 失败/0 错误/1 跳过**（跳过项为 `SupervisorRealModelEvaluationTest`，需真实模型评估端点，保持既有 assumption 门控）。环境事件如实记录：复测期间 Nacos 曾在引擎重启后出现 AI 注册能力降级（501 "version too low"、控制台 500 Whitelabel、agent-endpoints 出现 `____:` 前缀乱码条目），引擎再恢复后 v1 能力正常；两测试失败均由该降级触发，恢复并完成上述测试修正后全绿。测试遗留 mixed- 行已清理归零。

## 本批相关与全仓回归

使用仓库本地 Maven settings，串行离线执行，不修改 `.m2`、运行配置或服务部署：

```bash
mvn -o -gs .mvn-settings.xml -s .mvn-settings.xml -pl common-a2a -am test
mvn -o -gs .mvn-settings.xml -s .mvn-settings.xml compile
mvn -o -gs .mvn-settings.xml -s .mvn-settings.xml test
```

相关 reactor 于 2026-10-16 15:16:56（+08:00）BUILD SUCCESS，35.765s。common 75 项、common-skill 54 项、common-a2a 103 项（其中 1 项跳过），合计 232 项，failure/error 均 0；transport patch 无测试执行。新增 DTO 29 项与 Hook 25 项均通过，无跳过。common-a2a 的 `LiveSkillAgentCardsDockerTest` 因 Docker Nacos 更新凭据未配置跳过，不证明目标注册集合已验。

全仓 compile 于 15:18:14 BUILD SUCCESS，32.691s；随后 test 于 15:20:24 BUILD SUCCESS，02:09 min，22 个 reactor 项全部 SUCCESS。按本次日志的各模块最终汇总计数（非累加历史 Surefire 文件）：共 1027 项，failure/error 均 0，skipped 7，实际通过 1020 项。common 75、common-skill 54、common-a2a 103、gateway 5、auth 60、agent 154、memory 61、rag 347、notification 1、listing 42、customer 4、compare 5、media 6、marketing 12、promotion 2、business 1、contract 13、settlement 1、interview 81；根 POM、transport patch、common-config-manager 无测试执行。

除上述 common-a2a 一项外，agent-service 有六项真实环境测试跳过，均为既有 assumption gate，未删除或放宽：

- `ApprovalClaimDatabaseConcurrencyTest`：BK_AGENT_DB_URL 未配置，未执行真实数据库并发测试。
- `NacosRollingUpgradeDockerTest`：Docker Nacos 未配置。
- `SupervisorDistributedAcceptanceTest`：Docker acceptance 环境未配置。
- `SupervisorDockerDatabaseTest`：Docker acceptance 数据库未配置。
- `SupervisorRealModelEvaluationTest`：真实模型评估未配置。
- `NacosAgentRegistryDockerTest`：Docker Nacos 未配置。

构建日志仍有既有日志管理器初始化、commons-logging/类共享等提示；未据此更改依赖或系统配置。测试通过不替代这些跳过项、当前环境注册集合、授权链或四阶段性能基线。OpenSpec apply 进度为 3/66（1.2、1.3、2.1）；11.6 包含后续全部实现验证，本批回归通过不提前勾选该整项。

## 任务 2.2：memory v2 原生 Dubbo 契约（2026-10-18 完成）

新增 `MemoryV2RpcService` 单接口九操作（工作集读取、接收许可请求、消息交付、摘要提交、事项/实体查询、偏好/经验搜索、共享 grant 获取），group `bkanent-memory`、dubbo version `2.0.0`、CONTRACT_VERSION=2 固定于 `MemoryV2Contract`；每请求绑定 `MemoryRequestEnvelope`（operation/caller/audience/requestId/bodyHash/contractVersion），requestId+bodyHash 为请求 hash 身份。版本不匹配与 operation 错绑在**构造期**拒绝（UNSUPPORTED_VERSION / INVALID_ARGUMENT），错配请求无法到达序列化层。九方法全部显式 `throws MemoryException`，官方 fastjson2 按接口 throws 精确注册异常族——真实接口注册后全 15 错误码 writeThrowable/readThrowable 轮转通过（MemoryV2RpcExceptionEnvelopeTest 1 项）。18 个请求/响应 record 全量过 ctor-SPI wire 轮转与构造约束测试（MemoryV2RpcContractTest 6 项：版本/operation/信封形状拒绝、分页 cursor、事件 hash、交付 sequence、limit 边界）。设计根因见 LR-57。

本批范围边界：接口与数据形状即 2.2 全部交付；服务端实现、显式 consumer/provider 绑定（2.4/KI-100）、签名与防重放（3.1）未动——运行中的 memory-service 不感知 v2，新契约尚未接入任何真实消费者，不宣称 RPC 链路可用。common 模块回归 82 项（75 既有 + 7 新增）全绿，0 失败/0 跳过。

## 任务 2.3/2.4：薄适配层决策与受管绑定（2026-10-18 完成）

**2.3（不建新模块）**：`mvn dependency:tree` 实证 common 依赖树零 Spring AI / Alibaba graph / agent-framework 制品（仅 dubbo 3.2.15 供 RPC 契约）；memory 包 36+21 个文件零 AI 框架 import；common-a2a 无任何 `com.bkanent.agent`/`com.bkanent.common.memory` 反向 import。按任务"无需求时沿既有共享层组织"判定：**common-memory 不独立建模块**，v2 契约随 common 共享层分发，避免无消费者的 reactor 模块。

**2.4（受管绑定 + KI-100 防回归）**：`MemoryV2ConsumerConfiguration`（ReferenceConfig 构造期钉死 protocol="dubbo"、group `bkanent-memory`、version `2.0.0`、check=false、retries=0、timeout=5000）与 `MemoryV2ProviderBinder`（ServiceConfig 同组版本 + protocolIds="dubbo" + 部署侧 ProviderConfig 注入）。注册中心/地址/凭据只来自部署配置；**任何 `memory.v2.dubbo.url`/`memory.v2.dubbo.host` 配置直接抛 IllegalStateException 拒绝**——延续 KI-100 修复的 consumer.protocol 钉扎模式，杜绝同名 HTTP 实例误选与请求级目标选择。防回归 `MemoryV2ConsumerConfigurationTest` 2 项：绑定钉扎断言 + URL/host 拒绝。Dubbo setter 归属经 javap 逐一核对（ReferenceConfig.setProtocol(String) 可用；ServiceConfig 侧 setProtocol 重载为 ProtocolConfig 故用 protocolIds）。

本批边界：绑定类为受管装配件，**未接入任何运行服务**（memory-service 无 v2 实现、agent-service 无 v2 消费者引用），不宣称链路可用；v1 兼容与差异发布属 2.5。common 回归 84 项全绿（82 + 2）。

## 任务 2.5：v1 兼容边界与 v1/v2 能力差异发布（2026-10-18 完成，第 2 组收口）

源码核实 v1 真实消费者面：agent-service 六个活跃调用点（会话归属 claim/assertOwned、工件存储 `@Primary`、交接、工作流历史、约束引导）+ RAG 经 `MemoryRagRpcService.search` 的私有检索；用户偏好 REST 无业务调用点（接口方法存在、无 caller，已被 RAG 偏好投影链路替代）。交付 `docs/memory-v1-v2-compatibility.md`：逐通道消费者表、v1 五项"不提供能力"清单（无完整历程/四重身份/可靠交付/压缩契约/共享授权——"接入 v1 ≠ 已有域隔离记忆"）、防互污染四规则、失败差异发布表。防回归 `MemoryV1V2CompatibilityTest` 3 项：v2 operation 与 v1 REST 路径不相交、v1 形状无 v2 语义字段混入、v2 DTO 不嵌 v1 类型（结构上杜绝静默双写）。v1 活跃通道形状/鉴权/owner-only 语义承诺不变（LR-40/43）。common 回归 87 项全绿。第 2 组（薄适配与受管 RPC 契约）五项任务全部完成。

## 未完成范围

真实阶段延迟/I/O 基线、memory v2 RPC 与授权、可靠交付/数据库迁移、运行工作集/压缩持久提交、偏好/经验加工、新 RAG 投影和逐域启用均不得由本文件的源码或离线证据替代。未取得真实证据的任务继续保持未勾选；不归档、不宣称项目安全或生产就绪。

## 2026-10-19 追加：Memory v2 身份/防重放基础与最小运行观测

本次继续使用唯一开发栈 `bk-anent-dev`，未创建测试副本容器。登录认证通过并确认合成 fixture owner 为有效用户；没有在文档中保存 token、任务正文或认证材料。Supervisor 通用 async SSE 请求产生 `supervisor.async.accepted`、`supervisor.async.status`、`supervisor.completed`、`supervisor.async.completed`，DB 行为 `COMPLETED`。同任务状态 GET 两次 500（KI-128），所以本次不算状态读取通过。

marketing 与 interview 各提交 1 个受管合成请求，SSE 首个可见事件分别为 239ms / 148ms，请求总时间分别为 9.230s / 6.889s；二者都以 `supervisor.waiting_user_approval` 结束，pending capability 分别为 `a2a:marketing-agent` 与 `a2a:interview-agent`。这些数值只代表到审批边界的一次观察，不是目标 Agent 首事件、完整成功延迟或 P50/P95。未批准也未执行目标 A2A 调用，因此没有业务写入或域 Agent 完整成果可记录。

模型实际身份、输入/输出 token、阶段 RPC/DB I/O 计数、负载、P50/P95 和 Studio 可读结果仍未测。Studio GET 有 `syncLatest` 写入副作用，不能用于纯读取基线。本次安全代码增加 Memory v2 canonical body hash、专用 HMAC proof、精确 caller→domain→agent allowlist、Auth 资格复核和带唯一键的持久 nonce 表；focused tests 15 项 common + 8 项 memory 均通过。provider/client 未接入，未配置 caller，故这仅是安全基础实现，不是运行链路验收。任务 1.4 与 3.1 均保持未勾选。
