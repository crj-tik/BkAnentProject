## Why

当前对比报告有稳定来源快照和卡片投影，但报告读取仍将来源版本变化作为冲突处理，用户难以回看旧报告。推荐排序、明确选择两套房源和按需重新生成，需要形成同一条业务链并使用一致的条件、来源与权限。

## What Changes

- 经纪人代客户操作，按预算、区域、房型等条件筛选有权使用的在售房源，先展示推荐顺序和理由，再明确选择两套生成对比。支持临时条件及已建档客户需求。
- 符合当前筛选和使用权限的在售房源均可参与，选择来源可以是推荐列表或受权的房源筛选结果。
- 对比事实继续使用已存来源快照，卡片、表格和确定性指标同源。推荐表达关联其依据和条件。
- 旧报告保留原版，打开时检查来源变化，显示简短提醒。用户明确选择重新生成后按当前事实创建后续报告，原报告继续可查。
- 重新生成时若原房源已下架，保留旧报告并提示当前无法重新生成，由用户另起一次对比。
- 复用共同 A2A、会话产物、Memory 及客户运行态需求的契约，补充本域输入、结果与验收场景。

## Capabilities

### New Capabilities

- `recommendation-pair-comparison` 定义筛选推荐、选择两套、生成报告及历史报告按需重新生成。

### Modified Capabilities

- `listing-comparison-cards` 明确历史卡片来自原快照，报告来源变化以简短提示随读取返回。

## Impact

- `listing-master-service` 的对比创建、读取、存储、来源检查和 PDF 入口。
- `agent-service` 的推荐呈现、两套选择、历史产物与统一 A2A 编排。
- 复用 `align-customer-matching-boundaries` 的硬条件、推荐理由、完整符合优先排序，以及 `align-listing-marketing-workflows` 的统一信封。
- 与已有 `consolidate-listing-comparison-domain` 衔接，保留已建立的来源授权和报告归属。
