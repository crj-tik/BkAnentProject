# 房源经营域合并设计

## Context

动机见 proposal.md。当前 `CompareAnalysisServiceImpl` 先要求 `compare.report.create`，再经 `CompareListingReadClient` 使用签名授权逐套读取 `ListingDTO`；`ListingMasterRpcService.getListingById` 这类旧无凭据读取入口会拒绝请求。KI-115 已修复：报告缓存按 owner 和快照指纹隔离，单位换算由 `ListingComparisonFacts` 处理。尚未覆盖的是稳定来源引用/版本，以及历史详情、分享和 PDF 返回前的当前来源复核。

比较服务目前包含 REST `/compare/**`、MCP `compare-mcp-server`、A2A `compare-agent` 和 Long ID-only 的 `CompareEngineRpcService`。报告缓存将 JSON 元数据与 PDF 文件写入 `compare-report-data` 命名卷；报告可由 owner 显式发布为匿名可读分享。`listing-master-service` 则以本地 `listing_info` 为主要数据模型，负责 CRUD 和 RAG outbox/index，尚无上游接口来源适配器。Studio 是另一条工作区命令链：它经签名 RPC 读取当前房源、核对候选版本并写入自己的 `listing.comparison` 工件和回执，不调用 Compare 报告服务。

用户确认：业务数据目前模拟，房源以接口接入。现有本地记录不因此自动成为上游 REAL 数据；本 change 需要增加来源端口，并为存量记录提供明确的 `legacy-local` 来源身份。现有测试包括 `ListingBusinessAuthorizationTest`、Compare 缓存/分析测试、Studio 授权读取和两域技能装配测试；合并时需迁移对应行为验证。

## Goals / Non-Goals

**Goals:** 在现有 `listing-master-service` 内统一授权快照、对比与报告；上游接口可由模拟实现替换，派生结果保留来源、版本和数据性质。

**Non-Goals:** 不建设客户 CRM、成交状态机或真实上游写入；不改 Auth/Memory 通用协议、不默认启用全局授权或 Memory Auth 上下文模式，不合并 RAG，不在本 change 实施知识或营销调整。房源比较会消费现有 Auth/Memory 契约，并同步本领域配置。

## Decisions

### 1. 保留房源模块名，比较成为内部业务包

将现有比较计算、报告缓存、PDF、响应模型和必要入口迁入房源模块的 `comparison` 包，保留 `listing-agent`，新增 `listing-comparison` 技能。Compare 的签名读取与 owner 隔离是已修复行为，迁移时应保留；新域再加入稳定来源版本和报告读取时的当前来源复核。相比继续保留两个对外 Agent，此方式减少同一组事实在两个身份间来回读取；相比同时重命名为新服务，保留模块名可缩小部署与身份变更范围。报告有自己的持久生命周期，但其事实来源仍归房源域。

### 2. 接口事实与本地维护记录分别持久化

定义 `ListingSourcePort`，提供有界搜索、批量详情和当前版本核验。首期提供显式 `legacy-local`、`fixture` 与 `http` 适配器；`legacy-local` 只读现有 `listing_info`，为旧 Long ID 建立稳定来源引用，不替代现有管理写入和 RAG 索引。HTTP 地址/凭据来自受管配置，端点未配置或不可用时返回不可用，不回退到 fixture 或 `legacy-local`。fixture 使用相同契约并标记 `SIMULATED`，测试可通过本地受控 HTTP 夹具覆盖映射。

输入固定包含 `sourceSystem/sourceId/sourceRevision/observedAt/dataMode`、事实字段及金额单位；无上游 revision 时用规范化投影 hash，不能用抓取时间冒充版本。`legacy-local` 无来源性质证明时标记 `UNKNOWN`；只有服务端配置能确认模拟数据时才标记 `SIMULATED`。新增稳定来源映射，外部字符串 ID 不截断或哈希成可能碰撞的旧 ID。DTO 兼容层显式映射身份。

来源模式由服务端适配器确认：已确认真实为 REAL、模拟为 SIMULATED、性质不明为 UNKNOWN；派生材料只有全部来源均为 REAL 才为 REAL，含未知来源为 UNKNOWN，已知真实/模拟混合为 MIXED。数据模式与指标是否可计算是不同字段，不以 UNKNOWN 来源替代具体指标缺口说明。

上游价格、可售状态等为外部事实，本地只维护资料补充、维护事项和受权投影。没有上游写接口时，完成本地待办只代表操作人提交了处理记录，不能宣称上游已调价或已核验。既有 SQL 管理 API 和 RAG 索引职责继续保留；`legacy-local` 是显式选择的迁移来源，不是 HTTP 故障时的回退路径。

### 3. 比较只使用同次授权和量纲一致的快照

比较请求使用 2–6 个稳定房源引用和预期 revision/contentHash，通过 `snapshots/batch-read` 获取同次获准快照；返回报告前调用 `snapshots/check` 复核当前身份、逐套读取权和来源版本。金额输入明确单位，旧万元字段只在 legacy 边界换算一次。新报告指纹按规范化的 `sourceSystem/sourceId/revision/contentHash` 和规则版本构造，不沿用仅以 Long ID 排序的旧 DTO 指纹作为跨来源身份。缺失字段显示未知，硬条件不能被模型解释覆盖。

生成、缓存命中、历史详情、分享和 PDF 导出前都核验当前来源和读取权。来源改变时历史报告标为 `STALE`，可明确请求新报告；撤权时不返回旧正文/PDF；依赖故障返回结构化不可用。owner-only 报告索引和正文/文件校验替代裸 shareCode 读取。当前实现允许 owner 显式发布匿名公开分享；本 change 按新契约退役该授权方式，旧公开标记不再放行匿名读取，原文件保留供受控核对。

Studio 保留自己的工作区事务、候选版本、幂等回执和 `listing.comparison` 工件；它改用稳定来源引用与当前版本核验，并复用房源域确定性比较逻辑，不转为生成通用 Compare 报告或 PDF。

### 4. 合并时保留历史执行和 Memory 身份

已接受的 compare 调用继续按原 descriptor/taskId 对账或排空，不创建 listing 替代任务；旧检查点仍只读。新请求使用 listing 身份及必需 Memory（LR-22/23/96）。历史 `compare/compare-agent` 数据不批量改名，也不因 owner 相同开放给 listing；确需引用时使用现有受权共享导出。先保留已登记只读历史能力，确认无在途依赖后再停旧实例，不提前撤销原验证公钥。

新比较继续由 `listing-master-service/listing/listing-agent` 的既有 A2A Memory Hook 负责回合前读取、核当前来源和成功归档；`memory-service` 已有对应 caller/domain/agent allowlist，不创建 `listing-comparison-agent` 或重复注册。Memory 的基础生命周期必需，故障在模型和工具前失败关闭。`MEMORY_V2_AUTHORIZATION_CONTEXT_ENABLED` 是独立可选运行模式：在 Auth 公钥、上下文验证和部署门禁通过前保持关闭，不能把该可选开关当作关闭基础 Memory 的办法。旧 `compare-engine-service/compare/compare-agent` Memory 及其读取信任须在历史访问和未决任务完成前保留。

### 5. 对公开契约明确迁移

新的规范比较入口是 `listing-agent` 的 `listing-comparison` 技能及其稳定来源引用/预期版本输入。Supervisor 从 `compare-mcp-server:compareListings` 迁到该技能。现有 `/compare/**` REST 可暂作兼容适配，只将 Long ID 解析为 `legacy-local` 并执行当前授权和来源复核；旧 MCP、独立 `compare-agent` 和 `CompareEngineRpcService` 在消费者迁移后停止接收新请求。`CompareEngineRpcService` 的接口虽只有 Long ID 参数，当前分析服务仍要求已验证的操作上下文并经签名 Listing 读取；迁退它的原因是契约无法表达跨来源引用和预期版本，不是把它描述为当前无鉴权。

当前显式发布的匿名分享能力不迁入新域；旧分享码只保留为迁移定位符，未通过当前用户及资源检查时不得返回报告或 PDF。Studio 仍由工作区命令维护工件和回执，只迁移来源读取与确定性计算。通用动态目录不增加关键词路由，新增技能不引入顺序检查器。

使用当前 Auth 版本化签名上下文与新鲜度证明。Agent A2A 能力必须在稳定 `CapabilityId` 配置中精确映射；将 `a2a:listing-agent` 映射到基线动作 `listing.read`，不得用 `agent.chat.use` 或 Agent Card domain 推导授权。比较创建和本人报告读取分别要求签名上下文中的稳定动作标识 `compare.report.create` 与 `compare.report.read.own`，每套来源读取按 `listing.read`/`listing.read.all` 复核；这些基线动作不属于角色权限。Auth 目录保留稳定动作码并将目录元数据归入 `listing`；目录行在基线权限退役迁移中停用、既有角色关联清除，Listing 目录迁移不重新激活或新增授权。目录元数据变化时同步权限修订号和 outbox，令已有签名上下文刷新。报告仍由 `compare-report-data` 卷保存，不新增比较业务表或 `bk_compare` 库。来源读取仍校验 owner、broker映射、资源状态和版本，签名动作不能替代资源归属检查。A2A 使用已绑定的 Auth context/freshness 并为请求正文生成逐请求证明，由接收端本地验证；不在每个工具调用中远程请求 Auth。缺少签名动作、证明或接线时失败关闭。


Supervisor 的找房对比技能必须显式选择 `listing-agent` 当前发布的 `listing-comparison` 能力，并通过活动目录与调用记录确认实际目标。迁移后不得再经旧 compare MCP 或无凭据 RPC 绕行；新能力缺失时明确停止。房源比较保持只读，不加入写操作审批清单。

### 6. 配置项跨文件同步

配置变更以逐键矩阵驱动，记录配置所有者、Spring/Nacos 属性名、环境变量名、当前值/默认值、目标值、涉及文件、阶段、敏感性和退役门槛。矩阵覆盖本地 `.env`、跟踪模板 `.env.example`、服务配置模板、Nacos YAML 与 `docker-compose.yml`；新增 Listing 来源适配和 `listing-agent` Auth 映射必须在各适用层一致，Compose 必须把需要的环境变量实际传入容器。外部接口地址与凭据只经受管配置/秘密注入，不写入仓库。
配置清单至少覆盖 `nacos/agent-service.yaml`、`nacos/listing-master-service.yaml`、`nacos/compare-engine-service.yaml`、`nacos/auth-service.yaml`、`nacos/memory-service.yaml` 中的增删/保留项，以及 Compose 中 Agent、Listing、Compare、Auth、Memory 服务环境映射和卷挂载。Compare 配置文件及其 Nacos 发布项按回退窗口保留或退役，不能因模块删除漏掉客户端 endpoint、密钥变量或历史身份。
对应服务的 `src/main/resources/application.yml` 也纳入对照，尤其是 Agent capability、Listing ingress、Memory Auth 上下文及 Compare 报告/Memory 参数；同一属性的本地默认和 Nacos 默认不得相互矛盾。

Compare 专属的 MCP endpoint、Compose 服务项、A2A Card/MCP 服务项、报告存储路径及服务级 Memory 参数按调用迁移结果分阶段退役。`COMPARE_ENGINE_SERVICE_IDENTITY_*`、Auth trusted identity、Memory trusted caller 等身份配置只有旧任务排空且历史 Memory 访问不再依赖时才可删除；`compare-report-data` 必须迁挂到 Listing 并保留，不因服务块删除而删除卷。

当前 `.env.example` 将 `MEMORY_V2_AUTHORIZATION_CONTEXT_ENABLED` 设为 `true`，而 Nacos 默认值是 `false`，Compose 未将该变量传给 Memory 服务。方案将该可选模式的安全默认统一为 `false` 并同步显式 Compose 映射；本地 `.env` 需按此门禁协调值，保留其他本地密钥，不提交 `.env`。只有 Auth 公钥、版本化 Auth 路径和 Memory 上下文验签部署验收通过后，才可另行显式开启。Auth/Agent/Listing 各运行开关及授权 Redis 的已有关联项同样纳入矩阵，不能只改 Nacos 或只改 Compose。

配置验收逐项确认环境变量在模板有定义、Compose 有传递、Nacos 占位符与属性绑定一致；检查被废除变量无运行引用、默认值一致、旧信任/卷未过早删除、秘密未提交。共享 Nacos 发布和运行切换仍属于合并后协调门禁。

### 7. 并行会话交付边界

本会话拥有房源/比较模块、`common/listing/**`、原比较契约及必要调用方的最小兼容差异。公开提供 `domain-parallel-v1` 的 search、batch-read、check 三个房源 POST 接口，路径、字段、错误和模式规则固定于 [并行总览](../../../docs/business-domain-change-roadmap.md)。本会话用自有 HTTP夹具/生产者处理器测试这些线协议，不等待经营/营销消费方。

独立验收使用签名 Auth 上下文、新鲜度及精确操作集合的测试 fixture，并以隔离 Memory 测试身份验证成功、拒绝、撤回和不可用；真实运行仍保留失败关闭。KI-170 的源码修复不代表新 Auth 链已部署。不得修改 Auth/Memory 通用协议或共享在线登记。自己的独立 worktree/分支、`.m2repo`、测试数据库与端口按总览隔离；只 push 自身工作分支。

根 POM、部署和 Agent 必要引用只提交与 compare/listing 有关的小范围语义差异。全局 Memory/RAG 历史登记保留；新增/退役密钥或共享 Nacos 的真正发布交协调门禁。本 change 完成条件是独立业务/来源/迁移/回退验证、逐键配置矩阵和可应用配置差异，真实共享环境切流和排空不在独立会话执行。


## Risks / Trade-offs

- [上游字段和单位不同] → 规范输入独立于旧 DTO，适配器校验单位、必填身份和版本，拒绝模糊数据。
- [一次比较读取期间来源变化] → 结果冻结版本并在输出/使用前复核；不能取得可核验快照时明确失败。
- [已有显式公开分享用户依赖匿名链接] → 新域不提供 bearer 式匿名访问；旧公开分享按私有/退役处理，保留原文件和迁移清单，不能按分享码或文件路径猜归属。
- [报告文件卷迁移期间出现双写或遗漏] → 对账 `compare-report-data` 中的 JSON/PDF 清单，切换时先停旧 Compare 写入，再由 listing 单独挂载并成为唯一写入方。
- [停 compare 导致任务丢关联] → 先清点并排空/对账，保留旧身份及部署回退制品；迁移回滚不重放业务调用。
- [模拟结果误当真实事实] → 数据模式从来源继承到报告/PDF/Agent 输出，不能由请求者改成 REAL。

- [配置散落在本地环境、模板、Nacos 与 Compose 后发生漂移] → 使用逐键配置矩阵核验属性、环境变量、默认值和传递链；保留 `.env` 的本地秘密但不提交，旧 Compare 信任和报告卷按排空门禁退役。
- [默认关闭的 Auth/Memory 上下文模式被模板误开] → `.env.example`、Nacos 和 Compose 统一为安全默认，只有 Auth 公钥、Redis ACL、上下文校验与服务消费路径验收完成后显式开启。
## Migration Plan

以下是合并后由协调会话执行的共享环境计划。本会话实现相应兼容、围栏和回退能力，并在私有夹具/隔离库验证，不等待其它 change、不操作现有开发栈。

1. 固化 `/compare/**`、Compare MCP/A2A/RPC、Supervisor 与 Studio 调用方、Compose/Nacos 发布项、`.env`/`.env.example`/配置模板、`compare-report-data` 卷以及历史 Auth/Memory 身份。形成逐键配置矩阵，标明新增、迁移、保留、废除、生效门槛及回滚值；不读取或提交本地密钥。
2. 在 listing 中实现 `legacy-local`、fixture 和 HTTP 来源适配，以及稳定引用/版本契约；先用 fixture 和现有本地数据映射验证授权、版本、金额，再按受管配置接上游 HTTP。来源 provider、endpoint 和凭据属性统一命名，并同步到 Nacos、Compose、`.env.example` 与本地 `.env`。
3. 迁入比较计算、报告缓存和 PDF；切换时将 `compare-report-data` 卷受控挂载给 listing，核验 JSON/PDF 与 owner/source hash 元数据，确保旧 Compare 停写后 listing 成为唯一写入方。
4. 发布 `listing-agent` 的 `listing-comparison` 技能并更新 Supervisor；配置精确 `a2a:listing-agent` 权限映射及 Listing ingress Auth 验签所需设置。Studio 保留工作区工件/回执语义，改用稳定来源版本。复用 Listing 既有 Memory Hook 和身份；旧 `/compare/**` 仅保留当前授权的 `legacy-local` 兼容，按旧身份排空或登记未知任务。
5. 在 `.env.example`、本地 `.env`、服务模板、Nacos 与 Compose 同步新增/废除项；核对 Compare MCP endpoint、Compose 服务块、报告路径和 Compare Memory 参数。将 `MEMORY_V2_AUTHORIZATION_CONTEXT_ENABLED` 默认统一为 `false` 并显式透传；Auth、Agent、Listing 和 Memory 上下文模式只有对应密钥/Redis/签名验证门禁通过后才开启。保留旧 Compare Auth/Memory 信任直到在途任务和历史读取排空。
6. 验证没有新消费者和活动调用后，从 reactor、Compose 和 Nacos 发布清单中退役独立比较部署及已无消费者的配置；报告卷、历史 Memory 空间及其必要读取身份保留。回退恢复原匹配版本、卷和路由；旧任务按原身份恢复，不重放或批量重生成报告。

## Open Questions

真实房源接口的受管地址、凭据、字段映射和速率额度由部署接入提供。规范契约、模拟验收和上述任务拆分不依赖这些具体值；其补齐仅决定真实 HTTP 适配的联机验收时点。
