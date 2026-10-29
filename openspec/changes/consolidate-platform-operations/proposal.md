## Why

经营分析、门店经营复盘和知识运营分别部署，造成门店身份、接口和配置重复。用户确认合并为“平台经营域”，并退役合同与结算业务。

## What Changes

- **BREAKING** 新建平台经营服务，承载现有经营分析、复盘与知识运营，提供统一入口并迁移所有调用方。
- **BREAKING** 移除合同与结算服务及其对外接口、调用方、Agent、权限和部署配置。
- 合并领域数据、应用配置、Nacos、Compose 与数据库初始化；开发库按已确认无正式历史资产的前提执行受管迁移。
- 保留访谈采集及其脱敏来源读取边界，更新前端功能清单和验收记录。

## Capabilities

### New Capabilities

- `platform-operations`: 平台经营域的分析、复盘、知识运营与权限边界。
- `contract-settlement-retirement`: 合同和结算能力退役及依赖清理。

### Modified Capabilities

无；与现有通用能力的要求保持一致，并在实施时核对相关进行中的变更。

## Impact

影响三个经营服务、合同与结算服务、Gateway、Agent、Auth、Memory、RAG、公共 RPC、数据库 manifest、Nacos、Compose、环境模板、测试和前端功能文档。相关 OpenSpec change 仍有未完成任务，需要逐项协调。
