## 1. 公共 SubAgent A2A 协议适配器

- [ ] 1.1 新增隔离的公共 A2A 支持模块或在现有公共模块中建立明确的依赖边界，提供 Text/Data 输入解析、metadata 映射和大小限制
- [ ] 1.2 实现项目侧 `AgentExecutor`，覆盖同步执行、流式执行、异步 TaskStore 兼容和取消处理，并替换 Starter 默认 `GraphAgentExecutor`
- [ ] 1.3 实现通用结构化输出策略：代码围栏清理、JSON object 解析、`contentType`/`summary`/`nextHints` 校验补齐、领域字段保留和脱敏
- [ ] 1.4 实现成功终态 DataPart、文本摘要、稳定 Artifact ID 和进度 Artifact 的生成，保证同一 Task 只有一个业务终态结果
- [ ] 1.5 实现失败/取消状态和机器可读错误类别，确保异常、工具失败和非法 JSON 不会先产生 completed 结果

## 2. 领域 Provider 接入

- [ ] 2.1 更新 `TradeOfficialA2aAgent`，接入公共 Executor/输出策略并保留交易工具和上下文拦截器
- [ ] 2.2 更新 `ListingOfficialA2aAgent`，接入公共 Executor/输出策略并保留房源工具和上下文拦截器
- [ ] 2.3 更新 `MarketingOfficialA2aAgent`，接入公共 Executor/输出策略并保留营销工具和上下文拦截器
- [ ] 2.4 更新 `MediaOfficialA2aAgent`，接入公共 Executor/输出策略并保留媒体工具和上下文拦截器
- [ ] 2.5 更新 `ContractOfficialA2aAgent`，接入公共 Executor/输出策略并保留合同工具和上下文拦截器
- [ ] 2.6 更新 `SettlementOfficialA2aAgent`，接入公共 Executor/输出策略并保留结算工具和上下文拦截器
- [ ] 2.7 更新 `NotificationOfficialA2aAgent`，接入公共 Executor/输出策略并保留通知工具和上下文拦截器
- [ ] 2.8 更新 `CompareOfficialA2aAgent`，接入公共 Executor/输出策略并保留比较工具和上下文拦截器
- [ ] 2.9 统一 8 个 `A2aSupervisorContextInterceptor` 的结构化上下文渲染、字段白名单和敏感信息限制

## 3. Prompt 与 Agent Card

- [ ] 3.1 更新 8 份领域 Prompt，明确成功 JSON 顶层字段、`nextHints` 数组、summary 和禁止 Markdown/额外解释的规则
- [ ] 3.2 校准 8 份 Nacos Agent Card 的 input/output modes、streaming capability、domain skill、端点和 official runtime metadata
- [ ] 3.3 为需要纯文本兼容的领域提供显式输出模式配置，避免 card 声明与实际响应不一致

## 4. 自动化测试

- [ ] 4.1 为输入解析测试 TextPart、DataPart、metadata、Supervisor structuredContext、空输入和超限输入
- [ ] 4.2 为输出策略测试合法 JSON、代码围栏 JSON、缺失通用字段、非法 JSON、未知领域字段和敏感字段过滤
- [ ] 4.3 为公共 Executor 测试同步 completed、流式增量+单次终态、异步查询、failed、canceled 和重复终态
- [ ] 4.4 为 Artifact 测试 DataPart 内容、TextPart 摘要、稳定 ID、进度/终态隔离和重放可见性
- [ ] 4.5 扩展 8 个 Provider 配置测试，验证自定义 Executor、生效的 Agent Card、上下文拦截器和 `/a2a` 配置
- [ ] 4.6 增加 Supervisor 侧兼容测试，确认现有 `OfficialA2aResponseNormalizer` 能读取 SubAgent 的 DataPart、文本增量和终态错误

## 5. 验证与交付

- [ ] 5.1 编译公共 A2A 支持模块和 8 个领域服务，修复第三方 SDK API/Bean 条件冲突
- [ ] 5.2 运行公共适配器、领域 Provider、agent-service 协议测试和完整 Maven 测试
- [ ] 5.3 使用同步、流式和异步样例检查真实 A2A 响应，确认 task state、artifactId、DataPart、nextHints 和失败语义
- [ ] 5.4 更新 OpenSpec 任务状态和验证记录，准备归档变更
