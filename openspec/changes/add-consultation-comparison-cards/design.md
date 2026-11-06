## Context

买方咨询（`implement-internal-listing-business` 已交付）与对比能力（`consolidate-listing-comparison-domain` 已迁入房源域）目前互不关联：对比报告以 owner 隔离、版本绑定，但咨询记录不知道"这次咨询做过对比"；报告响应以行导向 rows + Markdown 表格呈现，前端卡片式 UI 无结构化数据可渲染。动机见 proposal；两份 specs 定义行为（`listing-comparison-cards`、`inquiry-comparison-association`）。

现有基础：对比报告存储完整来源快照（sources 含每套房源事实与版本），读取时复验 owner/逐套读取权/当前版本；`listing-agent` 已有 `listing-search` 与 `listing-comparison` 技能；Supervisor 已有跨域技能先例（`listing-compare-marketing-draft`）；`customer.inquiry.write.own/.all` 操作码已登记（迁移 690）。

## Goals / Non-Goals

**Goals:** 以最小改动让"咨询 × agent 对比 × 卡片呈现"闭环——卡片投影零新逻辑、关联单向轻量、技能复用现有域能力、前端拿到纯数据契约。

**Non-Goals:** 不新增对比计算维度或规则；不修改对比过时/权限复验语义；不在卡片中放 agent 观点、匹配评分或 UI 语义；不做买方直接登录读报告（owner 是经纪人）；不在客户域缓存报告内容；不改 Auth 协议（无新操作码）。

## Decisions

### 1. 卡片是读取时投影，不是新计算也不是新存储

`cards`/`highlights`/`metrics` 在报告读取时从已存储的 sources（每套快照事实）与规则计算结果投影得出：每套房源一张卡（listingRef、revision、contentHash、标题、事实字段、unknownFields、highlights），跨卡 metrics（总价最低/面积最大/单价最低等确定性指标）。同一 `rulesVersion`、同一输入，卡片与 rows 不可能不一致；存量报告无需迁移自动获得卡片。相比"生成时存储卡片"：避免迁移与新旧格式并存；相比"前端自行转换 rows"：单位/未知字段/徽标语义由服务端一次做对，前端零逻辑。

### 2. 引用方向：客户域存 reportId（消费方存引用惯例）

`customer_inquiry_comparison(inquiry_id, report_id, created_by, created_at)`，`UNIQUE(inquiry_id, report_id)`、`INDEX(inquiry_id, created_at)`。与收藏表存 `listingId` 同构：报告不感知咨询，客户域不复制报告内容。关联清单（reportId+时间）随咨询读取返回；正文（含卡片）由前端按需经对比读取接口拉取——天然复用 owner/逐套权限/版本复验，报告过时或不可读如实呈现，客户域无需同步任何状态。

### 3. 关联时做一次报告可读核验，读取时不重复核验

attach API（`POST /customers/inquiries/{inquiryId}/comparison-reports`）校验：咨询归属（`customer.inquiry.write.own/.all`）+ 报告可读（经签名上下文调房源域报告读取元数据，owner 必须是当前经纪人）。防止挂入不存在/非本人的 reportId。读取咨询时只返回关联清单不再逐条核验——正文获取时的现有复验已覆盖"报告后来变得不可读"的场景（如实呈现不可读/过时），无需在清单层重复跨域调用。

### 4. 技能编排：Supervisor 新技能，listing-agent 零改动

新增 Supervisor 技能 `consultation-listing-comparison`（参照 `listing-compare-marketing-draft` 模式）：①取买方上下文（客户档案偏好/咨询记录/经纪人输入，不虚构）→ ②listing-agent `listing-search` 得版本化候选 → ③选 2–6 套（偏好匹配度优先，超 6 套与用户确认）→ ④listing-agent `listing-comparison` 以真实 refs+expectedRevisions 对比 → ⑤以卡片结构原样透传 + 文字引导 → ⑥有咨询记录时关联 reportId。硬约束：禁止凭记忆构造房源/版本；版本冲突重搜一次仍冲突则如实告知；权限/依赖失败明确上报不降级；agent 观点只写文字不进卡片。listing-agent 复用现有两个技能，无新域内技能。

### 5. 前端契约：一个数据结构，两种传输

卡片结构（cards + metrics + conclusion + 版本标识，字段见 specs）是唯一契约。传输方式 A：agent 回答内嵌结构块（`{type, reportId, cards, metrics, conclusion}`），即时对话渲染；方式 B：回答携带 reportId，前端调 `GET /listings/comparisons/{reportId}`（响应含卡片）按需拉取，适合历史回看与需复验场景。两式数据同构；前端 UI（卡片布局/徽标样式/未知呈现）完全由前端设计，数据不含 UI 语义。

### 6. 权限与迁移全部复用或最小新增

权限：关联用已登记的 `customer.inquiry.write.own/.all`；对比创建/读取沿用 `compare.report.create`/`compare.report.read.own` + 逐套 `listing.read`。迁移：仅一个 bk_customer 受管迁移（关联表）+ manifest/check 登记；卡片投影无数据库变更。

## Risks / Trade-offs

- [卡片与 rows 漂移] → 同源投影（同一存储快照与规则版本）+ 一致性测试（同报告两投影逐字段比对）。
- [报告被删/不可读后关联悬空] → 读取按需拉取自然暴露不可读；关联清单保留历史事实（曾对比过），不静默清除。
- [agent 编造卡片或往卡片加观点] → 技能硬约束"原样透传、观点只进文字"+ 卡片结构无自由文本字段（除标题/结论等报告既有字段）。
- [attach 与报告创建之间的竞态（报告被删）] → attach 时核验 + 读取时按需复验双层兜底；不承诺跨域原子。
- [内嵌结构块依赖聊天协议支持结构化内容] → 方式 B（reportId 句柄）作为不依赖协议改动的保底传输，契约相同。

## Migration Plan

1. 核对对比报告存储/读取路径与响应模型、报告 store 的 sources 结构、技能装配点与前端契约文档位置。
2. 实现卡片投影（读取时计算）+ 测试（3 套报告、未知字段、单价缺省、旧报告兼容、两投影一致性）。
3. bk_customer 关联表受管迁移（manifest 追加 + check：表/列/唯一键断言）+ attach/清单 API + 测试（归属/可读核验/幂等/多报告/越权）。
4. Supervisor 技能文档 + 装配测试（技能可被发现、步骤落到 listing-agent 能力、失败关闭）。
5. 前端契约文档（结构字段 + 两式传输）。
6. 端到端验收：咨询 → agent 匹配 → 对比 → 卡片回答 → 关联 → 历史回看拉取卡片；版本冲突与越权负例。

## Open Questions

聊天协议是否已支持结构化内容块（方式 A 的传输载体）由前端/网关侧确认；不影响本变更的数据契约与方式 B 保底路径，技能与接口先行交付。
