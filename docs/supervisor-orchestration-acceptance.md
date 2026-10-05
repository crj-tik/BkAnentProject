# Supervisor 改造验收记录（2026-10-04）

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

## 待验收

## 2026-10-05 旧 runner 数据库恢复

`LegacyRunnerDockerRecoveryTest` 的 `legacy-4f56e0a9-5424-4d83-bc08-34c6bb276568` 在旧 graph 产生待审批 checkpoint，重建 facade/graph 后仍走旧 timeline 和原 Agent；批准与重复回调最终仅执行一次。完成后再重建读取原结果，新请求进入 llm-tools-v1。原 Agent 撤销时失败且零业务执行，跨 owner 查询被拒绝。连同六个既有官方图回归，本轮七项通过。旧业务执行是计数夹具；生产旧 run 排空尚无证据，旧远端提交未知结果不能盲目重发。

真实模型评估及存量旧 runner 的完整发布/回滚演练继续按 OpenSpec 清单。九生产实例与各业务后台全量联调未在本地协议夹具验收中声称完成。真实模型评估须记录型号/版本、请求、偏差、轮次、时延、token 与成本；确定性测试不能替代这一项，也不能证明 Markdown 顺序的强保证（KI-19）。
