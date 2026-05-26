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
- 执行模式：`TOOL` / `PLANNER`

## 当前保留文档

- `README.md`：Nacos 配置说明
- `mysql-common.yaml`：MySQL 公共配置模板
- `agent-service.yaml` 等：各服务配置模板
- `mcp-迁移方案.md`：MCP 相关落地说明
