# 独立隔离 RAG 本地实施验收

- 变更：`extract-isolated-rag-service`，分支 `dev-fix`。
- 本记录区分源码/本地测试验证与真实运行迁移；不构成上线、安全完整审计或真实默认检索验收。
- 前序公共契约及 auth 实施提交：`2a3fa12`。后续迁移实施与本记录同提交；历史 `split-rag-by-domain` 不实施、不归档或改写。

## 实施边界

`rag-service` 集中 ES 关键词召回、Spring AI Milvus 数据读写及 embedding、RRF 去重融合和 BGE native/DashScope 语义重排。域通过 Dubbo 的版本化契约提交受控参数，不能自行选择物理目标或执行某一路。不同空间禁止共用 ES 索引或 Milvus 集合；注册隔离不替代真实用户、服务身份和领域权限。

房源与记忆保留 SQL 真源、语料、授权、生命周期与最终排序/复核。变更在同一领域事务持久化 revision/outbox，提交后发送完整投影；RAG 独立 `bk_rag` 保存索引控制状态，旧版本/部分写/删除命中在融合前不可见。最终领域响应的所有候选/正文均再核对真源，stageStats 不透传。只允许短暂漏检/延迟，不承诺跨后端原子事务或 exactly-once。

原 agent/listing 的本次后端执行类、bean、初始化和执行依赖已迁出；agent 保留 chat/Graph/A2A、旧 REST 和只读工具的纯包装，marketing 的独立 ES 业务搜索不属于本次提取。系统约束 bootstrap/YAML 仅在 memory 执行且仅补缺。Supervisor 历史快照只读、已完成调用不重发，知识工具首终态结果不能被迟到结果覆写（LR-37）。

管理操作同时要求服务/空间操作许可、USER、真实 signed principal 和 adminRoles；SERVICE writer 不能管理或搜索。凭据不进入模型参数、普通上下文、日志、outbox 或 checkpoint；异步凭据使用有界进程内租约，到期/重启需重新认证。

## 本地验证命令

在仓库根目录，Java 17 / Maven 3.9.16，均使用仓库 settings，离线运行。构建串行，期间无源码代理并发编辑或构建。

```bash
mvn -o -gs .mvn-settings.xml -s .mvn-settings.xml clean compile

mvn -o -gs .mvn-settings.xml -s .mvn-settings.xml test \
  '-Dtest=Test*,*Test,*Tests,*TestCase,!ApprovalClaimDatabaseConcurrencyTest,!SupervisorDockerDatabaseTest,!SupervisorDistributedAcceptanceTest,!NacosAgentRegistryDockerTest,!NacosRollingUpgradeDockerTest,!SupervisorRealModelEvaluationTest,!LiveSkillAgentCardsDockerTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

mvn -o -gs .mvn-settings.xml -s .mvn-settings.xml package \
  '-Dtest=Test*,*Test,*Tests,*TestCase,!ApprovalClaimDatabaseConcurrencyTest,!SupervisorDockerDatabaseTest,!SupervisorDistributedAcceptanceTest,!NacosAgentRegistryDockerTest,!NacosRollingUpgradeDockerTest,!SupervisorRealModelEvaluationTest,!LiveSkillAgentCardsDockerTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

openspec validate extract-isolated-rag-service --strict
git diff --check
```

显式排除七个真实外部联调测试，原因是继承环境可触发真实 MySQL 写入、Nacos 发布或模型 HTTP；本次不具备这些运行操作/目标的授权及安全前置条件。排除项不是已验收或测试内 skipped 的零值证明。其他 H2、mock 和 loopback 协议夹具正常运行，不宣称无 SQL 或无本机 socket。

## 回归覆盖

- 公共契约/认证：canonical hash、签名绑定、expiry、角色/账号失效、伪造身份、独立 response key。
- RAG：空间映射/权限交集、实际 Spring AI 过滤转换、数值精度/UTC 时间编码、两路 RRF/去重/稳定序、评分 profile 与重排协议、有界 STRICT/ALLOW_DEGRADED、deadline/取消、认证及 URL 安全拒绝不可降级。
- HTTP transport：81 项本地测试覆盖 HC5/HC4 原 transport 的全 DNS 答案、原始 mapped AAAA、每次新连接地址绑定、禁止代理/重定向、响应预算及实际取消；未访问真实后端。
- 索引账本：21 项 H2 生命周期测试覆盖持久化确认、乱序/幂等/冲突/tombstone、部分应用、不可取消迟到写、实际 hash 核对与失败关闭。
- 领域：SQL/outbox 同事务、所有活跃入口、回填交错、授权/阈值/失效/最新 revision/hash 最终复核、持久化 SQL 表示生成投影、统计/正文不泄漏失效候选。
- 兼容：旧六组 REST、固定别名/schema、默认房源 20+20/最终 10、知识 HTTP 最终 5、工具 topK 合法候选池、显式每路 500+500 保留而重排池封顶 500、非法数量拒绝与有限扩池。
- 编排：真实 Graph 知识开关、确定只读失败、首终态 CAS、历史审计恢复、已完成/legacy claim 不重发、异步租约及不落 token；未新增状态机 transition。
- 真实生产 Spring Boot context 测试：使用 H2、不替换生产后端 bean，发现三份 Dubbo ServiceBean；空密钥/空间下基础 readiness 503，lazy embedding/Milvus 不在启动主动探测。测试专门关闭注册/export，不证明运行环境 RPC 已导出/发现。

## 失败及修复历史

首轮 clean compile/test/package 通过，选中测试 643 项。补充显式召回数量及 Spring AI typed retry exception 的 HTTP 401/403 分类后，第二轮 clean compile/test 通过（646 项），随后的 package 重跑在 memory outbox 测试失败：`expected 2 / observed 1`。

固定时钟复现：H2 TIMESTAMP 精度 6，插入时间 `.123456700Z` 持久化为 `.123457Z`，晚于首次派发 cutoff `.123456800Z`。第一派发实际 eligible=0、PENDING/attempts=0/error=null；显式到期后第二派发才首次提交，得到 TIMEOUT/attempts=1。生产派发正确尊重 next_attempt_at，并无丢事件证据（KI-69）。

仅测试在第一次启用派发前显式到期，保留相同 eventId/revision 与 2/3 次提交断言，新增确定性微秒边界回归；未加 sleep、弱化断言或修改生产时钟/签名。修复后 memory 定向生命周期/派发/搜索/安全/bootstrap 20 项通过，随后重新运行上述完整串行验证。

本机原始日志在仓库外：
- `D:/project/memory-dispatcher-reproducer.log`（预修复确定性失败证据）。
- `D:/project/memory-dispatcher-fixed.log`（定向通过）。
- `D:/project/rag-reactor-verified-compile.log`。
- `D:/project/rag-reactor-verified-test.log`。
- `D:/project/rag-reactor-verified-package.log`。

### 最终结果

全 reactor 21 个项目的 clean compile、test、package 均 BUILD SUCCESS。选中测试 647 项，test 与 package 重跑各自均 0 failures / 0 errors / 0 skipped；七个真实联调类在选择器中显式排除，不能称所有真实联调通过。

| 模块 | 选中测试数 |
|---|---:|
| common / common-skill / common-a2a | 30 / 54 / 24 |
| gateway / auth / agent | 5 / 53 / 140 |
| memory / rag / listing-master | 23 / 179 / 23 |
| notification / compare-engine / media-worker | 1 / 5 / 6 |
| marketing-content / promotion / business | 6 / 2 / 1 |
| contract / settlement / interview | 13 / 1 / 81 |

运行依赖树另执行并通过：

```bash
mvn -o -gs .mvn-settings.xml -s .mvn-settings.xml \
  -pl rag-service,agent-service,listing-master-service,memory-service,marketing-content-service -am \
  org.apache.maven.plugins:maven-dependency-plugin:3.7.0:tree -Dscope=runtime
```

原始依赖日志：`D:/project/rag-verified-dependency-tree.log`。四个可执行 JAR 的 manifest 均为 Boot JarLauncher + 对应应用 Start-Class，包含 BOOT-INF/classes。只有 rag 含本次 Milvus SDK/Spring AI Milvus/OpenAI embedding/ES 后端库，三消费者无这些后端实现库；agent/listing 保留由 Graph/共享 SDK 传递的 `spring-ai-vector-store` 通用接口库，消费者保留 HttpClient5 通用 transport，不能把这些名字误判为残留 RAG 执行底座。首次宽泛 JAR 断言误将它们当后端库，已按真实依赖树区分，未删除业务依赖。源码检索也确认三消费者无相关后端 import/EmbeddingModel/ElasticsearchOperations，营销 ES 和 agent chat 保留。

本次最终 JAR（`各模块/target/模块名-1.0.0-SNAPSHOT.jar`）SHA-256：

```text
rag-service             bd1d3e7d97233594187caea428a4f9595b8d625c71f5bdc7142fa2d6d120b22e
agent-service           46f75aa8dbb7e8f9596cab282f4c01aa47b2f6c62ed27518639609d99e817754
listing-master-service  a1dfacd40efe11b565cf26fbc66bf7cd9b8f7d49579815f35e5c3f17aa5656f8
memory-service          827c9e06884bc1d1ee6c032e136979074d0e8e4d04e2ea5cfa973764c9b5b28d
```

严格 OpenSpec validation、diff whitespace 检查通过；最终模板代理离线严格解析 10 个 YAML、35 项选中断言，三部署脚本 bash -n/LF 与 chat/MCP 保留断言通过。最终静态漏接修复涉及 memory 管理角色键/registry、listing 入站 agent key 透传及精确 readiness 文案，仍留空角色/密钥，不表示环境已配置。旧首轮 JAR hash 因最终源码变化不再作为交付证据。

## 真实环境未执行与切流阻塞

1. 合规且经授权的 ES/embedding/重排真实目标、真实评分 profile/模型维度及权限尚未确认。现有私网 ES、host.docker.internal 不能豁免 URL 策略；不改地址、不公开本机服务、不向第三方发送语料。默认双路加重排真实验收未执行。
2. 旧 ES/Milvus 的 PK/schema、旧逻辑身份/时间表示、数据完整性及通用知识归属未在线盘点。模板只注册 listing.knowledge、memory.preference、memory.constraint；supervisor.knowledge 未注册/激活，未知数据不公开。
3. 三份 SQL 迁移已编写并有 H2 行为测试，但未对真实 MySQL 8.4 执行或重复执行。真实库/账号最小权限、备份/保留窗口由部署方核对；不据 H2 宣称 MySQL DDL 已验收。KI-37/38/39 的无关历史迁移缺陷仍未统一修复。
4. checked-in 配置、地址变量及健康名称静态核对不等于实际 Nacos namespace/group/data ID 生效、端口可用、镜像部署或真实 Dubbo 发现。密钥/角色/评分 profile 留空，worker/dispatcher 默认禁用；基础 readiness 是认证配置、控制表可查询和 RPC 已导出，能力证据须受权 inspect/provision 获取且保持新鲜，不在启动/健康主动探测后端。
5. 当前 A2A SDK 0.2.5.Beta2 无已验证专用凭据 transport 注入路径（KI-59）。REST/Dubbo 和进程内租约已验证；真实跨 A2A 房源 Agent 工具链仍失败关闭且未验收，不向模型 metadata 塞 token、不暗修 SDK。
6. 真实 backfill/删除水位、停写及旧 writer/在途 RPC 排空、消费者切流、RAG 停机的实际可用性、兼容发布周期及按事件水位回滚未执行。全部索引/集合/卷和旧部署保留，未进行 Docker/Nacos/SQL 运维、delete/prune/down-v。
7. 目标镜像 JRE 的 java.naming/jdk.naming.dns 模块未实测；HTTP 守卫不保护 Milvus gRPC 或业务 Dubbo，不能扩大安全声明。

LR-31～37、KI-55～60 记录持续开放门禁；KI-61～69 只记录本次已确认源码/测试缺陷的修复范围。没有得到完整 Mimosa 深度安全扫描结论；本次未擅自发起完整审计。OpenSpec 仅勾选本地实际完成部分，保持未归档。
