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
- `spring.ai.alibaba.a2a.*`
- `spring.cloud.nacos.discovery.metadata`
- `management.*`
- 业务运行参数

### 3. 环境变量约定

建议统一使用：

- `MYSQL_*`
- `REDIS_*`
- `ROCKETMQ_*`
- `MINIO_*`
- `ELASTICSEARCH_*`
- `MILVUS_*`
- `DEEPSEEK_*`

## Agent 当前方案

- 连接层：`spring.ai.deepseek`
- 业务层：`agent.deepseek`
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
