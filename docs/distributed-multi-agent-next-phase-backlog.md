# 分布式多 Agent 下一阶段 Backlog

## 1. 目标

当前中期方案已经完成：

- 主 Agent 官方 Graph 编排
- A2A sync/async/stream
- memory-service / artifact / handoff relation
- 审批、并行、handoff、resume 主链
- 事件驱动 notification
- diagnostics / event-audit / metrics / 限流 / 灰度

下一阶段重点不再是“把骨架搭起来”，而是把这套系统推进到更稳定、更可运营的状态。

## 2. P0 必做

### 2.1 Redis / 网关级限流替换

- 将 `SupervisorRateLimiter` 从内存版扩为可替换实现
- 增加 Redis 版本或网关版本
- 保留本地开发默认的 `InMemorySupervisorRateLimiter`

当前进度：

- 已有 `InMemorySupervisorRateLimiter`
- 已补 `RedisSupervisorRateLimiter`
- 当前通过 `agent.distributed.rate-limit.provider=memory|redis` 切换

涉及模块：

- `agent-service`

建议类：

- `RedisSupervisorRateLimiter`
- `SupervisorRateLimiterConfiguration`

### 2.2 灰度查询面继续补全

- `diagnostics` / `event-audit` 增加 `grayStrategyVersion` 查询
- 补按 `grayRelease=true` 的快速筛选
- 将灰度命中结果补入更多 graph/handoff 事件

涉及模块：

- `agent-service`

### 2.3 压测脚本与验收基线

- 单 Agent 主链压测脚本
- 并行链压测脚本
- 审批恢复链压测脚本
- async workflow 压测脚本
- publish 闭环压测脚本

建议产物：

- `docs/perf/` 下的压测说明
- `scripts/` 下的请求样例或批量脚本

## 3. P1 应做

### 3.1 权限继续下沉

- 将 workflow / artifact / handoff 权限从 controller 级继续下沉
- 增加 task owner 与 tenant 维度的统一校验

### 3.2 artifact 治理继续收紧

- 将更多大对象从 `structuredOutput` 收口到 artifact
- 统一 artifact type 枚举与版本治理
- 为 media/publish/contract/settlement 建更明确的 artifact schema

### 3.3 notification 事件消费增强

- 增加失败重试策略
- 增加死信处理说明
- 增加通知审计筛选和运营查询面

当前进度：
- 已增加 notification 侧本地最大重试次数与 `DEAD_LETTER` 状态
- `/notifications/workflow-events` 已支持按 `consumeStatus/eventType/taskId/recipientUserId` 筛选

## 4. P2 可后置

### 4.1 metrics 对接 Prometheus / Grafana

- 暴露指标抓取方案
- 建 Graph / Async / Security / Notification 四类看板

### 4.2 Graph 版本化

- 支持不同 `strategyVersion` 对应不同 route / 子图选择
- 支持回滚到旧策略

### 4.3 运营治理

- 限流阈值动态调整
- 灰度开关管理
- 事件审计保留周期与归档

## 5. 建议执行顺序

1. Redis / 网关级限流
2. 灰度查询面补全
3. 压测脚本与验收基线
4. 权限继续下沉
5. artifact 治理继续收紧
6. notification 事件消费增强
7. metrics 看板与 Graph 版本化

## 6. 验收口径

- 不新增主链编排回归
- `agent-service` 编译通过
- 关键入口维持现有兼容性
- 新增治理能力可以被 diagnostics / event-audit 看见
