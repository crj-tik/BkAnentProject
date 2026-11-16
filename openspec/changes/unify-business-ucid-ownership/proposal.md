## Why

各服务已存在多种用户和经纪人字段，但字段之间是否表示同一个人、何时取当前登录用户、历史数据怎样确定归属尚未形成统一规则，容易把经纪人编号、模型内容或其他人的身份误写为用户。

## What Changes

- 确立 Auth `user_account.id` 为本系统 UCID；保留现有字段名和类型，统一用户字段语义及可信来源。
- 梳理并改造 Auth、客户、房源、营销、通知、运营、Interview、Agent、Memory 和 RAG 的用户归属、执行者与目标对象写入及校验；业务上同一人使用同一 UCID，不同人按各自真实身份记录。
- 客户、咨询及房源中负责人 UCID 与 broker 业务编号指向同一经纪人，并通过 Auth 明确映射校验；不以数字相等或角色推断。
- 基于当前开发库实际数据逐条核对并直接修正有证据的旧数据，不新建迁移脚本；无法确定的记录列入待确认清单，不猜测和覆盖。
- 保持当前基础功能与受限权限点的既有边界、签名上下文与 Graph 调度方式；新建代录、协作、跨人审批和交接能力不作为已获确认的本期需求。

## Capabilities

### New Capabilities

- `business-ucid-identity`: 跨服务 UCID 的身份语义、真实参与人一致性及开发数据修正。

### Modified Capabilities

无；与正在实施的 `unify-user-organization-and-role-authorization`、`unify-versioned-permission-context` 协调，不重复宣称其未完成任务已完成。

## Impact

影响 Auth 及各业务服务的身份传递、写入和读取、跨服务签名与测试；开发 MySQL 中可证实的历史业务记录会直接修正。生产数据库变更及权限扩张不在本次开发环境数据修正范围内。
