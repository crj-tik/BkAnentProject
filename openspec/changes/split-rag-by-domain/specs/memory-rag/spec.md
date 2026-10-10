## Purpose

记忆域自有向量检索能力：memory-service 进程内管理系统约束与用户偏好的向量 upsert 与语义检索，与既有 MySQL 结构化半边（约束/偏好表）同进程闭合，结束记忆域数据分裂在两个进程的状态。

## ADDED Requirements

### Requirement: 记忆向量检索端点

memory-service SHALL 提供向量检索端点：输入查询文本、可选集合名与 topK，返回语义相似文档列表（含得分、内容、元数据）；集合名缺省时 MUST 使用服务端默认集合。检索语义与原 agent-service `milvusKnowledgeSearch` 工具底座行为一致。

#### Scenario: 语义检索返回相似内容
- **WHEN** 以与已写入约束语义相近的查询调用检索端点
- **THEN** 返回该约束对应文档，得分与相似度单调

#### Scenario: topK 缺省
- **WHEN** 请求未指定 topK
- **THEN** 服务端使用配置的默认值并正常返回

### Requirement: 记忆向量写入端点

memory-service SHALL 提供向量写入端点：按文档 ID upsert 文本内容与元数据（含集合名），写入即建立或更新对应向量。

#### Scenario: 约束更新后语义可检
- **WHEN** 同一约束文本被更新并重新 upsert
- **THEN** 后续语义检索返回新文本内容

### Requirement: 约束与偏好双写在同域完成

系统约束与用户偏好的结构化写入（MySQL）与向量写入（Milvus）SHALL 由 memory-service 域内统一完成；agent-service 侧 MUST NOT 再直接持有记忆域 Milvus 集合的写入路径。

#### Scenario: 约束引导收拢
- **WHEN** 系统约束引导流程执行
- **THEN** MySQL 记录与向量集合的写入均由 memory-service 域内完成，双写失败仅记录告警不阻塞启动

### Requirement: 依赖故障不阻塞记忆服务启动

memory-service 的就绪依赖 SHALL 声明 Milvus；Milvus 不可用时结构化记忆功能（MySQL 半边）MUST 仍可服务，向量检索端点返回明确错误。

#### Scenario: Milvus 故障时结构化功能可用
- **WHEN** Milvus 实例不可达
- **THEN** 记忆的 MySQL 读写功能正常，向量检索端点返回明确的服务不可用错误
