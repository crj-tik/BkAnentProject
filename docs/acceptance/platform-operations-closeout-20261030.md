# 平台经营域合并收尾验收（2026-10-30）

## 结论与边界

经营分析、门店经营复盘和知识运营已归入 `platform-operations-service`，对外使用一个平台经营 Agent，内部仍按三类能力隔离授权、工具和 Memory 身份。合同与结算业务域及旧 Compare、business、business-review、Knowledge 独立服务已从活动 Maven 模块、源码、受管测试、运行配置和调用登记中退役。历史 OpenSpec、迁移和审计文档保留为设计沿革，不是当前运行依赖。

本次验收覆盖仓库、获准的开发数据库、开发 Nacos 和本地 Compose。没有生成 KPI、复盘、知识等虚构业务记录；开发库当前无 KPI 事实数据，因此接口空列表不代表业务指标为零。Auth 门店关系、签名 STORE 范围匹配/撤销、缺失身份和跨门店拒绝均通过后端回归；Gateway→Auth→Agent 实际 API 验收也已通过。

## 退役清单

| 旧资产 | 当前处置 | 验收依据 |
| --- | --- | --- |
| `compare-engine-service` | Maven 模块、源码、维护测试、RPC 契约和活动服务配置已删除 | 根 POM、Compose、Nacos 白名单及 Provider 配置回归 |
| `business-service`、`business-review-service`、`knowledge-service` | 独立服务源码、Maven 模块、维护测试、模板及入口已删除 | 根 POM、Compose、Gateway、活动 Nacos/Memory/Auth 配置和服务退役测试 |
| `contract-service`、`settlement-service` | 独立模块、源码、活动调用入口及默认配置已删除 | 迁移 600/610/620 和活动配置核对 |
| 旧数据库、授权与 Nacos Data ID | 已按受管清单退役；历史检查仍保留用于审计 | 开发 MySQL 现态检查、Nacos 删除后回读 |

## 数据库与授权

- `scripts/deploy/apply-migrations.sh --dry-run` 和实际受管迁移均通过；迁移账本已幂等重跑。
- 独立临时 MySQL 从空卷执行完整 fresh manifest、以 upgrade 模式再次幂等重跑；平台经营库、视图 owner、当前权限及全部退役检查均返回 `1`。临时容器、网络和数据卷已清理。
- 初始化 manifest 包含 63 项受管记录及规范 SHA-256 校验。
- Auth STORE 身份管理验证同一用户最多一个门店、一个门店可分配多人、授权版本及审计随变更推进、撤销立即移除签名身份；平台经营只接受签名 STORE 与请求门店一致的上下文。
- 平台经营 21 张表、脱敏访谈来源视图、独立锁定的 view owner、应用账号和来源只读账号通过现态检查；旧合同/结算 schema、平台旧 schema 和旧授权检查均通过。
- 迁移 610 后活动授权目录为 111 项；三个平台经营能力操作保留，旧统一入口、知识、复盘、合同和结算权限停用且无旧角色关联。没有自动向普通角色授予新权限。
- Auth 本地 H2 初始化目录同步三个平台经营 Agent 分支操作、六个复盘操作和十二个知识案例操作，移除已退役结算权限；静态回归防止模板种子再次漂移，不自动给角色追加新授权。
- migration 570/590 的 manifest check 是迁移时状态断言，后续迁移会改变其中的历史条件；当前状态以 `platform-operations-current.sql`、`platform-operations-view-owner.sql` 和 `retired-platform-legacy-authorization.sql` 检查为准。

## 配置、Nacos 与 Docker Compose

活动发布清单与受管模板严格一致，12 个活动配置已写入 `BK-AGENT/DEFAULT_GROUP` 并逐项回读：

`auth-service.yaml`、`gateway.yaml`、`agent-service.yaml`、`listing-master-service.yaml`、`customer-service.yaml`、`marketing-content-service.yaml`、`media-worker-service.yaml`、`notification-service.yaml`、`interview-service.yaml`、`memory-service.yaml`、`rag-service.yaml`、`platform-operations-service.yaml`。

`business-review-service.yaml` 和 `knowledge-service.yaml` 已删除并验证不存在。full profile 纳入平台经营服务，`platform-preview` 可单独运行；启动/停止脚本、全量必需模板、Compose、环境模板、服务密钥身份、发布清单和 Nacos README 已同步。`docker compose config -q` 与配置矩阵检查通过。Nacos 模板中多个冒号的 Redis key prefix 已加引号，并完成重新发布回读。

Nacos 鉴权按用途分开：一次性 `config-init` 用 Admin 身份初始化账号并负责受控配置发布/退役；`bkagent` 只持有 `BK-AGENT:DEFAULT_GROUP:ai/*` 的读写权限，并只注入 A2A 提供方及 Agent 注册发现客户端；RAG 使用 `NACOS_CLIENT_USERNAME/PASSWORD`。本地 Nacos Derby 数据挂载到 `nacos-data`，避免 Compose 重建丢失状态。启用客户端鉴权和 Admin API 鉴权是不同开关，Admin API 仍须独立登录与授权。

本轮给开发 Nacos 增加命名卷时，旧容器层数据未迁移；按无历史资产约定，由 `config-init` 从受管初始化重新创建账号、权限并重发 12 个模板，平台经营与房源服务随后重新注册 AgentCard。Admin API 回读确认 `platform-operations-agent`、`listing-agent` 可由受限服务账号读取；平台经营卡片的分析、复盘、知识运营三项技能一致，readiness 为 UP。随后强制重建 Nacos 容器，验证命名卷保留 12 个模板、两个 AgentCard、服务账号与读写权限，退役的两个 Data ID 仍不存在。

## Memory 与物理 RAG 清理

平台经营继续使用三个相互隔离的 Memory 组合：`analytics/platform-operations-analytics`、`review/platform-operations-review`、`knowledge/platform-operations-knowledge`。复盘物理索引/集合名已与当前 review Agent 身份一致，RAG 运行探针及配置测试已同步。

在开发环境中，对 Compare、合同、结算和旧 trade 四组的五类空间逐个核对精确名称、ES 文档数为 0、无索引别名且 Milvus rowCount 为 0 后，删除共 20 个 ES 索引和 20 个 Milvus 集合；删除后读回均不存在。未删除任何有业务数据或别名引用的资产。

## 自动化验证

- Maven 全仓：Reactor 19 项（根工程和 18 个服务/共享模块）；Surefire 共 1,850 项，失败 0、错误 0、跳过 14。
- 收尾定向 Maven：Auth 执行绑定、门店身份管理、本地 H2 权限种子、STORE 范围、复盘链路、来源撤回及知识生命周期 73 项通过；Postman API 验收 15 项断言通过，包含 Agent 受保护读取 200、平台经营越权 403、伪造/替换身份拒绝及登出后令牌失效。
- `test_nacos_a2a_auth.py`（2 项）、`test_publish_nacos.py`（5 项）、`test_platform_operations_config.py`、`test_init_manifest.py`、`test_retired_migration_runner.py`（4 项）、`test_interview_credential_scan.py`（8 项）通过；RAG 累计截止与 Nacos 客户端绑定定向 18 项通过。
- OpenSpec `consolidate-platform-operations --strict`、full/platform-preview/rag Compose 展开与 `git diff --check` 通过；任务 2.5、4.2、4.3 已完成。
- `platform-operations-v1.openapi.yaml` 与已发布知识契约通过 Redocly lint。
- API 清单与页面功能说明见 [平台经营域前端功能清单](../platform-operations-frontend-function-list.md)。

## 开发机上的忽略文件残留

自动安全审查拒绝了删除退役身份密钥目录和旧模块生成的 Maven `target` 目录。它们不是受 Git 跟踪的源码或维护测试；旧身份已从受管公钥注册、Compose 和 Nacos 配置移除，旧模块目录仅剩被忽略的 `target` 构建产物。删除前应在本机按实际目录审查并清理，不可把这些本地忽略文件当作已提交资产。
