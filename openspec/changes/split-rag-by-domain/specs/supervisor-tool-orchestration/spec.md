## ADDED Requirements

### Requirement: 知识检索工具远程代理化

supervisor 本地工具 `milvusKnowledgeSearch` 的底层检索 SHALL 改为对 memory-service 检索端点的远程调用；工具名、输入参数、返回格式与工具注册方式（local 能力目录）MUST 保持不变。会话级门控（`allowKnowledgeSearch` 开关与 topK 覆盖）SHALL 保留在 agent-service 进程内，远程调用仅承担纯检索。

#### Scenario: 工具调用返回语义一致
- **WHEN** 模型在会话中调用 `milvusKnowledgeSearch`
- **THEN** 返回的检索结果格式与迁移前一致，结果内容来自 memory-service 端点

#### Scenario: 会话禁用知识检索
- **WHEN** 会话上下文将知识检索设为禁用
- **THEN** 工具不发起远程调用，直接返回禁用提示

#### Scenario: 远端不可用降级
- **WHEN** memory-service 检索端点不可达或超时
- **THEN** 工具返回明确的检索不可用提示，会话与工具环其余能力不受影响

### Requirement: agent-service 脱离 Milvus 直接依赖

agent-service SHALL NOT 再持有 Milvus 客户端、向量集合或 RAG 域编排代码；其就绪依赖 MUST 移除 `milvus`，启动不受 Milvus 实例可用性影响。

#### Scenario: Milvus 故障不影响编排
- **WHEN** Milvus 实例不可达时启动或运行 agent-service
- **THEN** supervisor 编排、A2A/MCP 能力目录与会话功能正常，仅知识检索工具在调用时返回不可用提示
