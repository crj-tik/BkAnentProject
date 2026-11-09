# 从访谈中拆出经验知识运营域

## Why

首批用户是知识运营人员和店长，需要把访谈中已明确发布的脱敏经验整理为可审核、可撤回、可版本化的经营知识。知识运营事实与访谈采集事实混在一起会扩大 Interview 的运行面职责，也会让报告、逐字稿和已发布知识之间形成多重权威。

## What Changes

- 新增 knowledge-service 与 bk_knowledge，由其独占经营案例、打法、revision、审核、发布范围、来源依赖、生成任务及操作回执。Interview 继续独占会话、话轮、逐字稿、报告及报告任务；现有 Interview Java 代码、配置和源表保持不变。
- 由于 Interview 当前没有返回正文的 API，Knowledge 通过独立只读数据库适配读取 bk_knowledge 内由受限视图投影的 Interview 来源，运行账号没有 bk_interview 表权限。仅准入当前为 PUBLISHED、评级 L2/L3、脱敏版本符合要求、正文哈希匹配且案例/会话关系有效的来源；只读取脱敏 content，不读取 rawContent。现有 PUBLISHED 状态是本期来源准入信号，Knowledge 操作另行记录验签主体、来源快照和用途。
- 撤回发布、删除、哈希/版本不匹配、脱敏校验失败或来源数据库不可用时，Knowledge 停止读取和输出相应派生正文。Auth 的签名上下文、freshness、精确 Knowledge 操作和可信身份范围仍是知识动作及发布读取的授权依据；缺少已验证 STORE 身份时门店范围失败关闭。
- knowledge-agent 使用现有必需 A2A Memory 生命周期，注册 caller/domain/agent 为 knowledge-service / knowledge / knowledge-agent。Memory 不可用或当前工作集不能验证时，不执行模型或业务工具；知识发布不自动创建 Memory 共享经验或授权。
- 同步新增服务相关的 .env.example 与存在时本地 .env 键、application.yml、Nacos 模板、Compose 服务与依赖、Gateway 路由、Agent 注册、Memory caller 白名单、构建模块和 bk_knowledge 初始化清单。新增独立 Knowledge 主库账号及 Interview 来源视图只读账号；Nacos 不保存密码或私钥。本 change 不废除 Interview 仍使用的配置项。
- Interview 旧 playbook_card/commonality_report 只做只读盘点和身份映射建议；不迁表、不复用 ID、不重放报告任务。首期不读取报告正文。
- 固定 domain-parallel-v1 的知识 published/search、batch-read、check HTTP 契约。本 change 不实现门店复盘，不修改 Auth/Memory Java 实现或 Interview 运行面，也不合并其它业务模块。

## Capabilities

### New Capabilities

- interview-transcript-consumption: 知识域只读消费已发布脱敏快照的准入、完整性与失效行为。
- knowledge-operations: 可追溯经营案例和打法的运营生命周期、发布范围及版本化读取。

### Modified Capabilities

无。现有 Memory 私有/共享经验规则、Interview 公开 MCP 行为及通用 Agent/技能契约继续适用。

## Impact

新增 knowledge-service、bk_knowledge、Knowledge 来源只读适配与必要的知识 wire DTO；Interview 的 Java 代码、配置、表和任务保持原样。agent-service 仅增加 knowledge-agent 的受众解析、精确能力权限映射与动态注册配置。配置差异覆盖 .env.example（及部署者本地 .env 的同名键）、Knowledge Spring/Nacos 模板、docker-compose.yml、Gateway/Agent/Memory Nacos 配置、bk_knowledge 初始化清单及可选 MySQL MCP 只读库白名单。Auth 需由协调登记精确 Knowledge 操作与服务公钥，Memory Nacos 需登记 Knowledge caller；不修改这两个服务的 Java 实现。来源模式缺少可信 provenance 的存量按 UNKNOWN，测试夹具可明确标记 SIMULATED。Auth 无可信 STORE identity 映射前，门店级发布、搜索、读取与 check 均保持关闭。沿用 LR-3/6/8/59/90/94/96/97、KI-15/204，并在逻辑释义清单补充本次数据库读取与 Memory 配置边界。

执行方式：本会话在独立 worktree 中新增 Knowledge 服务规划与实现，不搬移或改写原服务代码。未来消费者按冻结 HTTP 契约接入，不成为本 change 的构建条件；真实组织/服务登记与消费者互通在合并后另验。Interview 的 source 字段只有 AUTO/UPLOAD，不能证明 REAL 或 SIMULATED；不得从来源字段、等级或发布状态推断生产真实性。并行范围与隔离要求见 docs/business-domain-change-roadmap.md。