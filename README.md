# 房地产中台 Agent 系统

基于 Spring Cloud Alibaba、Spring AI Alibaba、Dubbo 的多模块微服务骨架工程。

## 模块说明

- `common`：公共 DTO、异常、常量、Dubbo 接口
- `gateway`：统一入口网关
- `auth-service`：认证与权限
- `agent-service`：AI Agent 核心编排
- `listing-master-service`：房源主数据
- `customer-service`：客源/业主管理
- `notification-service`：统一消息通知
- `marketing-content-service`：营销内容资产
- `promotion-service`：多平台发布与效果采集
- `business-service`：KPI 与经营分析
- `contract-service`：合同生命周期
- `settlement-service`：分佣与结算
- `compare-engine-service`：房源对比引擎
- `media-worker-service`：异步媒体生成 Worker

## 当前交付

- Maven 父工程与模块拆分
- 公共 Dubbo 接口定义与基础 DTO
- 各服务启动类与最小化配置
- Agent 服务中的示例编排入口

## 后续建议

1. 将 `common` 继续拆为 `common-core`、`common-api`、`common-ai`。
2. 为每个服务补齐数据库模型、Feign/Dubbo 实现、消息消费逻辑。
3. 接入 Nacos、ZooKeeper、RocketMQ、MinIO、DeepSeek 的真实环境配置。
