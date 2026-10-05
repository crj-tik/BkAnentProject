# Supervisor 改造验收记录（2026-10-05）

当前记录随验收继续补充，未通过的项目不视为已验收。

## 环境

- 独立 Docker Nacos `nacos/nacos-server:v3.1.0`，本机 28848/29848 映射 HTTP/gRPC；独立 MySQL `mysql:8.4`，本机 23306，数据库 bk_agent。
- Java 17；Spring AI 1.1.2，Graph/A2A Starter 1.1.2.3，Nacos SDK 3.1.0。
- 本机既有 Kafka 未修改。数据库凭据仅保存在系统临时目录的验收配置文件中，不写入仓库。

## 已验证

`NacosAgentRegistryDockerTest` 使用真实 SDK 发布 Card 与 endpoint，通过 NacosAgentCardProvider 获取实际描述、skills 和 URL，并由官方 A2AClient 完成 HTTP JSON-RPC 调用；检查远端 Task/Artifact ID、恰好一次请求和 HTTP Card fallback。HTTP 目标是确定性协议夹具，模型及房产业务服务没有在此测试中被冒充。

`SupervisorDockerDatabaseTest` 使用真实 MyBatis mapper、MySQL 调用账本和数据库 checkpoint：明确技能首轮加载→request_input 等待→关闭原 graph/saver→更改注册技能→新 runner 续接。验证旧正文、原 run、累计轮次和 input requestId 幂等保持；其他 runnerVersion 不读取此 timeline；两个 store 只有一个能取得同 run 租约。`ApprovalClaimDatabaseConcurrencyTest` 验证真实 MySQL 审批唯一键只允许一个并发 claim。

普通回归覆盖无内部回调的单轮模型适配、范围外拒绝、控制混批全拒绝、参数/schema/权限检查、显式与 AUTO 同循环、范围内重排与提前结束、审批前零动作与变参重新审批、同会话新请求不继承、待输入取消、并行工具预算、模型超时、SSE 故障不污染已完成事实、迟到结果对账不重发。

重启验收发现的 checkpoint 顺序问题已修复（KI-21，c2f5a4c）；外部 Card 省略布尔字段的上游 SDK 限制记录为 KI-22。

## 2026-10-05 混合协议与九服务验证

九个服务的实际 OfficialA2aAgent/ReAct/Executor 装配测试验证了 Card 发布、显式首轮正文、父技能不继承及版本错时零模型调用；营销草稿实际绑定 searchContents，只保留只读参考能力。访谈现有状态机、话轮决策、导演指令、质量门与归档等回归全部运行。

`SupervisorDistributedAcceptanceTest` 使用真实 Nacos Naming/Agent Registry、官方 HTTP A2A/MCP 客户端与真实 MySQL mapper。确定性模型依次请求缺失地区、提出 A2A 找房、MCP 对比和显式 marketing-draft 委托；每次实际业务调用审批前均零执行。暂停新接收后原 run 仍可续接，两次重建 runner 后累计事实保持，每个业务回调恰好执行一次。数据库留有原远端关联、三份主/衍生产物及 owner/技能/调用标识；真实事件审计按 eventId/sequence 回放。两个 MCP 连接发布同名工具仍不覆盖，撤销其中一个后目录立即移除。此验收的 HTTP 服务为确定性协议夹具，业务后台及模型生成能力分别验证，不冒充九生产实例全部联调。

注册扩展丢失及 SDK 被 BOM 降级的问题已在共享发布、发现与根依赖管理中修复（KI-25/26），实际网络验收确认营销显式选择包含 owner/version/hash。

## 2026-10-05 旧 runner 数据库恢复

`LegacyRunnerDockerRecoveryTest` 的 `legacy-4f56e0a9-5424-4d83-bc08-34c6bb276568` 在旧 graph 产生待审批 checkpoint，重建 facade/graph 后仍走旧 timeline 和原 Agent；批准与重复回调最终仅执行一次。完成后再重建读取原结果，新请求进入 llm-tools-v1。原 Agent 撤销时失败且零业务执行，跨 owner 查询被拒绝。连同六个既有官方图回归，本轮七项通过。旧业务执行是计数夹具；生产旧 run 排空尚无证据，旧远端提交未知结果不能盲目重发。

## 2026-10-05 滚动升级与暂停回滚

`NacosRollingUpgradeDockerTest` 的 `rolling-listing-5ad38dc9-7801-4d51-a74b-d9835a90ab2a` 实际发布版本 0（不支持显式契约）并验证显式调用零发送；版本 0 接受一个工作中的远端 Task。随后通过共享发布组件注册版本 1 的技能扩展和新 endpoint，再刷新 Supervisor 目录，新显式调用使用新地址并执行一次。旧 Task 的查询和取消仍到版本 0 原地址，原消息仅发送一次。实际 Nacos/HTTP 客户端通过，服务端为协议夹具。

新 runner 的恢复与暂停回滚使用混合协议验收：先升级服务端发布契约，再创建 Supervisor；设置 accepting=false 后新请求拒绝，已有待输入/审批 run 仍恢复，两次重建 graph 后调用各一次。旧 runner 的原图审批恢复见上一节。这是本机 Docker 的契约、地址与持久化演练，不代表九生产实例及全部业务后台已滚动部署。

## 尚需实际环境信息

生产旧 run 排空尚无证据；删除范围与核对方法见 [旧图清理范围](supervisor-legacy-cleanup.md)。九生产实例与各业务后台全量联调未在本地协议夹具验收中声称完成。真实模型评估单独记录，不能证明 Markdown 顺序的强保证（KI-19）。

## 真实模型评估

模型为本机 GPU 上的 `qwen3:4b-instruct-2507-q4_K_M`，完整 digest `0edcdef34593eac1aa2be9c7d06c432dcf81945adca5eca2f27662c18f168ba0`，Ollama 0.35.1，RTX 5070 Laptop 8 GiB。通过 Spring AI DeepSeek API 的 OpenAI 兼容协议连接本机 21434；使用生产 SupervisorModelTurn、SupervisorToolLoopGraph 和 SupervisorCapabilityCatalog。temperature=0，输出上限 1200 token，上下文 16384，SDK 重试与 Graph 模型重试在本次评估中均为 0。房源/对比/草稿和合同业务响应是数据夹具，不评估真实业务后台检索或 Subagent 模型质量。

最后一轮的全部可见输出、参数和实际回调记录见 [原始评估 JSON](acceptance/supervisor-real-model-20261005.json)。模型文本输出不是平台规则，测试仅确认真实推理已运行；不把八个 COMPLETED 状态等同于所有输出质量通过。

| 请求 | 状态/模型轮次 | 实际业务回调 | 总时延 | 输入/输出 token |
| --- | --- | --- | --- | --- |
| 问候 | COMPLETED / 1 | 0 | 9.045s，含冷加载 | 2930 / 14 |
| 不要通知，只分析合同 | COMPLETED / 2 | contract 1 | 3.153s | 6179 / 140 |
| AUTO 明确委托合同 Agent | COMPLETED / 2 | contract 1 | 4.713s | 6194 / 230 |
| AUTO 自主加载普通合同指引 | COMPLETED / 3 | skill 成功加载后 contract 1 | 4.438s | 7065 / 178 |
| 显式找房→对比→草稿，第一次 | COMPLETED / 5 | listing / compare / marketing 各 1 | 9.598s | 17102 / 572 |
| 同样请求创建独立新 run | COMPLETED / 5 | listing / compare / marketing 各 1 | 8.681s | 17101 / 571 |
| 缺失参数→补充输入 | WAITING_USER_INPUT→COMPLETED / 累计 7 | listing / compare / marketing 各 1 | 11.772s，不含人工等待 | 21990 / 724 |
| 指定技能但本次只问候 | COMPLETED / 1 | 0 | 1.082s | 2618 / 3 |

合计 26 次成功模型调用、12 次业务回调、81179 输入 token 与 2432 输出 token。单模型调用最大 9.024s、此小样本 P95 为 2.945s；不能推断生产 P95。调用本机模型，没有外部 API 账单；电费、设备折旧未测量，不能将总成本写成零。

调用选择和恢复行为：没有通知误调用；三组过程请求均依据实际候选 101/102 对比并生成 101 草稿，业务调用没有遗漏或重复，没有发布操作。显式技能仍理解自然语言预算、区域、户型和平台；缺失条件以 request_input 等待，补充后继续原 run。指定过程技能也允许本次只问候并零业务动作，符合第一种方案的软流程边界。

记录的偏差：明确委托合同请求的最终回答中出现“明显偏向买方”，与其引用的不对等责任结果自相矛盾；缺失参数续接中两次把工具别名当技能名尝试加载，平台拒绝后模型自行改正，增加两轮；预算 context 出现数值 300 但未标明单位，instruction 仍为“300万”。其他探索轮次曾重复追问已给出的浦东条件、编造子技能名/版本，并在草稿增加未返回的交通、配套及首付信息；最终回归未重现所有偏差，也不能据此消除风险。身份错误的 preflight 已修复为无副作用的可纠正错误（KI-28）；内容偏差保留为 KI-19/29，不新增步骤检查器。

预算决定：维持默认 max-rounds=12、max-tool-calls=24、max-concurrency=4、max-queued-calls=128，模型超时 30000ms、工具超时 120000ms、生产同模式 model-retries=2。最高观察 7 轮，保留纠错余量；确定性回归覆盖并发与额度耗尽。真实服务吞吐、工具尾延迟和生产 DeepSeek/DashScope 质量尚未由此小样本证明，不能据此提高并发或收紧超时。

复现模型服务时设置 `OLLAMA_CONTEXT_LENGTH=16384`、`OLLAMA_NUM_PARALLEL=1`、`LLAMA_ARG_CACHE_RAM=1024`，映射仅本机 `21434:11434`，保留模型卷。首次不限制缓存的一轮曾发生进程 signal: killed / HTTP 500（KI-29）；限制后实际日志确认 1024 MiB，最终八组完成且没有重试掩盖失败。测试使用环境变量 `BK_AGENT_EVAL_MODEL_URL` / `BK_AGENT_EVAL_MODEL_NAME`，默认未配置时跳过，不在普通 CI 强制下载模型。

## 最终验证结果

2026-10-05 09:44–09:46：本轮实际生成的 13 模块 Surefire 报告共 **330 项测试，0 失败、0 错误、0 跳过**（不计 target 中历史遗留报告）；其中真实模型用例包含上表八组请求。全模块 Maven compile 通过，OpenSpec 全部 18 项严格校验通过。

本轮记录：`mixed-651f1a48-dd12-4926-9c3b-5bc43d947116`、`legacy-cff9911c-2824-42a9-ab0f-4a436710c322`、`rolling-listing-baff2c14-d8ed-4d1e-839f-24bd05947c84`。每项验证的实际边界如上述说明，未对无远端幂等支持的未知提交承诺 exactly-once。
