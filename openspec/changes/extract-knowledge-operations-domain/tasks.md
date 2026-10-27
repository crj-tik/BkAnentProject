# Tasks

## 0. 拆分边界与授权基线

- [ ] 0.1 在独立 worktree/分支确认共同 base SHA、Maven 本地仓库和测试资源；登记本 change 只新增 Knowledge 微服务，不改 Interview Java、Interview Spring/Nacos 配置或 bk_interview 表/迁移。
- [ ] 0.2 冻结 domain-parallel-v1 三个 POST 契约和 Knowledge source status；将 Interview 当前 PUBLISHED 资格作为唯一来源准入条件，删除 Interview 用途授权 API、grant 表、wire DTO、操作码和 service proof 计划。
- [ ] 0.3 交付 Auth 操作目录/角色授权协调矩阵：agent.workflow.invoke.knowledge 与全部 knowledge.case.* own/store 操作；store 操作明确依赖可信 STORE identity，登记未完成时真实路径失败关闭。
- [ ] 0.4 建立配置对照表，列明 .env.example、本地 .env（若存在）、Knowledge application.yml、Nacos、Compose、公钥/私钥、MySQL manifest、MCP 白名单和 data ID 的新增/保留/废除项；确认本 change 没有废除仍被 Interview 使用的键。
- [ ] 0.5 与协调会话同步 docs/business-domain-change-roadmap.md 的本 change ownership 行，移除 Interview/common/interview/transcript 实现归属并保留冻结的知识 HTTP 契约；不覆盖其他并行 change 的范围。

## 1. Interview 来源只读适配

- [ ] 1.1 在 bk_knowledge 建 knowledge_interview_public_source_v1 版本化只读视图；复刻当前 Interview asset/case/session 发布资格，仅投影脱敏 content 与必要元数据，不投影 rawContent 或敏感映射；不改 bk_interview schema。
- [ ] 1.2 为视图建立受管 DB owner；Knowledge 主账号只获 bk_knowledge 业务表所需读写权限，来源账号仅获视图 SELECT，二者均无 Interview 表权限且 Compose 不向应用注入 root；从部署秘密提供凭据。
- [ ] 1.3 实现 InterviewTranscriptSource 两阶段读取与当前状态检查：仅选 PUBLISHED、L2/L3、未删除、脱敏版本达标、case 未删除且 session/UPLOAD 关系有效的来源；逐项检查标题/正文凭据形态、SHA-256(content)、snapshotHash 和所有绑定修订；未知结构/版本失败关闭。
- [ ] 1.4 按 assetId 精确固定 assetId、snapshotHash、contentHash、contentRevision、sanitizationRevision、publicationRevision 和 dataMode；生成 quoteHash/locator 并验证原声只能在相同脱敏快照内复核。
- [ ] 1.5 测试 PRIVATE/撤回/删除/低评级/旧脱敏版本/无效 case-session/凭据正文/hash 不符/视图不可用；验证正文不进入模型、来源引用不自动换版，分别返回 NOT_ACCESSIBLE、STALE 或 UNAVAILABLE。
- [ ] 1.6 用 Interview 当前 selectPublicAssets/isPubliclyEligible 条件建立对照验收；视图/实现不得比源服务公开资格更宽，Sanitizer.VERSION 未知或变化时停止来源读取。

## 2. 独立 Knowledge 服务和数据事实

- [ ] 2.1 新增 knowledge-service Maven 模块、9015 服务配置、Health/Readiness、Nacos import 和独立 Compose 服务；验证它可单独构建且不依赖 Interview 实现包。
- [ ] 2.2 在 bk_knowledge 创建 Knowledge 表、幂等生成任务和版本化回执；通过版本化 MySQL manifest 与 schema check 创建主 schema/只读视图及两种账号最小授权，不修改已应用 SQL/check/hash，不新增 Interview migration。
- [ ] 2.3 实现 KnowledgeCase、Playbook、不可变 KnowledgeRevision、SourceRef、ReviewDecision、OperationReceipt 和来源状态查询；禁止共用 Interview asset/report ID 或回写旧事实。
- [ ] 2.4 实现持久化生成任务与输入/规则版本 hash；用重启、回执丢失、相同请求重试、同号异内容及已完成结果复用验证不重复调用模型。
- [ ] 2.5 实现单案例/打法候选及证据质量字段；核验适用条件、未核实项、结果证据级别、UNKNOWN/SIMULATED 继承和稳定引用，不将样本数或来源评级提升为效果证明。
- [ ] 2.6 只读盘点 Interview playbook_card/commonality_report 与报告存量，提交旧 ID 到新身份的映射建议；确认旧表、旧报告任务和置信度记录留在 Interview，不读取报告正文、不假设表为空。

## 3. Auth、知识生命周期与只读消费

- [ ] 3.1 接入当前 Auth AuthorizationContext/AuthzFreshness 验签和精确动作校验；actor 只从本地验证上下文读取，requestId/expectedVersion/content hash 贯穿写操作和回执。
- [ ] 3.2 实现 create/edit/submit/generate/review/publish/withdraw/read 操作和 own/store 资源边界；按 design 精确操作矩阵检查每个 REST/Agent/API 入口，缺操作或缺 STORE identity 时拒绝。
- [ ] 3.3 实现来源依赖实时复核：每次 search/detail/export/Agent 输出/published/check 前复核 Interview view 状态与精确修订；验证撤回、重新发布、hash 变化、版本变化和数据库故障不返回旧派生正文。
- [ ] 3.4 实现 domain-parallel-v1 published/search、published/batch-read 和 status-only published/check；按 roadmap 验证 request/response、权限、revision 与 CURRENT/STALE/NOT_ACCESSIBLE/UNAVAILABLE 语义。
- [ ] 3.5 验证真实运营内容只在人工审核决定后发布；发布回执可靠找回，旧 Interview report task 不重放，合法空结果和依赖故障分开表达。

## 4. Agent、Memory 与配置同步

- [ ] 4.1 新增 official knowledge-agent 并复用 A2aMemoryHookFactory；固定 caller/domain/agent 为 knowledge-service / knowledge / knowledge-agent，在 ReactAgent 和 OfficialA2aAgentExecutor 安装同一 hook。
- [ ] 4.2 验证 Memory beforeAgent 先于模型/工具加载当前工作集，afterAgent 在完整轮次复核来源并归档；缺 caller/key、错误 owner/context、Memory 不可用或归档失败时不执行/不释放结果。
- [ ] 4.3 限制 Agent 消息合同：逐字稿正文与原声只在 Knowledge 内部生成/核验，不作为工具参数、工具响应或 assistant 回显；以唯一哨兵原文检查 Memory 消息、参数、响应和归档均不含原文/quote/rawContent/映射。
- [ ] 4.4 在 nacos/memory-service.yaml 精确登记 Knowledge 公钥、caller 和 knowledge: [knowledge-agent]；不登记 experience publisher、共享经验或 Memory grant；验证撤销登记即拒绝。
- [ ] 4.5 在 OfficialA2aAgentClient.audienceFor 增加 knowledge-agent → knowledge-service；更新 nacos/agent-service.yaml 的精确 capability-permissions 和最小 knowledge-agent 动态注册；不改 Supervisor Graph。
- [ ] 4.6 新增 nacos/knowledge-service.yaml 与 knowledge-service/src/main/resources/application.yml；把主库、只读视图库、Auth 验签、A2A 身份、Dubbo Memory registry、必需 Memory 生命周期和 readiness 配置占位符逐项配对，Nacos 不含密码或私钥。
- [ ] 4.7 更新 .env.example 和存在时部署者本地 .env 键：MYSQL_DB_KNOWLEDGE、Knowledge 主库非 root 账号/密码、source view 只读账号/密码、Knowledge Memory compression、Knowledge service URL；本地秘密值保留且不提交。将 Knowledge 公钥接入现有 public-keys.env 生成/模板流程，私钥独立生成和只读挂载。
- [ ] 4.8 更新 docker-compose.yml 新服务块、制品/Maven模块、独立私钥、Knowledge 非 root 主库与只读源账号、Dubbo Nacos registry、A2A 地址、Memory/Nacos 依赖；同步 bk_knowledge 的可选只读 MySQL MCP 数据库名单与说明数字，Interview service block 保持逐字不变。
- [ ] 4.9 更新 nacos/gateway.yaml 的 /knowledge/** route 和 knowledge-service audience；将 knowledge-service.yaml 加入受管 data ID 清单/部署说明，NACOS_CONFIG_DATA_IDS 默认留空且仍须显式选择。
- [ ] 4.10 扫描所有 .env、Spring、Compose、Nacos、密钥生成、README、data ID、MySQL manifest/MCP 白名单引用；确认新增项命名和默认值一致，废除项清零，Interview 原配置没有误删。

## 5. 验收与交接

- [ ] 5.1 在本 change 独立 worktree 和本地 Maven 仓库运行 Knowledge 模块编译、聚焦测试、视图/schema check 与配置静态检查，不拉取其它业务 change 制品或写共享数据库。
- [ ] 5.2 验证 USER/Agent Auth 正反路径、Memory 未登记/不可用失败关闭、PRIVATE/撤回/STALE/UNAVAILABLE 来源、原声定位和 Memory sentinel 不泄漏。
- [ ] 5.3 在明确标记 SIMULATED 的隔离 Interview 来源完成导入→生成→人工审核→发布→当前 check→来源撤回/失效闭环；全程保留 UNKNOWN/SIMULATED 语义，不声称真实效果。
- [ ] 5.4 交付新服务、只读视图/账号、Auth 操作、Memory caller、公钥、Nacos data ID、.env/Compose 配置和回滚手册；注明本地 .env 未提交、Interview Java/config/schema 未变更、真实 Auth/Memory/STORE/下游运行态仍需协调验收。
- [ ] 5.5 完成 OpenSpec 校验和差异检查后，以中文提交说明提交并推送当前 change 分支，记录最终 SHA；不运行 Docker 部署或改动共享运行环境。