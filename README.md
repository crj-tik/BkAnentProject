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
