# 房源与对比合并为房源经营域

## Why

`compare-engine-service` 已通过签名授权读取房源，并按 owner 和快照指纹隔离报告缓存（KI-115 已修复）；但比较、报告生命周期和房源来源仍分属不同服务。当前 `listing-master-service` 的主要事实模型仍是本地 `listing_info`，尚无统一的上游接口来源契约。将比较纳入房源域并引入稳定来源引用、版本核验和接口适配，可让检索、比较及历史报告共享同一授权事实边界。

## What Changes

- 将现有比较计算、报告缓存及 PDF 能力迁入 `listing-master-service` 的 `comparison` 包，通过 `listing-agent` 的 `listing-comparison` 技能提供；Studio 保留其工作区比较工件、版本校验和回执语义。
- 为房源事实建立统一来源端口，明确支持 `legacy-local`、`fixture` 和 `http` 适配器。现有本地房源映射到 `legacy-local`；模拟和真实接口适配都返回稳定来源、版本、观察时间、金额单位和服务端确认的 `dataMode`。
- **BREAKING**：不再为新请求发布 `compare-agent` 或独立比较 MCP/RPC 能力；迁移 Supervisor 等调用方。旧 `/compare/**` HTTP 路径可暂留为要求当前授权的兼容入口，旧 Long ID 仅映射到 `legacy-local`，不作为跨来源标识。
- 比较缓存、历史报告、分享和 PDF 输出都要核验当前用户、逐项来源读取权及当前来源版本。分享码仅定位报告，不授予读取权；既有匿名公开分享按退役/私有化迁移。
- 比较报告沿用现有 JSON 元数据和 PDF 文件；迁移时保留 `compare-report-data` 卷及历史文件，切换为单一写入方。
- 配置随服务迁移同步：为 Listing 来源接入和 Auth 授权映射补齐所需配置；按切换阶段更新本地 `.env`、`.env.example`、配置模板、Nacos 与 Compose 中的新增、迁移和废除项。旧 Compare 服务的配置和身份只在旧任务排空后退役；保留报告卷与历史记忆读取所需配置，不提交密钥。
- 保留旧报告和 `compare/compare-agent` 的历史 Memory 身份；不重写旧 owner/domain/agent，不将结果未知的旧调用转投新 Agent。
- 本 change 只处理房源与比较合并；知识运营、门店复盘和营销合并由各自 change 交付。
- 按 `domain-parallel-v1` 实现房源生产者契约，在独立 worktree/分支以自有来源、Auth/Memory 测试夹具完成本域验收；不要求经营或营销消费者先交付。

## Capabilities

### New Capabilities

- `listing-business-source`：接口接入、模拟标记、来源身份、授权与版本复核，以及上游事实和本地维护记录的边界。
- `listing-comparison`：同域授权比较、报告生命周期、来源失效处理和旧比较能力退役。

### Modified Capabilities

无。复用现有动态能力目录、技能契约、Auth 授权协议与必需 Memory 生命周期；只增加/调整本领域需要的配置，不改通用协议或默认角色授权。

## Impact

Supervisor 入口也属于本 change 的验收范围：验证动态能力目录、显式技能选择和真实 A2A fixture 调用均落到房源域比较能力，能力不可用时不得静默回退到旧 MCP/RPC。

主要影响 `listing-master-service`、`compare-engine-service`、`common`、Supervisor/Studio 比较调用方，以及 Maven、Compose、Nacos、`.env.example` 和本地 `.env`。沿用 LR-22/23/54/79/96/97/103/139/140/142/149；KI-115 已修复，本 change 迁移其签名读取、owner 隔离和来源指纹保护，并补充历史读取时的当前版本复核。Auth 协议由其他会话维护，本 change 使用现有签名上下文和资源范围契约，不另建认证协议；Memory 复用 Listing Agent 已登记的必需生命周期，不新增历史记忆身份。与 `implement-studio-v2` 保持只读比较契约兼容，不接管其余任务。

执行方式：四会话之一，可在共同基线上独立完成全部本期实施任务，发布冻结的房源 v1 HTTP 契约和本域验证证据。为 `.env`、`.env.example`、配置模板、Nacos 与 Compose 维护逐项配置矩阵，标明新增、迁移、保留、废除及生效门槛；本地 `.env` 只做保密值保留式更新，不纳入提交。共享 Nacos 发布和真实环境切流/排空由合并后的协调门禁核验，不作为本会话等待条件。具体上游 endpoint、字段映射和授权参数仍是部署接入信息，真实接口未验证时如实报告。并行边界见 `docs/business-domain-change-roadmap.md`。

## 功能完整性约束

合并后的房源经营域必须同时保留房源管理、检索、摘要、状态、资产/OCR/核验和 RAG 能力，以及比较域原有的比较、历史报告、受权分享和 PDF 结果。新旧 API 或工具名可以变化，但每项旧功能都必须映射到目标 API、Agent/MCP 能力或明确的兼容入口，并完成已知调用方迁移。分享码仅定位报告，不单独授予读取权；接收人失去来源读取权限后，报告正文与 PDF 也必须拒绝。任何源功能没有目标能力、调用方迁移或验收证据时，不得退役旧服务或宣称功能合并完成。
