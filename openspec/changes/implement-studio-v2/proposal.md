## Why

已确认的 Studio V2 前端需要持续工作、结构化成果和精确版本动作；现有按 task 查询的接口无法直接提供这些产品语义。补充应用门面以复用既有业务、执行与审批能力。

## What Changes

- 增加持久工作、上下文修订、运行/消息/成果关联、真实历史和授权恢复。
- 增加公共能力目录、类型化成果投影、修订/OCC、依赖与条件变化提示。
- 增加幂等命令与比较登记，并逐域提供发布计划、结算预览、合同/访谈/媒体文件契约。
- 修正 refresh 的 Gateway 通路及本次端点真实身份与动作校验，保留既有 LR 约束。
- 不新增编排器，不开放内部存储接口，不以模拟 provider 宣称实际业务成功。

## Capabilities

### New Capabilities

- `studio-workspace-api`: 私有工作聚合、恢复与公开能力发现。
- `studio-artifact-revisions`: 统一成果、修订并发控制与输入依赖。
- `studio-domain-actions`: 幂等命令、精确版本预览和既有审批/领域执行适配。

### Modified Capabilities

- `authentication-baseline`: refresh 经 Gateway 的精确方法/路径通路。

## Impact

agent-service、必要公共契约/存储与各领域适配、gateway、迁移及 LR/KI 文档。前端实现在 D:/project/BkAnentFrontend 同名独立 change 中管理。依据该前端仓库 docs/frontend-backend-integration-v2.md 和 backend-integration-prompts-v2.md；只在本仓库实施后端部分。
