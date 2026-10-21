# 事项驱动偏好装配验收（2026-10-21）

## 范围

本增量完成 `integrate-domain-agent-memory` 的 7.5、7.6，进度从 26/66 到 28/66。它将 owner 自身的 domain+agent 私有偏好按已授权事项目标装入 Supervisor 当前输入，并在同一事项、同一用户输入的模型/工具轮次复用。当前用户本轮明确表达仍优先；新输入、偏好版本变化或撤回会触发重新装配或阻止旧选择继续使用。

Memory 使用版本 2.1.0 的签名 Dubbo `assemblePreferences` 契约。服务端只从可信 context 得到 owner、domain、agent 与主体，不采纳请求中的扩权字段。检索按配置中的稳定维度和别名确定性匹配，不调用模型或通用 RAG；选出事项当前有效值及适用临时例外，在配置预算内按 relevance、confidence、activation、time weight 分别计分。一次响应固定 `asOf` 和 scoring version，不因读取、检索或注入增加证据。

每项选择钉定 blockId、revision、content hash、authorization revision、matter revision 和 effective-state hash。Memory 在读派生正文前核验来源；Agent 在模型输入、工具调用和最终输出前复核版本与授权。偏好作为数据注入，并明确服从本轮用户请求、政策、授权和审批约束。既有偏好块逻辑键和 blockId 保持不变，不需数据库迁移。

本次仅支持 owner-self 的 domain+agent 私有偏好。其他主体、共享组、经验装配、通用偏好搜索和向量语义检索仍未实现；7.1、8.x、9.x 及其余未勾选任务保持未完成，本验收不代表整个 OpenSpec change 完成。Agent V2、压缩、Memory 归档与偏好加工开关维持关闭。

## 验证

- `mvn -o -gs .mvn-settings.xml -s .mvn-settings.xml -pl common,common-memory,memory-service,agent-service -am test`：654 项，642 通过、12 跳过，失败/错误均为 0。
- 聚焦回归：`PreferenceHistoryServiceTest` 14 项、`SupervisorMemoryIntegrationTest` 25 项通过；覆盖目标匹配、当前值/临时例外、统一 `asOf`、多字节预算估算、无证据副作用、同事项复用、版本变化和撤回后的旧选择失效。
- 使用已有 `bk-anent-dev-mysql-1` 开发 MySQL 执行 `PreferenceHistoryMySqlTest`：1 项通过。测试只使用随机 owner/domain/agent/conversation fixture，无 DDL、无新增容器；finally 清理消息、事项、回执、writer 与偏好记录。
- `mvn -o -gs .mvn-settings.xml -s .mvn-settings.xml compile`：全 reactor 23 个模块通过。
- `mvn -o -gs .mvn-settings.xml -s .mvn-settings.xml -pl agent-service,memory-service -am '-DskipTests' package`：Agent、Memory 及上游依赖打包通过。

## 开发环境部署

仅通过 `bk-anent-dev` 现有 Compose 项目的 `memory-service`、`agent-service` 更新镜像并原位重建容器，使用 `--no-deps`；项目容器总数从 31 到 31，没有创建副本或启动依赖服务。两项 readiness 均为 HTTP 200 / `UP`。Agent V2、Agent 压缩、Memory 归档、偏好加工开关继续为 `false`；新 RPC 契约已部署但本次没有启用 V2 运行链。

| 服务 | 容器 ID | 镜像 ID | JAR SHA-256 | readiness |
| --- | --- | --- | --- | --- |
| Agent | `df52801e9a6c` | `b67601d99024` | `325f900f392f3077eafbcf3ddddb5cb290aab3a8f77fe20cfd8258365cc2e011` | 200 |
| Memory | `e7355b27abbf` | `bf36b41cd9b6` | `515a9c58d1514b147fa2fa0bc48db8066d7a80fcf8bda6b1ded1edf1620817a4` | 200 |

## 关联

LR-68/69/70/71/72/73、KI-140/141/142/144、change 7.5/7.6。
