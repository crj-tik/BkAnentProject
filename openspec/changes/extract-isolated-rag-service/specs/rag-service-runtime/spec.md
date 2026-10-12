## Purpose

本能力确保独立 RAG 服务具有可验证的运行、配置、安全和迁移边界。它要求执行凭据只归检索服务、消费者的基础可用性与检索能力健康分离，并以真实存储与检索验收作为切流依据，而不是依赖工件存在、容器运行或端口连通推断成功。

## ADDED Requirements

### Requirement: RAG 服务独立部署且配置归属清晰

rag-service SHALL 作为独立可执行应用发布，并通过版本化内部 RPC 向消费者提供能力。它 SHALL 独占本次 RAG 使用的 ES/Milvus/embedding/rerank 执行配置及凭据；agent-service、listing-master-service 和 memory-service SHALL 只保留获准消费配置和各自其他业务配置。凭据 SHALL 来自环境变量或秘密管理，不进入文档、模型参数、日志或仓库默认值。域的 RAG 知识空间策略 SHALL 来自受管注册，不借动态 URL 扩展后端。

#### Scenario: 检查部署后的配置归属
- **WHEN** 对受影响服务的有效配置及依赖进行检查
- **THEN** 仅 RAG 拥有本次检索后端执行配置，agent 的 chat 与其他业务配置仍保持，不把共享环境锚点泄漏的后端配置当作清理完成

### Requirement: 所有 RAG 出站 URL 请求执行地址安全校验

rag-service 发出的 URL 请求 SHALL 仅允许 http/https，发请求前 SHALL 校验 host 与全部解析地址并拒绝 localhost、环回、私有和保留地址，包括 IPv4、IPv6 及映射形式。请求重定向 SHALL 禁止或重新完整校验，每次连接 SHALL 防止解析地址与实际连接目标不一致；客户端重试、连接池新建连接和配置刷新也 MUST 遵守策略。受信任部署配置、环境变量、local 模式和检索降级 MUST NOT 绕过该约束。请求中的 endpoint MUST 被拒绝，策略拒绝 SHALL 返回 ENDPOINT_POLICY_DENIED 并与正常依赖故障区分。

#### Scenario: 配置内网或宿主机重排目标
- **WHEN** RAG 的 URL 目标为 localhost、host.docker.internal 解析到私有地址或 10.0.0.0/8 内的 ES 地址
- **THEN** 请求在发送前被拒绝，不能因配置来自 Nacos 或容器内部而放行

#### Scenario: 公开域名重定向或 DNS 改变
- **WHEN** 原目标通过校验但重定向或再次解析指向环回/私有/保留地址
- **THEN** 新目标不被访问，相关检索失败且响应不假报降级成功

### Requirement: 基础就绪与检索能力健康分离

RAG SHALL 提供基础 readiness 和分别反映关键词、向量、embedding、重排及空间准备状态的能力诊断。默认混合加重排能力仅在所有必需阶段、安全校验、schema/模型维度及空间准备通过时 SHALL 标为可用。消费者 SHALL 不将 RAG 列为结构化服务或 Supervisor 全局 readiness 的硬依赖；RAG 故障 SHALL 在调用层明确暴露，不阻止不依赖检索的功能启动和使用。仅 TCP 或容器 running MUST NOT 被视为完整能力健康。

#### Scenario: RAG 故障但结构化功能可用
- **WHEN** RAG 下线而业务数据库和消费者其他必需依赖正常
- **THEN** 房源详情、结构化记忆和不使用知识工具的 Supervisor 请求仍可用，检索调用返回明确能力不可用

#### Scenario: 重排或模型维度不正确
- **WHEN** RAG 基础进程健康但重排协议不兼容或 embedding 实际维度与空间不同
- **THEN** 默认检索能力标为不可用，诊断指出具体阶段，不仅凭 Milvus 端口连通宣布就绪

### Requirement: 发布配置与迁移状态必须在实际环境验证

切流前 SHALL 验证 Nacos 的实际 namespace、group、data ID 与消费者生效配置一致，执行后端安全目标检查、空间授权测试、回填水位核对和真实混合检索/重排 smoke。现有内网 ES 和本机 reranker URL 不满足本变更约束时 SHALL 阻断切流，不能自行更换目标、降低安全要求或将单路无重排测试代替默认能力验收。依赖不可得的测试 SHALL 标为未执行或阻塞，不宣称全量验证通过。

#### Scenario: 配置上传到错误命名空间
- **WHEN** 模板上传成功但不在应用实际使用的 namespace
- **THEN** 发布门禁失败，不以配置文件存在或上传命令退出成功代替生效检查

#### Scenario: 缺少合规实际检索目标
- **WHEN** 当前环境只有违反出站安全约束的 ES 或重排地址
- **THEN** 可以完成规划及不发网的契约测试，但完整检索验收和上线状态保持阻塞，旧数据与旧部署不被自动删除或覆盖

### Requirement: 验证覆盖真实迁移和旧实现清理

验收 SHALL 覆盖空间交叉访问、两路过滤等价、版本乱序及删除、防部分写暴露、重排协议与故障、领域最终排序、旧端点/历史审计兼容、生产知识开关和依赖清理。打包 SHALL 验证受影响消费者不再持有本次后端执行依赖；共享 SDK 的其他业务传递依赖 SHALL 与实际执行依赖区分，不为了清除所有 classpath 名称扩大无关范围。部署回滚 SHALL 有配置和数据水位记录；任何新增运行面或状态转换调用点 SHALL 同步增加真实调用路径单测。

#### Scenario: 全量迁移验收
- **WHEN** 工件、编译、单元测试及真实后端 smoke 均已执行
- **THEN** 记录各自结果及未执行项，只有安全、身份、数据和默认检索门禁全部通过才允许切流，工件 strict validate 通过不被当作实现完成
