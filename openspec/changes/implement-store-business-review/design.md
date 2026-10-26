# 门店经营卡点复盘设计

## Context

动机见 proposal.md。现有 `BusinessAnalyticsServiceImpl` 汇总月度 KPI、日工作量、排行榜、房源流转和门店看板，尚无经营问题、证据、动作和复盘的事实模型。官方 Agent 当前绑定 `business-service / trade / trade-agent`，其 Memory、技能与公钥映射已经登记，产品职责调整无需同时重命名技术身份。

首期业务数据模拟；房源从接口取得，逐字稿归访谈域，已发布经营知识由独立 change 提供。已有 `implement-studio-v2` 仍有 8 项未完成，本 change 不复制其 work/command/文件/发布门面。Auth 在另一会话完善，业务侧仅消费可信身份与门店范围。

## Goals / Non-Goals

**Goals:** 把“带看后停滞”和“房源维护缺口”转为有来源、可确认、可跟进的经营事项；明确效率观察与业绩结果的证据层次。

**Non-Goals:** 不做新人培训、自动给员工打绩效分、自动联系客户、调价或付款；不合并房源/客户/知识服务，不在此实现上游业务系统。

## Decisions

### 1. 保留 business-service，增加 review 业务包

新增 ReviewMatter、EvidenceSnapshot、DiagnosisRevision、ActionPlanRevision、ReviewAction、ActionReceipt、OutcomeObservation。业务状态及幂等回执归 `bk_business`，Supervisor/Memory 只引用业务 ID、版本和获准结果，不另存一份可推进业务的权威状态。

相比只扩充看板，这些对象让店长能处理问题并跟踪后续；相比建立完整客户/交易系统，事项只引用上游的客户、房源、带看及机会身份。客户需求、房源价格和成交状态仍由各自来源决定。

### 2. 模拟经营输入走可替换的数据端口

`StoreBusinessEvidencePort` 提供指定门店、时间窗口和 dataset 下的带看事件、跟进事件、议价状态及维护记录。首期实现固定版本 fixture provider，不从 employeeId、记录 ID 或模型文本随机推算业绩。每条记录包含 `sourceSystem/sourceId/revision/observedAt/eventTime/dataMode/datasetId`。房源读取由 `ListingBusinessReadPort`、知识由 `PublishedKnowledgeReadPort` 消费冻结的 `domain-parallel-v1` HTTP契约；本模块拥有 consumer wire DTO/mapper 及 HTTP fixture，不引用另两个 change 新增的 common 类型，不跨域读库或复制逐字稿。

本地证据快照仅保存获准的有界字段和来源定位。模拟与真实记录按 dataset 和来源标记分开，不能通过更新 dataMode 将旧模拟历史“转正”；任何模拟证据参与输出都继承为 SIMULATED 或 MIXED。真实 feed 的后续接入可替换端口实现，数据结构与本期状态机不改变。

来源不明为 UNKNOWN，含此类来源的派生结果也为 UNKNOWN；全部来源为 REAL 才输出 REAL，已知真实/模拟混合为 MIXED。来源模式与指标计算状态独立，仍逐项保留事实缺口及分母问题。

### 3. 把事实、假设、行动和结果拆开

诊断结果包含事实/出处、卡点假设、待核实问题、适用知识 revision、建议动作及其理由。来源不足时先返回缺口；低转化率不自动成为某个原因的证明。规则先计算客观数量和缺口，模型在有界获准材料上解释和建议；禁止模型创造带看、成交或价格事实。

知识域返回的已发布版本在装配和输出前复核，撤回或来源失效时停止显示相关正文并标记原建议待复核。知识查询不可用与合法无匹配分开表达；前者不能被伪装成没有案例。

### 4. 动作确认和执行反馈有独立回执

计划绑定 diagnosisRevision、evidenceVersions、knowledgeRevisionRefs 和 canonical argumentsHash。店长以当前权限确认 expectedPlanVersion 后，事务内创建动作与幂等回执；重复 requestId 同内容返回原结果，不同内容冲突，旧确认不能批准新计划。

动作状态为 `PROPOSED / CONFIRMED / IN_PROGRESS / DONE / CANCELLED`。DONE 需记录执行者、时间和证据级别；只有人工反馈时标记 USER_REPORTED，只有对应来源回执时才能标记 SOURCE_CONFIRMED。动作完成不自动更新房源/客户/成交状态。首期记录的是推进执行过程，外部发送、调价等需其他领域能力和独立后续 change。

### 5. 指标采用明确口径，模拟验收不承诺增长

指标定义版本固定时间区间、Asia/Shanghai 时区、聚合单位（事件/客户/机会）、样本集合、分子、分母及观察时间。例：带看后有效下一步率以同一观察窗口内的去重机会为单位；何谓“有效下一步”随定义版本存储，不从生成建议或 DONE 数量推断。分母为零或事实缺失返回 UNKNOWN 及原因，不当作零转化。

效率指标记录资料整理耗时、重复输入和动作闭环时间；成交推进指标记录来源确认的阶段变化与成交周期。前后对比只表达观察，不能自动声称因果增长。模拟数据和 USER_REPORTED 反馈不发送生产 MemoryExperienceOutcome；模拟验收仅覆盖业务流程，真实增长依赖后续实际业务观察。

### 6. 复用现有 Agent、审批与身份契约

`trade-agent` 展示和技能描述更新为门店经营复盘，保留当前 `trade` Memory 私域和历史记录。增加经营卡点诊断、方案准备与结果复盘技能，实际写动作仍执行服务端身份、范围、版本与审批要求；领域状态机不成为 Supervisor 的固定业务 DAG。

公开接口拟为 `/business/reviews`、`/business/reviews/{id}/plans`、`/business/reviews/{id}/actions` 与观察查询，输入不接受自授 owner/store 范围。Auth 范围不可用时拒绝受保护操作。首期提供领域 API、版本化 DTO 和安全 Agent artifact；Studio 前端及已有 P3/P4 由其原 change 对接，完成领域接口不宣称浏览器工作台已经落地。

### 7. 无跨 change 等待的独立验收

本会话只拥有 business 模块和 `common/business/review/**`，工作区/分支、Maven仓库及测试资源按 [并行总览](../../../docs/business-domain-change-roadmap.md) 隔离。房源和知识两个 HTTP consumer adapter 必须实际实现；路径、字段、批量检查和错误语义采用冻结 v1，使用本会话有界 HTTP服务器返回精确模拟来源/版本/撤回夹具，直接运行本域处理器、状态和数据库验证闭环。

Auth、Memory 的当前协议通过测试配置下的精确服务/用户登记替身覆盖，生产配置缺失仍前置拒绝。peerMode 显式选择 fixture/http，HTTP依赖不可用、版本不支持和拒绝不能自动回退。由此本会话可在没有另外三个实现提交时完成所有本期任务，不留下空实现或等待任务，也不把 fixture 结果声称为真实服务已接通。

共享根配置只提交本业务必需的最小差异/SQL与check，Auth/Memory全局登记、初始化runner的bk_business allowlist及真实来源接入由协调合并。合并后的生产者互通、组织范围和浏览器联调单独记录，不纳入本 change 的实施完成前置条件。

### 8. Supervisor 计划预览与写动作确认分为两次调用

新增门店复盘专属 Supervisor 技能（agent-service/src/main/resources/skills/supervisor/store-business-review.md），保留 trade-agent 的业务身份与 Memory 范围。通过动态目录分别暴露只准备计划的 prepareBusinessReviewPlan 与写入 confirmBusinessReviewPlan MCP capability：准备结果返回 planId、planVersion、参数 hash、证据/知识版本、负责人和期限，不创建已确认动作；Supervisor 展示该快照后，只能通过 confirm capability 提交相同 planId、expectedPlanVersion、hash 和 requestId。写入 capability 的精确目录 ID 加入隔离验收配置的 agent.supervisor.orchestration.approval-capabilities，用户拒绝或未批准时不得创建 ReviewAction 或 ActionReceipt。

确认端仍在 business-service 复核当前用户、门店范围、证据/知识版本和幂等键；Supervisor 的批准不能代替服务端授权。计划变化须重新准备和展示，拒绝后可用原计划/回执查询恢复，不可用新的 requestId 重复生成已确认动作。领域确认能力不得藏在同一次 trade-agent A2A 调用中。门店 change 只维护自身 Supervisor 技能文件和隔离配置差异，共享审批列表在合并后由协调会话并集更新。独立 fixture 验收经过实际 Supervisor 工具目录/审批路径，不等同真实 Auth、Memory 或跨服务生产联调。

## Risks / Trade-offs

- [模拟样本看起来像真实经营] → dataset、来源模式随 API/结果/导出保留，模拟观察不进入真实增长证明。
- [重复确认或丢回执导致重复任务] → request hash、expectedVersion、事务唯一键及原回执查询。
- [知识或房源更新后继续执行旧计划] → 确认与后续使用前复核依赖版本，失效即要求重新确认，已完成执行事实保留。
- [组织范围尚未有可信映射] → 接口依赖 Auth 适配验证，不用店名、工号或模型字段代替权限。

## Migration Plan

以下为合并后的共享环境计划；独立会话使用自身 fixture/隔离库验证行为并交付可应用步骤，不操作共享栈。

1. 增量增加 review 表及 schema 检查；原 KPI 表、API 和历史数据保持可读，不把历史看板行改写为已执行动作。
2. 实现输入、指标和事项/动作状态，以及房源/知识 v1 HTTP适配；独立验收用明确 fixture 运行全链，合并后将显式 HTTP配置指向已实现的生产者核验互通。
3. 在现有 Agent 身份下发布技能及结果 DTO，验证 Memory 必需接入和写动作确认；不启用外部发送。
4. 使用固定模拟数据完成带看停滞与房源维护两条功能验收；分别报告功能、效率采样和真实效果未验证状态。
5. 回退停止创建新复盘事项并撤下新技能，保留已确认动作、回执及观察记录，不清空数据或重放完成动作。

## Open Questions

首批 fixture 的具体字段值、门店代号和展示示例可以在实施中替换，但必须满足既定来源、模式、事件时间及版本契约。真实经营 feed 的地址和业务样本不构成本期模拟功能验收的前置条件。
