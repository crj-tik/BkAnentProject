# Knowledge 服务交接与运行配置

## 拆分边界

| 数据或入口 | 归属 | 本 change 的处理 |
| --- | --- | --- |
| Interview session、turn、asset、原始/脱敏逐字稿、来源修订、发布、报告与报告任务 | `interview-service` / `bk_interview` | 不改 Java、Spring/Nacos 配置、源表、迁移或旧 ID。只由 `bk_knowledge.knowledge_interview_public_source_v1` 投影当前合格的脱敏来源。 |
| 新经营案例、打法、不可变 revision、审核决定、发布状态、来源定位、生成任务、操作回执 | `knowledge-service` / `bk_knowledge` | 新服务、新 schema 和新身份；不回写 Interview，不复用旧 ID。 |
| 签名身份、AuthorizationContext/AuthzFreshness、操作目录、可信 STORE identity | `auth-service` | 只消费现有验签协议；本 change 交付待协调登记的精确操作，不改 Auth Java 或猜测角色映射。当前 STORE 身份缺失时门店动作失败关闭。 |
| Agent 工作集与轮次记忆 | `memory-service` | 使用现有必需 Memory hook；只登记 `knowledge-service / knowledge / knowledge-agent` 和其公钥，不改 Memory Java、不开放经验共享。 |
| Knowledge Agent 发现和受众 | `agent-service` | 仅增加 `knowledge-agent → knowledge-service`、精确 invoke capability 和最小动态发现元数据；不增加 Supervisor Graph 分支。 |

Interview 的 `playbook_card` 与 `commonality_report` 目前是旧域表/实体占位，尚未发现对应的业务 mapper 查询、服务实现或报告消费入口。此仓库检查只确认结构和代码引用，不连接数据库，因此不推断表为空，也不读取报告正文或行数。

v1 中一个经营案例和它的结构化打法是同一 Knowledge 聚合：案例元数据由 `knowledge_case` 保存，打法/证据保存在不可变 `knowledge_revision.body_json`，共享 `knowledgeId` 并通过 revision 追踪。不会为相同内容再生成独立 Playbook ID 或可变 Playbook 表；若未来允许一个案例挂多个独立打法，应单独扩展版本化合同。

| 旧事实 | 可见字段 | 迁移/映射建议 |
| --- | --- | --- |
| `playbook_card.id`、`asset_id`、`title`、`quote_registry`、`constraint_status` | 旧 ID 与来源 asset ID；quote registry 可能包含旧原声编号 | 不搬迁或复用旧主键。未来如需整理，先按 asset ID 经只读视图重验当前 PUBLISHED 快照，再在 Knowledge 创建新 ID 和新审核；旧 quote registry/原声不作为自动迁入正文或可信 locator。 |
| `commonality_report.id`、`asset_ids`、`profile_doc`、`selling_doc`、`confidence`、`individual_signals` | 多 asset 来源列表、报告正文和按样本数生成的置信字段 | 不导入报告正文、个体信号或 confidence，不转成 `VERIFIED` 证据。未来只可将有效 asset ID 作为待人工核验的来源建议，并由 Knowledge 新任务/新 ID 重做。 |
| `interview_report_task` 及其历史 task ID/快照 | 旧任务事实 | 不重放旧任务、不转写为 Knowledge generation task；Knowledge task 使用自身 ID、请求回执与输入/规则 hash。 |

以上为代码/迁移定义盘点，不构成旧记录计数、存量清理或迁移完成声明。

## Auth 操作与资源矩阵

调用方必须携带已验签的 Auth `AuthorizationContext` 与 `AuthzFreshness`。actor 只从本地验证上下文读取，`agent.workflow.invoke.knowledge` 不授予任何 Knowledge 业务权限。

| 操作码 | 服务入口 | 资源边界 |
| --- | --- | --- |
| `agent.workflow.invoke.knowledge` | `knowledge-agent` A2A invocation | 仅允许调用 Agent；工具内仍逐项检查 Knowledge 操作。 |
| `knowledge.case.create.own` | `POST /knowledge/v1/cases` | 只允许创建 OWN；请求的 `storeId` 被拒绝。 |
| `knowledge.case.edit.own` | `POST /knowledge/v1/cases/{id}/edit` | Auth actor 必须等于该案例 owner；仅 DRAFT/WITHDRAWN 可新建 revision。 |
| `knowledge.case.submit.own` | `POST /knowledge/v1/cases/{id}/submit` | OWN 案例、expectedVersion 与来源快照均须当前。STORE submit 暂无操作码，失败关闭。 |
| `knowledge.case.generate.own` | `POST /knowledge/v1/cases/{id}/generate`、Agent generation tool | OWN owner 才能启动/查询自己的生成任务；相同 requestId 复用持久结果。STORE generate 暂无操作码，失败关闭。 |
| `knowledge.case.review.decide.own` / `.store` | `POST /knowledge/v1/cases/{id}/review` | 对应范围的审核操作；审核人不得与 owner 相同；只记录人工决定，不执行发布。STORE 仍须可信 STORE identity。 |
| `knowledge.case.publish.own` / `.store` | `POST /knowledge/v1/cases/{id}/publish` | 对应范围、expectedVersion、当前来源和该 revision 的 APPROVE 决定全部有效。STORE 仍须可信 STORE identity。 |
| `knowledge.case.withdraw.own` / `.store` | `POST /knowledge/v1/cases/{id}/withdraw` | 只能撤回对应范围当前已发布的案例。STORE 仍须可信 STORE identity。 |
| `knowledge.case.read.own` / `.store` | 案例详情、published search、batch-read、check | 每个资源按 owner/store 绑定授权；每次输出前检查精确 published revision 和所有 Interview 来源。STORE 仍须可信 STORE identity。 |

`OWN` 的审核操作由获授权的审核员执行，而不是案例 owner 执行。`knowledge.case.review.decide.own` 仅允许读取 `REVIEW_PENDING` 的待审版本；它不授予草稿、已发布、已撤回详情或搜索权限。`knowledge.case.read.own` 仍按 owner 限定；owner 不能审核自己的案例。审核批准只记录决定并保持待审，之后 owner 才可发布。

| Auth 协调角色/身份 | 建议精确操作 | 当前状态与资源内二次约束 |
| --- | --- | --- |
| `KNOWLEDGE_AUTHOR`（建议新增） | create/edit/submit/generate/read/publish/withdraw `.own` | 尚未在 Auth 注册；所有 OWN 写入/读取仍须 actor 等于记录 owner；publish 还须当前 revision 有另一审核人 APPROVE。 |
| `KNOWLEDGE_REVIEWER`（建议新增） | `knowledge.case.review.decide.own` | 尚未在 Auth 注册；只可读/审核待审版本，审核人必须不同于 owner；不因此获得发布、搜索或其他状态读取权。 |
| Agent 调用者 | `agent.workflow.invoke.knowledge` | 单独登记调用能力；不能替代任一 `knowledge.case.*` 动作。 |
| STORE 运营角色 | `.store` 的 read/review/publish/withdraw | 暂不授予；需先由 Auth 签发可信 `STORE` identity，再协调角色权限。Knowledge 依据受签 identity 的 storeId 二次校验记录。 |
| `businessAdmin=true` 的授权主体 | Auth 当前实现会获得全部已登记且 active 的操作码 | 这是 Auth 授权事实字段，不可由 `ADMIN` roleCode 推断；Knowledge 仍执行 owner/STORE 资源检查及审核人分离。需在 Auth 协调中确认该既有全目录策略符合发布要求。 |
| 现有 `BROKER` / `MANAGER` | 不自动新增 Knowledge 操作 | BROKER 当前显式权限清单不含 Knowledge；MANAGER 虽有 seed role，但非版本化授权路径仅支持 ADMIN/BROKER。Knowledge 权限目录与角色分配需由启用版本化 Auth 的协调变更完成。 |

当前 Auth 权限目录/角色策略没有由本 change 修改或在共享环境登记。Auth 支持版本化权限目录与角色权限映射，但操作码必须先 active 注册，之后才能分配给角色；本 change 只交付协调矩阵，不更新 Auth Java、SQL seed 或共享授权。不要把建议角色视为已存在配置。虽然预留 `.store` 操作码，当前 Auth 不签发可信 `STORE` identity，因此所有门店 read/review/publish/withdraw 和任何 STORE 案例路径都拒绝，不接受请求体 `storeId`、actor ID 或模型输出作为替代。

## 配置同步矩阵

当前 worktree 未发现本地 `.env`；`.env.example` 仅有变量名与空/安全默认值。此 change 不写入或提交部署者秘密，也没有废弃 Interview 配置键。

| 配置面 | 新增/调整 | 密钥及开关 |
| --- | --- | --- |
| `.env.example` / Compose 环境 | `MYSQL_DB_KNOWLEDGE`、Knowledge primary/source URL、两个 DB 用户及密码、`KNOWLEDGE_SERVICE_BASE_URL`、Memory compression、Auth 开关 | Compose 的空 URL 默认落到容器网络 `mysql:3306/bk_knowledge`；宿主机运行需显式配置 URL。`scripts/deploy/init-environment.sh` 在 `full` profile 下为两个数据库密码补随机值；部署前仍要由 DBA/数据库初始化流程为锁定账号设置同一秘密并解锁。Auth 默认关闭。 |
| `application.yml` | 9015、Nacos import、primary/source 双 DataSource、view/sanitizer 版本、Auth ingress 公钥、服务身份、必需 Memory、Dubbo Nacos、readiness | Nacos/环境提供数据库秘密；用户名/密码缺失或为空时 DataSource 建立失败，不回退 root/H2。 |
| `nacos/knowledge-service.yaml` | 无秘密的服务配置与环境变量占位符、只读视图名/版本、Auth audience、Memory readiness | 不写密码或私钥；`NACOS_CONFIG_DATA_IDS` 仍默认空，发布时必须显式选择。 |
| `.secrets/service-identities/` | `knowledge-service` 独立 Ed25519 keypair；公钥由既有生成器写到 `public-keys.env` | Compose 只读挂载 Knowledge 私钥；生成公钥由 Memory trusted caller 配置引用。不得将私钥放到 `.env`、Nacos、镜像或仓库。 |
| `docker-compose.yml` | 独立 Knowledge 服务/制品、端口健康检查、Memory/Nacos/MySQL 依赖、双 DB 账号/URL、私钥挂载、A2A/Dubbo 与 readiness | Knowledge 容器的 `MYSQL_USER/PASSWORD` 覆盖 root anchor；Interview service block 保持原样。 |
| `nacos/agent-service.yaml` / `nacos/gateway.yaml` | Agent 精确 invoke capability 与动态注册；`/knowledge/**` route 和 audience | 不新增固定 Supervisor Graph 分支，不改变其他路由。 |
| `nacos/memory-service.yaml` | Knowledge 公钥、trusted caller、`knowledge: [knowledge-agent]` | 不授予 experience publisher、shared grant 或其它 agent 域。 |
| `scripts/deploy/publish-nacos.py`、`init-environment.sh` | knowledge data ID allowlist/必需分布式模板列表 | 只允许显式 data ID；默认选择为空，不自动发布模板。 |
| MySQL manifest/schema checks | 510 创建 `bk_knowledge` 和锁定 definer 的 Interview 列权限；520 创建业务表、受限 view 和最小应用/只读 grant | 前向追加版本与 canonical SHA；无 Interview migration 或已应用 hash 改写。账号初始锁定，DBA 密钥注入后再解锁。 |
| MySQL MCP 白名单/说明 | 只读 allowlist 加 `bk_knowledge`，数据库数同步为 13 | MCP 仍是只读可选入口；不授予业务账号权限。 |

账号准备的安全顺序：运行受审 manifest 后，DBA 从秘密管理器取得 `.env` 中两个独立密码，为 `knowledge_app` 和 `knowledge_source_ro` 设置密码并解锁；保留 `knowledge_source_view_owner` 锁定。核对 `knowledge_app` 仅有六张知识业务表权限，`knowledge_source_ro` 仅有 view SELECT，二者对 `bk_interview` 均无权限。密码应在受控 MySQL 客户端交互中输入，避免进入 shell 历史、SQL 日志或工单。部署前静态 schema check 验证 grant 范围；应用 readiness 验证凭据连通性。

## 迁移与回滚

- 变更数据库前由部署者在目标仓库根目录核对 manifest 与配置；该 change 未对任何共享数据库执行 DDL，也未运行 Docker。
- 新迁移只向前追加：510 目标记录位于 `bk_auth.bk_schema_migration`，负责创建 `bk_knowledge` 和受限来源 definer；520 在 `bk_knowledge` 建立业务事实与来源 view。fresh 和 upgrade 均先执行 510 再执行 520。
- 回滚应用版本时停止 Knowledge 服务、撤下 Gateway 路由和 Agent 注册/能力配置；保留已应用的 Knowledge schema、回执和来源 view，不删除 Interview/Memory 数据，不重放旧 Interview report task。
- Auth 操作、Memory key/caller 与 STORE identity 未协调/登记前，保持 Auth 总开关关闭或由入口授权失败关闭；不得靠绕过 Auth、Memory 或扩宽 SQL grant 恢复流量。
- 可选 MySQL MCP 新增 `bk_knowledge` 仅增加只读库名，不影响 Interview 数据库权限或表结构。

## 合并后运行态验收清单（KI-218）

以下事项统一由 OpenSpec 任务 5.1–5.3 跟踪，完成后在 KI-218 更新证据并关闭：

- 数据库迁移、schema/view/grant 检查、两个运行账号凭据与 readiness。
- 目标环境 Nacos 配置审核、`BK-AGENT/DEFAULT_GROUP` 的 `knowledge-service.yaml` 发布与回读，以及 Gateway、Agent、Memory 配置加载。
- Auth 操作码/角色映射及 USER/Agent 正反路径；Auth 未提供可信 STORE identity 时，门店范围保持 fail-closed。
- Memory caller、公钥、完整轮次归档与故障关闭验证。
- 隔离 SIMULATED 来源的导入、生成、人工审核、发布、check、撤回/失效闭环及 sentinel 泄漏检查。

本地代码和模拟夹具不能证明上述运行态条件成立；验收结果不得推断为真实来源或经营效果验证。
