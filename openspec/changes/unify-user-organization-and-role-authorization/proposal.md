## Why

Auth 已支持账号、角色及签名授权，但档案和组织成员关系尚不完整，管理员存在独立于角色的授权路径，业务归属又依赖 brokerId 映射。需要统一用户、组织与角色授权，并安全迁移现有业务，满足公司下门店经纪人和运营职能人员的真实开发环境验收。

## What Changes

- Auth 维护通用用户档案、公司/门店/部门组织结构与用户成员关系；角色分为岗位和管理角色，第一阶段显式分配。
- **BREAKING**：受限权限只来自有效用户角色与角色权限关系；管理员由角色显式授权，逐步退役 businessAdmin 直接扩权，并保证旧接口兼容和最后管理员保护。
- 基础操作不新增权限码；身份、资源归属、组织范围和业务状态依旧分别核验。Graph 多次调用 subAgent 复用请求级签名上下文。
- **BREAKING**：业务中表示平台负责人的字段逐域迁移到可信 userId；保留可验证的历史 brokerId 兼容路径，独立业务主体不能机械替换。
- 提供受控开发环境初始化：一家公司、一个门店、一个运营部门和两位分别属于门店及运营部门的非管理员测试用户；生成的凭据只保存在本地受保护配置中。
- 明确 MySQL 受管迁移、Nacos/Compose 受影响配置、WSL Docker 部署、API 验收和回退门禁。

## Capabilities

### New Capabilities

- `user-organization-management`: 用户通用档案、Auth 组织树、成员关系及开发验收样例。
- `role-authorized-user-ownership`: 角色授权、管理员迁移、业务负责人 userId 与历史归属安全迁移。

### Modified Capabilities

- `authentication-baseline`: 认证后的受限权限统一来自用户有效角色及角色权限；签名上下文和失效语义保持可信。

## Impact

- Auth、Gateway、Agent、common、客户/房源、通知及实际消费旧业务身份的服务；组织成员管理和档案 API。
- 现有 `unify-versioned-permission-context` 及其运行上下文、管理员、BROKER/STORE 约定需要明确交接，迁移完成前保留兼容；不宣称其未完成任务已经完成。
- 数据库 manifest 与校验脚本、Nacos/Compose 中实际受影响项、WSL 开发栈初始化和端到端 API 验收；相关 LR/KI 按最终实现更新。
