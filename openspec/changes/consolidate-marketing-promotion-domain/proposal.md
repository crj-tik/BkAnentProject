# 营销内容与推广合并为内容营销域

## Why

`marketing-content-service` 管理内容及发布状态，`promotion-service` 负责发布记录、效果和素材，并通过 RPC 回写前者，同一发布过程横跨两个服务。统一业务归属可以把内容版本、目标账号、发布回执和效果来源连成一条可核对的链路，减少双写与重复状态。

## What Changes

- 将推广、品牌素材和效果记录迁入 `marketing-content-service` 的独立业务包，以同一营销域管理内容、发布计划和各平台尝试；保留 `marketing-agent`。
- **BREAKING**：退役独立 `promotion-service` 部署与新的内部 RPC 消费；旧 `/promotions/**` 仅作为同一领域内的受权迁移适配，不保留跨服务发布状态双写。
- 发布计划固定正文、素材、渠道账号和参数版本；确认只作用于该快照，重复请求可找回原回执，未知外部结果进入对账而非自动重发。
- 继续使用独立媒体 Worker 和通知能力；真实平台未接入时返回明确不可用，模拟发布与模拟效果始终带 `dataMode`。
- 将当前 `syncEffectStat` 按记录 ID 生成曝光/线索、用线索数乘固定金额计算 ROI 的样例逻辑收口到模拟适配器；真实效果必须有渠道来源，真实 ROI 必须有可归属收入和成本证据。
- 原有 `bk_promotion` 数据先盘点、映射、核对再迁移；保留旧 ID 来源和发布幂等身份，不能据当前使用模拟数据推定历史库可删除。
- 本 change 只处理营销与推广合并，不包含知识运营、经营复盘、房源合并或真实外部平台采购接入。
- 在独立 worktree/分支验收自身生命周期与迁移；需要房源时按 `domain-parallel-v1` 使用本模块消费适配和本地 fixture，不引入房源合并分支的类型或实现。
- 明确 A2A 草稿、营销服务内 ReAct Agent 和 Supervisor 发布各自边界：A2A 子技能只返回草稿；ReAct 可创建草稿或准备计划但不得确认发布、发送通知或自行宣称已发布；Supervisor 通过独立发布技能展示确切计划，再经单独可审批的 MCP 确认能力执行。
- 为 Supervisor 提供可继续完成闭环的结构化 MCP 结果：草稿返回内容 ID，发布确认返回 receipt/attempt 标识，并开放原回执查询与未知结果核对。
- **BREAKING**：旧 `/promotions/**` 路径仅作受权过渡入口，不承诺旧请求/响应 DTO 兼容；迁移前须盘点并迁移调用方，旧负载必须有明确的契约错误，不能把路径仍可访问描述为完整兼容。

## Capabilities

### New Capabilities

- `marketing-campaign-lifecycle`: 同域内容版本、发布确认、平台回执、对账与真实/模拟效果度量。

### Modified Capabilities

无。沿用调用级审批、必需 Memory、外部 Provider 失败语义和现有媒体能力契约。

## Impact

除营销服务内 A2A、ReAct Agent、MCP 工具外，本 change 还需要一个独立的 Supervisor 发布技能和实际能力目录/审批配置验收；共享 Supervisor 审批能力集合由合并协调阶段并集更新。

主要影响 `marketing-content-service`、`promotion-service`、`common` 的推广契约、部署与网关清单，以及发布调用方。遵循 LR-22/23/53/96/102；承接 KI-167 的效果来源缺口，规划不标记修复。与 `implement-studio-v2` 的发布计划/确认任务按领域执行契约衔接，不重复实现 Studio 命令与审批门面。Auth 由其他会话交付，本 change 只消费身份及发布账号的受信授权。

执行方式：四会话之一，可与其它三项同时实施和独立验收；产品启用优先级可后置，但不形成代码等待关系。房源按冻结 wire 契约引用，外部平台/Auth/Memory 用明确测试登记或 fixture 验证协议边界；真实模式未配置必须失败，不能暗中切换模拟。共享环境停旧/迁移与真实平台接入在合并后另验，见 `docs/business-domain-change-roadmap.md`。

## 功能完整性约束

营销域合并完成要求原营销内容管理和推广生命周期的业务结果在统一域内全部可用：内容创建、渠道版本、审核/发布状态、检索，推广发布、效果同步/查询、ROI 报表及品牌素材管理。旧 `/promotions/publish` 与 Dubbo 发布调用可迁移为“准备—审核—明确批准—回执/核对”的新流程，但仅准备计划不等于发布成功；所有 REST、Dubbo、A2A/ReAct、MCP、Supervisor 和已知客户端都必须有迁移记录。原 BEIKE、DOUYIN、XIAOHONGSHU 实现是本地模拟适配器；真实 provider 未接入时，规划不得把模拟能力说成真实渠道发布。任何源功能没有目标入口、调用方迁移或验收证据时，不得据此退役旧服务或宣称完整合并。
