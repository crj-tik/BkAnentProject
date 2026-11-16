## Context

Auth 的 `user_account.id` 是现有数值型账号主键；`auth_user_business_identity` 将 userId 与类型化 brokerId 显式关联。Customer/Listing/Marketing 已有可空 owner 列，Customer/Listing 新建时取签名 actor 和 BROKER 映射。Agent、Memory 和 Knowledge 的部分持久字段以字符串保存用户 ID。现有清单 `docs/authorization-user-ownership-inventory.md` 为具体表、字段和旧链路索引；LR-145/146/167 附近的实际规则及 KI-55/204/206/243 需在实施时复核，编号以清单实际条目为准。

## Goals / Non-Goals

**Goals:** 建立可追踪的字段语义与写入来源，修复当前已支持路径上的 UCID、实际操作人与 broker 对应关系，直接对开发数据库已证实旧数据进行修正并验收。

**Non-Goals:** 不新增第二套 UCID、为字段统一名称而全表重命名、重做角色权限、开放代录/协作/交接或跨人审批、以历史映射推断未经确认的历史归属，不触碰生产数据。

## Decisions

### 1. 标识与字段

UCID 定义为正数 `user_account.id`，使用现有 principal/context 的 actor；BIGINT 原样存储，VARCHAR 存十进制字符串。沿用 API 的 userId、ownerUserId 等命名并记录 UCID 定义，不引入冗余新列。替代方案是新建独立 UCID 字段：在本系统单账号标识需求下带来映射及全链兼容成本。

按 `docs/authorization-user-ownership-inventory.md` 为每个字段建立表级矩阵：取值是已验证 actor、已验证资源 owner、还是受权目标用户；必须记录具体创建人但现有字段只能代表负责人时列为缺口，不能虚构历史创建人。实际无业务审计需求的子记录通过父记录关系继承归属，不强制增加字段。

### 2. 各域写入与验证

Customer：本人创建 profile/inquiry 时 owner = actor，broker = 签名上下文 BROKER 映射；follow owner = 实际跟进 actor，比较关联 created_by 和命令回执 actor 为操作人。对非本人已有授权路径，遵循原有资源边界，不新增跨人操作授权。Listing：创建 owner 和 broker 同源，asset/price/status/receipt 记实际 actor；更新不得将 owner 改为操作人。Marketing：内容 owner 和计划 owner 仍由当前合法流程决定；确认人与 owner 目前相同的现有门禁保持，若以后开放协作另行设计。Notification：收件人按业务关系或经授权目标确定，Customer 委托提醒读取客户 owner。Interview：case/session 创建人、participant、inviter、ticket holder、report/command 请求人、发布审批人按各自真实语义；不能拿创建者填受访人。Platform Operations：复盘 actor/plan owner、知识 owner/作者/审核人按当前作用写入；employeeId 与工号继续作为独立业务编号。

Auth 管理关系区分被管理用户和操作人；Agent 持久 run/async/A2A 和 Studio 关联原始用户；Memory 个人资源和 RAG 受控元数据采用 owner UCID，分享目标按已有分享授权确定；Media permit 保存已验证 owner。实现应检查所有 REST、RPC、MCP、A2A 和后台入口，版本化上下文与每次请求证明在边界本地验证，权限变更按原协议刷新，不对 Graph 每次 subAgent 增加 Auth 远程调用。不得从 LLM、工具参数或业务对象中的 userId 替换当前 actor。

### 3. 直接修正开发环境数据

遵照用户要求，不新增迁移脚本。先确认连接的是 WSL 开发 MySQL、服务库和实际 schema，做只读逐表统计和样本检查；输出字段、旧值、候选 UCID、映射、证据、状态及冲突。已确认的 `broker_id=1` 一批按 KI-55 的逐行账本核对，已正确行不重复更改；仅存在唯一业务确认和可审计映射的其他行才处理。

执行前取得可恢复备份或按行保存旧值及版本，限定主键和预期旧值，短事务执行；逐表验证行数与映射、空值/冲突与业务查询，再记录结果及独立复核证据。无法确认的行不回填；若有并发变更或影响行数不符则回滚并重查。若需要 schema 变更，先设计仓库新库初始化一致性及已部署环境影响；本次开发环境直接执行受核对 DDL，并记录精确操作、前后 schema、恢复方法及责任范围，绝不修改已有 checksummed manifest 条目或已执行脚本。若无法保证新库与运行库一致，停止依赖该 DDL 的实现并说明限制，不伪称完成。

### 4. 历史与身份生命周期

UCID 为账户主键，不因角色或组织变化变更；禁用/删除不重用 UCID，历史操作记录不改写。broker 映射发生变更不自动证明既有历史负责人变化。若日后开放交接，业务负责人和 broker 映射需要同一业务事务内同步并审计，历史操作人保持不变。替代方案以当前登录人或当前映射覆盖所有历史记录会丢失事实，故不采用。

## Risks / Trade-offs

- [用户列可空或历史编号含义不明] → 单独列出未确认记录并保持既有访问限制，不能用最近操作人兜底。
- [本次直接更改开发库导致新库 schema 不一致或不可重放] → 优先利用现有字段；必须改结构时按上述前置核对并报告可复现性缺口。
- [一处兼容入口仍接收模型 userId] → 按入口矩阵验证并覆盖伪造 actor；服务目标用户与操作人分离。
- [并行工作区有其他未提交改动] → 只修改本变更相关文件，不覆盖或提交其他会话的内容。

## Migration Plan

实施时先盘点运行库与代码，再完成领域代码修改与必要检查；备份并直接修正可证实的开发历史行，核对结果后做 API/跨服务验收。若代码需回退，保留已审计数据；如确需恢复数据，使用事先保存的逐行旧值与预期当前值核对后恢复，不批量覆盖。运行库修正不表示其他环境已升级。
