# Memory v2 私有与共享授权验证（2026-10-19）

本轮实现 OpenSpec `integrate-domain-agent-memory` 的 3.2 服务端授权组件。请求的接收 context 与来源 exportRef 分离；默认域私有，接收会话、scope/matter/purpose、owner、grant/content revision、有效期和 durable 投影逐项核对。当前用户的账号资格在入站与输出前复核，ADMIN 没有跨个人 owner 特例。`getGrant` 只读取一个指定导出版本，索引状态不决定 SQL 详情是否可读。

授权依据是当前 `memory_share_grant` 行及不可变 `memory_share_grant_history` 快照；RPC 请求不能提供可被信任的 grant。读取顺序是元数据→锁定当前 grant/export→授权→正文；Auth RPC 在事务外执行，返回前再锁内核对 grant/export 当前状态。fixture 验证未授权元数据请求不会读取正文，读取后用户资格失效、grant 撤回或 export 撤回均拒绝输出。链式上游 grant 在任务 3.5 来源复核实现前拒绝。

## 定向验证

使用仓库本地 Maven settings，Java 17，`-o -pl memory-service -am`，仅执行 Memory 契约、签名、兼容、异常 envelope、请求安全、nonce 及共享授权相关测试；Windows JVM 附加限制沿用 `-DforkCount=0`，测试不依赖 Mockito 代理。共享 JDBC fixture 对同一新增 SQL（仅移除 H2 不支持的 MySQL 引擎/字符集选项）执行两次，覆盖新增和重复迁移。

共享授权用例覆盖 TASK_SHARED/SESSION_SHARED 合法访问，完整私有 context 隔离，owner/域/Agent/会话伪造，scope/matter/purpose/content hash/grant revision 不符，未生效/过期/撤回，只有展示/协作权限，仅持引用无 SQL grant，grant 历史/元数据损坏，正文夹带未授权来源，输出窗口的用户资格与 grant/export 撤回，以及签名篡改和 replay。锁定读取与正文读取必须处于实际事务内；fixture 同时断言 Auth 调用不处于 SQL 事务内。

最终定向测试 common **45 项**、memory-service **22 项**（其中共享授权 14 项），合计 **67 项，0 失败、0 错误、0 跳过**，BUILD SUCCESS。初次补充 wire 用例时修正测试辅助方法的 Object 返回类型转换；日期 fixture 按微秒截断后复测通过。未通过删测试或放宽授权断言取得结果。

```powershell
mvn -o -gs .mvn-settings.xml -s .mvn-settings.xml -pl memory-service -am "-Dtest=MemoryContractTest,MemoryV2RpcContractTest,MemoryRequestSignerTest,MemoryV1V2CompatibilityTest,MemoryV2RpcExceptionEnvelopeTest,MemoryV2RequestSecurityTest,MemoryV2ReplayGuardTest,MemorySharingAccessServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DforkCount=0" test
```

## 现有开发数据库迁移

只使用已存在且标签核验为 `bk-anent-dev:mysql` 的 `bk-anent-dev-mysql-1`。完整清单 24 个 SQL/check 文件的 LF/SHA256 均匹配；只应用清单 110（nonce）和 120（共享授权）两项，未执行其他库迁移。两次执行均通过对应 MySQL schema check，迁移账本保存原 checksum；新 nonce/grant/history/export 四表行数均为 0，无运行 grant 或用户正文 fixture。

此次没有创建、启动或重建容器，没有修改 `.env` 或现有账号权限。MySQL 凭据仅由容器既有环境提供，未进入命令参数值、文档或结果。Git Bash 受当前 Windows 沙箱的 MSYS 对象创建限制，迁移选择、清单 checksum、目标标签、回执与重复执行使用同一受管规则的 PowerShell 调用完成。

## 保留的验收边界

v2 provider/真实消费者、来源域 grant 签发与导出发布尚未启用；服务端代码及开发库 DDL 不代替目标 A2A/SSE、性能或浏览器验收。任务 1.4、3.3–3.5、完整 4.2 仍未完成，v1 链路继续沿原授权。来源删除、隐私清理与衍生内容重建不得由本轮授权用例宣称已经实现。根因见 LR-59，缺陷见 KI-129/130。
