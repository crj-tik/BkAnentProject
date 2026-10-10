# Nacos 配置目录说明

该目录用于维护需要导入到 `Nacos` 的服务配置模板。

## 文件命名

- 服务配置统一使用 `${spring.application.name}.yaml`
- 默认分组使用 `DEFAULT_GROUP`
- 公共配置可单独拆分，例如 `mysql-common.yaml`

## 当前约定

### 1. 本地 `application.yml`

每个服务本地仅保留：

- `server.port`
- `spring.application.name`
- `spring.config.import`
- `spring.cloud.nacos.discovery/config.server-addr`
- `dubbo.application / protocol / registry`
- 仅用于本地启动的开关，例如 `local` profile 下的 `discovery.enabled=false`

### 2. Nacos 承载的配置

统一放入 `nacos/*.yaml` 的内容包括：

- `spring.datasource`
- `spring.data.redis`
- `rocketmq`
- `media.minio`
- `spring.elasticsearch`
- `milvus`
- `spring.ai.deepseek`
- `spring.ai.openai`（仅 agent-service embedding 通道，经 `spring.ai.model.chat=deepseek` / `embedding=openai` 选择）
- `spring.ai.alibaba.a2a.*`
- `spring.cloud.nacos.discovery.metadata`
- `management.*`
- 业务运行参数

### 3. 环境变量约定

`.env` 只维护端口、连接地址、凭据和启动引导参数；宿主机预检/启动工具使用的基础设施地址继续保留。Nacos 中的连接与密钥占位符由应用进程环境变量或密钥管理系统提供，禁止把真实密钥写进 YAML。

- 连接与凭据：`MYSQL_*`、`REDIS_*`、`ROCKETMQ_*`、`MINIO_*`、`ELASTICSEARCH_URIS`、`MILVUS_*`（不含业务开关）、模型 API 密钥、`AUTH_TOKEN_SECRET`
- 启动引导：`NACOS_SERVER`、`NACOS_NAMESPACE`、`NACOS_USERNAME`、`NACOS_PASSWORD`、profile/readiness 和 A2A 地址
- Compose 容器使用服务名连接内部基础设施；宿主机 `.env` 中的 loopback 地址不自动替换容器内连接。应用的 `NACOS_USERNAME`、`NACOS_PASSWORD` 和 `MINIO_PUBLIC_BASE_URL` 可通过 Compose 插值覆盖。账号初始化及目标命名空间 Card 更新授权仍需独立完成，设置密码不等于授权。

### 4. 业务配置单一来源

以下参数直接在对应 Nacos data ID 中维护字面值，不在 `.env` 或 Compose 环境中重复定义：

- `auth-service.yaml`：`auth.token.access-ttl-seconds=3600`、`refresh-ttl-seconds=604800`
- `contract-service.yaml`、`notification-service.yaml`、`media-worker-service.yaml`、`promotion-service.yaml`：各自 `*.integration.mode=local`
- `contract-service.yaml`：OCR 真实实现配置 `ocr-gateway-base-url`/`ocr-model`（KE 百度 `baidu-general`）；`media-worker-service.yaml`：文生图真实实现配置 `image-gateway-base-url`/`image-model`（KE Seedream `doubao-seedream-4.5-gen`）。生产 `real` 模式分别将 `ocr-provider` 切为 `baidu-general`、`mode` 切为 `real` 即启用
- `business-service.yaml`：`business.ranking.use-redis=true`
- `agent-service.yaml`：`agent.distributed.stream.provider=rocketmq`、`milvus.enabled=true`
- `listing-master-service.yaml`、`marketing-content-service.yaml`：各自 `*.search.use-elasticsearch=true`
- `contract-service.yaml`：技能目录/监听、模型 `gpt-5.6-terra`（可经 `CONTRACT_AGENT_MODEL` 覆盖）、温度 `0.2`、最大 token `4000`、风险审查开关 `true` 及 OCR/电子签 provider 名称

这些默认值保留 Docker 开发编排此前的有效行为。分布式生产部署前必须在 Nacos 显式配置四个服务的 `*.integration.mode=real`，并接入/选择真实 provider（合同须同时选择真实 OCR 与电子签 provider）；仅写 `real` 或选择尚未实现的厂商占位 provider 不代表集成可用。不得通过 `.env` 切换业务模式，也不得为绕过依赖失败而削弱 readiness。开发 `config-init` 原样上传本目录模板，重复运行会覆盖同 data ID；生产导入前应准备环境专属配置，避免用开发模板覆盖生产设置。

### 5. 目标命名空间与账号（本机生产化基线，2026-10-10）

- 命名空间 `BK-AGENT`（custom，quota 200）：全部 16 个 data ID 已发布，应用容器经 `NACOS_NAMESPACE` 默认接入。
- 管理账号 `bkagent`（ROLE_ADMIN，`globalAdmin=true`，可登录 console/18080 与 admin/8848 并发布/更新配置）；密码存于 `.env` 的 `NACOS_PASSWORD`（本地文件不入库）。`nacos` 内置账号保留但密码未启用，不要依赖它。
- 服务间鉴权（`NACOS_AUTH_ENABLE`）开发态保持关闭，identity 头直通 admin API；生产启用前须轮换 `NACOS_AUTH_TOKEN` 并确认 Card 更新走鉴权链（LR-28：设置密码不等于完成授权）。
- 维护提示：Nacos 3.1 的 ROLE_ADMIN 只能经无鉴权初始化端点创建且"已有 admin 即拒绝"；本机曾因内置 `nacos` 账号密码未知陷入死锁，最终以停机 Derby 离线补 `bkagent` 角色行解决——生产重建账号时避免依赖该路径，初始化时直接创建自己的管理员。

## Agent 当前方案

- 连接层：`spring.ai.deepseek`（chat，KE 网关 `gpt-5.6-terra`）+ `spring.ai.openai`（embedding，KE 网关 `baai-m3-1b-base-v1-embedding-20240619`，1024 维）
- 业务层：`agent.chat`（模型名逐次调用传入）
- 工具层：`agent.mcp`
- 默认由 LLM 根据 A2A/MCP 等能力描述选择工具；指定 skill 时加载正文与能力范围，业务步骤由模型理解执行。
- 九个 Subagent 的本地技能由注册表动态生成 Card，`card.skills` 不再手工维护。技能热加载立即刷新 HTTP Card，Nacos 发布失败每 5 秒重试；Supervisor 成功目录缓存仍受刷新间隔影响。
- Nacos 3.1 的同版本发布不会覆盖 Card，热更新使用官方更新 API。配置 `NACOS_USERNAME`、`NACOS_PASSWORD`，账号需具有目标命名空间 Card 更新权限；客户端注册鉴权关闭时 Admin 更新接口仍要求凭据。凭据仅从环境变量读取。
- 下游流式执行期限配置为 `agent.a2a.execution.stream-timeout-ms`，默认 120000 毫秒；超时输出 `EXECUTION_TIMEOUT` 并取消本地订阅。

## 当前保留文档

- `README.md`：Nacos 配置说明
- `mysql-common.yaml`：MySQL 公共配置模板
- `agent-service.yaml` 等：各服务配置模板
- `../docs/official-a2a-runtime-migration.md`：A2A 与 MCP 运行时边界说明
