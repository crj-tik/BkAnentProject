# Knowledge 运营域运行说明（已退役）

> 本文原先描述独立 `knowledge-service` 与 `bk_knowledge`，已被 `consolidate-platform-operations` 合并变更取代。不要按本文创建旧服务、发布 `knowledge-service.yaml`、执行旧账号流程或启动旧路由。

## 当前入口

- 知识运营由 `platform-operations-service` 承载，统一路由为 `/platform-operations/knowledge/v1/**`，Nacos Data ID 为 `platform-operations-service.yaml`。
- 知识案例、revision、审核、发布、撤回和受限访谈来源保存在 `bk_platform_operations`；访谈仍归 `interview-service` / `bk_interview`。
- 当前 Agent 是 `platform-operations-agent`。知识能力在服务内独立校验 `knowledge` 分支操作，并使用 Memory domain `knowledge`；它没有独立 A2A 服务身份。
- 门店作用域依赖 Auth 签发的可信 STORE 身份。用户请求中的 `storeId` 不构成授权；身份或权限链缺失时，STORE 操作失败关闭。

## 当前接口与页面需求

- API 契约：[平台经营域 OpenAPI](contracts/platform-operations-v1.openapi.yaml) 与[已发布知识读接口细则](contracts/platform-operations-published-knowledge-v1.openapi.yaml)。
- 前端页面和现有后端能力：[平台经营域前端功能清单](platform-operations-frontend-function-list.md)。
- 实施状态和设计决策：[consolidate-platform-operations OpenSpec change](../openspec/changes/consolidate-platform-operations/)。

本文作为历史交接索引保留；独立服务部署步骤、旧数据库账号矩阵和旧 Nacos 清单不再是现行运维依据。
