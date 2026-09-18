# 房地产中台 Agent 系统

基于 Spring Boot、Spring Cloud Alibaba、Spring AI、Dubbo、MCP 的多模块微服务工程。

## 模块说明

- `common`：公共 DTO、基础模型、Dubbo 接口
- `gateway`：统一入口网关
- `auth-service`：认证与权限
- `agent-service`：Agent 编排，包含 `DeepSeek + MCP + Planner`
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
- 工具调用仅使用 `MCP server/client`
- 复杂任务使用 `Planner` 串行规划与执行
- 业务服务之间的 RPC 仍通过 `Dubbo` 进行正常调用
- 基础配置统一通过 `Nacos` 管理

## 配置约定

- 服务本地 `application.yml` 只保留最小启动配置
- 数据库、Redis、MQ、对象存储、Elasticsearch、Milvus、模型参数统一放在 `nacos/*.yaml`
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

启动后访问 `http://127.0.0.1:9101/auth/health`。本地演示账号为 `broker01`，密码为 `demo-password`，只用于冒烟测试，生产环境必须替换。

## 分布式环境启动

完整系统启动前需要准备 Nacos 3.x、MySQL、Redis、RocketMQ、Milvus、MinIO 和 Elasticsearch，并将 `nacos/` 下以服务名命名的 YAML 导入 Nacos。每个文件名就是 data ID，例如 `auth-service.yaml`、`agent-service.yaml`、`business-service.yaml`、`compare-engine-service.yaml`、`contract-service.yaml`、`listing-master-service.yaml`、`marketing-content-service.yaml`、`media-worker-service.yaml`、`notification-service.yaml` 和 `settlement-service.yaml`。默认分组为 `DEFAULT_GROUP`；namespace 使用 `NACOS_NAMESPACE` 指定，默认值见各服务的 `application.yml`。

启动前可执行非破坏性的环境检查：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\local\check-environment.ps1 -Mode distributed
```

分布式部署时还必须为每个 Agent 设置可被其他服务访问的 `A2A_PUBLIC_BASE_URL`，不能使用其他主机上的 `127.0.0.1`。DeepSeek、DashScope、数据库和基础设施凭据必须通过环境变量或密钥管理系统提供。

合同、通知、媒体和推广服务还必须显式设置集成模式：`CONTRACT_INTEGRATION_MODE`、`NOTIFICATION_INTEGRATION_MODE`、`MEDIA_INTEGRATION_MODE`、`PROMOTION_INTEGRATION_MODE`。只有明确的 `local` 模式允许模拟 provider；分布式/生产模式使用 `real`，未接入的真实 provider 会明确报告未实现，不会伪造成功。

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

开发环境基础设施对宿主机 `127.0.0.1` 的入口为：MySQL `3306`、Nacos API `8848`、Nacos 控制台/健康接口 `18080`（gRPC `9848/9849`）、Redis `6379`、RocketMQ NameServer `9876`、Broker `10911/10909`、MinIO API/控制台 `19000/19001`、Elasticsearch `9200`、Milvus gRPC/健康检查 `19530/9091`。Compose 默认只绑定 `127.0.0.1`，如需局域网访问可在 `.env` 中修改 `HOST_BIND_ADDRESS`。容器内部仍使用服务名和标准端口互联。

Docker 编排默认只用于开发演示：full profile 会启用 Milvus 和 Elasticsearch 搜索；MySQL 使用 `sql/mysql-init.sql` 初始化业务库和表，MinIO 创建 `generated-assets` bucket，Elasticsearch 创建 `listing_info`、`marketing_content` 索引。Redis、Elasticsearch、Milvus 开发模式不创建业务表空间或独立账号，MinIO 使用 root 开发账号；生产部署前必须通过环境变量或密钥管理系统提供 `MYSQL_ROOT_PASSWORD`、`AUTH_TOKEN_SECRET`、模型/API 密钥和独立的基础设施凭据，并接入真实 Provider。停止并删除容器（保留数据卷）使用 `docker compose down`，清理数据卷前请确认数据已备份。

变量名称清单见 [.env.example](.env.example)。
