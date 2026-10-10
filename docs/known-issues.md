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
