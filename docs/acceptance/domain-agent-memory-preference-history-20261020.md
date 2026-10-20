# 偏好归并与版本化完整历程后端验收（2026-10-20）

## 范围

本轮完成 `integrate-domain-agent-memory` 的 **7.3/7.4**，整体 **23/66**。提供稳定偏好块、来源幂等、显式修正/OCC、未决冲突、临时例外及固定版本历史分页的原生 RPC 后端。

目前只支持自身、domain+agent 私有范围，跨该 Agent 的会话归并。7.1 的其他主体和显式共享、7.2 的异步模型评估 worker、7.5 的 Agent 装配、7.6 的完整权重与使用策略保持未完成。`searchPreferences` / `searchExperiences` 仍显式返回 MEMORY_UNAVAILABLE；本轮不接 V2 RAG、不自动学习偏好、不启用 V2，不代表已完成整组偏好功能。

## 实现

- `MemoryPreferenceAssessment` 仅携候选语义；owner/subject/scope/ID/序号/来源时间由服务端确定。稳定维度注册表拒绝逐句新键。仅同一可信 source_caller、当前用户、已归档且当前有效的 USER 事实可提交，assistant 复述、问题、事项约束及其他评估不增加长期观察。凭据形状和空长期值拒绝。
- `submitPreferenceAssessment` 使用每块 revision/OCC 和行锁，sourceContext/event + block 唯一身份幂等；同源不同候选 hash 冲突。源状态、内容 hash、角色和首次接收时间均核验。SERVICE 交付身份不能使用该接口。
- 纯 reducer 按同会话 sourceSequence 与显式 corrects 关系决定当前值，不比较不同会话的局部序号，不用加工完成顺序覆盖。跨会话互斥表达无明确因果则保留未决；否定使用独立 polarity。临时例外按事项和有效期选择，过期回到仍有效的长期值。
- 每次归并保存不可变 transition、块 revision 快照、来源依赖与独立 INDEX_PREFERENCE 待办；尚未启用该索引 worker，写入成功不等于可检索。原有 v1 单值偏好表和衰减逻辑不参与。
- `readPreferenceHistory` 按稳定 blockId 读取；固定 content/authorization revision，通过受信接收时间及来源身份的复合 keyset 分页。迟到旧源在新版本的时间轴中归位，旧游标继续原版本且不移位。单片段引用也只定位同一个块，不另建碎片偏好；V2 RAG 派发待后续接入。
- `block.currentState` 是归并时快照，实际消费使用详情中的 `effectiveState` 与显式 asOf/matter。读取与分页不写回 revision、confidence、观察时间或证据计数。
- 来源元数据先鉴权再读取快照/历程正文；Auth RPC 在事务外，输出前再次验证来源及用户。来源撤回立即阻止整个依赖块读取，删除清除快照/历程正文并保留必要 hash/定位；外部清理继续使用 DELETION_PENDING，未实现清理 worker 不报完成。

## 时间与容量边界

新增 `memory_message_observation.accepted_at` 与首次归档同事务保存，重复交付不刷新。occurredAt 记录 Memory 源事件首次接收，observedAt 记录首次归并观察；不冒充用户客户端发言时间。无可靠时间的旧来源拒绝自动归并。

单块最多 10000 条，累计正文与来源元数据各 8MiB，单次响应最多 1MiB；容量不足显式失败，原历史保留。分页查询使用块/来源索引，并在有界数据上排序；不宣称无 filesort，不将 EXPLAIN 当作端到端性能基线。来源撤回后本阶段保守禁用依赖块，自动重建仍待后续 worker。

## 验证

相关广泛回归 **279 项，零失败、零错误、零跳过**：common 54、common-memory 2、Supervisor/认证/编排 78、memory-service 145。随后补充的安全断言、原生薄客户端及实际分页 EXPLAIN 用 **15 项聚焦回归**再次通过；不将重复执行叠加计数。

- 12 项偏好 SQL 集成覆盖负极性、同源幂等/内容冲突、重投不刷新时间、重启读取、迟到旧表达、跨会话冲突与显式修正、例外过期、旧版本继续、撤回/删除、跨 Agent 正文零读取、未知维度/assistant/凭据/空值拒绝、SERVICE 拒绝、Auth 失效、正文 hash 篡改、缺失可信时间及并发 OCC。
- 601 条完整历程通过 500 + 101 两页读取；长 fixture 直接构造有效历史以专门验证分页，不冒充 601 次真实模型评估。迟到源场景同时验证新版本按时间归位、旧版本继续页不混入新增事件。
- 既有 MySQL 上执行新偏好用例与原档案用例；新用例覆盖 UTC 微秒、稳定回执、固定版本完整度、真实复合 keyset 查询 EXPLAIN、删除后旧引用拒绝。使用随机 domain/agent/context，finally 精确清理，不创建数据库或测试容器。
- 真实原生 Dubbo provider/consumer 和 common-memory 薄客户端验证新写入/详情方法；默认 provider 关闭、显式启用的依赖注入、异常 envelope 及旧 v1 兼容通过。
- 修复规范 hash 对有限 Double 的支持，拒绝 NaN/Infinity，既有消息/摘要类型标签不变。测试请求先冻结 asOf 再构造签名草稿和最终请求，避免两次取时使 bodyHash 不同。

```powershell
mvn -o -gs .mvn-settings.xml -s .mvn-settings.xml -pl agent-service,memory-service -am "-Dtest=Memory*Test,Memory*CompatibilityTest,Preference*Test,SupervisorToolLoopGraphTest,AgentRagCredentialLeaseStoreTest,OrchestrationStoreTest,SupervisorMemoryIntegrationTest,SupervisorMemoryPropertiesTest,SupervisorMemoryProjectionTest,SupervisorMemoryResultViewTest,SupervisorMemorySummaryGeneratorTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DforkCount=1" test
```

本地 MySQL 身份仅从 `.env` 注入当前测试进程，凭据不进入验收材料。测试使用固定候选和受控 Auth，不代替模型评估质量、真实业务 SSE 或性能验收。

全仓 23 模块离线 compile、Memory 服务及上游 4 模块离线 package（跳过重复测试）、OpenSpec strict validate 均通过。

## 迁移与部署

迁移 160 新增 observation、preference_block/revision/transition 四表，保持既有 SQL 字节不变。通过 manifest LF/SHA256 dry-run，既有 `bk-anent-dev-mysql-1` 完整 upgrade/check 两次通过；H2 fixture 同一迁移也重复执行。没有自动导入旧单值偏好。

本轮只更新既有 `bk-anent-dev-memory-service-1`，V2 保持原先默认关闭状态。使用本机离线 package 产物覆盖既有镜像内 Memory JAR，随后 `--no-deps --no-build` 更新规范服务，无额外构建/测试容器。

2026-10-20 14:39（Asia/Shanghai）复核：

| 服务 | 容器 ID | 镜像 ID | readiness / health |
| --- | --- | --- | --- |
| `bk-anent-dev-memory-service-1` | `09e919b7cde6` | `ac517bdedfba` | 200 / 200 |

容器内 JAR 与本机产物 SHA-256 均为 `c15fc5085cca1e284b66cc3ea53db67cb107eca7da728245b6ce59e8d85bef61`，镜像标记 `org.opencontainers.image.revision=6bba908`。现有项目容器仍为 31 个、运行 24 个，其余 30 个容器名称与 ID 完全不变；Agent 等原服务未重建。

## 关联

LR-68/69；KI-140/141。实现与修复提交：`6bba908`，已推送 `origin/dev-fix`。
