# 偏好异步评估与可靠微批归并验收（2026-10-20）

## 范围与状态

完成 `integrate-domain-agent-memory` 的 **7.2**，整体 **24/66**。后台评估、可靠回执、恢复归并及影子模式已实现。新制品已构建，Docker 引擎失联导致本轮部署尚未完成；不得将本记录解释为新容器已通过健康检查。

自身、domain+agent 私有范围沿用 7.3/7.4。其他主体/共享偏好、Agent 装配、V2 RAG 和其他加工/索引/隐私清理消费者仍未实现。4.5 只有偏好子集完成，任务保持未勾选。普通入站只做既有可靠追加与任务入库，逐 delta/progress 未增加模型或 Memory 调用；本轮没有测正式业务 SSE 性能。

## 实现

```mermaid
flowchart LR
    A[已接受的完整消息] --> B[归档与 ASSESS_MESSAGE 同事务]
    B --> C[精确来源核验与任务租约]
    C --> D[事务外无工具模型评估]
    D --> E[复验来源并保存 ASSESSED 回执]
    E --> F{加工模式}
    F -->|SHADOW| G[仅评估 SHADOWED]
    F -->|PUBLISH| H[按稳定块和来源顺序微批归并]
    H --> I[历程 版本 索引待办 SUCCEEDED 同事务]
```

- 任务以每次唯一租约和 attempt fence 防止过期 worker 提交；可到期接管、有限重试、失败终态。错误只保存枚举，不保存模型异常正文。模型调用不持 SQL 锁、不验证或换发用户 token，不获得通用读取/搜索权。
- 加工范围由部署明确注册，并与当前可信 source_caller 的 domain→agent 映射取交集。精确来源及传递依赖在读正文前、模型返回后和发布前复验；撤回或授权映射失效禁止发布。用户租约过期不冒充原用户。
- 模型只接收单条获准 USER 文本，区分 PREFERENCE/TASK_CONSTRAINT/QUESTION/OTHER、极性和变化类型；输出严格 JSON，拒绝未知/重复字段、工具调用、截断结束、未注册维度、重复维度、原文外值与凭据形状。主体/范围/来源/修正 ID 不交模型选择。assistant/工具消息不调用评估。
- extractionVersion 包含规则版本、实际逻辑模型标识与规则内容 hash。评估结果先保存，之后归并；重启与归并重试使用原回执，不再调用模型。不同模型版本升级不重解释已经评估的回执。
- 微批按逻辑块和同会话 sequence 排序，多个维度和任务回执原子提交；坏候选在批次回滚后逐事件隔离。归并复用原有完整历程校验与 reducer，不按 worker 完成时间覆盖旧值。模型不提供跨会话修正授权，同会话旧挂点由服务端链接。
- 临时例外只采用已有事项或原文出现的 ISO instant 有效期；缺少适用挂点不写长期默认。SHADOW 只存评估、不创建生产块和索引待办；模式变化不自动提升旧影子回执。
- 来源删除清除本会话评估正文并保留必要 hash/定位；派生块沿既有撤回规则立即不可读。外部副本未清理仍报告 DELETION_PENDING。

## 验证证据

最终本机回归 **302 项，零失败、零错误、零跳过**：common 54、common-memory 2、Supervisor/认证/编排 78、memory-service 168。显式排除四个外部类，没有以跳过外部测试伪造完整联机通过。

- 15 项 worker 回归覆盖有界微批、修正顺序、模型故障后会话继续、有限重试、评估后重启/模型升级、到期接管/旧结果拒绝、撤回/删除、影子、普通用户权限失效但精确后台授权有效、SERVICE 加工映射撤销、非 USER 跳过、凭据、伪造来源和坏事件隔离。
- 严格模型解析 7 项、配置与默认关闭 3 项通过；普通应用关闭加工时不需要 AI 模型或密钥，不触发模型网络访问。
- 故障前，现有 MySQL 的档案、偏好历程、偏好处理三个外部类均通过。新处理用例实际验证并发实例 claim、模型期间不持锁、原回执归并幂等及删除评估正文；使用随机私有 scope，正常结束精确清理。模型为受控 evaluator，Auth 为受控契约，不冒充真实登录用户业务链路。
- 最终规则下，既有开发网关 `gpt-5.6-terra` 的真实模型 4 组样例通过，覆盖偏好、长期修正、询问和临时例外。模型测试与 SQL worker 测试分别验证，不声称已跑通真实用户 SSE 到模型再到 RAG 的整链路。
- 初版规则复测曾把预期临时例外分类为 TASK_CONSTRAINT。补充既有默认/本次放宽的对照指引后复测通过，保留 **KI-142 LIMIT**，不将小样本当作完备语义保证。
- 完整 23 模块离线 compile、Memory 与上游 4 模块 package（跳过重复测试）、OpenSpec strict validate 通过。

本机回归通过命令（外部类显式排除）：

```powershell
mvn -o -gs .mvn-settings.xml -s .mvn-settings.xml -pl agent-service,memory-service -am "-Dtest=Memory*Test,Memory*CompatibilityTest,Preference*Test,!MemoryArchiveMySqlTest,!PreferenceHistoryMySqlTest,!PreferenceProcessingMySqlTest,!PreferenceLiveModelTest,SupervisorToolLoopGraphTest,AgentRagCredentialLeaseStoreTest,OrchestrationStoreTest,SupervisorMemoryIntegrationTest,SupervisorMemoryPropertiesTest,SupervisorMemoryProjectionTest,SupervisorMemoryResultViewTest,SupervisorMemorySummaryGeneratorTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DforkCount=1" test
```

凭据仅从 `.env` 注入测试进程；日志/本记录不包含凭据。开发库测试连接现统一限制连接 5 秒、读取 15 秒（KI-143），防止依赖失联时测试与 finally 清理无限挂起。

## 迁移、制品与部署待办

迁移 170 添加 `memory_preference_evaluation`，既有迁移字节未改。manifest LF/SHA256 dry-run、既有 `bk-anent-dev-mysql-1` 的完整 upgrade/check 两次通过；H2 fixture 也重复执行。迁移只建回执表，不自动启用加工。

本机新 JAR SHA-256：`b239260a5d54ed9a8bc5b72dca076496b4e009d88ff3d3d638908bc9d10221ce`。

15:47 后最终联机复核停在 MySQL 握手，Docker 列表查询及独立引擎 `_ping` 同时不返回。线程证据为 MySQL `readServerCapabilities`，不能归因为 worker 或假报测试通过。已精确结束本轮卡住的 Maven/test 进程，其他 Java 进程未动。环境根因与恢复待处理，关联 KI-124；如恢复需要重启整个 Docker，将涉及其他项目，须先明确此扩大范围的操作。

部署前记录 `bk-anent-dev` 共 31 个容器；本轮没有创建测试/业务副本，也没有发出镜像构建或服务重建命令。引擎恢复后需重新核对当前清单，完成有界外部复核，并用本机 JAR 叠加既有 Memory 镜像，只对 `memory-service` 执行 `--no-deps --no-build` 更新，再核验容器内 SHA/health/readiness。最终运行版本与其他容器状态尚未复核。

默认 archive/processing/Agent V2 保持关闭，processing 默认 SHADOW。启用要求真实模型配置、当前可信 caller、精确 `allowed-agents-by-domain` 注册，并重启该服务使条件 Bean 生效；没有注册时不扫描正文。当前 RPC 数量仍为 14，没有新增内网 HTTP 客户端或放宽原生 USER 写权限。

## 关联

LR-70；KI-142/143。实现提交及最终部署证据后续补入。
