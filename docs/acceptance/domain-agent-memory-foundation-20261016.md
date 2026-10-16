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

## 未完成范围

真实阶段延迟/I/O 基线、memory v2 RPC 与授权、可靠交付/数据库迁移、运行工作集/压缩持久提交、偏好/经验加工、新 RAG 投影和逐域启用均不得由本文件的源码或离线证据替代。未取得真实证据的任务继续保持未勾选；不归档、不宣称项目安全或生产就绪。
