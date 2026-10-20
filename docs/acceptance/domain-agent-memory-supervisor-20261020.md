# Supervisor Memory v2 工作集模块验收记录（2026-10-20）

## 范围

本模块把 `agent-service` Supervisor Graph 接入 Memory v2 工作集、写入 fence、可靠 checkpoint 引用 journal、当前来源复核和安全压缩。仅覆盖 Supervisor。marketing/interview、其他 Agent、v2 RAG、偏好/经验加工、共享 export/grant 和完整物理删除 worker 尚未接入。

实现与修复提交：`6d66c14`。

后续安全压缩模块（`8e02b8f`）已补齐 6.2/6.3；本文件保留前序验收时点，最新进度与部署制品见 `domain-agent-memory-compaction-20261020.md`。

## 运行边界

- Agent Memory v2 与压缩默认关闭；本地环境未配置专用 `AGENT_MEMORY_V2_SIGNING_SECRET`，因此不能启用该入口。
- Memory archive 开关仍默认关闭，可信 caller/domain/agent allowlist 未声明前 provider 继续拒绝请求。
- 迁移 140/150 已在既有 `bk-anent-dev-mysql-1` 应用并由 manifest/check 核验；完整 upgrade 清单 040–150 验证通过。真实 MySQL writer fence、无 fence 拒绝及可靠交付回归通过。Agent journal 的真实库结构检查通过，Graph journal 行为由 H2 集成回归覆盖。
- 本次复用既有 `bk-anent-dev`，只更新其 `agent-service` 与 `memory-service`，没有创建应用副本或测试容器。

## 实现

- `common-memory` 提供有界 LRU 工作集、actor 每次操作签名、writer 租约续期、来源边界复核和摘要协调器。
- Memory service 持久保存单 conversation writer fence；登记 fence 后拒绝无 fence 写入。SERVICE 仍只能补交付先前精确许可的事件。
- Agent journal 存 checkpoint 引用、hash、sequence、permit/state，不复制业务或工具正文。重复 capture 从原不可变 checkpoint 重建。writer acquire/许可申请对相同 run/fence/事件集合使用稳定 requestId；proof nonce 仍逐请求更新。
- Supervisor PrepareContext/Model/ApprovalGate/WaitInput/ExecuteTool/Observe/Complete 接边界；已执行的调用不会因 Memory 失败重跑，恢复使用同 run 和原节点。
- 结构化工具结果按完整 JSON 归档；回调返回 `ACCEPTED`/`RUNNING` 映射为 RETURNED，不作为服务端 invocation-acceptance 证明。
- 压缩模型没有工具，候选必须引用冻结依赖，服务端先提交 conversation 覆盖，执行器再采用；失败保留原 checkpoint。事项确认触发尚未接入。
- 存在前序 run 或摘要时，Supervisor 不把记忆内容发给外域 A2A 能力，直到 durable recipient grant 路径完成。
- 来源在模型或工具观察期间撤回时，终态响应隐藏调用正文、工件与交互元数据；执行 checkpoint 保留已发生事实供同 run 恢复，已完成动作不重发。

## 回归状态

- 最终一次相关聚焦回归共 **242 项，零失败、零错误、零跳过**：common 契约 52、common-memory 缓存 2、Supervisor/认证租约/编排 60、memory-service 128。包含真实 MySQL 用例；随机 fixture 在 finally 中精确清理，没有新建数据库容器。
- Supervisor 的 12 项 Graph 集成覆盖工作集复用、冷恢复、不重复输入、丢回执后复用已完成调用、输出/工具观察撤回、禁用 V2 后拒绝旧 checkpoint 回退、外域历史阻断、硬预算和摘要超时取消。RPC 远端使用受控 fixture；这不是启用开发 V2 后的外部 SSE 负载验收。
- Memory runtime 的 18 项集成覆盖当前来源复核、writer 接管/过期、无 fence 拒绝、SERVICE 精确补交付、writer/permit 丢回执幂等、新输入到达、token/确认闭合候选竞争、提交后 run adoption 失败及完整尾部恢复。
- 父 POM 固定 Surefire 3.5.4，并把仓库根路径传给独立测试 JVM，解决默认 fork 中 SQL fixture 根路径为空的问题。最终使用 `forkCount=1`，避免多个模块在 Maven 进程内共享 H2 驱动注册状态。
- 全仓 23 模块 `compile` 通过；选中两个服务及上游的 9 模块离线 `package -DskipTests` 通过。manifest 的 Windows CRLF 末列读取已修复，dry-run 与实际 upgrade 均通过。

最终回归命令（MySQL 测试身份只从本地安全配置传入进程环境）：

```powershell
mvn -o -gs .mvn-settings.xml -s .mvn-settings.xml -pl agent-service,memory-service -am "-Dtest=Memory*Test,Memory*CompatibilityTest,SupervisorToolLoopGraphTest,AgentRagCredentialLeaseStoreTest,OrchestrationStoreTest,SupervisorMemoryIntegrationTest,SupervisorMemoryPropertiesTest,SupervisorMemoryProjectionTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DforkCount=1" test
```

## 开发部署

| 服务 | 当前容器 ID | 当前镜像 ID | readiness / health |
| --- | --- | --- | --- |
| `bk-anent-dev-agent-service-1` | `9aa38fdccfcc` | `85e6d2bba3c1` | 200 / 200 |
| `bk-anent-dev-memory-service-1` | `8281bcaf40fb` | `1197085aaee2` | 200 / 200 |

容器内 JAR 的 SHA-256 与本机 package 产物一致：

- Agent：`532d089b7ba1aceb992af0b67a7ae38d6792a46750e65b436657be2ea58fd0e8`
- Memory：`3d9311bd22e080d21f17c494bc7da7c3542dd8cdedcc2e2f76983cbe6c5be7a2`

Docker 内 Maven 冷构建期间再次出现 KI-124 的 Engine API 500；恢复现有 Docker 后端后，使用本机离线 package 产物覆盖既有镜像中的对应 JAR，保留共享镜像内其他模块制品。随后以 `--no-deps --no-build` 串行更新上述两个规范服务，其他开发服务容器 ID 未改变。Agent 的整体 health 暴露 KI-48 的 Redis localhost 误连，本次为它注入开发网络内的 Spring Redis host/port，复核整体 health 与 readiness 均为 200。

## 验收结论

Supervisor 工作集、持久 writer、可靠 checkpoint 引用交付及压缩提交/采用恢复模块已完成本次验证并部署到既有开发容器，V2 开关保持关闭。OpenSpec 本次完成 5.1、5.2、5.4、6.4、6.5，整体为 19/66。

6.1 的真实事项闭合触发、6.2 的大工具结果有界引用、6.3 的实体摘要接线仍未完成，因此这些任务保持未勾选；其他领域消费者及后续加工/检索模块亦未宣称完成。外部真实 SSE 负载基线仍按 task 1.4 记录为未测。
