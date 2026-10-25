# 四会话并行业务域 Change 清单

日期：2026-10-25。四个 change 均为规划，实施任务尚未执行。本文的并行约定取代初版“经营复盘等前两项就绪再验收”的串行安排；每个会话都能从同一基线独立实现、构建、验收和提交自己的 change。

## 用户目标与输入事实

- 首批用户为知识运营人员和店长，目标是房源维护、带看、成交推进提效及辅助业绩增长，首期范围不含新人带教。
- 当前经营数据模拟；房源经接口接入；逐字稿属于访谈域。接口真实地址和凭据由部署配置提供。
- Auth 由另一会话完善。四个 change 使用既有可信身份边界及可替换的范围适配器，独立测试使用明确的测试替身；真实范围未接通时受保护操作失败关闭。
- 四个 change 没有相互的实施完成前置条件。其业务服务互相消费公开接口；独立完成以固定契约及本会话 fixture 验证，合并后的真实跨服务联调单独报告。

## 会话与修改范围

| 会话/change | 主责代码和契约命名空间 | 独立交付内容 | 建议工作分支 |
| --- | --- | --- | --- |
| [房源合并](../openspec/changes/consolidate-listing-comparison-domain/proposal.md) | `listing-master-service`、`compare-engine-service`、`common/listing/**`，原比较契约/纯计算及其必要调用方 | 房源接口适配、同域比较/PDF、比较服务退役配置与迁移方案 | `codex/consolidate-listing-comparison-domain` |
| [知识拆分](../openspec/changes/extract-knowledge-operations-domain/proposal.md) | `knowledge-service`、访谈的窄来源读取适配、`common/knowledge/**`、`common/interview/transcript/**` | 逐字稿消费、知识审核发布、知识 v1 读取接口及独立身份配置片段 | `codex/extract-knowledge-operations-domain` |
| [门店复盘](../openspec/changes/implement-store-business-review/proposal.md) | `business-service`、`common/business/review/**` | 本地经营事件、房源/知识 consumer adapter、诊断、动作与反馈闭环 | `codex/implement-store-business-review` |
| [营销合并](../openspec/changes/consolidate-marketing-promotion-domain/proposal.md) | `marketing-content-service`、`promotion-service`、`common/marketing/campaign/**` | 内容/计划/尝试/效果统一、provider 适配及推广退役方案 | `codex/consolidate-marketing-promotion-domain` |

命名空间以上述 common 的 Java 包根 `com/bkanent/common/` 为前缀。各会话只更新本 change 的 tasks；独立 consumer DTO 放在消费模块自己的 integration 包，不导入另一个 change 尚未合入的 Java 类型，也不重写生产者模型。既有 `ListingDTO` 等共享类型默认保持兼容，转换在本域边界完成。

## 工作区、构建与运行隔离

1. 开始前由协调会话固定一次共同基线提交；四个会话各自从该提交创建独立 worktree 和上述工作分支，记录各自路径/分支/base SHA。禁止四会话共用一个 checkout、暂存区或都在 dev-fix 上提交。
2. 每个会话只向自己的远程分支提交和 push，不自行将其他会话分支合入或推送到 dev-fix。完成后交付 branch、head SHA、验证证据及共享文件差异，协调会话负责合并。
3. Maven 使用每个 worktree 已忽略的 `.m2repo/` 作为 `-Dmaven.repo.local`，避免共同的 SNAPSHOT install 覆盖。构建与测试工作目录必须为本会话 worktree，不引用另一会话的 target/JAR。缓存缺少依赖可正常下载，不清理其他会话缓存。
4. 独立验收优先使用本进程 H2、injvm 和本地有界 HTTP fixture；需要容器时使用不同 Compose project、端口、数据库与卷，运行前记录本会话所有权。不得重启/迁移现有 `bk-anent-dev`、发布共享 Nacos、轮换共享密钥、provision 共享 RAG 空间或关闭别人的容器。
5. fixture 必须显式选择并标记 SIMULATED；HTTP/RPC 模式依赖不可用时不得回退 fixture。Auth/Memory 测试替身只在测试配置可用，生产接线缺失仍前置失败；不为并行开发增加无记忆或免鉴权旁路。

## 冻结的跨会话契约 domain-parallel-v1

以下路径为本轮新增的目标契约，不声称当前服务已提供。房源会话实现 listing 生产者；知识会话实现 knowledge 生产者；经营和营销会话分别实现自己的消费适配器和本地契约 fixture。读取使用当前专用凭据/可信上下文；请求里的门店、用途和 dataset 只是选择条件，不授予权限。

| 生产者 | 方法与路径 | 请求与返回 |
| --- | --- | --- |
| 房源 | POST `/listings/business/v1/search` | keyword、cursor、limit（默认20、最大100）；返回房源快照列表和 nextCursor |
| 房源 | POST `/listings/business/v1/snapshots/batch-read` | 1–20个 refs，包含 sourceSystem/sourceId/可选 expectedRevision；返回精确快照 |
| 房源 | POST `/listings/business/v1/snapshots/check` | 1–20个精确 refs（含 expectedRevision）；返回每项 CURRENT/STALE/NOT_ACCESSIBLE/UNAVAILABLE |
| 知识 | POST `/knowledge/v1/published/search` | problem、scene、tags、cursor、limit（默认20、最大100）；返回当前获准发布知识的摘要和 nextCursor |
| 知识 | POST `/knowledge/v1/published/batch-read` | 1–20个 refs，包含 knowledgeId/revision；返回精确已发布 revision |
| 知识 | POST `/knowledge/v1/published/check` | 1–20个 refs，包含 knowledgeId/revision/contentHash；返回每项 CURRENT/STALE/NOT_ACCESSIBLE/UNAVAILABLE |

固定响应沿用 `ApiResponse(success,code,message,data)`，data 包含 `contractVersion=domain-parallel-v1`、items 和必要的 nextCursor。成功空结果是 `items=[]`；依赖故障使用结构化不可用，未知契约版本拒绝。批量结果保留输入关联，不可访问项不带正文、来源详情或能区分“不存在/越权”的私有信息；身份整体无效返回401/403，不能降级为一批空匹配。

每个请求体必须携带 `contractVersion=domain-parallel-v1`；未知版本HTTP400/code=UNSUPPORTED_CONTRACT_VERSION，非法limit/cursor/refs为HTTP400/code=INVALID_ARGUMENT，已选HTTP来源不可用为HTTP503/code=DEPENDENCY_UNAVAILABLE。认证拒绝沿当前Auth错误契约和401/403表达，消费者不得只凭某个Auth字符串错误码判断授权。批量check的资源状态正常以200及逐项status表达，读取请求的expectedRevision冲突为409/code=SOURCE_VERSION_CONFLICT。ID、revision和expectedRevision在线协议中均为非空不透明字符串，contentHash为64位小写SHA-256十六进制，时间为带时区ISO8601；消费方只传递/比较版本，不从其值推断权限或生成顺序。

- 房源快照：`listingRef={sourceSystem,sourceId}`、`revision`、`contentHash`、`observedAt`、`dataMode`、`datasetId`、`facts`。facts 至少明确 title、areaSquareMetres（十进制字符串或 null）、totalPrice（amount十进制字符串、currency、unit=YUAN）及未知字段；旧万元输入在生产者边界换算一次。
- 知识快照：`knowledgeRef={knowledgeId,revision,contentHash}`、`publicationState=PUBLISHED`、`observedAt`、`dataMode`、`datasetId`、`sourceRefs` 和 body。body 表达问题/情境/前提/动作/观察结果/适用边界/未核实项；sourceRefs 保留访谈 assetId/snapshotHash，不能返回私有逐字稿全文。
- 来源模式由服务端确定：REAL、SIMULATED、MIXED、UNKNOWN；含未知来源的派生结果为 UNKNOWN，全部确认真实才为 REAL。contentHash 和 revision 都不授予读取权。
- check 是只读当前使用核验。STALE 要求消费方拒绝旧版本确认或标记待复核；NOT_ACCESSIBLE 停止正文使用；UNAVAILABLE 表达依赖故障。任何状态都不能成为新业务动作的授权。
- 单个 change 不得擅自更名这些路径/字段/错误语义。实现细节和域内 DTO 可独立变化；契约不兼容调整须在协调会话更新版本和所有相关方案后再交付。

## 共享文件与迁移的合并规则

独立 worktree 消除了运行期间互相覆盖，最终合并仍需审查共享文件。各会话只提交本 change 必需的最小语义差异，禁止全文件重排、格式化或改造公共框架。

| 共享位置 | 允许的本分支最小改动与协调责任 |
| --- | --- |
| 根 `pom.xml`/Dockerfile | 房源只调整 compare；知识只新增 knowledge；营销只调整 promotion；经营通常无需改模块列表。协调合并保留各方增量 |
| 根 Compose、Nacos/网关服务列表 | 各会话只维护自己的服务/路由/环境块，独立运行用自身 override；共享环境的真正启停与发布由协调会话执行 |
| `auth-service/**`、Auth Nacos、共享身份协议 | 四会话只读；需要新增受信调用者时在自身 design/受管配置片段列出 key ID、caller、operation 与范围，由 Auth 会话/协调会话接入，独立测试使用精确登记夹具 |
| Memory/RAG 全局登记、密钥生成器全局服务表 | 不删历史登记、不向共享环境发布。知识会话交付 knowledge 身份及最小授权片段；其他会话保留既有身份。协调合并并验证登记 |
| `sql/mysql-init.sql`、初始化 runner/manifest | 已应用 SQL/hash 不改。各会话新增带 listing/knowledge/business/marketing 业务前缀的 SQL/check，交付唯一版本及 manifest 行；在隔离库验证。当前 runner 固定数据库 allowlist，新增 bk_knowledge/bk_business/bk_marketing 由协调合并其 allowlist，不能声称现 runner 已支持任意片段 |
| `agent-service`、Studio | 只更新本域必要兼容引用和新增能力映射；不改通用 Graph/身份框架，也不执行 `implement-studio-v2` 未完成任务。跨域呈现联调后置 |
| LR/KI 动态清单 | 本域优先更新对应 LR-99/100/101/102；新增编号使用下方保留段；不得移动或改写别人的条目 |

新增编号保留段：房源 LR-106–113/KI-170–177；知识 LR-114–121/KI-178–185；经营 LR-122–129/KI-186–193；营销 LR-130–137/KI-194–201。其它会话本批期间使用 LR-138 起、KI-202 起的未用编号；保留号不复用。已保留 Auth 会话的 LR-103/104 和 KI-168/169；LR-105 记录本次并行规则。若需要超出保留段，先由协调会话分配，不能各自取同一 max+1。KI-115 由房源、KI-15 由知识、KI-167 由营销维护；经营仅引用 KI-114/128/166，不替其他 change 宣布修复。

## 独立完成的验收门

四个 change 各自必须在共同基线上、没有另外三个实现提交的情况下完成：模块编译、真实业务处理器/持久回执测试、生产者或消费者的 v1 HTTP契约测试、授权/Memory前置拒绝、版本/撤回/重复请求反例，以及本域迁移/回退的隔离验证。经营必须实现实际 HTTP consumer adapter 并通过本地 HTTP fixture，而非仅留下未实现接口；其本地域服务、状态和数据库是真实实现，fixture 只替代远端来源。知识同理以访谈来源夹具/injvm provider 验证，不等待房源或经营。

这些门通过即可完成本 change 的本期实施任务并提交；“另一个会话未完成”“Auth 未来版本未上线”“当前共享环境没部署”均不得替代本期可独立执行的验收。运行模式缺配置时的拒绝行为仍须验证。真实联机未做的部分明确报告，不将 fixture 通过写成已联机。

四分支合并后，由协调会话核验共享配置的并集、Auth/Memory精确登记、初始化allowlist/顺序/hash、生产者和消费者的真实互通、旧实例排空及浏览器工作台。此处的跨服务联调和共享环境切换是合并后门禁，不回填成任一独立会话的等待任务。

## 每个会话的启动要求

在各自 worktree 调用 `$openspec-apply-change <本会话change名>`，先读本文件及自身 design 的并行交付段，确认共同 base SHA、当前 branch/worktree、独立 `.m2repo` 和所负责范围；只完成本 change，使用固定 v1 契约与自有 fixture，不等待其它分支、不实施其它 change。验证通过后中文 commit 并 push 本会话分支，返回 head SHA、任务/测试结果、共享文件差异、配置片段和真实联机未执行项。若需合并其它会话代码才能编译，应修正消费边界，而不是自行拉取对方未完成提交。

## 保留的业务责任与交付记录

访谈继续拥有会话、逐字稿及原报告；客户、合同、结算保持现有源状态责任；媒体/通知保留独立能力；Supervisor/Memory/RAG继续承担通用协调、获准上下文和检索。业务建议、动作完成与真实业绩结果分别记录。

初版四份规划提交为房源 `5801295`、知识 `d257cbd`、经营 `8cad8fd`、营销 `2b7dbe2`，汇总 `9d13981`。初版69项任务的串行门禁已由本次并行修订替换，当前数量以各 tasks 为准；所有实施任务仍未执行。本次仍只修改规划及项目认知文档，不执行 Java/配置/SQL 实施、Maven业务测试、数据迁移或共享环境部署。

并行修订后共81项未执行任务：房源19、知识21、经营21、营销20，各增加3项独立会话/范围/契约检查。首次在原基线上严格校验25项通过；同步Auth会话的401c583/fa763e4后再次严格校验26项通过、0失败（6个活动change、20份主规格）。旧跨change等待条件扫描、任务编号/未执行状态、固定契约标识、本地链接和LR/KI重复编号检查通过。这些结果证明规划的一致性，不替代四份未来实现及合并后联机证据。

并行规则提交为 `e56a849`，已推送dev-fix并保留Auth新增代码与LR-103/104、KI-168/169。Auth新协议启用及KI-169刷新缺口仍由其独立change负责，四个业务会话不能为解除等待而修改它；应按上述范围适配和测试隔离完成自己的交付。

关联：LR-3/6/8/22/23/53/54/59/79/90/94/96/97/99/100/101/102/105；KI-15、KI-114、KI-115、KI-128、KI-166、KI-167。
