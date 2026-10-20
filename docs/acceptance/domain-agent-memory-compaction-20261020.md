# Supervisor 安全压缩与大结果补忆验收（2026-10-20）

## 范围与完成项

承接工作集模块，本轮完成 `integrate-domain-agent-memory` 的 6.2/6.3，整体 **21/66**。包括有界工具结果视图、独立只读补忆 Graph 节点、严格结构化条目/实体候选、实体版本与来源校验、累计摘要保留锚点，以及完整工具组跨长距离配对。6.1 的真实事项闭合触发、6.6 的事项关闭/重开与指代解析仍未完成。其他领域消费者、共享导出、偏好/经验、V2 RAG 与真实 SSE 基线不在本次验收结论中。

## 行为与边界

- 原始 JSON 结构和类型完整保留于现有执行 checkpoint 与 Memory 档案。仅 prompt 投影将大结果替换为带完整度、event/call/source 版本/hash、原执行状态和专用 reader 的引用。`RETURNED` 不升级为业务成功，部分完成与未知结果不被改写。
- `memory_read_result` 只在 V2 工作集生效时加入模型工具定义；不能与其他调用混批，计入既有工具额度。参数仅允许既定 event/call/ref/pointer/offset/limit，不支持任意 owner、context、URL 或 checkpoint。
- journal 元数据定位不读取正文；同 context、RECEIVED、精确 ref 匹配且当前 Memory 来源检查通过后，才重建原不可变 checkpoint。正文读后再次核验，失败保留执行事实，可从 MemoryRecall 节点恢复，不重跑业务工具。
- 补忆按 JSON pointer 选择字段，返回有界 JSON 文本页及 UTF-16 编码、完整度和下一偏移。整份返回受 `recallChars` 约束，分页不拆代理对；已受限的补忆页保持内联，避免递归引用。
- 摘要模型没有工具，只能生成严格 JSON 对象中的七类 items 与 entities；evidence 引用冻结下标。空 items、失败字符串、错误字段/下标、凭据形状及超预算候选不提交，原工作集保留。
- 新实体身份来自 context/revision/候选序号，新别名必须逐字出现在所引用户文本或工具标量值；assistant 自述和元数据键不能建立实体。旧实体按明确下标修订，不按重名推测合并，未修改实体随累计摘要保留。provider 验证实体自身 hash、精确来源和原工具状态。
- 工具定义、description/schema 和摘要系统指令纳入输入估算预算。估算器仍为已记录的 UTF-16 粗估版本，不等同供应商 tokenizer。
- 摘要 payload 内增加 `toolResults`，无新增 SQL；旧空列表摘要 hash 不变。V2 请求签名字段增加，首次启用前 caller/provider 须同步升级；v1 不受影响。

## 验证

最终聚焦回归 **265 项，零失败、零错误、零跳过**：common 53、common-memory 2、Supervisor/认证/编排 78、memory-service 132。真实 MySQL 用例使用 `.env` 的本地身份，通过进程环境传入；既有开发 MySQL 中随机 fixture 精确清理，无测试容器。

全仓 23 模块离线 compile、两个受影响服务及上游的 9 模块离线 package（跳过重复测试）均通过；OpenSpec strict validate 通过。

- 18 项 Supervisor Graph 集成覆盖大结果完整归档与按字段补忆、跨 run 压缩后冷回取、来源撤回后正文读取为零、同 run 恢复且业务只执行一次、未知引用安全失败、混批拒绝、补忆全局额度、巨型工具定义预算拒绝；保留此前工作集/撤回/超时回归。
- 5 项 ResultView 回归覆盖完整原结构不变、引用状态、JSON 转义与代理对分页、伪造引用/无效字段及已受限补忆页内联。
- 6 项 SummaryGenerator 回归覆盖七类结构入口、带证据实体、同名不同身份、旧实体保留/修订、无依据别名/非法 evidence/凭据拒绝、隐藏大结果不进入摘要 prompt、候选预算失败。
- 29 项 ArchiveService 与 18 项 WorkingSetRuntime 集成覆盖原服务约束、实体 hash/详情篡改、未变实体跨累计摘要重用、精确结果状态/撤回、超过 20 消息的工具配对、后续累计摘要保留结果锚点。
- common 契约回归验证 Java/Fastjson2/Jackson 往返、缺失新字段的旧 JSON、空锚点旧 hash 和新增锚点 hash。

```powershell
mvn -o -gs .mvn-settings.xml -s .mvn-settings.xml -pl agent-service,memory-service -am "-Dtest=Memory*Test,Memory*CompatibilityTest,SupervisorToolLoopGraphTest,AgentRagCredentialLeaseStoreTest,OrchestrationStoreTest,SupervisorMemoryIntegrationTest,SupervisorMemoryPropertiesTest,SupervisorMemoryProjectionTest,SupervisorMemoryResultViewTest,SupervisorMemorySummaryGeneratorTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DforkCount=1" test
```

真实 Graph 使用受控 Memory RPC endpoint；provider 使用真实 SQL/签名/事务与进程内 Dubbo 测试。这些结果不代表开发环境已启用 V2，也不代表外部真实 SSE 性能验收。

## 开发部署

部署仅更新既有 `bk-anent-dev` 的 `agent-service` 与 `memory-service`，不启动新副本或冗余依赖。使用本机离线构建产物覆盖现有镜像内对应 JAR，避免 KI-124 已确认的 Docker 冷构建过载。V2 及压缩开关保持原状态。

最终部署制品与健康检查结果在完成更新后补录。

## 关联

LR-66/67；KI-138/139。前序模块验收见 `domain-agent-memory-supervisor-20261020.md`。本轮实现提交及部署制品关联在最终记录中补录。
