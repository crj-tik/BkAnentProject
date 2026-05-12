# Contract 集成 Provider 规范

## 1. 目的

本文档定义 `contract-service` 当前使用的 OCR 与电子签 provider 名称规范。

统一常量定义位置：

- [ContractProviderNames.java](/D:/project/BkAnentProject/BkAnentProject/contract-service/src/main/java/com/bkanent/contract/config/ContractProviderNames.java:1)

## 2. OCR Provider

规范名称：

- `mock-ocr-provider`
- `vendor-ocr-provider`

兼容别名：

- `mock`
- `vendor`
- `third-party-ocr`
- `dashscope`

当前行为：

- `mock-ocr-provider`
  - 返回确定性的模拟 OCR 结果
  - 适合本地开发与联调测试
- `vendor-ocr-provider`
  - 通用第三方 OCR 占位实现
  - 用于替代旧的 `dashscope` 命名占位方案

## 3. 电子签 Provider

规范名称：

- `mock-esign-provider`
- `esign-cn`
- `fadada`

兼容别名：

- `mock`
- `esign_cn`

当前行为：

- `mock-esign-provider`
  - 返回模拟签章结果
- `esign-cn`
  - E-Sign CN 占位实现
- `fadada`
  - 法大大占位实现

## 4. 配置位置

Nacos 配置文件：

- [contract-service.yaml](/D:/project/BkAnentProject/BkAnentProject/nacos/contract-service.yaml:1)

关键配置项：

- `contract.integration.ocr-provider`
- `contract.integration.esign-provider`

推荐默认值：

```yaml
contract:
  integration:
    ocr-provider: ${CONTRACT_OCR_PROVIDER:mock-ocr-provider}
    esign-provider: ${CONTRACT_ESIGN_PROVIDER:mock-esign-provider}
```

## 5. 演进规则

后续若替换为真实第三方接入，建议遵守以下规则：

1. 尽量保持规范名称稳定，不随实现类名频繁变化。
2. 只有在历史兼容需要时才增加 alias。
3. 通用占位实现不要再绑定具体厂商名。
4. 修改 provider 规范时，同时更新：
   - `ContractProviderNames`
   - 本文档
   - `nacos/contract-service.yaml`
