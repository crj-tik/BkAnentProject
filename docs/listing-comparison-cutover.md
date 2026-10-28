# 房源比较合并配置矩阵与切换手册

本文定义 `consolidate-listing-comparison-domain` 的配置真源、迁移动作与合并后切换门禁。仓库模板可先合并；共享 Nacos 发布、Compose 操作、真实来源联机和旧服务排空必须由协调人按实际环境执行。

## 数据库同步

| 数据 | 当前存储/权限 | 合并后的处理 | 迁移与验收 |
| --- | --- | --- | --- |
| 房源业务数据 | `bk_listing.listing_info` | 表结构和房源写入职责不变；比较继续读取稳定来源快照 | 不新增房源字段或比较业务表 |
| 比较报告 | `compare-report-data` 卷中的 JSON/PDF | Listing 写 `.listing.json` 和 PDF；旧 Compare 文件保留只读盘点 | Compose 保留并迁挂原命名卷，不迁进 MySQL，不删除卷 |
| Auth 比较动作目录 | `bk_auth.auth_permission` 中 `compare.report.create`、`compare.report.read.own` | 稳定操作码仍进入签名上下文，但不是角色权限；基线退役先停用目录行并清除角色关联，Listing 迁移只把目录 domain/operation 改为 `listing` / `comparison.report.*` | `20261028_listing_comparison_authorization_domain.sql` 通过 MySQL 初始化清单顺序 460 应用；check 验证目录行保持停用、无角色关联且迁移账本完整。目录元数据变化时递增授权修订号并写 outbox；不重新激活或授予角色 |
| 旧公开分享权限 | `bk_auth.auth_permission` 中 `compare.report.share.public` | 不迁入 Listing；新 Listing 不提供匿名分享，该操作只留给受控旧服务排空/回退 | 在旧 Compare 排空签收前保留目录记录；不可通过新 Listing 启用或解释为新的分享能力 |

当前没有 `bk_compare` 业务库或比较报告表，因此不添加业务 DDL。Auth 元数据迁移不改变有效权限码，也不授予新角色；旧 Compare 回退仍能按原权限码校验。数据库脚本已纳入版本化初始化清单，实际应用由部署协调人执行；本 change 不连接共享数据库。迁移的隔离库执行和授权关联前后对账仍是部署前门禁。

## 入口与身份迁移清单

| 入口/资产 | 当前目标与调用身份 | 本次变更 | 保留或退役门槛 |
| --- | --- | --- | --- |
| Listing Business REST：`/listings/business/v1/search`、`/snapshots/batch-read`、`/snapshots/check` | `listing-master-service`；用户当前签名 Auth context，按 `listing.read` / `listing.read.all` 及 broker scope 读取 | 固化 `domain-parallel-v1`；受管 HTTP 来源的 actor/scope header 由已验签上下文生成 | `listing.authorization.enabled` 与 provider 地址/凭据完成部署验收后启用；真实 upstream 验收由现场执行 |
| 比较 A2A / 技能：`listing-agent` 的 `listing-comparison` | 既有 Listing card、`listing/listing-agent` Memory caller；Supervisor capability 精确映射 `listing.read`，比较与报告操作由 Listing 分别授权 | 新建比较请求、报告、历史与 PDF 均进入房源域；复用原 Listing Memory Hook | Listing Memory caller 不新增；仅在 Auth 签名、来源和报告验收后接收生产流量 |
| 比较/报告 REST：`/listings/comparisons/**` | `listing-master-service`；创建和 owner history/PDF 要求签名上下文分别包含 `compare.report.create` / `compare.report.read.own` 基线动作，并核验逐套来源读取与 owner | 新增稳定来源 REST API、owner-bound report 和 PDF；不提供匿名公开分享 | 与 Gateway 路由、Auth key 和来源 provider 一同验收 |
| 旧 REST：`compare-engine-service` 的 `/compare/**` | 旧 `CompareController`；新 Gateway 路由指向 Listing legacy controller | 旧 Compare REST 默认由 `COMPARE_LEGACY_REST_ENABLED=false` 关闭；Listing 兼容入口只收 Long ID 并解析为 `legacy-local`，按当前授权服务 | 旧 Compare REST 不作为新入口；`/compare/**` 用户流量由 Listing 承接，匿名分享不恢复 |
| 旧 Compare MCP：`compare-mcp-server:compareListings` | 原 Agent → Compare MCP；旧 Compare MCP 开关保留为 rollback 配置 | Supervisor 中旧 capability 与 `COMPARE_MCP_ENDPOINT` 已移除；Compare MCP 默认关闭 | 等旧服务排空并确认无客户端后删除服务端配置/allowlist |
| 旧 Compare A2A / RPC：`compare-agent`、`CompareEngineRpcService` | 原 Compare 制品、`compare-engine-service-current` Auth caller 与原请求/taskId | 新 Listing 调用不通过旧 descriptor/RPC；A2A、RPC 默认关闭；Compare 服务留在 `legacy-compare` Compose profile | 已接受/结果未知 task 仍使用原 descriptor/taskId 查询；RPC 仅在受控需要时显式开启；现场排空后再删除 reactor、Nacos 与 Auth trust |
| Memory 历史空间 | `listing/listing-agent` 与 `compare/compare-agent` 是不同 caller/domain/agent 范围 | 新 Listing 请求只走 Listing 必需 Memory Hook；不复制、改名或导入 Compare 历史内容 | Compare Memory caller、公钥和原空间保留到任务/历史读取排空签收 |
| 报告文件卷 | 既有 `compare-report-data` 卷 | Listing 写新 `.listing.json`/PDF；旧 JSON/PDF 只读盘点，文件名、shareCode、owner 元数据和字节不批量改写 | 卷定义继续保留，直至业务保留期和受控迁移核对结束 |

`SupervisorDistributedAcceptanceTest` / `OfficialA2aAgentClientTest` 为本地 task correlation/resume contract fixture；它们不代表现场 Compare 在途任务已被盘点或迁移。实际任务、owner、descriptor、Memory scope 和结果未知项必须在原 Compare 部署可查询时按下面步骤核对，禁止将其换成新的 Listing task。

## 配置矩阵

| 配置项 | `.env` / `.env.example` | Listing 应用模板 / Nacos / Compose | 默认与敏感级别 | 用途、动作与退役条件 |
| --- | --- | --- | --- | --- |
| `LISTING_SOURCE_PROVIDER` | 本地值保留；模板新增 | `listing.source.provider` / `listing-master-service.yaml` / Listing environment | `legacy-local`；非密钥 | 显式选择 `legacy-local`、`fixture` 或 `http`。HTTP 故障只返回不可用，不切换 provider。 |
| `LISTING_SOURCE_DATASET_ID` | 本地值保留；模板新增 | `listing.source.dataset-id` / Listing Nacos / Compose | `legacy-local`；非密钥 | 记录本地来源数据集身份；HTTP/fixture 的 dataset 由其来源事实提供。 |
| `LISTING_SOURCE_FIXTURE_BROKER_ID` | 本地值保留；模板新增 | `listing.source.fixture-broker-id` / Listing Nacos / Compose | `1`；非密钥 | 固定模拟数据归属的 broker；仅 `listing.read.all` 可跨越该范围。 |
| `LISTING_SOURCE_HTTP_BASE_URL` | 本地值保留；模板新增 | `listing.source.http.base-url` / Listing Nacos / Compose | 空；非密钥 | 只在显式 `http` provider 时使用；目标未配置或不可达即失败。真实地址由部署接入提供。 |
| `LISTING_SOURCE_HTTP_BEARER_TOKEN` | 保留本地秘密；模板留空 | `listing.source.http.bearer-token` / Listing Nacos 占位符 / Compose | 空；密钥 | 上游要求服务凭据时从本地密钥注入；开放上游才可为空。不得将凭据值写入 Nacos 或版本库。 |
| `LISTING_SOURCE_HTTP_SYSTEM` | 本地值保留；模板新增 | `listing.source.http.source-system` / Listing Nacos / Compose | `upstream`；非密钥 | 将上游字符串 ID 限定在稳定来源命名空间；不可截断或数值化。 |
| `LISTING_SOURCE_HTTP_CONFIRMED_DATA_MODE` | 本地值保留；模板新增 | `listing.source.http.confirmed-data-mode` / Listing Nacos / Compose | `UNKNOWN`；非密钥 | 由部署配置确认数据性质，覆盖上游自报的模式；真实来源联机前不得把默认值改成 `REAL`。 |
| `LISTING_SOURCE_HTTP_CONNECT_TIMEOUT_MS` | 本地值保留；模板新增 | `listing.source.http.connect-timeout-ms` / Listing Nacos / Compose | `1000`；非密钥 | 有界连接等待；范围限制 1–30000 毫秒。 |
| `LISTING_SOURCE_HTTP_READ_TIMEOUT_MS` | 本地值保留；模板新增 | `listing.source.http.read-timeout-ms` / Listing Nacos / Compose | `3000`；非密钥 | 有界响应等待；范围限制 1–60000 毫秒。 |
| `LISTING_AUTHORIZATION_ENABLED` | 本地值保留；模板新增 | `listing.authorization.enabled` / Listing Nacos / Compose | `false`；非密钥开关 | HTTP 鉴权入口默认关闭；只有 Auth 公钥、入口验签和当前范围验收全部通过才可开启。签名的内部来源 RPC 自身仍失败关闭。 |
| `AUTH_SERVICE_IDENTITY_PUBLIC_KEY` | 由忽略的 `.secrets/service-identities/public-keys.env` 管理 | Listing 应用及 Nacos 使用占位符；Compose `env_file` 注入 | 空；公钥，不是私钥 | Listing 验证当前 Auth 授权签名。私钥仍只在 Auth 服务。禁止把公钥材料展开写入 Nacos YAML。 |
| `LISTING_COMPARISON_STORAGE_DIR` | 本地值保留；模板新增 | `listing.comparison.report.storage-dir` / Listing Nacos / Compose | `/app/runtime/reports`；非密钥 | 新 Listing 报告的唯一写目录，Compose 映射到保留卷 `compare-report-data`。 |
| `[a2a:listing-agent]` capability operation | 不使用环境变量 | `nacos/agent-service.yaml` 精确映射为基线操作 `listing.read` | 非密钥授权映射 | Supervisor 仅在当前 Auth 上下文包含 `listing.read` 时才能委托 Listing Agent。比较创建与报告读取分别校验签名基线操作 `compare.report.create` / `compare.report.read.own`；不映射 `agent.chat.use`，也不要求角色授予这些基线动作。 |
| `LISTING_RAG_RPC_SECRET` | 保留本地秘密；`.env.example` 留空 | Listing 应用/Nacos 占位符；Compose 只传给 Listing | 空；签名密钥 | 保留既有 A2A/RAG 请求级证明。与 Auth 公钥不同，不能复用或写入 Nacos 明文。 |
| `COMPARE_A2A_ENABLED` | 新增 `false` 默认 | 旧 Compare Nacos 开关；Compose 仅 `legacy-compare` profile 传递 | `false`；非密钥开关 | 阻止旧 Compare 注册或接受新 A2A 委托。只允许在回滚/受控排空时由协调人显式开启。 |
| `COMPARE_MCP_ENABLED` | 新增 `false` 默认 | 旧 Compare Nacos 开关；Compose 仅 `legacy-compare` profile 传递 | `false`；非密钥开关 | 关闭独立 Compare MCP server。Agent 的 `COMPARE_MCP_ENDPOINT` 已废除；旧入口不能恢复为 Supervisor 新能力。 |
| `COMPARE_LEGACY_REST_ENABLED` | 新增 `false` 默认 | Compare 应用/Nacos 条件化旧控制器；Compose 仅旧 profile 透传 | `false`；非密钥开关 | 关闭旧 Compare 自身 `/compare/**` REST；用户兼容流量由 Listing Controller 提供。除通过独立 owner/Auth 与分享策略复核外，不得开启旧 REST。 |
| `COMPARE_LEGACY_RPC_ENABLED` | 新增 `false` 默认 | Compare 应用/Nacos 条件化旧 Dubbo provider；Compose 仅旧 profile 透传 | `false`；非密钥开关 | 不再接收旧 Long ID Compare RPC；只在确认原调用方需要排空/受控回退时显式开启。 |
| `COMPARE_AGENT_MODEL` | `.env.example` 新增回滚默认 | Compare Nacos 占位符；Compose `legacy-compare` profile 显式透传 | `gpt-5.6-terra`；非密钥 | 仅匹配旧 Compare 制品并按原配置回滚时使用；旧服务排空后删除。 |
| `COMPARE_AGENT_TEMPERATURE` | `.env.example` 新增回滚默认 | Compare Nacos 聊天/Agent 温度占位符；Compose `legacy-compare` profile 显式透传 | `0.2`；非密钥 | 仅匹配旧 Compare 制品回滚时使用；当前 Listing 比较使用房源域确定性规则。 |
| `COMPARE_AGENT_MAX_TOKENS` | `.env.example` 新增回滚默认 | Compare Nacos Agent 参数；Compose `legacy-compare` profile 显式透传 | `4000`；非密钥 | 仅匹配旧 Compare 制品回滚时使用；旧服务排空后删除。 |
| `COMPARE_AI_CONCLUSION_ENABLED` | `.env.example` 新增回滚默认 | Compare Nacos Agent 开关；Compose `legacy-compare` profile 显式透传 | `true`；非密钥开关 | 仅回滚到匹配的旧 Compare 制品时启用；Listing 不读取该参数。 |
| `COMPARE_REPORT_STORAGE_DIR` | `.env.example` 新增并与 Listing 路径对齐 | Compare Nacos 占位符；Compose `legacy-compare` profile 默认 `/app/runtime/reports` | `/app/runtime/reports`；非密钥 | 旧服务受控回滚时仍定位同一 `compare-report-data` 卷；切换期间旧服务 profile 默认关闭，Listing 是新报告唯一写入者。 |
| `COMPARE_MEMORY_V2_COMPRESSION_ENABLED` | `.env.example` 已有，固定 `false` | Compare 应用模板及 Compose legacy profile 显式传递 | `false`；非密钥可选 Memory 优化开关 | 不影响必需 Memory Hook；旧 Compare 禁止新增压缩写入，保留到旧任务排空。 |
| `COMPARE_MEMORY_EXPERIENCE_OUTCOME_PUBLISHER` | `.env.example` 已有，固定 `false` | Memory Nacos / Compose Memory 服务透传 | `false`；非密钥发布开关 | 旧 Compare 不再发布经验结果；原身份与只读历史空间继续保留到记忆读取排空。 |
| `MEMORY_V2_AUTHORIZATION_CONTEXT_ENABLED` | 模板默认 `false`；本地 `.env` 同步为 `false` | Memory 应用模板、Nacos 默认值和 Compose 显式传递均为 `false` | `false`；非密钥开关 | 这是 Memory v2 的可选授权上下文模式，不关闭必需 Memory 生命周期。只有 Auth 公钥与该模式部署验收通过后才可显式设为 `true`。 |
| `COMPARE_ENGINE_SERVICE_IDENTITY_PUBLIC_KEY` 与 Auth/Memory caller 登记 | 由忽略的 `public-keys.env` 管理；保留本地现有密钥材料 | `auth-service.yaml`、`memory-service.yaml` 及旧 Compare 身份配置继续使用原占位符 | 公钥；非私钥，但必须由受管身份生成/登记 | 保留旧 task 对账和受控历史 Memory 读取；排空门禁完成前不得删除或改名成 Listing。 |
| `compare-report-data` | 不存放 `.env` | Compose 同卷挂载到 Listing 与可选旧 Compare | 持久数据卷 | Listing 成为新报告唯一写入者；旧服务 `legacy-compare` 关闭写入口后保留该卷。不得清卷、批量改名或覆盖旧文件。 |
| `/compare/**` 网关路由 | 不适用 | `nacos/gateway.yaml` 改由 `listing-master-service` 提供受权兼容入口 | 非密钥 | 旧 Long ID 仅解析为 `legacy-local`；分享码不能授予正文/PDF读取权。旧服务按原任务查询时需直接使用其旧运行入口，不从新兼容路由重发任务。 |
| `COMPARE_MCP_ENDPOINT` | 已从本地 `.env` 核对为缺席；不在 `.env.example` | 已从 Agent 应用模板、Nacos Agent 和 Compose 删除 | 废除 | 新 Supervisor 不再连接 Compare MCP；Nacos 发布 allowlist 暂保留旧服务自身配置，直到排空后协调退役。 |

Nacos 发布 allowlist 暂保留 `compare-engine-service.yaml`，因为排空期间需要通过显式关闭的旧服务配置维持身份和旧实例可识别性。Listing 新配置、Gateway 路由与 Compare 关闭开关一起审阅；本 change 不发布共享 Nacos。

## 合并后迁移步骤

1. **冻结并盘点旧执行**：在原 Compare 部署和原授权身份下列出已接受任务、结果未知任务、报告 JSON/PDF、shareCode、owner/source hash 元数据与 Memory 读取需求。保留原 `taskId`、owner、domain 和 agent；结果未知的任务继续按原关联查询，不新建 Listing 替代任务。
2. **确认配置门禁**：为 Listing 准备 Auth 公钥、受管来源地址和所需上游凭据；检查数据模式确认值、broker scope 转递与超时策略。先保留 `listing.authorization.enabled=false` 进行配置验收，未配置来源时维持 `legacy-local`，不把它作为 HTTP 回退。
3. **部署 Listing 单一写入路径**：部署含本 change 的 Listing 制品，将原 `compare-report-data` 卷挂到 `/app/runtime`，报告目录设为 `/app/runtime/reports`。新报告只由 Listing 写入；旧 JSON/PDF 只读盘点，不自动导入无 owner 或公开分享记录。
4. **验证受权端到端**：以当前 Auth 签名身份逐项核对 Listing 搜索、批量快照、CURRENT/STALE/NOT_ACCESSIBLE/UNAVAILABLE、`compare.report.create`、owner-only 历史读取与 PDF。以模拟来源验收时确认所有输出保留 `SIMULATED`；真实 provider 未验收时不要把接口 fixture 结果视为联机结果。
5. **切换兼容路由并停新委托**：发布 Gateway `/compare/** -> listing-master-service` 路由；保持 `COMPARE_A2A_ENABLED=false`、`COMPARE_MCP_ENABLED=false`、`COMPARE_LEGACY_REST_ENABLED=false`、`COMPARE_LEGACY_RPC_ENABLED=false`。旧比较不再接受新委托。确认新的 Supervisor 仅调用 `a2a:listing-agent`，创建/读取权限分别由 Listing 当前 Auth 操作核验。
6. **排空历史身份**：在原 Compare 服务关闭后，继续保留其 Auth 验签 key、Memory caller/domain/agent 登记与旧 Memory 空间，直到在途任务、受控历史读取和文件盘点记录完成签收。新 Listing 比较只使用既有 `listing/listing-agent` Memory 身份。
7. **满足退役条件后清理**：只有确认没有活跃 Compare 消费者、在途 task、历史读取或旧 Memory 依赖后，才由协调人单独提出从 Compose/Nacos/reactor/发布 allowlist 移除旧 Compare 配置与身份信任。`compare-report-data` 卷继续保留，直到业务保留期和受控迁移核对结束。

## 回退步骤

- 发生 Listing 入口或报告写入故障时，先停止新比较写入并保留卷；记录当前报告文件、用户和调用关联，不自动重试结果未知的旧任务。
- 协调人仅在匹配的旧 Compare 制品、Auth 与 Memory 配置可控时执行回退；按真实旧调用方需要恢复 A2A/MCP 或 RPC。`COMPARE_LEGACY_REST_ENABLED` 保持关闭，除非先独立验收 owner 授权与旧公开分享关闭；不得只开启一个旧 flag 而混用新旧接口/缓存模型。
- 旧版本恢复后按原 taskId 查询未决任务。不得在 Listing 下重放；用户明确发起新的比较时，才以新的稳定来源引用创建新报告。
- 回退验收后再决定是否恢复 Listing。报告卷中已存在的 `.listing.json` 与旧 Compare `.json` 分开保留，不相互覆盖。

## 本次未执行的现场操作

本次只维护仓库配置模板、更新本地 `.env` 并进行静态/隔离验证；没有运行 Docker、没有发布共享 Nacos、没有连接真实上游，也没有切换线上路由或停服。上述步骤由合并后的部署协调门禁执行。
