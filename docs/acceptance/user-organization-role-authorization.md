# 用户、组织与角色授权开发验收手册

适用于当前没有前端工程的开发环境。浏览器交互专项暂缓；Auth、Gateway、Agent、组织和业务归属验收均使用实际 HTTP API。执行记录需注明代码提交、镜像版本、迁移清单版本和日期，不能把源码或 Mock 测试结果写成容器验收。

## 配置位置

版本化授权开关必须同时在根目录被忽略的 `.env`、`docker-compose.yml` 对应服务环境和 Nacos Data ID 中一致：

| 能力 | `.env` | Compose 服务 | Nacos 配置 |
| --- | --- | --- | --- |
| Auth 版本化鉴权 | `AUTH_AUTHORIZATION_ENABLED` | `auth-service` | `nacos/auth-service.yaml` 的 `auth.authorization.enabled` |
| Gateway 请求签名 | `GATEWAY_AUTHORIZATION_ENABLED` | `gateway` | `nacos/gateway.yaml` 的 `gateway.authorization.enabled` |
| Agent 请求级授权上下文 | `AGENT_AUTHORIZATION_ENABLED` | `agent-service` | `nacos/agent-service.yaml` 的 `agent.authorization.enabled` |
| 显式登记的 A2A 基础能力 | `AGENT_AUTHORIZATION_BASELINE_CAPABILITIES` | `agent-service` | `nacos/agent-service.yaml` 的 `agent.authorization.baseline-capabilities` |
| Auth Redis writer | `AUTH_AUTHORIZATION_REDIS_ENABLED`、`AUTHZ_REDIS_WRITER_CONFIGURED`、`AUTHZ_REDIS_WRITER_PASSWORD` | `auth-service` | `nacos/auth-service.yaml` 的 `auth.authorization.redis` |
| Agent Redis reader | `AUTHZ_REDIS_READER_CONFIGURED`、`AUTHZ_REDIS_READER_PASSWORD` | `agent-service` | `nacos/agent-service.yaml` 的 `agent.authorization.redis` |

ACL 凭据只保存在本机 `.env`/密钥文件；不得打印、提交或放进命令行参数。Auth 与 Agent 使用不同的 Redis ACL 用户和密码。Auth、Gateway、Agent 公钥、受信调用方和精确 operation 登记分别核对 `nacos/auth-service.yaml`、`nacos/gateway.yaml`、`nacos/agent-service.yaml` 及 `.secrets/service-identities/public-keys.env`。

## WSL 开发栈准备

从 Windows PowerShell 在项目根目录调用 WSL Ubuntu。先预览受管迁移和全部校验和：

```powershell
wsl.exe -d Ubuntu -- bash -lc "cd /mnt/d/project/BkAnentProject && ./scripts/deploy/apply-migrations.sh --dry-run"
```

检查输出只包含预期的 manifest upgrade 项后，应用 forward-only 迁移。不得删除 Docker 数据卷：

```powershell
wsl.exe -d Ubuntu -- bash -lc "cd /mnt/d/project/BkAnentProject && ./scripts/deploy/apply-migrations.sh --yes"
```

本变更的迁移为 manifest 顺序 720–800，涵盖 Auth 组织、角色分类、预备管理员角色、成员有效期/outbox、角色授权切换策略、Customer/Listing/Marketing user owner 和通知幂等键。历史 owner 账本只有在逐条证据复核后才允许应用；空值或冲突不自动补负责人。

启动最小验收依赖并检查容器状态：

```powershell
wsl.exe -d Ubuntu -- bash -lc "cd /mnt/d/project/BkAnentProject && docker compose --profile minimal up -d auth-service gateway agent-service"
wsl.exe -d Ubuntu -- bash -lc "cd /mnt/d/project/BkAnentProject && docker compose ps"
```

Auth、Gateway、Agent、MySQL、Redis、Nacos 和 RocketMQ 的 readiness 都需为健康状态；任何容器异常应先记录具体服务及日志错误，再决定修复，不删除卷或执行全局 prune。

## API 验收

1. 需要时先执行 `python3 scripts/acceptance/bootstrap-auth-organizations.py --apply-development`。脚本只访问本机 Gateway，创建一家公司、门店、运营部门、门店经纪人与运营职能用户；两种样例角色都不授予受限权限。只读 Listing A2A 与平台经营 A2A 的入口分别由精确的 `a2a:listing-agent`、`a2a:platform-operations-agent` 配置为基础能力；运行时仍以 `listing.read`、`agent.chat.use` 绑定签名动作，平台经营的具体受限能力另行检查权限码。下游继续核验 actor、run、资源归属和数据范围。已有非预期权限或数据冲突会停止，不覆盖账户。凭据仅写入被忽略的 `.env`，不会输出。
2. 从 Windows PowerShell 项目根目录执行下方脚本。它使用 `.env` 中的管理员、经纪人和运营验收账号，通过真实 Gateway API 验证两用户登录、`/auth/me`、角色和组织关系、refresh 轮换/重放拒绝、本人资源读取、跨用户身份替换拒绝、受限操作拒绝及匿名伪造头拒绝；默认还会运行只读 Graph 场景。

```powershell
wsl.exe -d Ubuntu -- bash -lc "cd /mnt/d/project/BkAnentProject && python3 scripts/acceptance/user-organization-role-api-smoke.py"
```

Graph 场景会调用已配置的模型服务，只要求以 `a2a:listing-agent` 分别查询浦东和徐汇房源，不执行创建、修改、发布或发送；它在请求 `context` 和普通文本中放入伪造的 userId/brokerId，检查实际调用仍绑定登录用户，并要求持久化 run 状态包含至少两次 listing subAgent 调用。脚本不输出密码、token 或完整业务响应。调试纯 Auth/组织/基础 Agent API 时可附加 `--auth-only`，该模式不算完成 Graph 验收。

首次 Graph 请求无需预建 Memory session：已认证的当前 owner 查询不存在的 session 会得到空结果，Agent 随后建立本人会话；已有 session 继续严格核对持久 owner。该流程一并验证首次运行绑定和多次 subAgent 调用。

3. 开发环境可使用 `wsl.exe -d Ubuntu -- bash -lc "cd /mnt/d/project/BkAnentProject && python3 scripts/acceptance/auth-role-cutover-api-smoke.py"` 核验管理员权限等价、role-only 切换、最后管理员保护、30 秒 freshness 和成员迁移版本/outbox。该脚本会把当前开发库管理员切到 `PLATFORM_ADMIN` 并启用数据库中的 role-only 策略；role-only 是受控切换，不要在生产或未完成当前环境权限对账时运行。只有实际完成的 API/数据库/容器证据可以计入验收；源码检查或 Mock 测试不能替代运行验收。

历史 owner 回填通过 `scripts/migrations/apply-user-owner-ledger.sql` 手动执行；仅允许已由管理员复核且状态为 `VERIFIED` 的逐记录账本项进入回填。执行前暂停对应业务写入并备份，执行后运行 `scripts/migrations/report-user-owner-state.sql`。回退使用 `scripts/migrations/rollback-user-owner-ledger.sql`：仅在维护窗口清除仍与账本候选 userId 和原业务 ID 完全一致的回填值；修改或删除过的记录会保留并由脚本末尾查询列出，需人工对账。回退不会删除业务记录、旧 brokerId 或迁移账本。

没有配置可验证的 brokerId/员工映射时，不创建或猜测它；业务私有资源写入应拒绝。浏览器 fetch/SSE、token 存储和多标签页行为待前端工程建立后单独验收，详见 [Auth 与组织前端需求](../auth-frontend-requirements.md)。

## 2026-11-12 开发环境验收记录

本轮在 WSL Ubuntu 的开发 Compose 栈执行；没有删除或重建任何持久数据卷。应用镜像为 `bk-anent-services:0af714bca7ee1d41eee7b2151105e37ba700a24a`。

- 数据库：manifest 720–800 在开发库重复执行均显示 `Verified previously applied`。另用只挂载 tmpfs 的隔离 MySQL 从空库执行完整 fresh manifest，fresh 重放和 upgrade 重放均通过；Auth 用户、权限目录、角色、组织及授权迁移计数在重放前后保持 `2:185:4:0:19`。
- 归属账本：隔离空库验证唯一映射 apply/repeat、回退/repeat、回退时保留并发变更行，以及同一 broker 对应多个候选用户时 fail-closed。夹具只存在于临时 MySQL tmpfs。
- Nacos：`config-init` 发布并读回 12 个服务 Data ID；Auth、Gateway、Agent 和各消费服务配置均通过校验。
- 管理员切换：开发库两名管理员的旧权限与预备 `PLATFORM_ADMIN` 角色逐项对账，125 项受限权限无缺口；两人切换到显式角色后启用 role-only。移除最后一名有效管理员的请求返回 409，管理员能力保留。
- 刷新与组织：撤销验收运营角色的临时 `business.kpi.read.all` 授权后，Gateway 受限 API 在 **0.28 秒**内拒绝；经纪人主归属从门店调往运营部门再恢复，两个变更都递增授权版本并生成 outbox 事件，最终门店归属已恢复。
- 完整 API：两个非管理员样例账号通过登录、`/auth/me`、刷新轮换/重放拒绝、本人资源范围、跨用户身份替换拒绝、受限操作拒绝及匿名伪造头拒绝。首次 Graph run 成功，两次实际 `a2a:listing-agent` 调用均绑定入口登录 actor；prompt/context 中的伪造 `userId`/`brokerId` 没有替换身份。role-only 切换后再次验收仍通过。
- Maven：全量 `package` 为 19 个模块成功，360 个测试套件 / 4104 项，失败和错误均为 0，28 项外部依赖测试跳过。之后 `memory-service -am test` 定向回归通过；容器重建后的 Graph API 通过。

### 尚未切换的历史私有数据

开发库只读报告显示：`customer_profile` 3 条、`customer_listing_inquiry` 1 条、`listing_info` 3 条的 `owner_user_id` 均为空；Customer、Listing、Marketing 迁移账本均为 0 条。没有可信历史 `brokerId→userId` 证据，因此本轮没有回填，也没有把旧 broker 字段当作所有权证明。依赖历史私有 owner 的读写与 Customer 提醒仍保持拒绝/跳过；这项数据对账与切换是本 change 唯一保留的未完成任务 4.5。浏览器专项仍按前端需求单冻结。
