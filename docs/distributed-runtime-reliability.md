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
| `AGENT_A2A_CHILD_TASK_POLL_REQUEST_TIMEOUT_MS` | 15,000 | 限制执行期间轮询及前端查询接口触发的一次 `getTask` 状态查询 |
| `AGENT_A2A_CHILD_TASK_TIMEOUT_MS` | 1,800,000（30 分钟） | 异步子任务接受后的总等待时限，也是同步/流式子 Agent 调用的最长等待时限 |
| `AGENT_A2A_CHILD_TASK_INITIAL_POLL_INTERVAL_MS` | 1,000 | 首次状态查询间隔 |
| `AGENT_A2A_CHILD_TASK_MAX_POLL_INTERVAL_MS` | 10,000 | 指数退避间隔上限 |
| `AGENT_A2A_CHILD_TASK_POLL_BACKOFF_MULTIPLIER` | 1.5 | 每次非终态轮询后的退避倍率 |
| `AGENT_A2A_CHILD_TASK_POLL_JITTER_PERCENT` | 20 | 查询间隔抖动百分比，避免实例同时集中轮询 |

同步 `sendMessage`、SSE `stream`、异步提交和状态查询都由有界线程池及各自时限约束；不支持流式能力时的同步回退也走同一时限。状态查询单次超时会取消 Future 并在总时限内退避重试。到达总时限后，Supervisor 会停止等待：异步任务会在已取得远端 taskId 时后台尝试 A2A `cancelTask`；流式请求只有在已经收到带 taskId 的事件时才能尽力取消。对于阻塞式同步 `sendMessage`，超时会中断本地等待，但响应前尚未取得远端 taskId，不能承诺远端执行也随之停止，因此子 Agent 仍应配置自己的执行时限。A2A SDK 的流式 API 也不提供可由调用方直接关闭的订阅句柄；所有迟到的流事件都会被丢弃。如果异步提交请求超时，远端是否已经接受无法确定，系统会将其标为 `A2aTaskSubmissionOutcomeUnknownException`，禁止自动重提以避免重复执行；A2A metadata 会继续携带原请求的幂等键。

上述 30 分钟是每一次子 Agent 调用/子任务的上限，不是跨多个串行子 Agent 的整个 Supervisor 工作流总时限；如果业务要求整个工作流也有统一总时限，需要另行设置工作流级 deadline 并在图节点间传播。

`agent.distributed.async-runtime.max-attempts` 仍只控制 Supervisor 外层异步任务失败后的执行重试，不是 A2A 状态轮询次数。

Supervisor 持久化任务/工作流的租约仍可配置（默认 300 秒），但 worker 会从成功认领时开始续租（包括本机执行队列等待期），并在实际执行期间持续续租；每个进程在配置的 worker-id 前缀后追加唯一实例标识，每次认领再生成单独的 lease token。完成、失败、续租和释放都会核对本次 token，旧执行不能覆盖重新认领后的结果。所有存活实例还会周期性回收已过期租约，因进程退出而遗留的 RUNNING 任务可以重新进入队列。

当前子 Agent A2A 调用是在 `SupervisorAsyncTaskService` 或 `SupervisorAsyncWorkflowService` 执行图的过程中等待结果；因此子 Agent 运行期间由包住整次图执行的 Supervisor 持久化任务/工作流租约负责续期，而不是给远端 A2A taskId 伪造本机 lease。回归测试分别覆盖这两条异步执行路径：阻塞中的 A2A 子 Agent 调用期间，外层任务和工作流租约都仍会续期。旧的 `CHILD_AGENT` 状态查询兼容分支仅用于查询已有远端任务镜像，不是当前任务认领/续租路径。

Agent 版本升级时应先从调度中摘除旧实例并等待其在途任务完成，再启动/启用新版本的租约回收器；旧版本不续租且使用固定 worker-id，混合运行期间不能保证旧版本长任务不会被新版本按过期租约重新认领。

## Token 撤销共享

分布式 `auth-service` 将令牌 SHA-256 摘要写入共享 Redis，键默认以 `auth:token:revoked:` 为前缀，并按令牌剩余有效期设置 TTL；Redis 不保存原始 token。所有 Auth 实例在校验签名和有效期后都查询同一撤销键。Redis 读写失败会拒绝认证/令牌撤销操作（Gateway 对认证 RPC 不可用返回 503），不能降级为放行。

`auth-service` 分布式配置和 Docker Compose readiness 均依赖 Redis；`minimal` Compose profile 也会启动 Redis。只有 `application-local.yml` 使用进程内实现。由于旧版本的撤销摘要仅存在于旧 Auth JVM 内存，升级时无法迁移；如需保证升级后先前已登出的 token 立即全部失效，应在切换到 Redis 撤销存储时轮换 `AUTH_TOKEN_SECRET`，代价是所有现有 token 需要重新登录。

Gateway 对每个受保护请求都调用 Auth RPC，Auth RPC 对每次校验查询共享撤销存储；注销写入成功后，新版 Auth 实例会立即拒绝相同旧 token。部署时必须先完成所有 Auth 实例升级并从 Dubbo 注册中心摘除旧版本，再依赖此保证；混合部署期间，仍在提供服务的旧 Auth 实例不认识 Redis 撤销键，可能暂时接受旧 token。Redis 不可用时校验失败并返回服务不可用，不会按未撤销处理。
