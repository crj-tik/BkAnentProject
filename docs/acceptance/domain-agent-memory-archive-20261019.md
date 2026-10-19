# Memory v2 可靠归档模块验收（2026-10-19）

变更：integrate-domain-agent-memory；分支 dev-fix。按一个完整归档模块交付，不按单个任务停顿。只使用 bk-anent-dev 原开发栈。

## 已实现边界

- 当前用户申请持久精确许可；caller、完整 context、事件/hash/purpose 绑定。相同 requestId/body 重试返回同许可，异 body 冲突。许可保留 7 天；秘密/用户 token/proof 不入库。
- USER/SERVICE actor 进入签名；SERVICE 仅在部署明确启用该 caller 的 serviceDelivery 后，补交付原许可事件。不能据 owner 换发用户凭据、申请许可或读记忆。进程重建仍需原 actor 当前认证才能读取。
- 延迟调用结果绑定已经归档的 TOOL/ACCEPTED 来源、callId、固定结果 event/message/sequence；每次受理最多一个结果身份，首次内容冻结，模型调用提案及插入其他用户输入被拒绝。真实执行器来源构造仍属 Agent 接线验收。
- 消息 sourceEventId/messageId/sequence 唯一，严格连续追加；相同内容重试不重复。持久回执、真源和待加工 job 同事务，任何批内失败全部回滚。
- 版本化 conversation/message/matter/summary/entity 表，复合身份与 covered cursor。摘要完整结构、来源集合、conversation OCC 与覆盖同事务；工具组缺响应、审批等待或未知结果拒绝。未承担执行 checkpoint CAS，带 runVersion 的提交明确 VERSION_CONFLICT。
- 冷恢复从当前有效摘要加未覆盖尾部重建；私有/共享来源版本和当前授权递归复核，最终输出再认证与复核；Auth 不占 SQL 锁。旧同 owner 正文不会自证可读。
- 撤回立即失效派生集合/导出；删除清除本地正文，保留 tombstone 与清理待办，状态 DELETION_PENDING。迟到许可/交付不复活，领域业务执行记录不受改写。外部清理 worker 未实现，不能报告全部删除完成。
- 固定原生 Dubbo provider，增加 withdrawSource 后共十方法。归档开关默认 false。偏好/经验搜索尚未实现，明确 MEMORY_UNAVAILABLE，不返回假空匹配。

## 验证

定向 Maven 使用仓库本地 settings、离线依赖和 forkCount=0；不启动 Spring/AI/全量 Docker 集群。测试类：MemoryContractTest、MemoryExceptionSerializationTest、MemoryRequestSignerTest、MemoryV1V2CompatibilityTest、MemoryV2ConsumerConfigurationTest、MemoryV2RpcContractTest、MemoryV2RpcExceptionEnvelopeTest、MemoryArchiveProviderTest、MemoryArchiveServiceTest、MemorySharingAccessServiceTest、MemoryV2ReplayGuardTest、MemoryV2RequestSecurityTest。

```text
mvn -o -gs .mvn-settings.xml -s .mvn-settings.xml -pl memory-service -am "-Dtest=MemoryArchiveProviderTest,MemoryArchiveServiceTest,MemorySharingAccessServiceTest,MemoryV2RequestSecurityTest,MemoryV2ReplayGuardTest,MemoryContractTest,MemoryRequestSignerTest,MemoryV2RpcContractTest,MemoryV1V2CompatibilityTest,MemoryV2RpcExceptionEnvelopeTest,MemoryV2ConsumerConfigurationTest,MemoryExceptionSerializationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DforkCount=0" test
mvn -o -gs .mvn-settings.xml -s .mvn-settings.xml -DskipTests compile
mvn -o -gs .mvn-settings.xml -s .mvn-settings.xml -pl memory-service -am -DskipTests package
```

Native provider/consumer 测试为进程内 injvm、scope=local，不使用 Nacos、不新增网络服务/容器。普通 DTO 使用实际 fastjson2ctor wire 轮转，异常声明沿官方接口注册回归；这不替代真实跨进程 RPC/Agent 调用验收。

最终定向结果：common 52 + memory-service 50 = 102，失败/错误/跳过均为 0（其中归档服务 25、provider 2、共享来源授权 15、nonce 3、请求身份 5）。加真实 MySQL 1 项，共 103 项验证通过。冷恢复超预算、最大实体 ID 游标、缺少 sourceEventId 的来源失败关闭均已回归。

MemoryArchiveMySqlTest 以 MEMORY_ARCHIVE_TEST_MYSQL_URL/USER/PASSWORD 显式启用；凭据从已核实 bk-anent-dev:mysql 容器进程环境取入当前测试进程，不写入测试源码或日志。只在现有 bk_memory 的随机 archive-fixture context 中写合成数据，finally 按精确 context/keyId 清理。

真实 MySQL：1 测试通过，含两实例并发同事件、一个消息/一个 job、重建 service、摘要/覆盖提交、恢复、删除 pending、拒绝迟到重放；EXPLAIN 命中 uk_memory_message_event。该测试使用当前用户授权 stub，证明 SQL/事务契约，不冒称真实 token/租约或 A2A 端到端已通过。

全仓 compile 通过，Memory reactor package 通过。一次宽泛 `Memory*Test` 触发既有 Mockito 动态附加管道失败，已中止；最终使用上述明确类清单通过，不宣称全仓 test 通过。

## 迁移

清单新增 130：memory-v2-archive-20261019-v1。

- SQL SHA256：31de78670026d1f0ab7b36c2d398ae473da5e00938f3b759b19bb5bae42791c1
- check SHA256：f82928d7a5775e683924fadfa3de98c9d52f5297a24a7fa6d9895a53404f5b4b
- 现有 bk-anent-dev-mysql-1/bk_memory 上两次执行均 check=1，73 列/10 张 InnoDB 表及事件/消息唯一索引符合。
- 全部 26 个 manifest SQL/check 文件按 canonical LF SHA256 通过。未修改已应用 110/120 的字节；账本记录 130 版本与校验和。
- H2 空数据库每个 fixture 重复执行三份迁移；未创建额外 MySQL 容器或新卷，未做全新 MySQL 卷的端到端首启演练。

## 部署与启用

只更新原 bk-anent-dev-memory-service-1，镜像 bk-anent-memory:dev；Compose build 限定 memory-service 的上游模块。此次在宿主完成 package，复用已有本地运行时镜像加入新 jar，无拉取/全栈重建。Compose 使用 --no-deps --no-build，仅 Memory 容器 ID 改变；其他原容器 ID 均相同，未出现 release/public-a2a 旧副本。容器 readiness UP，jar hash 与构建一致。

最终运行 jar SHA256：3f1e9140ecd0b4b1803d1315f86cb71f3bf8797aee5b27338f929bfb01249d8d；容器 restartCount=0、readiness UP、archive-switch=false。现有开发库的 message/permit/job/nonce 行数均回到 0，随机 fixture 无残留。保留迁移表结构与版本账本。

`MEMORY_V2_ARCHIVE_ENABLED=false`；trusted-callers 仍为空。未来只在受管配置中登记精确 caller/domain/agent、ENV 密钥和 serviceDelivery 后开启。没有新增通用用户读取权限、用户 token 持久化或服务 impersonation。关闭归档开关不会删除 v2 真源/许可/job，也不回填 v1 的 summary/memory_json。

## 尚待后续大模块

- Agent 工作集、原 actor 入口认证/租约、journal/可靠领域账本引用、checkpoint 恢复与每次入模/输出来源复验的真实调用点（第 5 组）。当前没有改动业务执行器或模型调用链。
- 3.5 的运行工作集/旧 checkpoint 适配；4.2/4.5 的偏好、经验 schema 与受管 worker/lease/有限重试；4.7 的外部副本/索引删除确认；6.x 的模型候选、独立 run CAS 与新压缩接线。队列原子提交只是 handoff，不标已加工或已索引。
- 跨进程注册中心 RPC、正式 Supervisor/marketing/interview SSE 与模型/性能基线仍未完成，不启用生产式新读取或加工。

本次验收完成 3.3、3.4（服务端安全组件）、4.1（结构/升级）、4.4。总体 14/66；其他任务保留原状态，未自动归档。
