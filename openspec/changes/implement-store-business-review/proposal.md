# 将经营分析落到门店卡点复盘与推进

## Why

当前 `business-service` 主要查询 KPI、排行榜和看板，缺少将经营问题转成可追踪推进事项的业务流程。首期需要服务知识运营人员和店长，围绕房源维护、带看与成交卡点减少整理和判断成本，并利用实际应用反馈辅助业绩增长。

## What Changes

- 在现有 `business-service` 建立经营复盘事项、证据快照、卡点假设、建议动作、确认执行和结果观察；首个完整场景为带看后停滞事项梳理，同时支持房源维护缺口。
- 以受管模拟数据集提供初期带看、跟进、议价和经营指标输入；房源通过房源域接口接入，逐字稿保持访谈域来源，打法通过知识域读取。
- 所有事实、计划和结果保留来源、时间、版本及 `SIMULATED/REAL/MIXED` 标记；模拟验收证明流程可用，不形成真实增长结论或生产经验结果证据。
- 定义指标口径、样本范围、分母和观察窗口；缺失数据表达为未知。区分事实、原因假设、动作完成和经营效果，模型不能自行宣告成交或业绩改善。
- 店长确认后形成有负责人、期限和版本的推进动作，幂等保存原请求回执；首期记录执行反馈和上游回执，不自动发送消息、调价、改房源状态或触发付款。
- 保留现有 `trade/trade-agent` 技术身份并新增经营复盘技能，避免将产品命名调整变成 Memory 历史重归属。
- Agent 工具授权采用当前稳定 `CapabilityId` 方案：只为 `a2a:trade-agent` 增加到精确 Auth 操作的最小映射；经营服务方法仍逐项核验 `business.review.*.store` 操作和可信门店范围。复盘写操作不作为 Supervisor 本地工具或 MCP 工具发布。
- 本 change 不包含新人带教，不合并其他服务，也不承接 `implement-studio-v2` 中尚未完成的发布、结算和文件操作。
- 按 `domain-parallel-v1` 实现房源/知识 HTTP consumer adapter 与自有 fixture；消费方 wire DTO 留在本模块，不依赖另外三个 change 新增的 Java 制品。

## Capabilities

### New Capabilities

- `store-business-review`: 有来源的经营卡点复盘、优先事项、确认推进、执行反馈和指标观察。

### Modified Capabilities

无。Supervisor 仍按通用工具循环协调，业务状态由经营域维护；既有审批、技能和 Memory 安全契约继续适用。

## Impact

**本 change 负责**：`business-service` 的复盘领域、`bk_business` 复盘表与迁移、`common/business/review/**` 中必要的业务 DTO、房源/知识 v1 消费适配及本域 fixture、`trade-agent` 现有身份下的技能和安全结果投影。业务事项、证据、计划、动作、回执和观察只由 Business 持久化。

**仅允许的 Agent 接入差异**：在受管 `agent-service` 配置中增加 `CapabilityId.a2a("trade-agent")` 到精确 Agent 调用操作的单项映射；不改 Supervisor/Graph/Agent 身份框架、通用工具循环或审批实现。当前仓库尚无此映射，也未找到 `agent.workflow.invoke.trade` 的 Auth 权限登记；协调/Auth 交付精确登记后映射才可启用，缺任一项时目录隐藏且直接调用拒绝。

**Auth/协调方负责**：在 Auth 权限目录登记 `agent.workflow.invoke.trade` 及本 change 提交的精确 `business.review.*.store` 操作码；建立并签入可验证的 STORE 主体/门店范围。当前 `AuthorizationContext.businessIdentities` 只允许 `BROKER`，因此业务侧只实现可信门店范围端口和独立测试替身，生产实现缺失时关闭读写；不得在本 change 修改 `common/authz/**`、`auth-service/**`、Auth 全局目录或用 userId、brokerId、员工号、请求 storeId 推导门店权限。

房源与知识 change 只拥有各自生产者、表和接口；本 change 只拥有 `domain-parallel-v1` HTTP consumer/wire DTO，不读其数据库、不引用其新 Java 类型，也不复制访谈逐字稿。Studio 页面、work/command、发布结算和 KI-114/128/166 的问题留在各自 change。实际服务互通及真实增长验证是合并后的门禁，不阻止本 change 在固定 HTTP/Auth/Memory 测试登记下独立完成。变更范围见 `docs/business-domain-change-roadmap.md`。

执行方式：四会话之一，在共同基线上以真实本域处理器/持久记录、固定 v1 HTTP fixture 和精确 Auth/Memory 测试登记完成全部本期验收。房源、知识和 Auth 其它会话的交付均不是实施完成前置条件；仍需完成真实 HTTP adapter、拒绝/版本/撤回反例，不能以空接口代替。实际服务互通与真实增长验证在合并后的门禁分别报告，不能把模拟通过写成已联机。范围与隔离要求见 `docs/business-domain-change-roadmap.md`。
