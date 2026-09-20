## 1. A2A 响应归一化

- [ ] 1.1 定义官方 A2A 响应归一化所需的保留字段和映射规则，确保 Supervisor 本地 taskId、远端 remoteTaskId、状态、错误、Artifact IDs、structuredOutput、nextHints 和 summary 的语义不冲突
- [ ] 1.2 实现 Text、JSON/Data part 和 Artifact 内容的统一提取与有序聚合，支持直接 JSON 对象、代码围栏 JSON 和普通文本回退，并过滤不应进入会话事件的 raw/reasoning 等敏感字段
- [ ] 1.3 将同步 invoke、流式终态处理和异步 Task 状态查询接入同一套归一化逻辑，保留原始文本并让领域字段直接进入 structuredOutput
- [ ] 1.4 处理重复 Task/Artifact 更新、缺失 Task ID、空内容和失败/取消状态，确保最终响应具有稳定状态和完整 Artifact 关联

## 2. Supervisor 终态响应透传

- [ ] 2.1 在子 Agent 终态会话事件的 metadata 中生成统一的 result envelope，携带 status、remoteTaskId、artifactIds、structuredOutput、nextHints、summary 和 error
- [ ] 2.2 修改流式执行、同步阻塞和异步状态查询的终态发布路径，使最终响应都通过同一个发布方法进入 SessionStreamEvent，同时保持本地 taskId、traceId 和生命周期 metadata 不变
- [ ] 2.3 保证增量事件只发送进度/文本内容，终态事件只发布一次完整结果，并确保速率限制、异常回退和底层未发送终态事件时仍能补发最终结果
- [ ] 2.4 验证现有审计存储和 SSE replay 能保留并重放终态 result envelope，确保旧事件和旧 SSE 客户端仍能按原有字段工作

## 3. Supervisor 路由与下游消费

- [ ] 3.1 验证归一化后的 nextHints 能驱动 Supervisor handoff，缺少 nextHints 时安全进入完成路径，失败或取消状态不继续路由
- [ ] 3.2 验证 BuildNextAgentContext、PersistArtifacts 及并行结果合并逻辑能够读取结构化领域字段和 Artifact 关联，而不是读取被包裹的原始文本
- [ ] 3.3 为远端 taskId、Artifact IDs 和终态错误建立一致的日志/事件关联，避免覆盖 Supervisor 工作流本地 taskId

## 4. 自动化测试

- [ ] 4.1 增加 A2A 响应归一化单元测试，覆盖 JSON 文本、代码围栏 JSON、JSON/Data Artifact、普通文本、多个 Artifact 聚合和字段类型异常
- [ ] 4.2 增加官方 A2A 客户端测试，验证同步、流式和异步路径产生等价的 AgentTaskInvokeResponse，并保留远端 taskId、状态和错误
- [ ] 4.3 增加 A2aExecutionService 与 SessionStreamService 测试，验证终态 result envelope 透传、增量不提前终止、终态幂等和 SSE replay 恢复
- [ ] 4.4 增加 Supervisor Graph 路由与下游节点测试，验证 nextHints handoff、领域字段上下文构建、Artifact 持久化和失败终止路径

## 5. 验证与交付

- [ ] 5.1 运行 agent-service 及相关 common 模块的编译和测试，修复因事件 metadata 或响应解析引入的兼容性问题
- [ ] 5.2 使用同步、流式和异步样例请求检查最终 SSE 事件，确认客户端可读取结构化结果、远端 taskId 和 Artifact IDs
- [ ] 5.3 更新本次 OpenSpec 变更的任务状态和验证记录，确保实现完成后可以归档该变更
