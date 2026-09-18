## 1. 建立迁移基线与依赖边界

- [ ] 1.1 为 `SupervisorTaskService`、`SupervisorWorkflowService`、审批回调和异步入口补齐迁移前基线测试，固定单 Agent、并行、需审批、拒绝重生成、终止和重复回调的现有输入输出。
- [x] 1.2 将根 `pom.xml` 的 Spring AI 版本与 Spring AI Alibaba `1.1.2.3` 对齐到设计确定的稳定版本，执行 Maven 依赖树检查，确认 Graph、MCP、A2A、DashScope 和向量存储没有出现不兼容的传递依赖。
- [x] 1.3 为 Graph 编译器、Checkpointer 和运行线程配置增加统一装配入口，确保生产配置使用数据库实现，测试配置才允许使用 `MemorySaver` 或内存替身。

## 2. 固化 Supervisor Graph 状态契约

- [x] 2.1 扩展 `OfficialSupervisorGraphKeys`、`OfficialSupervisorGraphState` 和 `OfficialSupervisorGraphSchema`，明确请求、计划、当前节点、当前 Agent、审批、恢复动作、重试、错误、结果和可观测性字段的类型与默认值。
- [x] 2.2 修正状态更新策略：请求和当前执行位置使用 replace，事件引用、artifact 增量和 handoff 历史使用 append，并增加测试防止节点把完整累积列表重复追加。
- [x] 2.3 统一 `threadId`、`taskId`、`sessionId` 和 `traceId` 的生成、解析和传递规则，保证入口、Graph 节点、审批回调、checkpoint 和审计记录使用同一关联关系。

## 3. 将技能匹配和计划校验纳入 Graph

- [ ] 3.1 将 `SkillMatchNode` 接入规范化顶层 Graph，使技能匹配结果在计划节点之前进入 Graph state，并验证未匹配或匹配失败时的可诊断错误路径。
- [ ] 3.2 将 `WorkflowPlanValidator` 接入计划生成后的 Graph 节点，校验 Agent 注册、领域白名单、步骤类型、审批插入点、并行上限、handoff 目标和允许的状态转换。
- [ ] 3.3 增加非法计划测试，确认校验失败直接进入 Graph 的失败分支，且不会调用任何子 Agent、handoff 或受保护节点。

## 4. 建立唯一的 Supervisor 官方 Graph

- [x] 4.1 新增或改造顶层 Graph Factory/Holder，串联 `LoadSession -> SkillMatch -> Plan -> Validate -> Route` 以及单 Agent、并行、审批、恢复、handoff、重生成、完成、取消和失败节点。
- [x] 4.2 使用官方条件边根据受控的计划状态选择单 Agent、并行、审批网关或失败分支；条件函数只能返回白名单节点，不能接受 LLM 或请求直接注入节点名。
- [x] 4.3 使用 Graph 原生并行分支和统一聚合节点执行多个领域，明确 all-of/any-of 策略、分支失败处理、结果合并和聚合后的下一路由。
- [x] 4.4 将单 Agent、并行、handoff、重生成和完成现有子图改造成顶层 Graph 可调用的节点或兼容适配器，保证节点只做状态转换和领域调用，不反向控制主流程。

## 5. 实现审批暂停、人工决策和条件恢复

- [x] 5.1 实现审批网关节点：在受保护业务节点之前判断审批规则，创建包含 `approvalId`、待执行节点、线程关联、允许动作和重试信息的审批状态，并在需要审批时返回 `WAITING_USER_APPROVAL`。
- [x] 5.2 确保 Graph 在审批等待状态停止后续 Agent、handoff、发布和完成节点；不需要审批时通过同一条件边直接进入选定执行节点。
- [x] 5.3 将审批回调改为 Graph 恢复命令，校验 `approvalId`、`taskId`、`threadId`、状态版本和允许动作，并对重复回调返回稳定的幂等结果而不重复执行下游节点。
- [x] 5.4 在 Graph 中实现审批决策路由：批准进入记录的下一节点，拒绝并要求重生成进入重生成分支并保留反馈，终止或取消进入终止分支，未知动作拒绝恢复。
- [ ] 5.5 为审批暂停、批准继续、拒绝重生成、终止、非法回调、重复回调和服务重启后恢复分别增加 Graph 集成测试，验证下游节点调用次数为预期值。

## 6. 统一官方 Checkpointer 与数据库 checkpoint

- [x] 6.1 基于现有 `AgentWorkflowCheckpointEntity`、Mapper 和数据库表实现官方 Graph Checkpointer 适配层，保存版本化状态 envelope、节点位置、线程信息和恢复输入。
- [x] 6.2 将所有官方 Graph Factory 的独立 `MemorySaver` 替换为统一 Checkpointer 注入；内存保存器仅保留给明确的单元测试配置。
- [ ] 6.3 为旧 checkpoint 增加读取兼容和状态 envelope 迁移逻辑，验证服务重启、多实例切换和等待审批状态恢复时不会重新执行已完成的规划或 Agent 节点。
- [x] 6.4 增加并发版本校验、审批唯一约束或等价幂等保护，覆盖相同审批在并发请求下只能推进一次 Graph。

## 7. 收敛 Service 为 Graph 门面

- [x] 7.1 将 `SupervisorTaskService` 的单 Agent/并行分支和结果判断迁移到顶层 Graph 门面，Service 只保留请求校验、鉴权、限流、线程初始化和 Graph 调用。
- [x] 7.2 将 `SupervisorWorkflowService` 的审批判断、回调 `switch`、并行执行、自动路由和完成判断迁移到 Graph 条件边与节点，删除或隔离绕过顶层 Graph 的主流程路径。
- [x] 7.3 将同步、异步和工作流入口统一到同一个 Graph 执行/恢复协议，保留现有任务创建、审批回调、状态查询 DTO 和 A2A 对外契约。
- [ ] 7.4 保留 `SupervisorWorkflowQueryService` 等查询服务的只读职责，确保查询从 checkpoint/审计状态读取，不重新推导或修改 Graph 主流程。

## 8. 统一节点事件与错误处理

- [ ] 8.1 为规划、路由、审批等待、审批恢复、Agent 调用、并行聚合、handoff、重生成、失败和完成节点接入统一审计适配器，记录节点、任务、线程、trace、attempt、开始和结束时间。
- [ ] 8.2 统一可恢复错误、不可恢复错误和审批错误的 Graph 状态更新，确保错误按照策略进入重试、审批、取消或失败分支，并保留诊断上下文。
- [ ] 8.3 验证现有 SessionStream、event-audit、artifact 和查询接口仍能关联顶层 Graph 节点事件，不因 Service 下沉而丢失任务状态。

## 9. 完成回归验证与最小运行验证

- [x] 9.1 增加状态 schema、条件路由、计划校验、技能匹配、并行聚合、审批决策和 Checkpointer 适配器的单元测试。
- [ ] 9.2 增加端到端 Graph 测试：无审批单 Agent、需审批暂停、人工批准继续、拒绝重生成、选择下一 Agent、终止、重复回调和非法计划。
- [ ] 9.3 增加数据库 checkpoint 重启恢复和并发幂等测试，确认恢复从待执行节点继续且不重复创建 artifact 或调用 Agent。
- [x] 9.4 执行 `agent-service` 模块测试、Maven 依赖收敛检查、最小微服务启动和 MCP/A2A smoke test，并记录 Spring AI 版本对齐后的兼容问题。
- [x] 9.5 更新 Supervisor Graph、审批状态、恢复接口、状态字段和最小启动方式的开发文档，明确后续 Spring AI Alibaba `2.0.0-M1.1` 评估必须在独立变更中进行。
