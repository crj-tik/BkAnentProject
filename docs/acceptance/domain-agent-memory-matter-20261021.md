# 事项生命周期与历史指代验收（2026-10-21）

## 范围

完成 `integrate-domain-agent-memory` 的 6.1 和 6.6。OpenSpec 当前为 **26/66**；尚余 40 项，自动事项候选及偏好/经验装配仍是 5.3、7.5。

Supervisor 请求新增可选 `matterIntent`，提供 START/CONTINUE/CLOSE/REOPEN，并与原 USER 档案事件一起留存。模型工具、assistant/tool 状态和子任务完成结果不能关闭事项。每条持久消息冻结所属 matter；关闭事项不会被普通新输入自动选中或重开。关闭产生的压缩走同一协调器，要求 Memory 中已确认 CLOSED，且只覆盖该事项安全的连续前缀，保护审批、未知执行结果、工具组及其他事项。

事项写入以当前用户、已归档 USER 事件、原可信 caller、writer fence 与 conversation/matter revision 为约束。START 稳定 ID 基于 context+source event；事项变更追加不可变版本。重放回执幂等，旧回执不回退新工作集版本。

`memory_resolve_reference` 在 Graph 的只读 MemoryRecall 节点运行：先精确匹配当前摘要中的 ID/别名；无本地匹配时最多补查同一 context 的两页、每页 20 个实体。歧义、无证据或扫描未完均要求澄清。查询范围没有任意 owner/domain/context 参数，补查依赖会在模型输出前再次授权。

发现并修复 KI-144：事项、实体派生正文以前会先解码再查来源。现先验证精确来源依赖元数据，再打开对应版本正文，并核对 hash、revision 与依赖一致性。

## 验证

- 受影响模块全仓 **23 模块离线编译**通过；Agent、Memory 及上游 **9 模块离线打包**通过。
- 本轮广泛聚焦回归 **178 项**通过；代码收尾后再次运行 **71 项定向回归**，覆盖 Graph 生命周期、关闭后不自动归属、关闭低于软阈值压缩、重开、旧 journal JSON 兼容、精确/歧义指代、两页扫描边界、元数据先授权和 Native Dubbo。
- 使用 `.env` 中已有的开发 MySQL 凭据，对现有数据库运行事项生命周期测试 **1 项通过**：事项创建/关闭、压缩、冷启动重开、旧回执不回滚当前版本、删除来源后清除版本/回执正文。fixture 在 finally 精确清理，复查残留 conversation 数为 0。无测试容器。
- 迁移清单 180 先 dry run，再执行升级两次；两次检查均通过。
- `openspec validate integrate-domain-agent-memory --strict` 通过。

## 开发环境部署

代码提交并推送：`777787e`（`feat: 完成事项生命周期与历史指代解析`）。只重建 `bk-anent-dev` 原有两个服务，容器总量仍为 69，项目容器仍为 31；其余 29 个项目容器名称与状态未变。没有新增容器。

| 服务 | 更新后容器 ID | 镜像 ID | 容器内 JAR SHA-256 | readiness |
| --- | --- | --- | --- | --- |
| Agent | `ae3a75244afc` | `0e87153f96d0` | `92d6de8d0e9a73a421f674d92b48b2180e4489c8a3ad8d78f5f74557cd613e65` | 200 |
| Memory | `124a0f99d0d7` | `63ddefff0dd7` | `812c5ae782f7e71e2a70da56515411ad89129682a850620cf9c990fc50e12f0f` | 200 |

Agent V2、Agent 压缩与 Memory V2 开关继续保持 `false`；这批调用方和 provider 契约已同步部署，功能尚未启用。启用前须为 Agent 与 Memory 两端配置匹配的签名 caller/key 和环境参数；v1 API 不变。V2 共 15 个操作。验收本身不代表线上模型或真实用户请求已走 V2。

## 关联

LR-71/72、KI-144、change 6.1/6.6。
