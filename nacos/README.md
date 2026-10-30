# Nacos 配置说明

`nacos/` 保存服务运行模板。服务 Data ID 使用 `${spring.application.name}.yaml`，分组固定为 `DEFAULT_GROUP`；`mysql-common.yaml` 是公共模板，不由当前应用发布器自动导入。

## 当前配置

| 配置文件 | 作用 |
| --- | --- |
| `auth-service.yaml` | 登录、令牌、版本化授权与 Auth RPC |
| `agent-service.yaml` | Supervisor、A2A 调用、工具权限、记忆与事件流 |
| `gateway.yaml` | 活动服务路由、认证边界与请求限制 |
| `listing-master-service.yaml` | 房源、比较报告和房源 Agent |
| `customer-service.yaml` | 客户档案、跟进与收藏 |
| `marketing-content-service.yaml` | 营销内容、素材、发布流程与搜索 |
| `media-worker-service.yaml` | 异步媒体任务和图片生成 |
| `notification-service.yaml` | 站内信、邮件与事件消费 |
| `interview-service.yaml` | 访谈采集、归档、脱敏和来源版本 |
| `platform-operations-service.yaml` | 经营分析、门店复盘和知识运营；一个 A2A Agent 按能力分支并隔离授权、工具和 Memory |
| `memory-service.yaml` | Memory 服务身份和按 analytics/review/knowledge 分隔的 Agent 访问范围 |
| `rag-service.yaml` | 检索、embedding、重排及受管 Memory v2 空间 |
| `mysql-common.yaml` | MySQL 公共连接参数参考模板 |

合同、结算、旧经营服务与 Compare 独立服务不属于当前运行面。Nacos 中的旧 Data ID 必须从目标开发命名空间退役；历史 OpenSpec、迁移和验收记录仅用于追溯，不应重新发布。

## 环境变量和秘密

`.env` 只保存连接地址、凭据和启动引导值。业务行为、模型选择、权限目录、memory space 与 readiness 放在 Nacos 模板。服务私钥只从 Git 忽略的 `.secrets/service-identities/<service>/` 挂载；`public-keys.env` 只含公钥。模板中的 `${...}` 应由 Compose 环境或受控秘密源提供，YAML 中不放真实密码、令牌或私钥。

`NACOS_ADMIN_USERNAME/PASSWORD` 只由一次性 `config-init` 引导管理员、初始化 A2A 账号，并在启用客户端鉴权时发布或退役配置；不传入业务应用。`NACOS_USERNAME/PASSWORD` 只传给 A2A 提供方和 Agent 注册发现客户端，账号权限限定在 `BK-AGENT:DEFAULT_GROUP:ai/*` 的读写。Nacos 配置/普通服务发现客户端另用 `NACOS_CLIENT_USERNAME/PASSWORD`。`NACOS_AUTH_ENABLE` 与 Admin API/控制台鉴权相互独立；设置密码不代表已经授予权限。

容器内服务用 Compose 服务名连接基础设施；宿主机 `.env` 的地址供预检和本地非容器运行使用。设置 Nacos 用户密码不等于已获目标 namespace 写入权限；发布失败必须显式处理，不能绕过访问控制。

## 发布与退役

- `NACOS_CONFIG_DATA_IDS` 默认留空。只在检查具体变更后显式列出要发布的 Data ID；发布器校验固定白名单、`BK-AGENT` namespace、`DEFAULT_GROUP`，并逐项回读模板摘要。
- 当前发布器白名单与除 `mysql-common.yaml` 外的所有活动服务模板保持一致。选择性部署只列出本次要更新的 data ID；本地 `full` 开发环境可将以下全部活动 ID 放入 `.env` 完成一轮模板同步：`auth-service.yaml gateway.yaml agent-service.yaml listing-master-service.yaml customer-service.yaml marketing-content-service.yaml media-worker-service.yaml notification-service.yaml interview-service.yaml memory-service.yaml rag-service.yaml platform-operations-service.yaml`。`.env.example` 仍留空，避免新环境未经选择就覆盖 Nacos 配置；不要把本地全量列表用于生产配置导入。
- 退役脚本 `scripts/deploy/retire-nacos-configs.py` 只允许检查/删除代码内列明的旧服务 Data ID，目标固定为本地开发 Nacos 的 `BK-AGENT/DEFAULT_GROUP`。默认 dry-run，只列出实际存在项；删除时需显式设置 `NACOS_RETIRE_DRY_RUN=false`，并回读确认已不存在。
- 生产导入前须准备并审核环境专属配置；本仓库开发模板不代表生产环境的运行态配置。

## 平台经营 Agent 与 Memory

外部只发布 `platform-operations-agent` 一张 Agent Card。A2A 请求必须选择 `analytics`、`review` 或 `knowledge`，再由平台经营路由检查该分支的精确签名操作并绑定独立工具和 Memory 身份。Memory caller 使用同一个 `platform-operations-service` 公钥，允许的分支身份分别是 `platform-operations-analytics`、`platform-operations-review` 和 `platform-operations-knowledge`；旧 `trade`/`business-review` space 已迁至新的能力名称。

## 配置职责边界

- `auth-service.yaml`：访问令牌 TTL 和版本化授权目录。
- `agent-service.yaml`：Supervisor 并发、流式 provider、Agent caller 和 RAG 消费配置。
- `platform-operations-service.yaml`：`platform-operations.analytics.*`、`platform-operations.review.*`、知识来源、数据库账号、Auth 验签与 readiness。
- `rag-service.yaml`：独立 RAG 库、索引/集合、embedding 和 rerank 配置；空凭据、空角色或关闭 worker 不视为就绪。
- `listing-master-service.yaml`：Listing 来源、比较报告与授权设置；Listing 比较不依赖独立 Compare 服务。
- `marketing-content-service.yaml`：营销索引与发布 provider；没有真实 provider 时写操作失败关闭。

所有模板、Compose、`.env.example` 和服务绑定需通过 `scripts/deploy/test_platform_operations_config.py` 与部署预检共同核对。模板核对不能代替实际 Nacos 回读或服务 readiness 验收。
