# 从访谈中拆出经验知识运营域

## Why

首批用户是知识运营人员和店长，目标是通过经营案例复用提高房源维护、带看和成交推进效率。访谈已经拥有逐字稿、评级和案例报告，但知识编辑、审核、发布、版本与应用记录缺少独立业务归属，继续全部放入访谈会扩大其运行面职责。

## What Changes

- 新增 `knowledge-service`，独占新经营案例、打法、revision、审核、发布范围、依赖和操作回执；访谈继续独占会话、话轮、逐字稿资产及原报告/报告任务事实，不迁移其表或 ID。
- 逐字稿通过访谈域的只读来源契约接入；访谈侧签发并撤回绑定 `assetId`、snapshot/content hash、内容/脱敏修订、知识用途、唯一消费服务和目标知识范围的来源使用授权。知识域只保存来源/授权引用及必要派生内容，不直连 `bk_interview`，不复制可编辑逐字稿库。
- 逐字稿与知识分别保留责任：来源访问使用 Auth 签名 `AuthorizationContext`/`AuthzFreshness` 和绑定完整请求体、caller、audience、operation、requestId 的服务 proof，并核验访谈域当前案例关系；知识发布范围只能取可信 Auth 身份范围与所有来源授权范围的交集。不得从 L0–L3、公开 `visibility`、请求体中的 `storeId`/`ownerId` 或模型声明推导权限。
- 为打法记录问题情境、前提、动作、观察结果、反例/不适用条件及原声证据；L0–L3 评级只表达来源质量，不授予共享权或证明增长效果。
- 建立发布范围、不可变修订、人工审核、幂等回执和来源失效复核；首期优先单案例，跨案例共性只作为候选线索，不以样本数直接证明通用有效。
- 增加知识运营技能与官方 `knowledge-agent`，沿用签名 Auth context/freshness、逐请求服务证明、必需 Memory 和精确能力权限映射；只做 Supervisor 注册/受众/权限映射，不新增 Graph 领域分支，不修改 Auth 或 Memory 实现。
- 知识域从本 change 起拥有新案例和打法事实；访谈侧 `playbook_card`、`commonality_report` 继续保留为历史/占位数据，不搬表、不复用 ID 或按样本数生成的置信度。先只读盘点并交付映射方案，任何存量迁移另行审批和验收。
- 访谈报告可作为不透明来源引用；首期只消费逐字稿来源契约，不读取报告正文，不重放或覆盖现有报告任务。
- 本 change 不实施门店复盘、不改访谈运行面状态机，也不执行其他模块合并。
- 按 `domain-parallel-v1` 发布知识读取/当前使用检查，通过本会话访谈来源与精确身份夹具独立验收；Auth 当前尚无 knowledge Agent、知识生命周期/来源授权操作登记，也无可信 `STORE` 身份映射，因此交付精确操作与服务身份登记差异，并在缺少已验证身份范围时失败关闭，不等待其它业务 change 的新提交。操作映射详见 design。

## Capabilities

### New Capabilities

- `interview-transcript-consumption`: 跨域逐字稿来源引用、当前读取权、快照完整性和失效传播契约。
- `knowledge-operations`: 可追溯经营案例/打法的运营生命周期、发布范围和版本化读取。

### Modified Capabilities

无。现有 Memory 私有/共享经验规则、访谈公开 MCP 边界和通用 Agent/技能契约继续适用；领域知识发布不隐式兑换 Memory grant。

## Impact

新增 `knowledge-service`/`bk_knowledge`，在 `common/knowledge` 和 `common/interview/transcript` 定义纯 wire 契约；`interview-service` 增加签名来源读取/检查、用途授权签发/撤回及独立来源授权迁移；`agent-service` 仅增加 `knowledge-agent` 受众解析、精确能力映射及最小动态注册块。Auth 操作目录、角色授权、公钥在线登记和 Memory caller 白名单仍由 Auth/Memory 会话或协调门禁合入，本 change 交付 design 中的精确操作矩阵、服务身份登记清单与本地授权夹具，不修改这两个服务。当前 `interview_asset` 不保存 `dataMode`，因此无可信 provenance 的存量统一为 `UNKNOWN`，测试夹具可明确标记 `SIMULATED`。可信 `STORE` 身份映射未接通前，门店范围的发布、读取和消费保持关闭。沿用 LR-3/6/8/59/90/94/96/97，承接 KI-15 中打法卡与共性提炼的规划责任；本次规划不改变 KI 状态。

执行方式：四会话之一，可在共同基线上独立完成本期实施。未来消费者按冻结 HTTP契约接入，不成为本 change 的构建或完成条件；真实组织/服务登记与消费者互通在合并后另验。Interview 当前字段不能证明来源模式，不能从 `AUTO|UPLOAD` 或 L0–L3 推断真实/模拟；测试模拟来源必须由夹具显式标记，不能作为真实经营效果证据。并行范围与隔离要求见 `docs/business-domain-change-roadmap.md`。
