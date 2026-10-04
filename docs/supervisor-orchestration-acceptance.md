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

## 待验收

九生产业务服务完整分布式联调、真实模型评估及完整滚动升级/回滚演练仍跟随 OpenSpec 清单。真实模型评估须使用可调用模型并记录型号/版本、请求、偏差、轮次、时延、token 与成本；确定性测试不能替代这一项，也不能证明 Markdown 顺序的强保证（KI-19）。
