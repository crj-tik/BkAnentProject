## Why

买方咨询过程中，agent 匹配多套房源后需要向买方呈现对比。对比能力本身已存在（房源域报告/PDF/版本复验），但存在两个缺口：对比报告与咨询记录没有任何关联（无法回答"这次咨询对比了哪几套房"）；报告以 Markdown 表格呈现，前端卡片式对比 UI 缺少结构化数据支撑，agent 回答只有文字。

## What Changes

- 对比报告读取响应增加**卡片结构投影**（`cards` + `highlights` + `metrics`）：每套房源一张卡、金额/面积显式单位、未知字段显式列出、徽标来自确定性指标。投影在读取时从已存储的来源快照计算，**零新增对比逻辑**，存量旧报告自动获得卡片视图。
- 客户域新增**咨询-对比报告关联**：`customer_inquiry_comparison` 表存 `reportId` 逻辑引用（沿用消费方存引用惯例）；关联 API 校验咨询归属与报告可读性（报告 owner=当前经纪人）；咨询读取带出关联清单，报告正文按需拉取并复验现有 owner/权限/版本语义。
- 新增 Supervisor 技能 `consultation-listing-compare`：编排"买方上下文 → listing-agent 搜索（版本化候选）→ 选 2–6 套 → listing-agent 对比（真实 refs+revisions）→ 以卡片结构返回 → 关联咨询"。listing-agent 复用现有 `listing-search`/`listing-comparison` 技能，卡片数据原样透传，agent 观点只出现在文字部分。
- 提供前端契约文档：卡片数据结构字段说明 + 两种传输方式（回答内嵌结构块 / reportId 句柄按需拉取），同一契约。
- 权限全部复用现有操作码（`customer.inquiry.write.own/.all` 已登记；对比创建/读取沿用 `compare.report.*` + 逐套 `listing.read`），无新 Auth 迁移（关联表本身需要一个 bk_customer 受管迁移）。

## Capabilities

### New Capabilities

- `listing-comparison-cards`: 对比报告的卡片结构投影——同源确定性投影、显式单位与未知字段、徽标来自确定性指标、存量报告兼容。
- `inquiry-comparison-association`: 咨询与对比报告的显式关联——关联校验、幂等与多报告语义、关联清单读取与正文按需获取分离。

### Modified Capabilities

无。不修改已归档主规范中的对比、授权或编排需求；技能编排为新增技能文档，不改变现有技能契约。

## Impact

涉及 `listing-master-service`（对比报告响应投影 + 测试）、`customer-service`（关联表迁移 + API + 测试）、`agent-service`（Supervisor 技能文档 + 装配测试）、`docs`（前端契约）。需要一个 bk_customer 受管迁移（`customer_inquiry_comparison` 表）与 manifest/check 登记。不修改对比计算逻辑、不修改 Auth 协议、不在卡片中引入 agent 观点或 UI 语义。
