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
- `marketing-content-service`：营销内容资产与检索
- `promotion-service`：多平台发布与效果统计
- `business-service`：业务分析、KPI、排行榜、门店看板
- `compare-engine-service`：房源对比与报告生成
- `contract-service`：合同全生命周期管理
- `settlement-service`：分佣与结算
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
- 房源 RAG 重排默认指向本机独立部署的 bge-reranker（`http://host.docker.internal:18119/rerank`，native 协议，别名 `bge-reranker-v2-m3-v1`）。该容器由独立仓库 `D:\AI\Reranker-BGE-V2-M3` 的 compose 管理，不在本编排内；调用方密钥在该仓库 `config/api-keys.json` 维护，经 `AGENT_RAG_RERANK_API_KEY` 注入应用容器。云端 DashScope 重排通过 `AGENT_RAG_RERANK_ENDPOINT`（DashScope URL）、`AGENT_RAG_RERANK_PROTOCOL=dashscope` 与 `AGENT_RAG_RERANK_MODEL=qwen3-rerank` 环境变量切换
- 文本模型与向量模型统一走 KE 网关（Bella OpenAI 兼容，`https://open-chatgpt.ke.com/v1`）：九个子 Agent 与 Supervisor 的 chat 经 DeepSeek starter 使用 `gpt-5.6-terra`（2026-10-10 质量优先评估：各家族最新梯队 15 个候选，深度合同风险识别 6/6、陷阱交易拦截、多轮工具链综合输出 5/5 且决策枚举合规，单轮约 12s；完整记录见 `docs/acceptance/ke-gateway-model-evaluation-20261010.json`。早期按延迟选的 `qwen3.8-flash` 在多轮工具链后输出退化为散文，已弃用；`Qwen3.8-Max` 为同网关质量回退，经各服务 `*_AGENT_MODEL` 环境变量切换；`gemini-3.8-flash` 因网关 tool 回传 400 通道缺陷不可用于 ReactAgent 循环）；agent-service 的 embedding 经 OpenAI starter 使用 `baai-m3-1b-base-v1-embedding-20240619`（1024 维，与 Milvus 集合维度一致，实测 4/4 排序正确）。密钥优先 `DEEPSEEK_API_KEY`，回退 `KE_API_KEY`，两者均不写入 Nacos。rerank 评估过网关 `qwen3-rerank-8b`（`/v1/reranks`），因带硬约束的房源场景排序不稳（预算/通勤用例排序错误）保留本地 bge-reranker，不接入
- 配置类统一通过 `@ConfigurationProperties` 收口
- 不在业务代码中硬编码连接地址、密钥和环境差异参数

## 关键配置

- Agent 模型配置：
  [AgentDeepSeekProperties.java](/D:/project/BkAnentProject/BkAnentProject/agent-service/src/main/java/com/bkanent/agent/config/AgentDeepSeekProperties.java:1)
- Agent MCP 配置：
  [AgentMcpProperties.java](/D:/project/BkAnentProject/BkAnentProject/agent-service/src/main/java/com/bkanent/agent/config/AgentMcpProperties.java:1)
- Agent Nacos 配置：
  [agent-service.yaml](/D:/project/BkAnentProject/BkAnentProject/nacos/agent-service.yaml:1)
- 数据库初始化脚本：
  [mysql-init.sql](/D:/project/BkAnentProject/BkAnentProject/sql/mysql-init.sql:1)

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

给已有 MySQL 开发库升级时，执行 [20260930_auth_user_management.sql](/D:/project/BkAnentProject/sql/migrations/20260930_auth_user_management.sql:1)。新建开发库可由 `sql/mysql-init.sql` 创建用户和角色表。

## 分布式环境启动

完整系统启动前需要准备 Nacos 3.x、MySQL、Redis、RocketMQ、Milvus、MinIO 和 Elasticsearch，并将 `nacos/` 下以服务名命名的 YAML 导入 Nacos。每个文件名就是 data ID，例如 `auth-service.yaml`、`agent-service.yaml`、`business-service.yaml`、`compare-engine-service.yaml`、`contract-service.yaml`、`listing-master-service.yaml`、`marketing-content-service.yaml`、`media-worker-service.yaml`、`notification-service.yaml` 和 `settlement-service.yaml`。默认分组为 `DEFAULT_GROUP`；namespace 使用 `NACOS_NAMESPACE` 指定，默认值见各服务的 `application.yml`。

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

合同、通知、媒体和推广服务的集成模式由对应 Nacos data ID 中的 `contract.integration.mode`、`notification.integration.mode`、`media.integration.mode`、`promotion.integration.mode` 管理，不在 `.env` 中设置。仓库模板为保留 Docker 开发行为使用显式 `local`，仅该模式允许模拟 provider。**已实现的真实 provider**：合同 OCR（KE 网关百度通用文字识别 `baidu-general`，real 模式配 `contract.integration.ocr-provider: baidu-general`，服务端下载附件后 base64 上送——百度后端拉不到内网 MinIO 地址）与房源文生图（KE 网关豆包 Seedream `doubao-seedream-4.5-gen`，`media.integration.mode=real` 即启用，每角度一张 2048×2048、约 15s/张由 RocketMQ 异步消费吸收）。**仍为占位**：电子签（esign-cn/fadada）、通知渠道、推广发布平台——生产 `real` 模式须接入真实实现，未实现会明确失败，不能回退模拟成功。生产导入前准备环境专属配置；开发 `config-init` 会原样上传模板并覆盖同 data ID，不要覆盖生产设置。

分布式服务提供 `/actuator/health/liveness` 和 `/actuator/health/readiness`。readiness 会检查当前模板中声明的 Nacos、数据库、Redis、RocketMQ 或 MinIO 依赖；应用进程存活不代表已经可以接收业务流量。

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

仓库提供了 Docker Compose 开发编排。`minimal` profile 只启动 MySQL、Nacos、配置导入、认证服务和网关，并使用 `bk-anent-services:minimal` 镜像，只编译 `common`、`gateway`、`auth-service` 三个模块，适合接口、认证和基础链路测试；`full` profile 会额外启动 Redis、RocketMQ、MinIO、Elasticsearch、Milvus（含 etcd 和内部 MinIO）及其余业务服务，并使用包含全部模块的 `bk-anent-services:dev` 镜像。启动时会自动把 `nacos/` 下的 YAML 导入 Nacos 公共命名空间，同时初始化 MinIO bucket 和两个 Elasticsearch 索引。

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

账号初始化任务会在已有数据卷上创建或更新专用 `mcp_ro` 用户，只授予 `MYSQL_MCP_DATABASES` 中业务库的 `SELECT`、`SHOW VIEW`，不执行业务初始化 SQL。默认包含 11 个 `bk_*` 业务库，不包含 Nacos 或 MySQL 系统库；按需要在 `.env` 缩小范围。不要删除数据卷来创建 MCP 账号。已有数据卷的 root 密码需通过 `MYSQL_ROOT_PASSWORD` 提供。

仅在宿主机数据库客户端或本地 Java 服务需要直连 MySQL 时，显式启用端口覆盖配置：

```powershell
docker compose -f docker-compose.yml -f docker-compose.mysql-client.yml up -d mysql
```

这会把 `3306` 绑定到 `127.0.0.1`（可通过 `MYSQL_EXPOSED_PORT` 调整）。MCP 对外绑定独立使用 `MYSQL_MCP_BIND_ADDRESS`，默认始终为 loopback；远程接入前配置 HTTPS 入口及 `MYSQL_MCP_ALLOWED_HOSTS`，客户端应能提供 Bearer 请求头。

Docker 编排默认只用于开发演示：full profile 配套的 Nacos 模板启用 Milvus 和 Elasticsearch 搜索、Redis 排行榜、RocketMQ 流式 provider，Token TTL 为 3600/604800 秒，四类集成为 `local`；这些业务值不再由 Compose 环境覆盖。应用容器的 `NACOS_USERNAME`、`NACOS_PASSWORD`、`MINIO_PUBLIC_BASE_URL` 可通过 `.env` 覆盖，但 Nacos Admin 账号初始化和目标命名空间 Card 更新授权仍需独立配置，填入密码不等于已授权。MySQL 使用 `sql/mysql-init.sql` 初始化业务库和表，MinIO 创建 `generated-assets` bucket，Elasticsearch 创建 `listing_info`、`marketing_content` 索引。Redis、Elasticsearch、Milvus 开发模式不创建业务表空间或独立账号，MinIO 使用 root 开发账号；生产部署前必须通过环境变量或密钥管理系统提供 `MYSQL_ROOT_PASSWORD`、`AUTH_TOKEN_SECRET`、模型/API 密钥和独立的基础设施凭据，并接入真实 Provider。合同 OCR 已实现 KE 网关百度通用文字识别（`baidu-general`，real 模式配 `contract.integration.ocr-provider: baidu-general`，服务端下载附件后 base64 上送——百度后端拉不到内网 MinIO 地址，不能直接传 image_url）；电子签仍为占位。停止并删除容器（保留数据卷）使用 `docker compose down`，清理数据卷前请确认数据已备份。

变量名称清单见 [.env.example](.env.example)。
