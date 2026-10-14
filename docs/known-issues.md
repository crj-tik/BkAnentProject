# Bug 与已知问题清单

> **目的**：记录项目全部已知缺陷（已修/待修）与设计限制，让开发者与 AI 快速了解项目真实状态。
> **读者**：所有 AI 助手与开发者。**修复前先查重（避免重复排查）；修复后在条目更新状态与 commit；发现新问题必须当日追加。**
> **格式**：`KI-N [状态·优先级] 标题` → 现象 → 根因 → 处置（方案/修复 commit/防回归手段）。编号稳定不复用，引用时写 `KI-N`（常与 `docs/logic-rationale.md` 的 `LR-N` 互相关联）。
> **状态**：`OPEN` 待修 ｜ `FIXED` 已修（附 commit）｜ `LIMIT` 设计限制（附暂不修的原因）
> **维护规则**：与代码同仓同提交（见 AGENTS.md「项目认知清单」）。

---

## 待修复（OPEN）

## KI-1 [OPEN·P0] 表单式 prep 阶段凭据真空（鸡生蛋）

**现象**：`POST /interviews` 开台不返回 ticket，而 `POST /{id}/questions`、`POST /{id}/questions/confirm`、`GET /{id}` 都要求 `x-ticket` 头 → 表单前端开台后无法进行任何后续操作，前端页面被卡死。

**根因**：ticket 绑定在 IN_PROGRESS 签发（`startInterviewSession`），而表单入口的题目操作发生在 DRAFT/QUESTIONS_CONFIRMED——把「话轮凭据」用在了比话轮更早的端点上。详见 LR-1 的信任断点分析。

**处置方案（已对齐，待实施）**：
1. ticket 语义升级为会话凭据，`openCase` 开台响应即签发（表单入口）；
2. 补 `POST /{id}/start` 端点（QUESTIONS_CONFIRMED → IN_PROGRESS，**不重签** ticket，仅推进状态；含 AI_LEAD/ASSIST 模式选择）；
3. 权限分层改由状态机守卫承担（DRAFT/QUESTIONS_CONFIRMED 放行题目操作与 monitor；IN_PROGRESS 加话轮与导演指令），不由签发时机承担；
4. 对话式入口保持 start 卡片签发不变（见 LR-2）。

**关联**：LR-1、LR-2

## KI-11 [OPEN·P2] 导演台字幕流只有心跳

**现象**：`GET /{id}/stream` 仅 15 秒心跳，无真实逐句字幕推送；导演面板刷新依赖轮询 monitor。

**处置方向**：话轮落库后向会话级 Sinks 广播（或引入 Redis pub/sub 支撑多实例）。

## KI-12 [OPEN·P2] SSE 断线续取为单实例内存态

**现象**：`terminalReplies` 是 `InterviewController` 内的 `ConcurrentHashMap`——多实例部署时 `GET /{id}/replies/{reqId}` 经负载均衡命中另一实例会 404。单实例部署无此问题。

**处置方向**：多实例部署前迁移到 Redis 或 DB 暂存表。

## KI-13 [OPEN·P3] 行业大脑 Milvus 集合未建设

**现象**：访前预读缓存（`interview_session.industry_brain_cache`）无填充来源，访中「听懂」能力完全依赖 live-probe 技能 prompt 自身。接口先行、语料运营未启动（见 design.md Open Questions）。

## KI-15 [OPEN·P2] P2/P3 功能未实现（表已建，逻辑未接）

- 小凤双脑引擎 + 导演台监播面板（慢脑题卡/灯号/账本/MOT 缺口）
- 社区专家 26 题制式问答
- 打法卡生成（原声编号表机制）与共性提炼（`playbook_card`、`commonality_report` 表已建）
- 语音模式（VoiceLoop 防抢话三层）、受访人公网链接（Token 状态机）

定义见 `openspec/changes/archive/2026-10-10-create-interview-subagent/proposal.md` 的范围边界。

## KI-37 [OPEN·P1] Linux 增量迁移未选择目标数据库

**确认日期**：2026-10-05。

**现象与根因**：`scripts/deploy/apply-migrations.sh:64` 每个文件创建独立 mysql 连接，未传 `--database`；`20260929_marketing_content_source.sql`、`20261004_supervisor_orchestration.sql`、`20261004_supervisor_run_control.sql` 又没有 `USE`。隔离 MySQL 8.4 实测三份文件均返回 `ERROR 1046: No database selected`，前一个文件的 `USE` 不会跨连接保留。

**处置方向**：每份迁移明确目标库，或使用可审查的文件到库映射传入数据库；不能把全部文件统一指定到 bk_agent。验证单文件和按顺序批量升级。完整检查见 `docs/linux-deployment-init-review.md`。

## KI-38 [OPEN·P1] 历史迁移语法不兼容 Compose 的 MySQL 8.4

**确认日期**：2026-10-05。

**现象与根因**：`20260916_async_runtime_leases.sql` 和 `20260918_supervisor_stream_events.sql` 使用 `ADD COLUMN IF NOT EXISTS`。显式指定 bk_agent 后，真实 MySQL 8.4 仍返回 `ERROR 1064`；前者关于 MySQL 8.0.29+ 支持该语法的注释不成立。新增 Linux 迁移入口默认首先执行旧文件，不能完成声明的全量增量升级。

**处置方向**：按 information_schema 检查后执行兼容的 ALTER，结合目标库与升级基线判断已应用内容；参照现有 interview asset_id 迁移。已有列、缺列和重复运行都要验证。关联 KI-37。

## KI-39 [OPEN·P1] 全新 Compose 数据库缺少已实现业务所需结构

**确认日期**：2026-10-05。

**现象与根因**：按 Compose 原样挂载并执行全部首启 SQL，MySQL 8.4 初始化成功，Supervisor 两张新表及 cancel_requested 存在，但 `bk_marketing.marketing_content.source` 和 `bk_interview.interview_director_command` 不存在。对应实体与服务已使用这些结构；营销来源迁移和导演指令迁移均未包含在首启链。新增文档所述“全新数据卷无需手工迁移”不足以保证全量业务可用。该缺口来自原有 Compose/SQL，检查新增部署流程时确认。

**处置方向**：补齐规范的首启 schema，确保与已实现功能一致；用真实新库验证字段、表及相关业务访问，不能仅以数据库容器 healthy 作为完成标准。关联 KI-37、KI-38、KI-10。

## KI-40 [OPEN·P1] Linux 初始化指南的环境覆盖未传入应用容器

**确认日期**：2026-10-05。

**现象与根因**：`docker-compose.yml` 应用环境写死四类 `*_INTEGRATION_MODE=local`、空 NACOS_PASSWORD、固定 NACOS_USERNAME 和 loopback MINIO_PUBLIC_BASE_URL。使用测试 .env 渲染 full/mcp 配置，四项 real 仍变为 local，Nacos 凭据及公共资源地址也不生效。新增初始化脚本和文档要求在 .env 调整这些值，但原有 Compose 没有相应插值，无法按指南接入真实 Provider、同版本 Card 更新凭据或外部资源地址。

**处置方向**：让相应应用变量可通过明确的 Compose 插值覆盖，保留开发默认值；Nacos Admin 初始化和权限仍须独立配置，不能只生成密码便视为授权完成。用有效配置渲染和真实服务验证。关联 LR-28、KI-30。

## KI-41 [OPEN·P2] Linux 可达性检查忽略配置的远端地址

**确认日期**：2026-10-05。

**现象与根因**：`check-environment.sh:26` 只将 port 传给 `lib.sh:73` 的 port_open，该函数始终连接 127.0.0.1。隔离 Linux 中将七项地址设为不可达远端、在本机对应端口启动监听，检查仍全部显示远端“可达”并返回 0；真实远端可用而本机无监听时也会被误报。

**处置方向**：分开宿主机端口占用和目标 host/port 可达性探测，给连接设置有限超时；验证远端/本机同端口但状态不同的正反例。

## KI-42 [OPEN·P2] Linux 启动等待与状态检查存在成功误报

**确认日期**：2026-10-05。

**现象与根因**：wait_all_healthy 使用 `docker compose ps -q`，真实 Compose 默认列表不包含已退出容器。两个隔离容器中，一个 running、另一个 exit 42，默认列表仅返回前者；按该真实行为构造的函数测试返回“全部就绪”和 0。它也不比对预期服务集合。`status.sh --no-http` 在 compose ps 失败后仍 exit 0；HTTP probe 失败同样只打印，未累积失败状态。

**边界**：Compose up 的 service_completed_successfully 依赖仍会拦住其已检测到的初始化失败，不能据此声称所有 config-init 失败都会绕过 up。问题在独立等待/状态层不能证明所有预期服务及初始化任务成功。

**处置方向**：检查全部容器和预期服务，区分成功的一次性任务与业务进程退出；保留 Compose/HTTP 失败的非零退出码，并按档位选择健康端点。

## KI-43 [OPEN·P2] minimal 初始化漏检实际使用的 Redis 端口

**确认日期**：2026-10-05。

**现象与根因**：Compose 的 Redis 属于 minimal/full，auth-service 依赖其 healthy，但 init-environment.sh 只在 full 分支检查 Redis。隔离 Linux 中占用 minimal 配置的 Redis 端口，初始化仍成功。已有任意本项目容器运行时跳过全部端口检查，也不能证明新启用档位的端口可用。

**处置方向**：从目标档位的实际服务/发布端口检查冲突，排除本项目对应容器已拥有的端口；覆盖 minimal Redis 和 minimal 升级 full/mcp 的场景。

## KI-44 [OPEN·P2] 自定义 env 解析与 Compose dotenv 语义不同

**确认日期**：2026-10-05。

**现象与根因**：lib.sh 的 env_value 只裁剪空白及两端引号。合法 Compose 写法 `MYSQL_ROOT_PASSWORD="值" # 注释` 被读成包含引号和注释的整段；真实 Compose 配置渲染可以正确读取相同写法。迁移脚本因而可能用错误密码连接 MySQL，检查脚本也可能得到错误端口。重复键、插值及环境覆盖同样没有统一语义，不能将该解析器视为完整 Compose dotenv 实现。

**处置方向**：明确定义并校验支持的配置格式，或读取与 Compose 一致的有效配置；不要通过 source 执行用户配置。优先验证行尾注释、引号、CRLF 与覆盖优先级。

## KI-45 [OPEN·P3] Linux 部署选项缺值时直接抛 Bash 未绑定变量

**确认日期**：2026-10-05。

**现象与根因**：需要值的选项直接读取 `$2`，与 `set -u` 组合时缺值绕过正常参数错误提示；实测 `init-environment.sh --profile` 返回 `$2: unbound variable`。start/stop 的 profile、start 的 wait-timeout、apply-migrations 的 file 等存在相同读取方式。

**处置方向**：读取选项值前检查剩余参数和空值，返回具体选项的用法错误；补充缺值、非法值和正常值的入口验证。

## KI-48 [OPEN·P2] agent-service 把 Redis 列为就绪必需但默认不使用，切到 redis 限流还会连错地址

**确认日期**：2026-10-10（基础组件使用排查，配置与代码静态确认）。

**现象与根因**：`nacos/agent-service.yaml` 全文没有 `spring.data.redis` 配置块，但 readiness 把 redis 列为必需依赖（`required-dependencies` 默认含 redis）；代码中唯一 Redis 用途 `RedisSupervisorRateLimiter` 带 `@ConditionalOnProperty(provider=redis)`（`agent-service/src/main/java/com/bkanent/agent/service/RedisSupervisorRateLimiter.java:10`），而默认 provider 是 memory（`nacos/agent-service.yaml:172`）——默认配置下 Redis 实际闲置却因 readiness 依赖它。若把 provider 切成 redis，Lettuce 因无配置回落 `localhost:6379`：compose 注入的是自定义 `REDIS_HOST`（`docker-compose.yml:13`），不是 Spring Boot 认识的 `SPRING_DATA_REDIS_HOST`，会出现 readiness 探活（用 REDIS_HOST）通过、真实读写失败的反差。

**处置方向**：要么默认 provider 改 redis 并补 `spring.data.redis` 配置块（`host: ${REDIS_HOST:...}`），要么从 readiness 必需清单剔除 redis；compose 侧可统一改注入 `SPRING_DATA_REDIS_HOST`。修复时需真实切换 provider 验证客户端连的是配置地址。

**关联**：KI-49（同类 readiness 误配）。

## KI-49 [OPEN·P2] compare-engine 无数据库却把 database 列为就绪必需

**确认日期**：2026-10-10（基础组件使用排查）。

**现象与根因**：compare-engine-service 无 datasource、pom 无 mysql/mybatis 依赖（无状态模型计算服务），但 `nacos/compare-engine-service.yaml:76` 的 `required-dependencies` 默认含 database——MySQL 故障时该无状态服务被 readiness（纯 TCP 探活）误判 not ready，被编排层无谓摘流。

**处置方向**：`SERVICE_READINESS_REQUIRED_DEPENDENCIES` 默认改为 `nacos`。

**关联**：KI-48；KI-41（检查误报同族）。

## KI-50 [OPEN·P3] mysql-common.yaml 是无人导入的死配置

**确认日期**：2026-10-10（基础组件使用排查）。

**现象与根因**：`config-init` 把 `nacos/mysql-common.yaml` 上传到 Nacos，`scripts/deploy/init-environment.sh:186` 还把它列为必需配置，但全仓库没有任何服务以 shared-configs / shared-dataids 导入它——各服务的 `spring.config.import` 只导入自己的 dataId；其 `spring.datasource` 块本身也无 url，纯模板。上传与校验给人"该配置在生效"的错觉，实际各服务 datasource 全部来自各自 yaml。

**处置方向**：删除该文件及其上传/校验逻辑，或改造为真正的共享配置导入。

## KI-51 [OPEN·P3] memory-service 数据库名硬编码，mysql-common 映射缺 memory/interview 条目

**确认日期**：2026-10-10（基础组件使用排查）。

**现象与根因**：`nacos/memory-service.yaml:4` 的 datasource url 直接写死 `bk_memory`，不经 `${MYSQL_DB_MEMORY:bk_memory}` 占位符，与全部其他服务 `${MYSQL_DB_*}` 占位的模式不一致，无法按环境改库名；`mysql-common.yaml` 的 databases/jdbc 映射也没有 memory 和 interview 条目（该文件目前是死配置，见 KI-50，若复活则缺口成立）。

**处置方向**：改为占位符写法；如保留 mysql-common.yaml 则补齐条目。

## KI-52 [OPEN·P3] 基础组件版本管理旁路：模块写死版本与根 dependencyManagement 并存

**确认日期**：2026-10-10（19 个模块 pom 全量核对）。

**现象与根因**：根 POM 已管理 `rocketmq-spring-boot-starter` 2.3.3 与 `nacos-client` 3.1.0，但 media-worker-service 的 rocketmq starter 写死 **2.3.2**（真实偏差，`media-worker-service/pom.xml:33-36`）；另有 9 个模块（notification、listing-master、compare-engine、media-worker、marketing-content、business、contract、settlement、interview）硬编码 nacos-client `<version>3.1.0</version>` 字面量，脱离根管理；agent-service 的 json-schema-validator 1.5.7 与 media-worker 的 minio 8.5.12 未纳入根管理直接写死。KI-26 正是版本漂移翻的车（BOM 降级 3.0.3），写死字面量会在未来升级根版本时重现同类漂移。

**处置方向**：去掉与根管理重复的 `<version>` 标签；minio、json-schema-validator 纳入根 dependencyManagement。修复后用 `mvn dependency:tree` 核对各模块无版本分歧。

**关联**：KI-26。

## KI-53 [OPEN·P3] common 模块声明全量 dubbo 依赖但代码零使用，传染全部下游

**确认日期**：2026-10-10（基础组件使用排查）。

**现象与根因**：`common/pom.xml:33-36` 依赖 `org.apache.dubbo:dubbo`，但 `common/src/main/java` 全部 rpc/* 均为纯 Java 接口、无任何 `org.apache.dubbo` import。该依赖把 Dubbo 传递给全部 18 个服务模块及 common-* 公共库——memory-service、interview-service 等不接 Dubbo 的模块也被迫背上全量 Dubbo 类路径，"某服务不用 Dubbo"事实上无法成立。

**处置方向**：移除 common 的 dubbo 依赖（各服务自带 dubbo-spring-boot-starter），全量编译验证。

## KI-54 [OPEN·P3] mysql-init.sql 基线缺 bk_interview 库

**确认日期**：2026-10-10（基础组件使用排查）。

**现象与根因**：`sql/mysql-init.sql` 只创建 11 个 bk_* 库 + nacos，`bk_interview` 仅由 `sql/migrations/20260929_interview_subagent.sql` 创建。Compose 首启链挂载了该迁移（`docker-compose.yml:83`），容器环境不缺；但按 AGENTS.md 约定 init.sql 是 bootstrap 脚本，单独使用它初始化的新环境没有 interview 库，interview-service 起不来。

**处置方向**：把 `CREATE DATABASE bk_interview` 并入 init.sql 基线，或在部署文档中明确"基线 = init + 全部迁移"。

**关联**：KI-39（首启 schema 缺口同族）。

## KI-55 [OPEN·P1] 现有身份基线不足以支撑 RAG 空间和资源授权

**确认依据**：独立 RAG 规划阶段静态核对，未测试运行环境或宣称已利用漏洞。

**现象与根因**：`common/.../rpc/AuthenticatedPrincipal.java` 只返回 userId；`auth-service/.../rpc/AuthPermissionRpcServiceImpl.java:36-60` 验证账号/角色活跃，但 hasPermission 不按具体权限码授予资源范围。房源 brokerId 来自业务请求，当前未证明等于认证 userId。固定 gateway 标记、普通 userId 或 Dubbo 服务名不能作为新 RAG 的服务认证或跨空间授权证明。

**处置（实施中）**：`2a3fa12` 已提交最小 RAG principal 事实契约、请求绑定 HMAC 及 auth 响应签名，common 全量 30 项、auth 全量 53 项无外部依赖测试通过。领域权威 scope、房源资源 grant 与服务/用户/操作交集已实现并经反例回归，真实部署接线仍待验收；偏好强制当前 owner，系统约束由显式角色和 active 规则授权。保持原一般认证接口，不扩张为全系统 RBAC 重构。真实跨服务密钥、资源基线及 A2A 凭据传播尚未验收，本项保持 OPEN。

**关联**：LR-31；该 change 的 design D3、tasks 第 3 组。

## KI-56 [OPEN·P1] 房源与记忆索引生命周期缺少可靠事件和双索引补偿

**确认依据**：RAG 规划阶段源码核对，未验证现存后端数据完整性。

**现象与根因**：`ListingManagementServiceImpl` 在业务事务提交前写 ES 并调用 agent 索引 RPC，agent 再回调房源读取文档；外部写入不随事务回滚，失败主要告警。memory 的偏好 upsert/decay（含删除）及系统约束更新/停用没有覆盖全部向量同步，agent bootstrap 单独双写也不能保证一致。旧 ES 写入默认关闭，不能据配置推断旧索引完整。

**处置（实施中）**：已实现领域 revision/outbox 完整投影及 RAG 应用账本、两路状态、补偿和 tombstone；账本侧 21 项 H2 测试通过，包括不可取消的迟到旧写、实际 hash 核对及失败关闭。领域入口覆盖已通过房源/记忆完整回归；真实 MySQL 8.4、ES/Milvus、回填水位与切流尚未完整验收。允许短暂漏检/延迟，不承诺跨后端原子事务或 exactly-once；本项保持 OPEN。

**关联**：LR-31；design D7/D8、rag-index-lifecycle spec。

## KI-57 [OPEN·P1] 现有 RAG HTTP 目标与本次出站地址安全约束冲突

**确认依据**：checked-in 模板及客户端调用静态核对，未发请求、未修改容器或配置中心。

**现象与根因**：`nacos/agent-service.yaml` 的本机重排 `host.docker.internal:18119` 和房源私网/Compose ES HTTP 目标不能满足本次要求的 host/DNS 校验及拒绝 localhost、环回、私有、保留地址。现有重排客户端只校验 endpoint 非空；Nacos/env 来源可信不构成地址豁免。

**处置（实施中）**：已在原 HC5/HC4 transport 实现每次新连接的地址校验、全 DNS 答案检查与地址绑定，禁止重定向及代理；不发真实后端请求的 transport 测试 81 项已通过，全仓非外部联调回归通过。重排目标已由外部 Reranker-BGE-V2-M3 项目交付专用合规目标：仅经 external Docker 网络 `bkanent-rerank-dev`（10.253.240.0/24）以 `http://bkanent-reranker-dev:18111/api/v1/rerank` 访问，本仓模板/Compose 已登记该 host 的精确 CIDR map 并以治理协议加 revision/artifact_digest/scoring_version 钉扎（见 LR-43），不再引用旧 `host.docker.internal:18119`。该专用网络与本仓 compose 引用为配置接线，真实容器联调（rag-service 接网运行、governed 协议真实调用、能力探活）尚未执行，不宣称端到端通过。ES 公开/私网合规目标仍未提供，缺目标不自行公开本机服务、更换提供方、修改网络或降级假报成功，本项保持 OPEN。

**实施前置核对**：本机项目依赖树确认 Spring AI 1.1.2 的 OpenAI embedding 经同步 Spring Web 6.2.7 RestClient / HttpClient5 5.4.4；ES 8.18.1 经 HttpAsyncClient 4.1.5 / HttpClient 4.5.14，须使用不同代的连接池 DNS 扩展。现有 BGE 自建 RestClient，不会自动消费受保护 Bean。连接层必须校验全部解析地址并绑定连接、禁重定向及代理；启动前一次解析不能覆盖换址。Milvus SDK 2.5.8 的 gRPC 不应被宣称由这些 HTTP 扩展保护。此核对不是实现或真实检索验收，合规 ES/重排目标仍未提供。

**关联**：LR-31；design D11、rag-service-runtime spec。

## KI-58 [OPEN·P1] 手工构建 Spring AI Milvus 存储未显式初始化及逻辑 ID 不适配默认 schema

**确认依据**：独立 RAG apply 阶段只读核对项目使用的 Spring AI Milvus 1.1.2 JAR API/实现与旧 `SpringAiMilvusDocumentStore`，未访问现存集合。

**现象与根因**：旧封装以 `initializeSchema(true)` 构建 VectorStore 后未调用 `InitializingBean.afterPropertiesSet()`，手工 builder 的构建不等于已执行 schema 初始化。该版本初始化主键为最大 36 字符的 VarChar；任意偏好复合逻辑 ID 不能据此假定可原样存储。`add` 实际调用 insert，不能当作按逻辑 ID 原子 upsert；Milvus 过滤转换器使用固定 metadata 字段，旧任意字段配置也不等于查询支持。

**处置**：新服务保留 Spring AI 数据执行，显式受权 provision 与检索分离、只读核对真实 schema；新空间使用稳定 UUID 物理键并在 metadata 保存无歧义逻辑身份，固定兼容的 metadata 字段。更新/删除通过 Spring AI 对安全物理键操作，并由版本账本遮蔽迟到旧投影。旧集合的真实主键、文档映射与模型维度须另行盘点；未经确认不得猜测旧映射或删除数据。本问题尚未完成真实旧数据验证，保持 OPEN。

**关联**：LR-31；KI-56；该 change tasks 1.2、4.1、4.2、5.6、8.2。

## KI-59 [OPEN·P1] 当前 A2A SDK 无专用凭据头的受支持注入路径

**确认依据**：独立 RAG apply 阶段只读核对本机 `a2a-java-sdk-client 0.2.5.Beta2` sources/JAR，以及 `OfficialA2aAgentClient` 和 `common-a2a/OfficialA2aAgentExecutor`；未发送请求或修改 SDK。

**现象与根因**：现用 `A2AClient` 构造器仅接收 URL 或 AgentCard，任务发送/流式发送/查询/取消没有自定义 headers 或 transport 参数。`getClientFromAgentCardUrl(customHttpClient, ...)` 的自定义客户端仅获取 Card，返回的新 `A2AClient` 未保留该 transport。Starter 的 JSON-RPC 请求处理和异步 executor 也未提供已经验证的专用 token 上下文传播。不能据一般 HttpClient 支持 headers 就宣称实际 A2A 调用能携带凭据。

**处置**：本次先实现受权 REST/Dubbo 消费与有界进程内凭据租约，缺少真实凭据的 A2A/模型工具路径明确要求重新认证且不发起 RAG 请求；token 不放模型参数、metadata、RunnableConfig、日志或 checkpoint。真实房源 Agent RAG 调用及跨 A2A 凭据生命周期仍未完成，tasks 3.5、6.2 的该部分保持阻塞。SDK 最小修补、升级或协议 transport 替换需要另行确认范围，不通过反射改 final 字段或暗建第三客户端绕过。

**关联**：LR-18、LR-22、LR-31；KI-55；该 change domain-rag-consumption spec。

## KI-60 [OPEN·P1] Spring AI Milvus 类型化过滤转换器未转义字符串字面量

**确认依据**：独立 RAG apply 核对 Spring AI 1.1.2 的 `AbstractFilterExpressionConverter.doSingleValue`；字符串仅加双引号，不转义内含引号及反斜线。未访问后端或测试现存数据。

**现象与根因**：构造类型化 AST 不等于其最终表达式安全。未经约束的领域字符串可能破坏 Milvus 字面量边界，影响与强制 owner/resource 条件相交的查询。不能将 Spring AI builder 本身作为防表达式注入的证据。

**处置（实施中）**：在受控 typed filter 转换入口对字符串按 Milvus/JSON 字面量规则转义，仍通过原 Spring AI 存储执行，不开放原生 DSL。已增加实际 Milvus 转换器输出的引号、反斜线及控制字符回归，全仓非外部联调回归通过；真实过滤等价验证未执行，保持 OPEN。

**关联**：LR-31；KI-55；tasks 3.4、4.2、8.2。

## KI-61 [FIXED·74c8d92·P1] 新领域 RAG 包装的最终复核未同步收窄诊断正文

**确认依据**：独立 RAG apply 集成审阅新增 `MemoryRagSearchFacade` 和 `ListingRagFacade`，尚未切流。

**现象与根因**：记忆包装虽剔除 SQL 已失效候选，响应中的原 retrieval 仍携带全量旧正文；房源包装重建最新详情，却尚未复核领域最新 revision，可能将旧语义评分与新详情拼接。主结果复核不能代替对所有可见响应字段的复核。

**修复（本次 RAG 实施提交）**：最终响应仅保留当前真源及 revision/hash 复核通过的候选；正文与允许 metadata 从真源重建，stageStats 统一为空，删除、撤权、阈值及更新后失效候选不进入 matches、正文或诊断列表。房源和记忆完整测试均通过，包含 RPC 返回后真源变化及非空上游统计的反例；最终数量与命令见本次 RAG 验收记录。源码缺陷已修，部署仍未切流。

**关联**：LR-31；KI-55、KI-56；tasks 6.1、6.3。

## KI-62 [FIXED·74c8d92·P1] 新 RAG 数值与时间编码可能移动硬过滤边界

**确认日期**：2026-10-10（本次迁移实现审阅）。

**现象与根因**：BigDecimal 转 double 可溢出、下溢或损失精度；变长/非 UTC 的 ISO 字符串在向量后端比较不保持 Instant 顺序，可能与 ES 硬过滤不等价。

**修复**：NUMBER/NUMBER_LIST 严格有限及 round-trip 校验；INSTANT 固定 UTC 纳秒并限制 ES date_nanos 正 signed-long 范围。实际 Spring AI converter 输出及 ES mapping 单测覆盖；这是新 schema，不猜测旧投影兼容性。见 LR-35。

## KI-63 [FIXED·74c8d92·P1] 记忆投影使用请求表示导致真源 hash 不一致

**确认日期**：2026-10-10（H2 DATETIME 舍入回归确认）。

**现象与根因**：请求纳秒/decimal 表示可被 SQL 持久化规范化；直接从请求生成投影，与最终 SQL 记录重建的 hash 不同，合法候选被剔除。

**修复**：upsert、decay 与约束写入在相同事务锁内重新读取已持久化 SQL 表示再生成投影。领域生命周期与最终复核测试通过；真实 MySQL 精度与迁移仍未验收。见 LR-35。

## KI-64 [FIXED·74c8d92·P1] 实际依赖权限拒绝误归不可用可被降级

**确认日期**：2026-10-10（实际异常类型及 Spring AI 1.1.2 retry 源码核对，未发 Provider 请求）。

**现象与根因**：HTTP 401/403、gRPC 权限错误及 Spring AI 丢失 typed cause 的 retry exception 若通用归类 DEPENDENCY_UNAVAILABLE，显式 ALLOW_DEGRADED 可绕过硬权限拒绝。

**修复**：搜索与管理 probe 保留真实状态及 cause；对 typed AI retry exception 只匹配完整固定 HTTP 前缀。新增不可重试/降级、普通文本不误分类反例，实际后端权限联调仍未执行。见 LR-36。

## KI-65 [FIXED·74c8d92·P1] SERVICE 域写 key 的管理操作绕过 adminRoles

**确认日期**：2026-10-10（新配置与管理入口集成核对）。

**现象与根因**：原 admin SERVICE 分支只验 operation grant，模板增加管理操作后空角色不再能阻断域 writer 管理。

**修复**：本期所有管理操作强制 USER + signed principal + adminRoles；SERVICE 三管理操作全拒绝，真实 ADMIN 正向及空角色反例通过。不增加独立运维 key DSL，也不自动为部署设置管理角色。见 LR-36。

## KI-66 [FIXED·74c8d92·P2] 新 RAG 模板健康名称及发现变量与应用不一致

**确认日期**：2026-10-10（checked-in App/Compose/Nacos 静态核对）。

**现象与根因**：readiness 引用了不存在的 dependencyReadiness；App 读取 NACOS_SERVER_ADDR/RAG_DUBBO_REGISTRY_ADDRESS，而 Compose 只给 NACOS_SERVER，可能上传成功却未接通发现。

**修复**：健康组对齐 ragBaseReadiness/ragCapabilities，Compose 显式提供 App 所读地址/组/Dubbo registry aliases。最终模板核对另修正 memory 的 constraint-admin-roles 键、memory Dubbo registry，以及 listing 入站 AGENT_RAG_RPC_SECRET 的空默认透传；不启用角色或填入密钥。基础 readiness 文案对齐认证配置、两控制表可查询与至少三个已导出 RPC，不误称直接检查 Nacos。YAML 严格解析和逐值断言通过。实际 namespace/group/data ID、鉴权与 RPC 发现仍未验证，不据此声明配置已生效。

## KI-67 [FIXED·74c8d92·P2] 只读知识工具迟到结果覆盖已完成失败审计

**确认日期**：2026-10-10（新远程只读工具与现有账本集成核对）。

**修复**：限定知识 capability 的 CAS 首终态写入；超时迟到、已完成复用和 legacy claim 不重发回归通过。一般副作用工具语义不变，无新增 transition。见 LR-37。

## KI-68 [FIXED·74c8d92·P2] 旧房源显式召回数量被总数 500 额外拒绝

**确认日期**：2026-10-10（最终设计 D9 对账）。

**现象与根因**：每路 1–500 的契约被兼容层额外收紧为两路和不超过 500，合法显式 500+500 被拒绝。

**修复**：保留显式每路数，rerankCandidates=min(500, 两路和)；默认两次域调用仍总预算不超过 500，显式首池超过 500 时只允许该次有界召回。新增默认/显式边界与有限扩池回归，不能静默改用户数量。

## KI-69 [FIXED·74c8d92·P2] 记忆 outbox 回归假定初始时间戳立即到期

**确认日期**：2026-10-10（H2 固定时钟复现，非真实 MySQL 验收）。

**现象与根因**：全 reactor test 通过后，package 重跑时重试用例偶发 expected 2 / observed 1。H2 TIMESTAMP 精度为 6；`.123456700Z` 入库舍入为 `.123457Z`，可晚于首次派发 cutoff `.123456800Z`。首次派发正确跳过尚未到期事件，测试却将第二次派发当作第二次提交；无事件丢失或生产重试故障证据。

**修复**：仅测试在首次启用派发前显式设置到期时间，保留 2/3 次提交、相同 eventId/revision、TIMEOUT 与 attempts 断言；新增固定时钟边界回归，确认未来行不变、到期后才应用。不修改生产时钟/签名/调度、不加 sleep 或弱化断言。复现与最终验证见 `docs/acceptance/isolated-rag-local-verification.md`。

## KI-70 [FIXED·ee4c9b9·P2] 记忆领域衰减逐候选采样时间导致排序依赖遍历时序

**确认依据**：独立 RAG 继续 apply 的本地源码核对，未调用真实提供方。

**现象与根因**：MemoryRagSearchFacade 在候选循环内调用 Instant.now，且扩池再次采样；相同相关度、confidence、观测时间可能因处理先后得到不同分数，破坏确定同分顺序。

**修复（ee4c9b9）**：一次领域 search 捕获统一 asOf，所有池共用；补充固定时钟的精确半衰期与同分顺序测试。见 LR-38。

**验证**：本轮定向89项和全reactor683项test/package均通过，实际运行门禁仍开放；见 `docs/acceptance/isolated-rag-local-verification.md`。

## KI-71 [FIXED·ee4c9b9·P2] 记忆扩池替换首池且未累计召回预算

**确认依据**：本地源码及 D6 对账，未执行真实召回。

**现象与根因**：第二次 retrieval 整体替换第一次，首池有效但第二池未出现的候选丢失；每次合法数量仍可能累计超过域预算，显式500+500可再次请求。首池不足额时仍扩池也没有足量依据。

**修复（ee4c9b9）**：最多两池按documentId合并、后次重复身份覆盖，最终全池再次SQL核对；预算max(500,首池选中路数量和)，保留显式初始数量，不足池或预算耗尽不扩。补充候选保留、重复、真源更新、单路及最大显式数量回归。见 LR-38。

**验证**：本轮定向89项和全reactor683项test/package均通过，实际运行门禁仍开放；见 `docs/acceptance/isolated-rag-local-verification.md`。

## KI-72 [FIXED·ee4c9b9·P2] 记忆最终候选 metadata 超出领域公共返回集合

**确认依据**：本地MemoryRagSearchFacade与空间模板字段核对，不代表跨用户数据泄漏。

**现象与根因**：偏好从SQL重建后直接返回全部projection attributes，包括索引授权userId/ownerId；真源重建保证内容正确，但不自动保证公共metadata白名单。

**修复（ee4c9b9）**：偏好只返回preferenceKey/category/confidence/observationCount/lastObservedAt/active；约束constraintKey/category/tags/active，保留索引授权属性及独立已授权业务record响应。添加精确typed字段集合断言，stageStats仍为空。见 LR-38。

**验证**：本轮定向89项和全reactor683项test/package均通过，实际运行门禁仍开放；见 `docs/acceptance/isolated-rag-local-verification.md`。

## KI-73 [FIXED·ee4c9b9·P2] 记忆回填全表扫描持有全局 writer 锁

**确认依据**：MemoryRagBackfillService本地源码，与设计分批回填要求对账，未运行真实回填。

**现象与根因**：原入口无cursor/limit，一次事务扫描全部偏好、约束和持久投影表并持有writer锁；数据量增长时在线写入阻塞无界，仅返回count也不能表达分页或应用水位。

**修复（ee4c9b9）**：稳定keyset分阶段单页，默认100、最大500，每页保留锁后当前SQL快照、完整outbox和持久删除身份；返回cursor/nextCursor/QUEUED receipts，非APPLIED水位。补分页边界、缺失源tombstone与更新/删除交错回归。见 LR-39。

**验证**：本轮定向89项和全reactor683项test/package均通过，实际运行门禁仍开放；见 `docs/acceptance/isolated-rag-local-verification.md`。

## KI-74 [FIXED·ee4c9b9·P1] 房源遗留 reconcile 未把证明 hash 与当前完整投影比较

**确认依据**：ListingRagBackfillService/ListingRagOutbox本地源码核对，旧代理尚未切流。

**现象与根因**：expectedHash只与持久revision行比较，publishCurrent另行装配当前source/grant并推进revision；若遗留未受管写改变正文/授权而未同步revision，可接受不匹配证明的重建。

**修复（ee4c9b9）**：复用Outbox装配，在同一锁事务、任何revision/outbox写入前比较当前完整投影hash；不一致返回LEGACY_RECONCILIATION_REQUIRED。补成功upsert/delete、正文/grant漂移、源重建、签名拒绝及提交后派发失败回归。见 LR-39。

**验证**：本轮定向89项和全reactor683项test/package均通过，实际运行门禁仍开放；见 `docs/acceptance/isolated-rag-local-verification.md`。

## KI-75 [FIXED·8fed832·P1] 旧 ID-only 索引 RPC 缺可信遗留重建输入路径

**确认依据**：旧AgentRpcService签名/provider与领域reconcile契约本地核对，没有真实排空或调用证据。

**现象与根因**：syncListingKnowledge(Long)/deleteListingKnowledge(Long)无入站认证、expected revision/hash及排空证据，当前有效调用总是LEGACY_RECONCILIATION_REQUIRED，没有D9要求的条件成功路径。独立signed reconcile contract存在，但无生产旧入口能提供可信证明；由agent查询当前状态自签名不能证明原事务或顺序。

**历史处置（已被替代）**：此前ID-only路径失败关闭、不发事件，task6.7当时未完成。最新授权确认无真实旧消费者后改为直接删除旧两个RPC及signed legacy reconcile／证明／错误码／配置，不建设条件成功路径。8fed832已完成删除；当前c338b7e全仓生产／测试／配置复核仅旧RPC否定测试一处，无collector/retriever或旧执行底座，886项构建通过。正常index/backfill/outbox及worker实际hash补偿完整保留；真实stop-write／水位由8.3另验，不能重新补造旧证明。见LR-39及task6.7／8.5。

## KI-76 [FIXED·ee4c9b9·P2] 记忆语义版本缺失触发允许集合 NPE

**确认依据**：本地评分反例补充时核对Set.copyOf允许集与null候选版本，未调用真实模型。

**现象与根因**：immutable允许集合的contains(null)可抛NullPointerException，缺失scoringVersion未稳定返回约定的SCORING_PROFILE_MISMATCH；不能将这种输入错误当依赖不可用或通过默认模型版本掩盖。

**修复（ee4c9b9）**：先检查null版本，再校验受管允许集合与相关度字段；补非空允许集下未知/null版本、缺分数和两rank模式阈值反例。真实版本仍须独立profile证据注册。

**验证**：本轮定向89项和全reactor683项test/package均通过，实际运行门禁仍开放；见 `docs/acceptance/isolated-rag-local-verification.md`。

## KI-77 [FIXED·ee4c9b9·P2] 新记忆评分回归误把全进程 Instant 调用当领域时钟次数

**确认依据**：本轮串行定向Maven实际失败日志，纯本地夹具。

**现象与根因**：static mock的thenReturn参数内调用Instant.plusSeconds触发另一个被mock的静态构造，造成UnfinishedStubbing；移出参数后全局verify(now,times(1))又计入H2事务的24次内部时钟调用。领域评分没有重复采样的生产故障证据。

**修复（ee4c9b9）**：预先构造第二时刻，保留会前进的静态时钟、反向上游顺序、两条精确半衰期分数与重复运行断言；不统计数据库内部时钟，不加sleep或改生产clock。若恢复逐候选SQL后采样，其分数将从.36变为.18，精确断言仍能拒绝该回归。原始失败与重跑见本地验收记录。

**验证**：本轮定向89项和全reactor683项test/package均通过，实际运行门禁仍开放；见 `docs/acceptance/isolated-rag-local-verification.md`。

**当前共享验证（2026-10-14；以下已修代码／夹具／配置项）**：真实发布增量修复后22模块reactor串行clean test与package均BUILD SUCCESS，各855 tests／140 XML suites，覆盖19个有测试模块，failure／error／skipped均0；七个外部依赖测试类显式排除，不计为已执行。日志：`D:/project/rag-runtime-repairs-clean-test-20261014.log`、`D:/project/rag-runtime-repairs-package-20261014.log`。1216个源码输入LF指纹：`977fd9b2ba50968fcd7dfcbd626502ab15c9cecc51412c7f4d610ea9376b188e`；此前4e5307…／848项原证明保留。这段855项仅对应7bd7a52冻结版本。后续实启已关闭KI-95营销Bean、KI-96共享缺类及KI-97直连序列化／真实签名故障；KI-94后续未选模型阻塞由KI-98继续记录，新DNS和消费者误选分别为KI-99／100。当前执行增量已重新串行clean test／package各886项、142 XML、0失败／错误／跳过，14Boot JAR及私有probe编译通过，源LF4356c418965cfe851cdbdc118ae672ec7416b62beed20b4a9f6e7c4ac98a0cd5；证据D:/project/rag-dns-protocol-artifact-verification-20261014.json。c338b7e已推送并部署，七4356c4服务含Agent均healthy／restart0，KI-94／98启动故障关闭；KI-99／100保护路径及真实发现调用仍待新KI-101／102排除后验收，不能沿用旧制品证明或以基础健康声明默认能力解决。

14个Boot JAR已核对：含SDK制品仅唯一patch、两改动源码精确还原官方源、九未改官方binary class一致、许可证保留、后端依赖隔离及私有probe javac release17通过；十实际A2A消费者运行包均具唯一OkHttp4.12／Okio-jvm3.6／Kotlin。当前证据 `D:/project/rag-runtime-repairs-artifact-verification-20261014.json`、`D:/project/rag-runtime-repairs-artifact-proof-20261014.log`，原报告保留。这些仅证明本地实现，不代表部署／真实身份、五操作、排序及readiness矩阵完成；原失败日志与历史事实保留，KI-90运行修复另行取证。

## KI-91 [FIXED·本提交·P2] 受限发布镜像的源码模式健康检查依赖 JRE 缺失的编译器

**发现日期**：2026-10-14（当前发布运行准备）。

**现象与根因**：新受限release Dockerfile以源码模式执行 `ReadinessCheck`，需要 `jdk.compiler`；不可变digest `ec72…` 对应实际JRE17.0.20缺该模块，两个naming模块存在、SHA核对通过。此前离线模块字符串包含判断误报compiler存在；实际命令安全证据 `runtime-actual-module-predicates-safe.json` 覆盖该推断，不据离线检查声明健康检查可运行。

**修复与验证（本提交）**：仅受限release构建stage使用预编译Java17 class（major61、SHA-256为3334279557d7ff455abea1aeafe18accaae1e9280064d6e9688349f0d4a6b499）及class模式healthcheck，实际Compose config-q0；应用JRE固定digest不变，仅核对真实java.naming／jdk.naming.dns。Auth／RAG／Memory新实例实际healthy／running／restart0，消除该源码模式故障，不代表检索能力或其余应用已验。私有probe独立固定maven@sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc，实际java17／javac17／两个命名模块／jdk.compiler均通过，不运行Maven。证据release-class-health-and-probe-jdk-safe.json、release-four-current-health-safe.json；首镜像失败及错误离线compiler推断保留，未改业务源码或降低健康判断。

## KI-92 [FIXED·本提交·P2] 受限发布的数字 UID 缺少可写 user.home 导致 Nacos／Dubbo 缓存初始化失败

**发现日期**：2026-10-14（独立新release实例实启）。

**现象与根因**：固定运行UID10001无passwd home映射，Java user.home为`?`；Nacos尝试`/app/?/logs/nacos`，Dubbo服务缓存尝试`?/.dubbo`。只读rootfs下这些目录不可创建，实际抛FileNotFoundException／Invalid service store file，不是业务SQL或鉴权失败。

**修复与验证（本提交）**：仅受限stage Compose显式`java -Duser.home=/app/runtime -jar /app/application.jar`，复用既有tmpfs；实际目录10001:10001／0755且可写，rootfs只读及no-new-privileges不变。Auth／RAG／Memory新实例healthy／restart0且无该异常，实际ADMIN／BROKER登录与/auth/me通过，未改seed。证据release-initial-start-failures-safe.json、release-four-current-health-safe.json、actual-seed-auth-safe.json；关闭该缓存路径故障，不据此关闭Listing发现或检索能力门禁。

## KI-93 [FIXED·本提交·P2] RAG双网络默认Dubbo地址被注册到仅重排网络

**发现日期**：2026-10-14（新Listing消费者实启）。

**现象与根因**：仅RAG额外加入既有rerank网络后，Dubbo自动选10.253.240.3:20898；Listing仅在业务bk网络，实际RpcException／RemotingException无法访问该地址。不是HTTP guard拒绝，不能把全部业务服务接到rerank网络作为修复。

**修复与当前运行验证**：首次stage固定.12仅解决Dubbo，HTTP discovery仍错误，历史证据保留。7bd7a52重新发布时并行释放.12后被新Memory自动分配，故仅RAG改为已核对空闲的172.24.0.20，DUBBO_IP_TO_REGISTRY／BIND和官方SPRING_CLOUD_NACOS_DISCOVERY_IP统一；其他服务及rerank网络不变。实际认证Nacos读回HTTP9018、Dubbo20898和三RAG接口均.20且healthy/enabled，模板LF等于local；三空间实际签名ADMIN的INSPECT及输出前复验通过。关闭地址错网问题，不据此声明消费者协议选择或检索能力成功。证据repair-wave-nacos-discovery-safe.json、repair-wave-domain-inspect-safe.json；同名HTTP误选另记KI-100。

## KI-94 [FIXED·c338b7e·P2] Agent未使用的DashScopeAgent自动配置要求额外API key而阻止启动

**发现日期**：2026-10-14（新Agent release真实启动）。

**现象与根因**：AgentServiceApplication仅排除DashScopeChatAutoConfiguration，依赖仍导入DashScopeAgentAutoConfiguration。实际dashScopeAgent factory在DashScopeConnectionUtils.require apiKey处失败；业务DeepSeek配置非空不能替代独立DashScope凭据。不得复用公司网关key发送到未选提供方。

**处置与边界**：7bd7a52排除两个未使用的DashScope chat／agent自动配置，生产AutoConfigurationImportSelector回归及855项完整测试通过；当时新启动进一步暴露独立AudioSpeech工厂，见KI-98。c338b7e一次排除九个未选模型，保留DeepSeek、Graph／ReactAgent、A2A与非模型AsyncToolCallingManager；886项回归及4356c4真实Agent运行／healthy／restart0均通过，启动故障已关闭。模型和工具链另验。证据release-agent-exact-factory-safe.json、repair-wave-failure-fixed-frames-safe.json、dns-protocol-health-discovery-safe.json。

## KI-95 [FIXED·7bd7a52·P2] 营销两个必要RPC配置的默认Bean名冲突阻止真实启动

**发现日期**：2026-10-14（新marketing release真实启动）。

**现象与根因**：根包及rpc子包各有MarketingRpcReferenceConfiguration，默认同名marketingRpcReferenceConfiguration；实际Spring扫描抛ConflictingBeanDefinitionException。前者为MediaWorker／Promotion桥，后者为受鉴权Listing读取桥，不能将其中之一当重复死代码删除。

**修复与验证**：7bd7a52仅Listing配置显式独立Bean名marketingListingRpcReferenceConfiguration，保留两个必要配置及Primary／非local条件／实际签名读取；真实Spring默认命名扫描与consumer wiring两项回归及855项完整测试通过。新Marketing实际healthy／restart0、readinessHTTP200／UP，关闭Bean冲突；业务工具和模型完整验收另验。证据release-runtime-exact-causes-safe.json、repair-wave-stable-state-preservation-safe.json。

## KI-96 [FIXED·7bd7a52·P2] 共享Dubbo REST扩展缺OkHttp运行依赖，Listing启动后出现类加载失败

**发现日期**：2026-10-14（新Listing release实际日志）。

**现象与根因**：真实NoClassDefFoundError okhttp3.Callback。官方A2A SDK client不引用该类，其原POM依赖均保留；引用来自Dubbo3.2.15 OKHttpRestClient，不能误归为SDK补丁遗漏官方依赖。精确首次异常栈已闭合Nacos discovery事件→refreshInvoker/toInvokers→Protocol.Adaptive.refer→RestProtocol→OkHttpClientFactory.doCreateRestClient；不推断具体provider metadata。Listing制品缺该运行库，编译／部分Agent测试间接带库无法发现下游缺口。证据listing-first-callback-exact-frames-safe.json。

**修复与验证**：7bd7a52在common-a2a补齐受管OkHttp运行依赖；四项无网络运行回归及855项完整测试通过，十个实际消费者Boot制品均具唯一OkHttp4.12／Okio-jvm3.6／Kotlin运行库，SDK patch POM未改。实际新Listing缺类消失，进入相同错误REST选择链的后续MetadataResolver并抛CodeStyleNotSupportException，另记KI-100；关闭缺类不等于发现RPC或业务读取成功。证据repair-wave-failure-fixed-frames-safe.json、repair-wave-listing-rest-exact-safe.json。

## KI-97 [FIXED·7bd7a52·P2] 私有Dubbo直连探针缺协议序列化协商信息而退回Hessian2

**发现日期**：2026-10-14（真实DOMAIN探针，已进入Java但未完成signed principal）。

**现象与根因**：裸P2P URL未携带注册发现prefer-serialization，Dubbo默认回落Hessian2；真实Auth请求解码创建Hessian2工厂遇Java17 StackTraceElement反射InaccessibleObjectException，TCP20881成功。官方3.2.15 provider默认支持可用fastjson2,hessian2，实际制品具fastjson2 SPI。初次exit127来自选错WSL bash、未进runtime，后续真实exit1均不代表签名ADMIN／INSPECT通过。

**修复与验证**：7bd7a52探针原URL严格校验后只添加固定serialization=fastjson2；四项本地回归、855项完整测试及实际Java17探针编译通过，不使用add-opens、不改provider／Auth检查。当前冻结探针在listing.knowledge／memory.preference／memory.constraint均经真实signed ADMIN及操作后复验后输出INSPECT结果；独立安全诊断亦确认两次签名成功。各ready=false／SPACE_NOT_READY是能力失败，不再误判鉴权失败。安全证据repair-wave-domain-inspect-safe.json；此关闭不证明A2A五操作或默认检索成功。

## KI-98 [FIXED·c338b7e·P2] Agent未选DashScope音频等模型默认自动装配阻止启动

**发现日期**：2026-10-14。

**现象与根因**：排除旧chat／agent工厂后，真实Agent继续在DashScopeAudioSpeechAutoConfiguration.dashScopeSpeechSynthesisModel缺key失败。官方1.1.2.3的speech／transcription／image／video／embedding／multimodal／rerank模型均matchIfMissing=true；Agent实际仅消费ChatModel，未使用这些模型。不能复制公司key到未选提供方或逐次等待每个默认工厂失败。

**处置**：应用一次明确排除九个未选DashScope模型自动配置，保留选定DeepSeek以及Graph／ReactAgent／A2A与AsyncToolCallingManager。实际ImportSelector及当前886项完整回归通过；c338b7e／4356c4新镜像已真实部署，Agent running／healthy／restart0、readiness HTTP200／UP，未选模型启动故障关闭。证据dns-protocol-health-discovery-safe.json；不代表模型工具链／默认检索通过。证据repair-wave-failure-fixed-frames-safe.json，源码AgentServiceApplication／AgentServiceAutoConfigurationTest。

## KI-99 [FIXED·c338b7e·P2] JNDI组合A／AAAA转ANY导致受管Docker目标DNS超时

**发现日期**：2026-10-14。

**现象与根因**：官方OpenJDK17 DnsContext对getAttributes(name,{A,AAAA})选择IN／ANY(255)，不是分别查询。当前RAG同JRE／同网络实测三个批准host组合均CommunicationException→SocketTimeoutException，独立A成功且AAAA成功空；不能把getent成功当作保护路径成功，也不能归为整体DNS故障或缺schema。

**处置**：RagDnsRecords同一fresh InitialDirContext分别查询A和AAAA，共用一次整体deadline，两次成功后才返回完整原始地址；任一路失败不返回部分结果。原平台DNS、mapped／保留地址拒绝、逐host授权与实际连接绑定不变。新增16次JNDI facade夹具测试通过，含空AAAA与失败区别、混答、换址和整体期限；c338b7e修复后、accf97f新制品三空间实际protected embedding／governed rerank能力probe通过，已不再被组合ANY查询阻塞；ES独立reactor缺口另记KI-104，不重开本DNS根因。证据repair-wave-dns-separate-query-safe.json、inspect-wiring-domain-inspect-safe.json；关联LR-34、LR-45。

## KI-100 [FIXED·c338b7e·P2] 未限定消费者协议使同名Spring HTTP实例被适配成REST RPC

**发现日期**：2026-10-14。

**现象与根因**：实际RAG三RPC只导出Dubbo20898，同名HTTP9018另带SPRING_CLOUD标记；Listing错误栈与RagRetrievalRpcService／RagIndexRpcService及REST9018关联。官方Dubbo3.2.15在HTTP-only通知时合成REST通配metadata；consumer protocol未指定可匹配，继而RestProtocol先创建HTTP客户端、后校验REST注解。KI-96缺类与当前CodeStyleNotSupportException是该错误选择链先后阻塞，不应给纯RPC补REST注解。mixed通知不满足allMatch，不能声称每个混合快照都会适配。

**处置**：纯Dubbo消费者使用官方dubbo.consumer.protocol=dubbo，阻止REST候选匹配，保留HTTP发现、provider、命名group、序列化与REST SPI。新增官方适配器及实际配置绑定回归通过；accf97f新Listing实际生产发现调用进入RAG服务端requireReady／search，HTTP仍作为独立发现保留，不再误走REST RPC。当前返回异常的fastjson2注册缺口另记KI-103，不重开协议选择根因。证据repair-wave-approved-provider-protocol-safe.json、repair-wave-listing-rest-exact-safe.json、inspect-wiring-bounded-backend-serializer-safe.json；关联LR-45。

## KI-101 [FIXED·accf97f·P2] 房源实际鉴权入口按类型解析RPC出现Bean歧义

**发现日期**：2026-10-14（c338b7e／4356c4实际部署）。

**现象**：真实POST /listings/rag/search返回HTTP500；NoUniqueBeanDefinitionException固定链为DependencyObjectProvider.getIfAvailable→ListingRagIdentity.resolve／requireReader→ListingRequestAuthenticationFilter。故障发生于领域鉴权依赖解析，不能归为DNS／REST或声称真实发现调用已经成功。

**根因与当前修复**：官方Dubbo字段ReferenceBean的principal／retrieval／index与Distributed显式@Bean桥ragPrincipalRpcService／ragRetrievalRpcService／ragIndexRpcService各形成双候选；三个生产ObjectProvider按既有桥名精确Qualifier，不删除合法引用或加Primary。旧AuthPermissionRpcService是独立接口，不是根因；Memory的ReferenceConfig非FactoryBean及RAG直接字段注入不属同根。新增真实AnnotationConfigApplicationContext双候选／精确桥／缺桥失败关闭／signed principal／HTTP401、503回归，Listing五项及probe四项定向通过；新22模块串行clean test和package各896项／142 XML，0失败／错误／跳过，制品证明22f9fe2a…通过，accf97f新Listing／RAG两个镜像build/up0、healthy／restart0，其余五服务未重建；真实原ADMIN登录后的POST于828ms进入ListingRagFacade.search，当前不再被Bean歧义拦住，而在RPC异常反序列化失败，另记KI-103。证据inspect-wiring-domain-discovery-call-safe.json／inspect-wiring-domain-http-error-safe.json及历史dns-protocol-domain-http-error-safe.json；关联LR-46。

## KI-102 [FIXED·accf97f·P2] 新制品三空间INSPECT在签名ADMIN后远端超时

**发现日期**：2026-10-14（c338b7e／4356c4实际部署）。

**现象**：三个空间初段signed ADMIN通过后均RpcException→TimeoutException，返回DEADLINE_EXCEEDED，没有能力结果及DOMAIN后身份复验成功。七进程基础healthy不代表受保护embedding／rerank或空间就绪，亦不能判断旧偏好policy错误是否仍在。

**已证边界与源码缺口**：实际直接RagSpaceAdminRpcService.inspect消费者timeout为30000ms，RAG→Auth为5000ms；现有安全证据无elapsed或远端终止事实，不能断言预算不足或某一模型阻塞。源码inspect四阶段串行但未用overall剩余预算、Set迭代不稳定，inspect／provision共用全registry synchronized，无有界排队与迟到结果守卫；ES schema exists／mapping同步读取亦未复用可取消deadline reader。已修复共享整体预算／固定顺序／有界执行与ES可取消schema读取，按空间租约直到实际worker退出，迟到worker不发布证据；未执行／整体过期／容量拒绝失败关闭，已调用probe的TIMEOUT保留既有显式降级判断。实际SpringAI自动配置离线503证明确切注入单次无退避模板且惰性基础启动保留，Milvus只读schema使用新单次RetryParam再配置剩余gRPC deadline；定向37项通过，首次Mockito重复设桩失败保留并已修正；新22模块串行clean test和package各896项／142 XML，14Boot JAR与Java17 probe制品证明通过，源LF22f9fe2a…，实际新制品另验。保持既有SpringAI和出站安全，关联LR-47。probe补固定numeric elapsed／configured timeout／safe code，不输出异常原文，只有真实输出前复验成功才打印stage/code。未PROVISION，worker／dispatcher仍关闭。原证据dns-protocol-domain-inspect-safe.json、dns-protocol-new-inspect-error-safe.json、dns-protocol-timeout-selected-fields-safe.json；新的源缺口并不证明这些缺口是该次runtime超时的具体耗时归属。accf97f新制品实际三INSPECT调用耗时6780／5337／5326ms，consumer仍30000ms，全部返回结构化结果及操作前后signed ADMIN；退出1是SPACE_NOT_READY而非消费者超时。三空间1024维embedding及governed rerank真实保护能力probe通过；残余KEYWORD TIMEOUT／VECTOR DEPENDENCY_UNAVAILABLE另记KI-104，不能推定仅缺schema或提前PROVISION。新证据inspect-wiring-domain-inspect-safe.json。

## KI-103 [OPEN·P2] 领域RPC失败的Throwable反序列化错误遮蔽结构化RAG错误

**发现日期**：2026-10-14（accf97f／22f9fe实际部署）。

**现象与已证边界**：原ADMIN真实Listing POST越过Bean解析并进入Facade，但HTTP500／828ms，固定readThrowable→DecodeableRpcResult.handleException/decode分类为RagException／SerializationException／IllegalArgumentException／IOException。未输出异常正文、URL、完整堆栈。后续受限运行固定帧证实实际fastjson2 STRICT允许列表拒绝公共RagException，RAG server真实requireReady→search已执行。官方Dubbo3.2.15初始化registerInterface遍历throws自动精确注册异常及字段类型；当前接口没有throws RagException导致未注册。隔离framework实际writeThrowable／readThrowable在现接口初始化后拒绝，声明该异常的同签名接口初始化后39种code／field组合可完整重建，未知同包类仍拒绝，STRICT／serializable不变；无final字段／无参构造问题，不需add-opens或包级allowlist。四接口七方法已显式声明异常，既有官方wire六项定向通过，新增两项回归逐接口156种code／field组合恢复并保留未受信拒绝；冻结增量22模块串行clean test及package各916项／142 XML，0失败／错误／跳过；14Boot嵌套common逐字节一致及四接口七方法throws制品证明已通过。新消费者与提供者七release实际部署及Throwable返回仍待验证，保持OPEN。证据inspect-wiring-bounded-backend-serializer-safe.json；新构建证明D:/project/rag-contract-reactor-artifact-verification-20261014.json，关联LR-48。

**处置**：保持协议与身份检查，独立核对公共RagException可序列化契约和官方Dubbo3.2.15读写Throwable；新回归必须真实经过官方writeThrowable／readThrowable，不仅new mock异常。证据inspect-wiring-domain-http-error-safe.json；修复前不宣称领域默认检索成功。

## KI-104 [OPEN·P2] 真实空间能力仍有关键词TIMEOUT及向量DEPENDENCY_UNAVAILABLE

**发现日期**：2026-10-14（accf97f／22f9fe实际部署）。

**现象与已证边界**：三空间INSPECT均在5.3～6.8秒返回、操作后signed ADMIN通过，1024维embedding及governed rerank保护probe未失败；KEYWORD为TIMEOUT，VECTOR为DEPENDENCY_UNAVAILABLE。已关闭整体consumer超时并隔离残余后端失败，不再重复JNDI混合ANY／REST误选调查。

**处置**：受限运行Milvus官方ServerException及describeCollection帧已取证，typed status未知；ES请求状态未知。只读源码及当前制品确认HC4 shared=true却无reactor启动owner，官方该模式client.start只置ACTIVE而不执行reactor，connect可在INACTIVE入队直到deadline；这是已证生命周期代码缺口，不把它当尚未采样的实际reactor状态。已以官方shared=false由唯一Boot RestClient拥有原reactor，保留Spring官方幂等shutdown后备；十二项transport定向通过，含真实ACTIVE／SHUT_DOWN、重复关闭及无client后备。HEAD404官方自动ignore且无body不等待；原protected reader生产schema诊断和四项actual reader离线回调回归已补。Milvus describe-only按官方compatible CollectionNotExists分类SPACE_NOT_READY，保留硬失败优先、通用revision query及未知数字依赖失败；14项Milvus／8项ES投影定向全过。冻结增量22模块串行clean test及package各916项／142 XML、14Boot与Java17 probe制品证明已通过。新制品将输出固定HTTP状态及typed数字／enum，当前运行值仍未知；实际新部署另验，保持OPEN，关联LR-49／50。不能从两种错误推定只有missing schema，不绕HTTP守卫用curl当通过，不PROVISION／backfill／enable workers。官方传输与错误映射必要离线诊断独立进行。证据inspect-wiring-domain-inspect-safe.json；关联LR-34、LR-47。

## KI-105 [OPEN·P2] 房源RAG控制器未把正常业务错误映射为结构化HTTP失败

**发现日期**：2026-10-14。

**现象与根因**：KI-103异常传输修复后，ListingRagFacade保留正常收到的RagException，但Listing模块没有对应ControllerAdvice，SPACE_NOT_READY仍可能返回通用HTTP500而非可识别业务失败；修复RPC wire不等于修复HTTP边界。

**处置**：仅ListingRagController的定向advice处理RagException，沿用ApiResponse失败包络；code／message只用枚举，可选data.field只用原净化字段，不输出异常／cause／suppressed／stack。认证401、权限403、参数400、不存在404、冲突409、期限504、空间／依赖／重排失败503；普通RuntimeException及其他Controller不接管，成功包络与凭据清理不变。既有ListingRequestAuthenticationFilterTest增加五项真实MockMvc controller／advice／filter回归，8项filter／7项Facade／5项Identity／7项BusinessAuthorization共27项定向通过；冻结增量22模块串行clean test及package各916项／142 XML、14Boot制品证明已通过；实际新Listing POST及结构化HTTP失败仍待部署验证，保持OPEN。关联LR-48、KI-103。

## KI-78 [FIXED·本提交·P1] 个人回填游标、计数和持久身份未全部限定 owner

**确认依据**：本次授权 ADMIN 也只能访问自身个人记忆后，对现有回填分页实现进行源码复核。

**现象与根因**：仅限制偏好源或最终正文不足以保护跨用户持久 projection identity、扫描计数、回执和 r 游标；具有 ADMIN 的调用方可能观察或重建他人的删除身份。个人记忆强隔离包括这些控制事实，不只是内容。

**修复与验证（本提交）**：`MemoryRagBackfillService` 源与持久身份 SQL 在 LIMIT 前参数绑定当前 owner，foreign-owner preference 游标拒绝；输出前重验当前身份/admin/owner，失效时事务回滚 revision/outbox。`MemoryRagLifecycleTest` 15项通过，含H2分页、他人删除身份不影响计数/游标/回执、撤销回滚反例；当前全量验证见上，部署身份矩阵仍待执行。见 LR-39、LR-40。

## KI-79 [FIXED·本提交·P1] 内部记忆部分查询与后续写入缺持久 session owner 校验

**确认依据**：本次 memory 内部入口源码审阅，未在真实个人数据上利用。

**现象与根因**：artifact/by-id 省略 sessionId、handoffs/by-task 和 workflow/history 原先仅检查已认证 principal，没有依据持久 artifact/packet 的 session owner 限定输出；不存在 session 的后续 artifact/handoff/snapshot 写入也未拒绝。入口缓存 principal 不是返回前实时身份。此前关于既存会话可被直接覆盖的推断不成立：SessionSharedMemoryService 已拒绝变更既存 userId，session_id 唯一约束使并发新 ID 的失败插入不能覆盖已有记录；controller 前置校验不足不等于已证明服务层接管漏洞。

**修复与验证（本提交）**：内部请求强制真实 Bearer + X-Memory-Owner，以 requestURI/contextPath 限定路径；既存 session 不属本人即拒绝。`MemoryController` 三查询路由逐个核对持久 artifact/packet 的实际 session owner，workflow 内部 artifact 再核对实际所属；后续写入要求 session 已存在且属于本人。所有正常 controller 响应返回前重新认证 Bearer，核对 OWNER 及入口 actor。`MemoryControllerTest` 7项通过，含跨 owner、孤儿/缺 session、查询后撤销拒绝输出及本人正向回归；当前全量验证见上，部署身份矩阵仍待执行。不新增 service/schema/锁，不声称跨查询写入原子隔离。见 LR-40。

## KI-80 [FIXED·本提交·P1] A2A task lease 非原子容量检查及覆盖可破坏任务归属

**确认依据**：当前专用 A2aCredentialContext 新实现集成审阅。

**现象与根因**：并发 map size 检查后直接 put 不保证容量上限，且 taskId 相同的另一请求可替换原 owner/caller 的有效 lease；只在 AgentExecutor 校验还会漏 TaskStore 查询、取消和 resubscribe。

**修复与验证（本提交）**：`A2aCredentialContext` 原子注册/容量检查、拒绝另一在途 lease 和跨 owner/caller/task 覆盖、条件 remove；`A2aAuthenticatedRequestGuard` 在所有受支持任务操作访问 store 前核对原 owner，明确不支持操作失败关闭。对应Test全部通过，含跨 owner/并发/查询取消和线程恢复反例；当前common-a2a全模块42项；当前test/package与制品验证见上，真实五操作身份矩阵仍待部署。见 LR-41。

## KI-81 [FIXED·本提交·P1] A2A 默认 JDK transport 重定向可能转发专用凭据头

**确认依据**：当前官方 SDK JdkA2AHttpClient 源码核对，未向外部地址发送信标。

**现象与根因**：官方默认 Redirect.NORMAL 并非专用凭据安全策略；在任务 transport 装饰器添加头后，自动跟随跳转可能把信标发到原任务目标外。固定 Task endpoint 与请求签名不等于禁止 HTTP 转发。

**修复与验证（本提交）**：在已授权最小 SDK patch 制品中增加安全官方 `JdkA2AHttpClient` 构造路径，专用 beacon 使用 Redirect.NEVER，普通无凭据 SDK 默认行为不改；`BeaconA2aHttpClientTest` 2项通过，含本地302/307不跟随、不转发信标回归。制品/源码验证见上，部署transport矩阵仍待执行。不反射改 final、不暗写缓存或重写 JSON-RPC。见 LR-41。

## KI-82 [FIXED·本提交·P2] 版本初始化 LF checksum 首次不一致在 DDL 前失败

**确认依据**：本次真实开发库首次 manifest upgrade 返回 checksum mismatch。

**现象与根因**：Windows CRLF 未完全按 manifest 的规范 LF 字节归一，第一轮验证拒绝且未执行 DDL；不是迁移已成功或表已损坏。

**修复与验证（本提交）**：`scripts/deploy/run-init-manifest.sh` 在任何DDL前统一校验SQL/check的规范LF hash；当前源码与test/package验证见上，关联生产脚本H2回归见KI-87。此前运行事实保留：现存 MySQL8.4.11 六步升级及重跑全部 Verified，种子/历史计数不变；独立空新卷首启九步、fresh 重跑两次和 upgrade 六步均 schema check 通过。已应用 migration SQL/check 修改必须追加新版本，不绕 checksum；关闭checksum流程缺陷不代表当前应用部署矩阵完成。见 LR-42。

## KI-83 [FIXED·本提交·P1] 生产 PUBLIC_ONLY 空 allowlist 继承测试兼容的任意公开 host 行为

**确认依据**：本次实际 RagHttpTransportConfiguration configured 路径与 RagEndpointPolicy 源码复核，无外部未授权请求。

**现象与根因**：旧 Supplier<Set<String>> 测试接口允许空集合代表全部合规公开地址，生产 configured factory 共用该 snapshot 时仅在 MANAGED_INTERNAL 检查非空；生产缺失 allowed-hosts 未失败关闭，违背精确 host 授权。

**修复与验证（本提交）**：`RagEndpointPolicy` 区分旧测试入口和生产 configured 路径，生产两种模式空 host 集合立即拒绝，真实 transport 不绕过该 factory。`RagHttpPolicyBindingTest` 5项、`RagManagedEndpointPolicyTest` 80项通过，含实际工厂缺配置/空 PUBLIC_ONLY 的公开 hostname/IP 反例，DNS 零调用、HC5 连接 delegate 零交互；当前全量验证见上，部署网络策略矩阵仍待执行。见 LR-34。

## KI-84 [FIXED·本提交·P2] A2A 流式回调丢失认证失败错误码

**确认依据**：本次 current reactor test 后对 OfficialA2aAgentExecutor.executeStream 的定点源码核对。

**现象与根因**：流式 chunk 输出前 authLease.verify 抛出 RagException，订阅 error callback 却固定映射 EXECUTION_FAILED；最终同步/完成分支保留认证错误，但异步失败不能与普通模型故障区分。当前 blocking 撤销测试的首轮 NPE 另为夹具 anySatisfy 解引用 WORKING 空 message，并非生产未发 FAILED 的证据。

**修复与验证（本提交）**：`OfficialA2aAgentExecutor.executeStream` 回调只对 RagException 保留其结构化 code，其他错误仍 EXECUTION_FAILED；对应Test 11项通过，含撤销后零 progress/final 正文与安全认证错误、普通错误不泄漏provider正文反例。blocking 夹具精确核对 WORKING/FAILED、终态安全 error 和无 artifact，不删撤销断言；首轮失败事实保留，当前全量验证见上，部署流式身份矩阵仍待执行。见 LR-41。

## KI-90 [FIXED·本提交·P2] 开发 RocketMQ broker 卷根权限阻止存储初始化与注册

**发现日期**：2026-10-14（当前独立RAG发布前依赖准备）。

**现象与根因**：按原配置启动现有Redis／namesrv／broker，前两者协议或健康检查成功；broker在限定deadline未开放10911，mqadmin未确认注册，后续exit255／RestartCount9／OOMKilled=false。只读定位实际运行uid/gid=3000:3000、原store卷根0:0／0755不可写，日志同批确认commitlog／consumequeue mkdir Failed与store/lock Permission denied；盘仅23%使用。shutdown NullPointerException为初始化失败后的次级错误，不是已证卷损坏／OOM；原unless-stopped自动循环不是人工连续重启。

**修复与验证（本提交）**：实际停原broker一次、仅原卷根非递归chown为3000:3000、同一原容器启动一次，三个步骤均exit0，0755／id／mount／restart policy保持，未读取或删除数据文件。TCP10911 ready，真实mqadmin clusterList exit0且broker-a注册，running／restart0／OOMfalse；原Redis／namesrv保持。安全证据为release-prep内broker-permission-repair-safe.json、broker-after-repair-health-safe.json。后者store_failed分类仍true，不声称所有日志无错；关闭已证权限及注册故障，不替代Agent事件总线验收。禁止递归chown/chmod、删除commitlog、重索引、格式化或盲改MQ配置／restart policy。

**关联**：任务7.3、8.3、8.4；当前应用运行未完成。

## KI-89 [FIXED·本提交·P1] 房源单 ID 与记忆 Dubbo 返回未在最后 SQL 后复验身份

**发现日期**：2026-10-14（当前领域消费路径定点审阅，未在个人真实数据上利用）。

**现象与根因**：`ListingManagementServiceImpl.getListingSummary` 在 SQL 前取得 principal，SQL 后仍以旧角色决定单房源输出；新 signed listing READ RPC 与营销消费者可达。查询期间 ADMIN 降为 BROKER 或凭据撤销，可能仍返回 OFFLINE 房源。`MemoryRagSearchFacade` 虽在每次召回后重认证，但重认证早于最终真源事务的 `memory_rag_lock FOR UPDATE` 等待，唯一 return 前缺复验；直接 Dubbo 路径不经过 HTTP controller 的 checkedResponse，锁等待期间失效的 owner 可能继续收到偏好正文。

**修复与验证（本提交）**：在最终 SQL／评分完成后按原凭据复验当前 actor，再按当前角色检查房源；记忆唯一 return 前复验原凭据、actor 与请求 owner。新增 SQL callback 内撤销／降级／身份漂移的7项确定性回归，不使用 sleep、不改业务锁或重复后端召回。`ListingBusinessAuthorizationTest` 7项、`MemoryRagSearchFacadeTest` 24项共31项通过，定向日志 `D:/project/rag-domain-output-focused-20261014.log`，当前全量验证见上；实际运行身份矩阵仍待部署，不以单测声明全入口验收完成。

**关联**：LR-40、KI-79；任务3.2、3.3、3.4、6.3。

## KI-87 [FIXED·本提交·P3] 编排 H2 夹具未适配显式 MySQL 目标库

**发现日期**：2026-10-14（本轮22模块clean test）。

**现象与根因**：版本初始化为 `20261004_supervisor_orchestration.sql` 增加必要的 `USE bk_agent`。H2 编排测试逐语句执行同一生产脚本，但夹具未创建目标schema，导致 `Schema BK_AGENT not found`，三测试类共34个error；不是生产MySQL迁移失败，不能删除真实脚本的目标库或绕开checksum来修测试。

**修复与验证（本提交）**：`OrchestrationStoreTest` 夹具每个H2新连接初始化固定BK_AGENT schema并执行真实USE/CREATE；严格适配当前MySQL的SET/PREPARE/EXECUTE/DEALLOCATE块，仅在metadata缺列时执行生产脚本内嵌ALTER。未知SQL、其他数据库和不完整块拒绝；新增重复迁移、23列/3主键索引、PUBLIC空及失败关闭回归，不复制生产schema。主Maven定向Store4/Reconciler2/Graph30共36项全部通过，日志 `D:/project/rag-orchestration-repair-focused-20261014.log`；当前clean test与package亦通过，见共享验证，部署编排矩阵仍待执行。该夹具修复未改生产SQL、manifest与runner LF hash；完整失败日志 `D:/project/rag-current-reactor-clean-test-20261014.log` 保留。

**关联**：LR-42、KI-82。

## KI-88 [FIXED·本提交·P1] A2A push字段拒绝与在途重复发送检查晚于官方副作用

**发现日期**：2026-10-14（当前实现定点审阅官方SDK源，不是完整项目审计）。

**现象与根因**：SDK `0.2.5.Beta2` 的 `MessageSendConfiguration` 实际序列化字段为 `pushNotification`，守卫却检查 `pushNotificationConfig`，可使受签发送进入官方push配置路径。另同owner/caller/context的重复发送在guard通过后才由executor的task lease注册拒绝；官方handler已先更新TaskStore/history、复用队列并覆盖runningAgents，拒绝过晚可能干扰原在途任务及取消关联。

**修复与验证（本提交）**：`A2aAuthenticatedRequestGuard` 按实际SDK字段提前拒绝未支持push配置；`A2aCredentialContext` 在official dispatch前原子预留在途发送，执行采用同一租约，仅原forward和整个SDK command都完成后条件释放，不允许替换原执行身份。真实SDK序列化、同owner send/stream重复零official/store/queue副作用、失败转交和拒绝dispatch释放、两侧完成后释放回归通过。主common-a2a定向36项通过，日志 `D:/project/rag-a2a-repair-final-focused-20261014.log`，当前全量验证见上；两次此前夹具失败保留，未启用push、修改官方JSON-RPC或扩大认证范围。真实五操作运行联调仍待执行。

**关联**：LR-41、KI-80、KI-81。

## KI-86 [FIXED·本提交·P2] 记忆域评分版本白名单缺 Nacos 与 Compose 接线

**发现日期**：2026-10-14（当前 governed 配置复核）。

**现象与根因**：`MemoryRagSearchFacade` 按 `memory.rag.semantic-scoring-versions` 精确接受语义候选。application 已提供空默认环境占位符，但 Nacos memory 模板和 Compose memory 环境未透传；即使 RAG 使用已钉扎的 governed LOGIT profile 返回正确 scoringVersion，记忆域仍因空白名单拒绝全部语义候选。空白名单失败关闭本身正确，不能以移除版本校验修复接线。

**修复与验证（本提交）**：`nacos/memory-service.yaml`、`docker-compose.yml` 补齐同一个 `MEMORY_RAG_SEMANTIC_SCORING_VERSIONS` 环境变量接线，保持空默认及精确版本集合，不允许通配。开发发布仅选择当前三个 RAG profile 的确切版本；`MemoryRagSearchFacadeTest` 24项通过，含模板/Compose空默认、配置绑定、空/通配/未知/漂移版本拒绝和确切批准版本正向回归，当前全量验证见上；真实记忆排序仍单独验收。

**关联**：LR-31、LR-43；KI-76；任务6.3、7.1。

## KI-85 [FIXED·本提交·P3] chat 模型网关地址无法经 .env 配置，.env.example 存在失实条目

**发现日期**：2026-10-14（用户反馈「模型 URL 不固定、需要可配置」后全仓占位符级核对确认）。

**现象与根因**：各服务 chat 通道（deepseek starter）的 Nacos 模板均引用 `${DEEPSEEK_BASE_URL:...}`，但 Compose 公共环境锚点 `x-app-environment` 未透传该变量、`.env.example` 亦无对应条目——部署侧无法通过 `.env` 切换 chat 网关地址。同时 `.env.example` 有 9 个条目（`KE_EMBEDDING_BASE_URL`/`KE_EMBEDDING_MODEL`、`MILVUS_ENDPOINT`/`MILVUS_TOKEN`/`MILVUS_USERNAME`/`MILVUS_PASSWORD`、`ELASTICSEARCH_URIS`、`AGENT_RAG_RERANK_API_KEY`、`MYSQL_EXPOSED_PORT`——MySQL 服务未发布宿主端口）在 nacos/、docker-compose.yml、scripts/ 与各模块 application.yml 中均无消费方（embedding 已收口到 `RAG_EMBEDDING_*`），而 6 个实际被消费的变量（`DEEPSEEK_BASE_URL`、`RAG_AUTH_RPC_SECRET`、`MARKETING_ELASTICSEARCH_URIS`、`RAG_HTTP_ALLOWED_HOSTS`、`NACOS_A2A_AGENT_REGISTRY_ENABLED`、`MYSQL_DB_MEMORY`）反而缺失，误导部署与排障。

**修复**：`.env.example` 按 10 个分组重写并全量中文注释，补齐 6 个缺失条目、移除 9 个死条目；`docker-compose.yml` 的 `x-app-environment` 锚点新增 `DEEPSEEK_BASE_URL` 透传（默认值与 Nacos 模板默认一致）。

**验证**：`docker compose config` 通过；对全部新增/保留条目逐一占位符级核对消费方（nacos 模板、compose、预检脚本、application.yml），移除条目确认零引用。

## KI-46 [FIXED·本提交·P2] 受管 A2A 技能激活缺少技能名与任务锚点日志

**确认日期**：2026-10-10（`create-common-skill-module` 5.5 冒烟前置检查，源码确认）。

**现象与根因**：`SkillTool.call` 在成功加载时记录 `Skill '{}' activated (task={})`（task 原文），但受管 A2A 的 `SkillRoutingToolInterceptor.interceptToolCall` 直接调用 `SkillExecutionContext.activate` 并返回技能正文，不调用原 handler，也不记录激活日志。快照已激活时，模型拦截器同样直接走快照分支。旧日志既在受管路径缺席（不能以缺席判定未激活），又在未受管路径泄漏 task 原文（含个人信息风险）。

**修复**：受管成功加载路径记录技能名、task 的 SHA-256 指纹与字符数、callId、threadId；外部身份字段消除控制字符并限长（防日志注入），task 原文与技能正文不落日志；未受管 `SkillTool` 日志同步改为同一指纹形式。指纹逻辑抽取为共享 `SkillLogFingerprints`。不改变成功快照、显式选择或实际工具范围守卫（LR-21 不变）。

**验证**：`SkillRoutingToolInterceptorTest` 8 用例（成功、Runnable 元数据、无效/拒绝不误记、切换保持快照、显式锁定、默认工具直通、未受管直通、日志注入与越界）；`SkillToolTest` 脱敏断言；真实 contract A2A 冒烟确认激活日志出现且默认路径零激活（`docs/acceptance/contract-skill-smoke-20261010.json`）。

**代码位置**：`common-skill/src/main/java/com/bkanent/common/skill/runtime/SkillRoutingToolInterceptor.java`、`SkillTool.java`、`SkillLogFingerprints.java`。

**关联**：LR-21；`openspec/changes/create-common-skill-module/tasks.md` 5.5、5.7。

## KI-47 [FIXED·本提交·P1] 自定义 AgentCard bean 抑制 Starter 属性装配，九服务分布式启动即失败

**确认日期**：2026-10-10（contract-service 真实分布式启动冒烟，首次真实启动暴露）。

**现象与根因**：每个子 Agent 的 `*OfficialA2aAgent` 用 `SkillAgentCardPublisher.publish` 定义自己的 AgentCard bean；Starter 的 `A2aServerAgentCardAutoConfiguration` 类级带 `@ConditionalOnMissingBean(AgentCard)`，自定义 bean 使整个自动配置（含其 `@EnableConfigurationProperties` 的 `A2aServerProperties` / `A2aServerAgentCardProperties`）跳过；而服务自己的 Card bean 方法又以 `A2aServerAgentCardProperties` 为参数 → `UnsatisfiedDependencyException`，应用启动失败。既有 `ExplicitSkillAgentWiringTest` 用反射直接构造参数对象绕过容器，单测从未暴露该缺陷；旧 Docker 镜像构建于该 bean 方法加入之前，也未覆盖。九个服务全部受影响。

**修复**：`common-a2a` 的 `LiveSkillAgentCardAutoConfiguration` 统一 `@EnableConfigurationProperties` 注册 `A2aServerProperties` 与 `A2aServerAgentCardProperties`（注册幂等，Starter 自动配置正常执行时不重复注册）。contract-service 真实分布式启动、Nacos Card 注册与 A2A 冒烟通过。

**防回归提示**：以真实进程启动验证 A2A 装配，不再以反射构造参数的接线测试作为启动可行性证据。

**代码位置**：`common-a2a/src/main/java/com/bkanent/common/a2a/LiveSkillAgentCardAutoConfiguration.java`；九服务 `*OfficialA2aAgent.contractPublishedAgentCard`。

**关联**：LR-28（Card 发布链）；`openspec/changes/create-common-skill-module/tasks.md` 5.8。

## KI-30 [FIXED·本提交·P1] 技能热更新未同步 HTTP/Nacos Card

**发现日期**：2026-10-05。

**现象与根因**：九个 Subagent 的 Card 只在启动时构造，本地 reload 后仍发布旧 hash 或已删除技能，显式委托报 SKILL_CONTENT_MISMATCH/SKILL_NOT_FOUND。

**修复**：完整目录替换通知共享发布器；HTTP 路由读最新 Card，复用已启用 Nacos 注册器异步发布并重试最新版本；生成条目幂等重建。回归覆盖正文修改、新增、删除、失败重试和真实 Starter HTTP 路由。

**真实网络补充**：同版本 release 是幂等创建，不覆盖旧内容；必须使用官方 Maintainer update API。启动核对一并修复旧 YAML 留在注册中心的内容；按需创建更新客户端并复用 Nacos 配置及 latest 策略。`LiveSkillAgentCardsDockerTest` 验证真实同版本正文更新、新增及删除；更新权限失败保留待发布项重试，不假报成功。部署需提供命名空间 Card 更新凭据，见 LR-28。

**关联**：LR-24、LR-25、LR-28。发布及发现仍最终一致，旧执行快照保持不变。

## KI-31 [FIXED·本提交·P1] YAML 旧技能条目污染模型目录

**发现日期**：2026-10-05。

**现象与根因**：手工 Card 技能与本地 Markdown 注册表同时发布，多数服务的技能名已经漂移。

**修复**：移除九个服务 `card.skills` 及退役的 `agent-default-intent`，本地技能仅由注册表生成；不移除发布器中与本地技能无关的显式 Card 能力。九服务接线回归继续验证发布及执行身份。

**关联**：LR-17、LR-27、LR-28。

## KI-32 [FIXED·本提交·P2] 能力目录故障裸抛 Graph 异常

**发现日期**：2026-10-05。

**现象与根因**：准备、模型、守卫、审批节点在异常捕获之外获取目录，注册中心/MCP 故障或重复能力抛出未结构化异常。

**修复**：统一目录异常为 CAPABILITY_CATALOG_UNAVAILABLE，写入 FAILED checkpoint；审批节点补齐到既有 Complete 的失败边。回归逐节点注入目录故障并确认零实际调用。

**关联**：LR-18、LR-29。

## KI-33 [FIXED·本提交·P2] 审批恢复目标消失导致空指针

**发现日期**：2026-10-05。

**现象与根因**：恢复计算审批参数哈希之前直接解引用当前目录中的目标，没有重新验证目标存在性。

**修复**：批准后先整批验证目标、版本、范围和参数，再校验审批哈希；目标下线明确 FAILED/CAPABILITY_UNAVAILABLE，未发送记录 REJECTED，清除等待审批。拒绝审批无需访问目录。回归覆盖目标删除、版本变化、目录不可用时拒绝审批。

**关联**：LR-22、LR-27、LR-29。续接上下文必须与原策略一致，继续保留 CONTINUATION_POLICY_CHANGED 契约。

## KI-34 [FIXED·本提交·P2] Card 瞬时失败导致目录消失，缺省能力被判为 false

**发现日期**：2026-10-05。

**现象与根因**：获取 Card 失败删除旧描述并写成功刷新时间；SDK 的原始可空布尔在 official 转换时被填为 false。

**修复**：活跃且地址/Card 路径相同的实例保留最近验证描述，失败单独记录并在 1 秒后允许重试；实例下线或地址变化立即移除旧目标。strict Nacos 查询禁止静态注册配置重新填回已下线目标。Supervisor 在原始 Nacos 边界恢复可空事实，缺少静态配置或 metadata 时保持 unknown。回归验证缓存保留、快速恢复、地址变化/下线及 null/false 区别。

**关联**：LR-19、LR-25；KI-18、KI-22。SDK 兼容层保留防止原始布尔拆箱异常的归一化。

## KI-35 [FIXED·本提交·P3] 执行器拒绝没有终态审计

**发现日期**：2026-10-05。

**现象与根因**：Guard 已创建 PENDING 记录，但线程池拒绝只返回 EXECUTOR_BUSY，账本无法表达明确未执行。

**修复**：未发送拒绝原子落账 REJECTED，记录拒绝原因并输出 tool.rejected 事件；不得重 claim，不覆盖已执行/已完成事实。混合批次因未知结果停机时，仍记录其余已完成/已拒绝结果；已完成调用复用账本结果，不占用满载执行器或误报 BUSY。回归验证满载、排队超时、claim 与 reject 的互斥。

**关联**：LR-29。

## KI-36 [FIXED·本提交·P3] 并行等待期限累加及下游流式等待无界

**发现日期**：2026-10-05。

**现象与根因**：逐个 Future 等待时重新计时，最坏累计 N 倍期限；下游 finished.await 无本地期限。

**修复**：批次提交起使用同一截止时间；已完成结果可回收，未 claim 的排队调用可证明未执行，已提交且未确认结果继续对账。九服务使用可配置下游流式期限，默认 120 秒；超时输出 EXECUTION_TIMEOUT，超时/中断取消订阅。回归验证统一期限、真实账本状态、永久不结束流的失败与释放。

**关联**：LR-23、LR-29；KI-35。

## KI-16 [FIXED·本提交] 前后端联合声明的四项 API 缺口

**现象**：前后端 README 共同声明的待补缺口——审批待办分页、审批决策 API、`POST /auth/refresh`、报告跨任务搜索（任务历史分页在 6b5cc0e 已先行补齐）。

**根因**：后端各面按需生长，前端任务中心/审批中心/登录态续期三个页面各自等待对应查询与决策接口。

**修复**：
1. 审批待办分页 `GET /agent/supervisor/approvals/pending`（userId/page/pageSize；判定逻辑见 LR-15，防回归单测 `SupervisorApprovalTodoServiceTest`——含「已恢复任务历史 WAITING 行不算待办」用例）；
2. 审批决策 API 已有（`POST /agent/supervisor/approvals/callback`，APPROVED/REJECTED/TERMINATED + 幂等 claim），本轮仅确认契约无需新增；
3. `POST /auth/refresh`（DTO `AuthRefreshRequest`；轮换语义与「出生即吊销」护栏见 LR-16，单测 `AuthControllerTest` refresh 三用例）；
4. 报告跨任务搜索：REST `GET /interviews/reports/search` + 治理面 `searchReports` 工具 + MCP 面 `search_reports` 工具（keyword 对 report_json LIKE + creatorWorkNo 经 case 解析 + 分页；返回摘要不回传全文，oneLiner 从 report_json 抽取）。

**已知限制**：报告搜索走 `LIKE '%kw%'` 全表扫（report_json 为 JSON 列、无 FULLTEXT 索引、LIKE 本身用不上 B-tree）——数据量大后需物化摘要列 + FULLTEXT 或外置检索（与 `search_transcripts` 同一债务）。

**关联**：LR-15、LR-16

## KI-17 [FIXED·bfe48c2] Supervisor 入口规则分流偏离显式 skill 的设计边界

**确认日期**：2026-10-03；最终方案于 2026-10-04 对齐。用户确认正常请求应由 LLM 根据 A2A/MCP 等能力 description 判断调用；本次指定 skill 后仍由模型理解原始请求，并遵循技能正文中的流程。

**现象**：默认 `llm-enabled=false`、`strategy=rule-first`，入口关键词与默认 listing/intent 选择 Agent，nextHints 和 trade 结果又可自动交接。现有 Supervisor 知识技能与 Subagent 正文加载是不同消费路径，改造前 Supervisor 未接入统一的 A2A/MCP 模型工具循环；skillHint 也不能承担本次显式选择、版本固定和严格能力范围契约。正文与工具白名单本身不提供业务步骤顺序强保证（KI-19）。

**处置**：已修订 `realign-supervisor-tool-and-skill-orchestration` 为最终方案：统一可调用能力目录，AUTO/EXPLICIT_SKILL 使用同一通用模型工具循环；显式技能首轮前加载正文，但模型仍理解请求并选择工具。保留 Markdown 指引，严格约束调用范围、参数、权限与审批，同步 Subagent 契约及调用级恢复；不增加 workflow/DAG、步骤调度或正文顺序/完成检查器。2026-10-05 新请求与普通 chat 已切到 llm-tools-v1，范围和审批恢复已验证。本机验收 MySQL 7 条旧流程记录均为终态后，旧 Graph 执行链及旧入口配置已删除；历史 checkpoint 只读查询保留，不可恢复执行。该核对不代表生产数据已排空。实现见 bfe48c2、21c8084，验收见 b18ab33、ce36246。

**关联**：LR-17、LR-18、LR-9、LR-15、KI-19；主规格 `supervisor-domain-catalog` 的冲突行为在本变更 delta 中明确替代。

## KI-18 [FIXED] 动态 Agent 能力目录存在信息损失和地址缓存限制

**当前状态**：仍为 FIXED；7.4 清理的是 Supervisor 旧 Graph 路由执行器，不改变动态 Card/Nacos 注册发现及 endpoint/远端 Task 关联契约。

**确认日期**：2026-10-03（静态代码/依赖检查，未连接部署环境）。

**现象**：`OfficialAgentCardDiscoveryClient.convertWrapper` 将 skills 置空、异步能力设为 false，并以 transport 存在简化流式能力；`OfficialA2aAgentClient.clientFor` 按 agentId 缓存客户端，endpoint 改变没有失效逻辑。Compose 配置 Nacos 3.0.3，本地 A2A Starter 使用 Agent Registry API，需实际验证服务端支持版本，不能把普通服务发现成功等同于 Agent Card 注册成功。

**处置**：2026-10-04 已实现卡片 skills/description/原生 capabilities/协议字段保真，并在动态注册描述中保留这些字段和实例元数据（0064b85）；回归测试通过。原生 A2A Card 无独立 async 标志，保持未知并允许明确配置/实例元数据补充，不通过 transport 或 stateTransitionHistory 猜测。客户端已按 endpoint/版本/协议能力刷新，远端 Task 按 Agent+Task 关联原描述和客户端，查询/取消保留原地址；提供恢复原关联接口，17 项客户端/执行服务测试通过。部署冒烟、原 Task 关联持久化与混合协议已验证；地址修复见 b58b14d，完整网络验收见 b18ab33。

**代码位置**：`agent-service/registry/OfficialAgentCardDiscoveryClient`、`agent-service/client/OfficialA2aAgentClient`、`docker-compose.yml`。

**验收结论（2026-10-04）**：Docker Nacos `v3.1.0` + SDK `3.1.0` + A2A Starter `1.1.2.3` 已实测 releaseAgentCard→registerAgentEndpoint→NacosAgentCardProvider→官方 HTTP A2A 调用，HTTP Card fallback 也通过。Compose 升级 3.1.0；`agent.distributed.http-card-fallback-enabled` 默认 true，可显式关闭。调用账本接受关联与原地址对账见 `26cdeb4`；描述修复见 `0064b85`，地址缓存见 `b58b14d`。测试是确定性协议夹具，不代表真实模型或九服务业务联调全部通过。SDK 可选布尔字段限制见 KI-22。

**关联**：KI-17；`openspec/changes/archive/2026-10-05-realign-supervisor-tool-and-skill-orchestration/tasks.md` 第 2 组。

## 设计限制（LIMIT）

## KI-22 [FIXED] Nacos A2A Starter 读取缺省布尔字段的外部 Card 可失败

**确认日期**：2026-10-04（实际注册接口验收）。

**现象**：Starter `1.1.2.3` 的 AgentCardConverterUtil 对 pushNotifications、stateTransitionHistory、supportsAuthenticatedExtendedCard 直接拆箱，外部 Card 未填这些可选值时可抛 NullPointerException。项目的官方 Card 构建器发布明确布尔值，验收已覆盖完整字段；省略字段的外部发布者需补齐，或开启 HTTP Card fallback。不能把发现失败当成 Agent 不支持业务任务。

**项目修复（2026-10-05）**：共享 NacosSkillCardMapper 在副本中按协议默认值补齐可选布尔字段，再保真转换扩展；Supervisor 优先通过 SDK 读取原 Card，绕过 Starter 的有损转换。上游 Starter 缺陷仍存在，项目默认路径已修复，保留旧 provider 适配及 HTTP fallback。`NacosSkillCardMapperTest` 覆盖空字段且不修改原 Card。修复 commit：`b18ab33`。

**关联**：KI-18、KI-25；`NacosAgentRegistryDockerTest`。

## KI-20 [FIXED] Supervisor 审批回调未绑定网关身份和任务 owner

**确认日期**：2026-10-04。

**修复 commit**：`bfe48c2`。

**现象**：审批回调控制器直接使用请求中的 reviewerId 调用工作流服务，未像查询入口一样解析网关身份并检查任务 owner。通用工具审批不能依赖调用者自行填写 reviewerId。

**修复**：回调先绑定认证 reviewer，再通过现有 workflow 查询权限及 owner 校验，之后才能恢复具体审批调用。控制器新增未找到所属工作流时禁止恢复的回归。新旧 runner 均经过此入口。

**代码位置**：`agent-service/controller/AgentController.handleApprovalCallback`。

**关联**：LR-15、LR-18；`openspec/changes/archive/2026-10-05-realign-supervisor-tool-and-skill-orchestration/tasks.md` 6.3。

## KI-21 [FIXED] 数据库 checkpoint 重启后读取了最早状态

**确认日期**：2026-10-04（Docker MySQL 8.4 实测）。

**现象**：内存执行已暂停为 WAITING_USER_INPUT，创建新 saver/runner 后却读取 RUNNING，续接被错误拒绝；旧 graph 同样受影响。

**根因与修复**：Graph 1.1.2.3 的 MemorySaver 把最新 checkpoint 放在链表头，BaseCheckpointSaver.getLast 实际取 peek。原数据库加载按版本升序 append，使重启后最早记录位于表头。改为版本降序；增加普通回归与真实 MySQL 新 runner 续接测试。修复 commit：`c2f5a4c`。

**代码位置**：`DatabaseCheckpointSaver.loadedCheckpoints`、`DatabaseCheckpointSaverOrderTest`、`SupervisorDockerDatabaseTest`。

**关联**：LR-15、LR-23；本变更 3.5、6.1、8.4。

## KI-23 [FIXED] 技能注册表热加载暴露半更新索引

**确认日期**：2026-10-04。

**现象**：reload 清空并逐项填充名称/领域索引时，并发模型请求可能读到空目录或部分技能；领域查询还返回可修改的内部列表。

**修复**：先在局部构建完整的不可变目录和索引，再一次性发布 volatile 快照。暂停构建的并发回归确认读者始终看到完整旧版或完整新版，返回列表不可修改。修复 commit：`4d8f846`。

**代码位置**：`common-skill/core/SkillRegistry`、`SkillRegistryTest.concurrentReloadPublishesCompleteImmutableRegistryAtOnce`。

**关联**：LR-21、LR-24；本变更 3.5。

## KI-24 [FIXED] 产物事件故障与部分恢复可遗漏衍生产物

**确认日期**：2026-10-04。

**现象**：主产物入库后，事件发布异常可中断衍生产物保存；重试发现主产物已存在便提前返回，使草稿正文等衍生产物永久缺失。

**修复**：事件失败不覆盖持久事实；主产物存在时仍按独立来源身份检查并补存衍生产物。新增推送故障和主产物已存在、正文缺失两个回归。通用循环同时将 mode、capabilityId、callId 与 skill/version 传入主/衍生产物和事件。修复 commit：`993cb7e`。

**代码位置**：`agent-service/graph/node/PersistArtifactsNode`、`PersistArtifactsNodeTest`。修复 commit：`993cb7e`。

**关联**：LR-22、LR-23；本变更 6.4。

## KI-25 [FIXED] Nacos 注册与发现转换丢失显式技能扩展

**确认日期**：2026-10-05（Nacos 3.1.0 混合协议真实验收）。

**现象**：Starter 1.1.2.3 的 CardConverter 在双向转换中丢弃 capabilities.extensions，发布技能在 Nacos 中缺少版本/内容身份契约，或发现后返回空扩展，导致真实下游委托报 EXPLICIT_SKILL_UNSUPPORTED。

**修复**：共享 mapper 保留扩展原内容；只装饰已启用的 Starter 默认注册组件，使用同一个 SDK client、SERVICE 注册类型和 registerAsLatest 策略。Supervisor 注入实际 A2aService 后从 SDK 原 Card 转换，不再经过有损 provider；SDK 不可用时保留旧适配和已配置 HTTP fallback。自动装配和真实注册→发现→带子技能 A2A 调用均有回归。修复 commit：`b18ab33`。

**代码位置**：`common-a2a/NacosSkillCardMapper`、`SkillAwareNacosOperationService`、`SkillNacosCompatibilityAutoConfiguration`；`OfficialAgentCardDiscoveryClient`。

**关联**：KI-18、KI-22；LR-25；本变更 1.3、2.5、4.1、8.2。

## KI-27 [FIXED] 旧任务恢复可能改选业务目标或缺少 owner 校验

**确认日期**：2026-10-05。

**现象**：旧 checkpoint 已固定 selectedAgentId，但原目标下线后执行器会按 domain 重新选择；旧任务查询和审批恢复也未在 facade 校验原 owner。

**修复**：旧任务仍走原 graph timeline，已固定目标不可改选，撤销时明确失败；旧任务查询及审批核对 owner，新 timeline 优先按持久化 run 身份定位。

**验证**：`LegacyRunnerDockerRecoveryTest` 在真实 MySQL 重建 graph/facade，原审批恢复仅执行一次；重复审批、完成后重启均不重执行，撤销目标零执行，伪造 owner 拒绝，新请求使用新 runner。测试业务执行为计数夹具，未声称旧远端未知请求具有 exactly-once。

**代码位置**：`DefaultOfficialSupervisorGraphFacade`、`OfficialSupervisorGraphFactory`。修复 commit：`ce36246`。关联 LR-26、LR-15、KI-21。

## KI-28 [FIXED·833c800] 下游技能身份错误在调用 claim 后才校验

**确认日期**：2026-10-05（真实模型评估）。

**现象**：模型可将 a2a 能力 ID 或 Supervisor 技能名当成子技能名，或填入未发布版本。原 schema 校验无法验证发布映射，错误在执行回调内部抛出后被统一记为 OUTCOME_UNKNOWN，尽管尚未提交远端请求。

**修复**：能力目录提供无副作用的下游发布身份校验，GuardCall 与执行前共用；确定的技能错误在 claim/审批前整批拒绝，模型可以纠正。实际 A2A 回调仍再次解析当前发布映射；真实发送后的未知结果继续保守对账。技能目录显式展示 owner/version，提示区分父技能、能力 ID 与子技能，不猜版本。

**验证**：目录无远端调用回归及 Graph 的“拒绝错误参数→模型修正→一次实际调用”回归；真实模型评估使用生产 SupervisorCapabilityCatalog，不让简化回调绕过技能身份校验。关联 LR-27、KI-19。

## KI-29 [LIMIT] 本机真实模型推理进程退出及输出质量偏差

**确认日期**：2026-10-05。

**现象**：Ollama 0.35.1 的本机验收曾返回一次 HTTP 500，日志显示 llama-server `signal: killed`，模型提示缓存上限 8192 MiB，Docker VM 总内存约 7.47 GiB。缓存压力与该退出可能有关，但没有 OS 层证据确认唯一根因；上游有相同缓存增长问题记录 [Ollama #18264](https://github.com/ollama/ollama/issues/18264)。

**处置**：仅在本机验收容器设置 LLAMA_ARG_CACHE_RAM=1024，实际日志确认缓存上限 1024 MiB，关闭评估 SDK 内重试后八组请求重新完成。生产模型并未更换；不将这一小样本视为服务稳定性证明。

**质量限制**：模型仍可能误用工具别名作为技能名（会被现有治理拒绝）、增加多余加载、使用无单位的结构化预算，或在最终回答中写反风险归属、增加未返回的营销事实。正文指引不能消除这些质量偏差。保留 KI-19；不通过新增 DAG/业务顺序或完成检查器掩盖模型偏差。评估细节见验收记录及可见输出 JSON，不输出隐藏推理。

## KI-26 [FIXED] 九个 Subagent 的 Nacos SDK 被 BOM 降为 3.0.3

**确认日期**：2026-10-05（Maven 实际依赖树）。

**现象**：只有 agent-service 显式声明 3.1.0；公共 A2A Starter 在其余模块被 Spring Cloud BOM 管理为 nacos-client 3.0.3，缺少 A2aService/Agent Registry 契约，不能把 Supervisor 冒烟等同于九服务注册成功。

**修复**：根 POM 统一管理 nacos-client 3.1.0，Supervisor 去除重复硬编码版本；全模块使用相同基线。修复 commit：`b18ab33`。

**关联**：KI-18、KI-25；本变更 2.5、8.1、8.4。

## KI-14 [LIMIT] 运行面依赖 MySQL 单点读写

话轮管线每轮从 DB 重查重装三段记忆（无进程内会话缓存），延迟依赖 DB 且高并发下 `nextTurnSeq` 有竞态窗口（同会话并发话轮可能拿到相同 seq，靠幂等键去重兜底）。访谈会话天然单人串行，实际触发概率低；若未来支持多人同场访谈需引入分布式锁。

## KI-19 [LIMIT] 技能正文不提供业务步骤顺序强保证

**确认日期**：2026-10-04。

**现象**：现有 Subagent 的 SkillTool/技能拦截器加载 Markdown 指引并收窄工具集合，不验证调用顺序。Supervisor 已实现的统一循环延续这一边界：即使本次显式指定 skill，模型仍可能漏掉、提前执行或重复正文中的业务过程，工具允许清单本身不能证明流程正确完成。平台不将该软约束转为业务顺序或完成检查。

**根因与选择**：用户最终接受由模型遵循正文的方案，以避免步骤 DSL、DAG 或业务顺序/完成检查器与通用 Graph 耦合。平台已经硬约束能力范围、参数、身份、权限、预算和审批，但这些调用级守卫不构成正文流程的顺序保证。

**处置**：保留 LIMIT，不将强制顺序校验列入本次实施任务；通过清晰的技能正文、模型真实调用评估和运行轨迹观察改善遵循质量。评估通过不能证明顺序强保证；如果后续业务必须保证先后关系，应重新讨论设计并更新变更范围，不能在通用节点中暗加业务步骤判断。

**关联**：LR-17、LR-18、KI-17；`openspec/changes/archive/2026-10-05-realign-supervisor-tool-and-skill-orchestration/design.md` 的非目标和验证边界。

---

## 已修复（FIXED，历史记录与防回归）

## KI-2 [FIXED·e85fa93] 状态机权限表错位（致命）

**现象**：权限表按「目标状态→源状态」错位映射，6 个 `transition` 调用点中 5 个抛 `IllegalTransitionException`——开台能创建会话但访谈永远无法启动。

**根因**：权限表（状态机文件）与全部调用方（工具层/运行时/补偿器）分居多文件各自编写，无人对账；状态机依赖 DB、零单测覆盖。

**防回归**：`InterviewSessionStateMachineTest`——每个真实调用点一个正向用例 + 越权反向用例 + 前置状态链完整性（8 用例）。**新增 transition 调用点时必须同步加单测。**

## KI-3 [FIXED·e85fa93] searchAssets 创建人过滤永远空结果

**现象**：创建人过滤条件写成 `id = -1`，任何带 creatorWorkNo 的检索返回空。
**根因**：创建人字段在 `interview_case` 表而 `interview_asset` 表没有，实现时留下残缺条件。修复为经 caseMapper 解析 caseId 集合再过滤资产（场景过滤同修）。

## KI-4 [FIXED·e85fa93] 话轮对话历史倒序装配

**现象**：`synthesize` 取最近 6 轮后未反转，模型收到从新到旧的「倒放」对话，严重干扰造句连贯性。

## KI-5 [FIXED·e85fa93] 报告任务 RUNNING 租约卡死

**现象**：租约扫描只含 PENDING/RETRYABLE，执行器进程在 RUNNING 状态崩溃后租约过期也无人重扫，任务永久卡死。修复：RUNNING 且租约过期纳入扫描（见 LR-10）。

## KI-6 [FIXED·e85fa93] CLOSE/ACK_AND_SWITCH 不标当前题已答

**现象**：收尾/重复抗议换向时当前题永远悬在 PENDING，影响归集评级与 `allDone` 判定。修复：分支内显式标记。

## KI-7 [FIXED·e85fa93] 话轮入口无状态守卫

**现象**：ARCHIVED 会话仍能推进话轮。修复：入口加 IN_PROGRESS 守卫（收尾锁走独立极短道别分支，见 LR-11）。

## KI-8 [FIXED·e85fa93] allDone 恒假判定

**现象**：`currentQuestion()` 只返回 PENDING 题，current 非空恰好说明没答完——原复合条件是恒假绕圈。化简为 `current == null`。

## KI-9 [FIXED·e848923] 题目确认制走不通（无插入题目路径）

**现象**：全链路只有 question 的 update 没有 insert，`confirmQuestions` 确认的是不存在的行，题目确认制形同虚设。
**修复**：领域层 `createCandidateQuestions`（自动编号/核心题深度上限/落 outline_route）+ 治理面 `generateQuestionSet` @Tool + REST `POST /{id}/questions`。

## KI-10 [FIXED·e848923] 导演指令不生效

**现象**：`sendDirectorCommand` 只返回回执不落库，运行面下一轮话轮不读取——指令不会真正生效。
**修复**：新表 `interview_director_command`（PENDING/CONSUMED + source 留痕）+ 双端点落库（A2A 工具 / `POST /{id}/director-commands` 直连）+ 话轮决策点消费（见 LR-7）。

---

**变更历史**：本提交 补齐联合声明 API 缺口（KI-16）；6b5cc0e 报告任务分页/详情；e848923 补齐运行面四缺口（含 KI-9/KI-10 修复）；e85fa93 排查修复 8 处缺陷（KI-2~KI-8）；3b0f8a5 初始实现；4934137 立项规划。
