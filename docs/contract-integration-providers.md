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

关键配置项均在 Nacos 中直接维护字面值，不在 `.env` 重复定义：

- `contract.integration.mode`
- `contract.integration.ocr-provider`
- `contract.integration.esign-provider`

Docker 开发默认值（仅显式 `local` 允许模拟）：

```yaml
contract:
  integration:
    mode: local
    ocr-provider: mock-ocr-provider
    esign-provider: mock-esign-provider
```

分布式生产部署必须在 Nacos 显式设 `contract.integration.mode: real`，并同时选择已接入的真实 OCR 和电子签 provider。`vendor-ocr-provider` 已被 KE 网关百度通用文字识别替代（provider 名 `baidu-general`，`KeBaiduContractOcrExtractor`：服务端下载附件→base64→`POST {KE_OCR_BASE_URL}/ocr/general`，实测中文合同文本 5/5 行识别正确、延迟 0.4–0.8s；百度后端无法拉取内网 URL，因此不走 image_url）。media-worker 的房源文生图已由 KE 网关豆包 Seedream 承接（`doubao-seedream-4.5-gen`，`KeSeedreamImageGenerationClient`：逐角度生成→TOS URL 下载→byte[] 上传 MinIO；实测 2048×2048、约 15s/张、三角度任务 45s 由 RocketMQ 异步消费吸收；Seedream 拒绝 size 参数）。电子签 `esign-cn`、`fadada` 仍是占位实现，选择其名称或仅改为 `real` 不代表真实集成已就绪；未实现应明确失败，不能回退模拟成功。第三方密钥仍由环境变量或密钥管理系统提供，不得写入 Nacos YAML。

合同技能目录/监听开关以及 `contract.agent` 的模型、温度、token 上限、LLM 风险审查开关也在 Nacos 中直接维护，默认分别为 `deepseek-chat`、`0.2`、`2000`、`true`；依赖 readiness 不因配置收口而降低。

## 5. 演进规则

后续若替换为真实第三方接入，建议遵守以下规则：

1. 尽量保持规范名称稳定，不随实现类名频繁变化。
2. 只有在历史兼容需要时才增加 alias。
3. 通用占位实现不要再绑定具体厂商名。
4. 修改 provider 规范时，同时更新：
   - `ContractProviderNames`
   - 本文档
   - `nacos/contract-service.yaml`
