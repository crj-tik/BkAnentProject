# 房源与对比合并为房源经营域

## Why

当前 `compare-engine-service` 读取 `ListingDTO` 后生成指标、结论及报告，没有独立的业务事实来源；独立部署反而增加授权、缓存和 Agent 身份边界，旧比较读取还受 KI-115 限制。房源数据将通过上游接口接入，适合由一个房源域统一取得获准快照并完成检索、对比与资料诊断。

## What Changes

- 将比较计算、报告及 PDF 能力迁入 `listing-master-service`，通过 `listing-agent` 的独立技能提供；房源经营单元负责接口来源、快照和派生结果。
- 新增统一房源输入端口与 HTTP/模拟适配器。所有房源保留来源系统、上游 ID、版本或内容 hash、观察时间、金额单位和 `dataMode`；初期使用模拟数据验证同一契约。
- **BREAKING**：停止为新请求发布 `compare-agent`，退役独立比较服务和无凭据比较 RPC；旧 HTTP 路径仅可作为经过当前授权的迁移适配入口，裸分享码不授予读取权。
- 比较结果和 PDF 绑定当前授权房源版本；来源更新、撤权或不可达时按明确策略返回过时或不可用，不能以缓存冒充当前事实。
- 保留旧报告及 `compare/compare-agent` 的历史 Memory 身份；不重写历史 owner/domain/agent，不将未决调用转投新 Agent。
- 本 change 只处理房源与比较合并；知识运营、门店复盘和营销合并由各自 change 交付。
- 按 `domain-parallel-v1` 实现房源生产者契约，在独立 worktree/分支以自有来源、Auth/Memory 测试夹具完成本域验收；不要求经营或营销消费者先交付。

## Capabilities

### New Capabilities

- `listing-business-source`: 接口接入、模拟标记、来源身份、授权与版本复核，以及上游事实和本地维护记录的边界。
- `listing-comparison`: 同域授权比较、报告生命周期、来源失效处理和旧比较能力退役。

### Modified Capabilities

无。复用现有动态能力目录、技能契约和必需 Memory 生命周期，不更改它们的通用要求。

## Impact

主要影响 `listing-master-service`、`compare-engine-service`、`common`、比较调用方、Maven/Compose/Nacos/网关部署清单和相关技能。沿用 LR-22/23/54/79/96/97，处理 KI-115 的读取及分享边界；不会据规划关闭该问题。Auth 由其他会话完善，本 change 通过其最终可信身份与资源范围契约接入，不另建认证协议。与 `implement-studio-v2` 保持只读比较契约兼容，不接管其余任务。

执行方式：四会话之一，可在共同基线上独立完成全部本期实施任务，发布冻结的房源 v1 HTTP契约和本域验证证据。共享配置只提交自身最小差异；真实环境切流/排空由合并后的协调门禁核验，不作为本会话等待条件。具体上游 endpoint、字段映射和授权参数仍是部署接入信息，真实接口未验证时如实报告。并行边界见 `docs/business-domain-change-roadmap.md`。
