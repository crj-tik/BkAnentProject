# 分布式运行可靠性约定

## Supervisor SSE 跨实例

`SseEmitter` 仍只保存在持有浏览器连接的 Agent 实例内，不写入 Redis。Agent 事件先写入 `agent_event_audit`，随后通过 RocketMQ 通知各实例；消费者采用广播模式，每个实例只把事件交给本机的 SSE 订阅者。这样创建任务的实例和浏览器 SSE 所连实例可以不同，不需要 Gateway 粘性路由。

RocketMQ 通知用于低延迟实时分发，数据库审计记录是补发来源。消息发布失败不会中断业务执行；SSE 空闲达到 15 秒时会从审计表按游标补读，客户端重连仍可使用已有的 `Last-Event-ID` / `afterSequence` 回放。订阅回放按每轮最多 10,000 条分页，后续由下一轮通知或空闲补读取完。

多实例部署必须使用 `agent.distributed.stream.provider=rocketmq`（Nacos 配置默认值及 Docker Compose 已设置为 `rocketmq`）。`memory` 仅适合单实例。各 Agent 实例需要连接同一 RocketMQ topic，并使用相同 consumer group；RocketMQ listener 显式使用 `BROADCASTING`，不能改回默认的集群负载均衡模式。

## A2A 异步子任务轮询

子 Agent 的异步提交、单次状态请求和提交后的总等待时长均有边界。默认值如下，可通过 Nacos 环境占位符调整：

| 配置 | 默认值 | 作用 |
| --- | ---: | --- |
| `AGENT_A2A_CHILD_TASK_SUBMIT_REQUEST_TIMEOUT_MS` | 15,000 | 限制发送异步任务并等待接受回执的单次请求 |
| `AGENT_A2A_CHILD_TASK_POLL_REQUEST_TIMEOUT_MS` | 15,000 | 限制一次 `getTask` 状态查询 |
| `AGENT_A2A_CHILD_TASK_TIMEOUT_MS` | 300,000 | 子任务接受后，等待其进入终态的总时限 |
| `AGENT_A2A_CHILD_TASK_INITIAL_POLL_INTERVAL_MS` | 1,000 | 首次状态查询间隔 |
| `AGENT_A2A_CHILD_TASK_MAX_POLL_INTERVAL_MS` | 10,000 | 指数退避间隔上限 |
| `AGENT_A2A_CHILD_TASK_POLL_BACKOFF_MULTIPLIER` | 1.5 | 每次非终态轮询后的退避倍率 |
| `AGENT_A2A_CHILD_TASK_POLL_JITTER_PERCENT` | 20 | 查询间隔抖动百分比，避免实例同时集中轮询 |

状态查询运行在有界线程池；单次查询超时会取消 Future 并在总时限内退避重试。总时限到达后会发出 `a2a.async.timed_out`，作为不可重试的子任务终态，并在后台尝试调用 A2A `cancelTask`。如果异步提交请求超时，远端是否已经接受无法确定，系统会将其标为 `A2aTaskSubmissionOutcomeUnknownException`，禁止自动重提以避免重复执行；A2A metadata 会继续携带原请求的幂等键。

`agent.distributed.async-runtime.max-attempts` 仍只控制 Supervisor 外层异步任务失败后的执行重试，不是 A2A 状态轮询次数。

## Token 撤销共享

分布式 `auth-service` 将令牌 SHA-256 摘要写入共享 Redis，键默认以 `auth:token:revoked:` 为前缀，并按令牌剩余有效期设置 TTL；Redis 不保存原始 token。所有 Auth 实例在校验签名和有效期后都查询同一撤销键。Redis 读写失败会拒绝认证/令牌撤销操作（Gateway 对认证 RPC 不可用返回 503），不能降级为放行。

`auth-service` 分布式配置和 Docker Compose readiness 均依赖 Redis；`minimal` Compose profile 也会启动 Redis。只有 `application-local.yml` 使用进程内实现。由于旧版本的撤销摘要仅存在于旧 Auth JVM 内存，升级时无法迁移；如需保证升级后先前已登出的 token 立即全部失效，应在切换到 Redis 撤销存储时轮换 `AUTH_TOKEN_SECRET`，代价是所有现有 token 需要重新登录。
