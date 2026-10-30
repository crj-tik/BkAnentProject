# 房地产中台 Agent 系统

基于 Spring Boot、Spring Cloud Alibaba、Spring AI、Dubbo、MCP 的多模块微服务工程。

## 模块说明

- `common`：公共 DTO、基础模型、Dubbo 接口
- `gateway`：统一入口网关
- `auth-service`：认证与权限
- `agent-service`：Supervisor 模型工具循环，统一调用 A2A、MCP 与本地能力
- `listing-master-service`：房源主数据
- `customer-service`：客源与业主管理
- `notification-service`：统一消息通知
- `marketing-content-service`：营销内容、品牌素材、发布计划、平台回执与效果来源统一管理
- `platform-operations-service`：经营分析、门店复盘、知识运营（单一 Agent 入口，三类隔离能力）
- 房源对比与报告生成由 `listing-master-service` 承担
- `media-worker-service`：异步媒体任务

## 当前架构

- Agent 主链路使用 `DeepSeek` 官方模型接入
- 新请求由 LLM 根据真实 A2A/MCP/本地工具的描述和参数选择调用；模型节点不直接执行工具
- 本次指定 skill 时首轮前固定正文、版本与能力范围，模型继续理解需求并遵循 Markdown 指引；不提供 DAG 或业务顺序强校验
- AUTO 与 EXPLICIT_SKILL 使用同一通用 Graph，统一处理范围、身份、权限、预算、实际调用审批和恢复；历史 checkpoint 仅用于只读查询，不再恢复旧 Graph 执行
- 业务服务之间的 RPC 仍通过 `Dubbo` 进行正常调用
- 基础配置统一通过 `Nacos` 管理

部署顺序、迁移字段及暂停新接收见 [Supervisor 部署说明](docs/supervisor-orchestration-deployment.md)，本机 Docker 与模型验收见 [验收记录](docs/supervisor-orchestration-acceptance.md)。Nacos Server/SDK 基线为 3.1.0；九 Subagent 共享显式技能契约。正文流程遵循属于模型质量，不能把工具范围约束当作顺序保证。

## 配置约定

- 服务本地 `application.yml` 只保留最小启动配置
- 数据库、Redis、MQ、对象存储、Elasticsearch、Milvus、模型参数统一放在 `nacos/*.yaml`
- `.env` 仅维护端口、连接地址、密钥和启动引导；集成模式、Token TTL、排行榜/流式 provider、Milvus/ES 搜索开关在 Nacos 中直接使用字面值，不与 `.env` 或 Compose 重复。宿主机工具使用的基础设施地址继续保留；连接与密钥占位符仍从环境变量或密钥管理系统读取，禁止把真实密钥写进 Nacos
- 独立 RAG 的重排配置使用 `RAG_RERANK_ENDPOINT`/`RAG_RERANK_API_KEY`/`RAG_RERANK_PROTOCOL`/`RAG_RERANK_MODEL`/`RAG_RERANK_EXPECTED_REVISION`/`RAG_RERANK_EXPECTED_ARTIFACT_DIGEST`，后端 URL 与受管 allowlist 默认空，评分 profile 未验证时能力关闭。现采用外部 Reranker-BGE-V2-M3 项目的治理接口（`http://bkanent-reranker-dev:18111/api/v1/rerank`，`RAG_RERANK_PROTOCOL=governed`），仅经专用 external Docker 网络 `bkanent-rerank-dev`（10.253.240.0/24，仅 rag-service 加入）访问；模型 revision/scoring_version/artifact_digest 按每次响应钉扎校验，同 alias 模型漂移失败关闭（SCORING_PROFILE_MISMATCH），专用密钥由该项目仓库外 `rag.env` 提供、不进入本仓。历史本机 `http://host.docker.internal:18119/rerank` 目标与 native/DashScope 协议保留兼容但不再是默认。DashScope 等其他候选也须实际授权、URL/DNS/协议与模型能力验收后才使用。
- 文本模型与向量模型统一走 KE 网关（Bella OpenAI 兼容，`https://open-chatgpt.ke.com/v1`）：九个子 Agent 与 Supervisor 的 chat 经 DeepSeek starter 使用 `gpt-5.6-terra`（2026-10-10 质量优先评估：各家族最新梯队 15 个候选，深度合同风险识别 6/6、陷阱交易拦截、多轮工具链综合输出 5/5 且决策枚举合规，单轮约 12s；完整记录见 `docs/acceptance/ke-gateway-model-evaluation-20261010.json`。早期按延迟选的 `qwen3.8-flash` 在多轮工具链后输出退化为散文，已弃用；`Qwen3.8-Max` 为同网关质量回退，经各服务 `*_AGENT_MODEL` 环境变量切换；`gemini-3.8-flash` 因网关 tool 回传 400 通道缺陷不可用于 ReactAgent 循环）；历史 agent-service embedding 使用 `baai-m3-1b-base-v1-embedding-20240619`（记录维度 1024）；执行迁至独立 RAG 后使用专属 `RAG_EMBEDDING_*`，旧记录不能证明新配置/实际集合维度一致。chat 密钥优先 `DEEPSEEK_API_KEY`，回退 `KE_API_KEY`，两者均不写入 Nacos。rerank 评估过网关 `qwen3-rerank-8b`（`/v1/reranks`），因带硬约束的房源场景排序不稳（预算/通勤用例排序错误）保留本地 bge-reranker，不接入
- 配置类统一通过 `@ConfigurationProperties` 收口
- 不在业务代码中硬编码连接地址、密钥和环境差异参数

## 关键配置

- Agent 模型配置：
  [AgentDeepSeekProperties.java](/D:/project/BkAnentProject/BkAnentProject/agent-service/src/main/java/com/bkanent/agent/config/AgentDeepSeekProperties.java:1)
- Agent MCP 配置：
  [AgentMcpProperties.java](/D:/project/BkAnentProject/BkAnentProject/agent-service/src/main/java/com/bkanent/agent/config/AgentMcpProperties.java:1)
- Agent Nacos 配置：
  [agent-service.yaml](/D:/project/BkAnentProject/BkAnentProject/nacos/agent-service.yaml:1)
- 数据库初始化与升级统一使用受检清单：
  [init-manifest.tsv](/D:/project/BkAnentProject/docker/mysql/init-manifest.tsv)

## 构建

```bash
mvn -gs .mvn-settings.xml -s .mvn-settings.xml compile
```

```bash
mvn -pl agent-service -am compile
```

## 本地启动

仓库包含一个不依赖 Nacos 和 MySQL 的 `auth-service` 本地冒烟配置，用于验证 Java、Maven、Spring Boot 和基础认证链路。该配置仅用于开发测试，不代表完整分布式系统已经脱离基础设施依赖。

```powershell
mvn -pl auth-service -am -DskipTests install
mvn -pl auth-service "-Dspring-boot.run.profiles=local" spring-boot:run
```

启动后访问 `http://127.0.0.1:9101/auth/health`。本地演示管理员账号为 `admin01`，初始密码为 `demo-password`；业务演示账号为 `broker01`，密码同为 `demo-password`。这些账号只用于开发测试，生产环境必须替换并立即修改初始密码。

用户管理由 `auth-service` 提供，账号写入 `user_account`，可分配角色写入 `auth_role`。管理员可通过 `GET /auth/users`、`GET /auth/roles`、`POST /auth/users`、`PUT /auth/users/{userId}`、`PUT /auth/users/{userId}/password` 和 `DELETE /auth/users/{userId}` 管理账号；`GET /auth/me` 返回当前登录用户信息。管理接口要求有效的管理员访问令牌，删除采用逻辑删除，服务端会阻止删除或停用最后一个启用的管理员。

给已有 MySQL 开发库升级时，先运行 `./scripts/deploy/apply-migrations.sh --dry-run` 审核受管升级清单，再运行 `./scripts/deploy/apply-migrations.sh`。新建和已有库统一由 Compose 挂载的受管清单初始化；不要单独执行未纳入清单的 SQL。

## 分布式环境启动

完整系统启动前需要准备 Nacos、MySQL、Redis、RocketMQ、Milvus、MinIO 和 Elasticsearch。`full` 档位包含平台经营服务；开发配置按需从 `nacos/` 发布到指定 namespace，平台经营配置使用 `platform-operations-service.yaml`。合同和结算业务域及独立知识运营、门店复盘服务已退役；知识运营、门店复盘与经营分析统一由平台经营域承载。

Linux 主机推荐直接使用 `scripts/deploy/` 下的初始化脚本完成上述准备与启动：宿主机预检（docker/内核参数/端口）、`.env` 随机密钥引导、已有数据卷迁移、启动/状态/停止均为 bash 脚本，详见 [Linux 部署环境初始化](docs/linux-deployment-init.md)。

```bash
./scripts/deploy/init-environment.sh --profile full
./scripts/deploy/start.sh --profile full
./scripts/deploy/status.sh
```

启动前可执行非破坏性的环境检查：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\local\check-environment.ps1 -Mode distributed
```

分布式部署时还必须为每个 Agent 设置可被其他服务访问的 `A2A_PUBLIC_BASE_URL`，不能使用其他主机上的 `127.0.0.1`。DeepSeek、DashScope、数据库和基础设施凭据必须通过环境变量或密钥管理系统提供。

通知和媒体服务的集成模式由对应 Nacos data ID 管理；营销 campaign 的真实模式在缺少 provider、来源或授权时失败关闭，模拟模式仅用于明确标注的测试数据。

分布式服务提供 `/actuator/health/liveness` 和 `/actuator/health/readiness`。一般业务服务的 readiness 检查当前模板中声明的 Nacos、数据库、Redis、RocketMQ 或 MinIO 依赖；独立 RAG 的 `ragBaseReadiness` 检查安全配置、控制 DB 的 `rag_index_event`/`rag_document_state` 两表查询权限及至少三个已导出 RPC provider，不直接探测 Nacos 或检索后端。应用进程存活或基础 readiness 通过不代表空间、检索能力与切流水位已验收。

提交前可执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\local\check-credentials.ps1
openspec validate --all --strict --no-interactive
```

分布式配置使用显式的 `distributed` profile；该 profile 会从 Nacos 导入配置，缺少 `AUTH_TOKEN_SECRET` 或 `A2A_PUBLIC_BASE_URL` 时应直接补齐环境变量，不要恢复模板中的密钥或 loopback 回退值：

```powershell
$env:AUTH_TOKEN_SECRET = 'replace-with-a-long-random-value'
$env:A2A_PUBLIC_BASE_URL = 'http://reachable-host:9002'
mvn -pl auth-service "-Dspring-boot.run.profiles=distributed" spring-boot:run
```

旧数据库中的 `user_account.password_hash` 如果仍是明文或原型值，必须先用 BCrypt 重新生成哈希并迁移；新认证流程不会再接受原型明文或 `mock-access-token-*`。

### Docker 开发部署

仓库提供了 Docker Compose 开发编排。`minimal` profile 只启动 MySQL、Nacos、配置导入、认证服务和网关，并使用 `bk-anent-services:minimal` 镜像，只编译 `common`、`gateway`、`auth-service` 三个模块，适合接口、认证和基础链路测试；`full` profile 会额外启动 Redis、RocketMQ、MinIO、Elasticsearch、Milvus（含 etcd 和内部 MinIO）及全部当前业务服务，包括平台经营服务。核心服务使用 `bk-anent-services:dev` 镜像，平台经营单独构建 `bk-anent-platform-operations:dev`。配置发布必须显式设置 `NACOS_CONFIG_DATA_IDS` 为此次授权的空格分隔 YAML data ID 列表；默认空列表拒绝发布，不再全目录上传 public。当前活动模板 ID 为 `auth-service.yaml gateway.yaml agent-service.yaml listing-master-service.yaml customer-service.yaml marketing-content-service.yaml media-worker-service.yaml notification-service.yaml interview-service.yaml memory-service.yaml rag-service.yaml platform-operations-service.yaml`；`mysql-common.yaml` 是参考模板，不发布。`NACOS_NAMESPACE` 使用实际 namespace ID（Compose 默认 `BK-AGENT`），group 固定 `DEFAULT_GROUP`。Nacos Derby 使用 `nacos-data` named volume；普通容器重建保留配置与注册状态，`docker compose down -v` 会删除该开发数据。MinIO bucket 与营销 `marketing_content` 索引保留初始化；房源 RAG 索引仅经受权 provision 创建。独立 `rag` profile 不由 full 隐式启动，使用 `--profile rag` 或 `start.sh --with-rag` 显式选择，基础 readiness 不代表默认双路/重排能力或切流水位通过，完整门禁见 [RAG 部署与迁移](docs/linux-deployment-init.md)。

```powershell
docker compose --profile minimal up -d --build
docker compose --profile minimal ps
```

验证入口：`http://127.0.0.1:5010/gateway/health`；认证服务直连地址为 `http://127.0.0.1:9101/auth/health`。启动全部业务服务：

```powershell
docker compose --profile full up -d --build
```

停止最小测试环境但保留容器和数据卷：

```powershell
docker compose --profile minimal stop
```

开发环境基础设施对宿主机 `127.0.0.1` 的入口为：Nacos API `8848`、Nacos 控制台/健康接口 `18080`（gRPC `9848/9849`）、Redis `6379`、RocketMQ NameServer `9876`、Broker `10911/10909`、MinIO API/控制台 `19000/19001`、Elasticsearch `9200`、Milvus gRPC/健康检查 `19530/9091`。MySQL 默认仅在 `bk` 网络内部开放 `3306`，容器内部仍使用 `MYSQL_HOST=mysql` 连接。Compose 默认只绑定 `127.0.0.1`，其他基础设施如需局域网访问可在 `.env` 中修改 `HOST_BIND_ADDRESS`。

### 独立 MySQL MCP 容器

MySQL 保留官方 `mysql:8.4` 镜像和原有 `mysql-data` 数据卷，独立的 `mysql-mcp` 容器提供只读数据库工具。启动入口会为缺失的 MCP 数据库密码和 Bearer Token 生成随机值，写入已被 Git 忽略的 `.env`，并保留已有配置：

```powershell
powershell -NoProfile -File scripts/start-mysql-mcp.ps1
```

这条命令只启动 MySQL、一次性账号初始化任务和 MCP，不会启动 Nacos 或业务服务。MCP 地址为 `http://127.0.0.1:18081/mcp`，使用 Streamable HTTP；客户端必须发送 `Authorization: Bearer <MYSQL_MCP_TOKEN>`，Token 取自本机 `.env`。`18080` 已由 Nacos 使用，因此 MCP 默认采用 `18081`。详细工具、验证和维护说明见 [mysql-mcp/README.md](mysql-mcp/README.md)。

账号初始化任务会在已有数据卷上创建或更新专用 `mcp_ro` 用户，只授予 `MYSQL_MCP_DATABASES` 中业务库的 `SELECT`、`SHOW VIEW`，不执行业务初始化 SQL。默认包含 9 个当前 `bk_*` 业务库，不包含 Nacos 或 MySQL 系统库；按需要在 `.env` 缩小范围。不要删除数据卷来创建 MCP 账号。已有数据卷的 root 密码需通过 `MYSQL_ROOT_PASSWORD` 提供。

仅在宿主机数据库客户端或本地 Java 服务需要直连 MySQL 时，显式启用端口覆盖配置：

```powershell
docker compose -f docker-compose.yml -f docker-compose.mysql-client.yml up -d mysql
```

这会把 `3306` 绑定到 `127.0.0.1`（可通过 `MYSQL_EXPOSED_PORT` 调整）。MCP 对外绑定独立使用 `MYSQL_MCP_BIND_ADDRESS`，默认始终为 loopback；远程接入前配置 HTTPS 入口及 `MYSQL_MCP_ALLOWED_HOSTS`，客户端应能提供 Bearer 请求头。

Docker Compose 默认用于开发环境；平台经营服务随 `full` 档位启动，也可单独使用 `platform-preview` 档位联调。生产部署需准备环境专属配置、服务凭据及真实 provider，并完成授权和依赖验收。

变量名称清单见 [.env.example](.env.example)。
