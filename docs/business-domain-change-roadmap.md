# 业务域落地与独立 Change 清单

日期：2026-10-25。本文记录本轮规划，所有实现任务尚未执行；合并、拆分、迁移和运行验收均以各 change 的 tasks/证据为准。

## 用户目标与输入事实

- 首批用户为知识运营人员和店长，目标是房源维护、带看及成交推进提效，并辅助业绩增长；新人带教不在首期范围。
- 当前经营数据为模拟数据；房源经接口接入；逐字稿由访谈业务域提供。具体真实接口 endpoint/密钥不在本轮规划中预设。
- Auth 由另一 AI 会话完善。此批方案只消费其可信身份及组织/资源范围，缺少可信能力时拒绝访问，不另建角色/Token 协议。
- 每项结构调整独立 change、独立验收和归档。本文只做导航，不是承载全部实施任务的总 change。

## 合并与拆分决定

| 独立 change | 结构调整 | 主要业务成果 | 顺序与依赖 |
| --- | --- | --- | --- |
| [consolidate-listing-comparison-domain](../openspec/changes/consolidate-listing-comparison-domain/proposal.md) | `compare-engine-service` 能力迁入 `listing-master-service`，退役独立 compare 新入口 | 接口房源、当前授权快照、同域对比及报告 | 首期基础；与知识运营可分别实施 |
| [extract-knowledge-operations-domain](../openspec/changes/extract-knowledge-operations-domain/proposal.md) | 新增 `knowledge-service`；运营案例/打法从访谈职责中拆出，访谈保留运行面和逐字稿真源 | 单案例提炼、人工审核发布、版本/范围/撤回 | 首期基础；与房源合并无实现依赖 |
| [implement-store-business-review](../openspec/changes/implement-store-business-review/proposal.md) | 保留 `business-service`，增加经营复盘与推进事项；保留 `trade/trade-agent` 技术身份 | 带看停滞梳理、房源维护缺口、确认动作、反馈观察 | 首期落点；集成依赖前两项公开契约与 Auth 范围适配 |
| [consolidate-marketing-promotion-domain](../openspec/changes/consolidate-marketing-promotion-domain/proposal.md) | `promotion-service` 迁入 `marketing-content-service`，保留 `marketing-agent` | 内容/素材/账号版本、发布回执、效果来源 | 后续独立交付；不是经营复盘的前置条件 |

每项均有 proposal、design、specs、tasks。新能力规格路径互不重叠；共享源码（common、部署清单、身份登记）在各 change 实施时按当前分支增量集成，不能把另一 change 的任务顺手实现或勾选。

## 保留的职责边界

- `interview-service`：访谈会话、话轮状态机、脱敏逐字稿、现有原始报告；新增知识域只能经公开来源契约读取，不跨库抓取原稿。
- `customer-service`：保留客户/跟进等当前责任；本轮不将客户需求、带看与成交源状态搬入经营复盘。经营侧首期以明确 fixture 端口取得模拟事件，后续真实业务过程扩展另起 change。
- `contract-service` 与 `settlement-service`：保留独立生命周期/账务责任；现阶段没有足够业务依据进行物理合并，交易协同待真实流程明确后独立提案。
- `media-worker-service`、`notification-service`：保留异步媒体/消息适配，作为业务能力消费，不因内容或经营归并一同搬迁。
- Supervisor、Memory、RAG：继续承担通用协调、获准记忆和检索基础设施。业务状态、知识发布和外部操作回执仍归各自领域。

## 独立交付与兼容约束

1. 建议先分别实施知识运营拆分和房源合并，再接通经营复盘；营销合并可独立排期。经营复盘可先使用契约夹具开发，但依赖未就绪时不能将跨域集成验收标为完成。
2. 现有 `implement-studio-v2` 保留自己的 8 项未完成任务。新 change 提供领域契约/安全结构化结果，不复制其 work/command/文件/发布门面，也不宣称前端已联调。
3. 合并不会把历史 `compare/compare-agent` Memory 改为 listing 身份；新 knowledge Agent 使用自己的精确服务身份。保留旧账本、来源/版本及在途任务关联，按当前授权引用。
4. 各域 DTO 约定来源、版本、观察时间与数据模式字段，但各自拥有契约；避免多个 change 同时定义同名共享类型。需要单独抽取公共来源基础设施时另起独立 change。
5. 模拟、真实、混合和未知来源在结果中可辨识；只有全部获准来源被确认真实时才标 REAL。模拟功能验收不作为真实增长或生产 Memory 结果证据。

## 验收口径

首期以模拟房源接口、模拟经营事件及访谈域提供的逐字稿验证完整功能：来源可追溯、知识可审核、建议有依据、动作有回执、撤回立即影响后续使用。真实接口权限、真实经营效率与业绩变化，以及浏览器工作台端到端，分别按后续实际证据报告，不能用静态代码、任务数量或健康检查替代。

关联：LR-3/6/8/22/23/53/54/59/79/90/94/96/97/99/100/101/102；KI-15、KI-114、KI-115、KI-128、KI-166、KI-167。规划不关闭这些已有问题。

## 本轮规划交付记录

四个 change 均已具备 proposal、design、specs 和 tasks，共 6 份新增能力规格、69 项未执行的实施任务。每项结构调整各自创建中文规划提交：房源 `5801295`、知识 `d257cbd`、经营 `8cad8fd`、营销 `2b7dbe2`，均推送至 `origin/dev-fix`。

`openspec validate --all --strict --no-interactive` 验证 5 个活动 change（含既有 Studio）及 20 份主规格，共 25 项通过；文件完整性、任务编号、占位内容和本地链接检查通过。该记录只证明规划材料齐备；本轮没有实施 Java/配置/SQL 变更、运行 Maven 测试、迁移数据库或部署服务。
