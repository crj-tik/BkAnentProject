# Tasks

## 0. 拆分边界与契约基线

- [ ] 0.1 在共同基线的独立 worktree、Maven 仓库和隔离数据库中实施；开始前重新核对 service ID、数据库 bk_business_review、端口 9015、路径 /business-review/**、Data ID business-review-service.yaml、A2A ID、Memory domain 和 key ID 没有被其它活动 change 占用。
- [ ] 0.2 固定所有权：新增 business-review-service 承接本域实现；逐文件差异检查确认 business-service 的源码、application.yml、business-service.yaml、bk_business、/business/**、Compose 块、trade-agent、Memory 身份与旧 env 键均未修改。
- [ ] 0.3 固定 domain-parallel-v1 房源/知识 HTTP 请求、响应和成功空集、拒绝、版本过期、撤回及不可用 fixture；新 consumer DTO 留在本服务，不引用并行会话新增 Java 类型。
- [ ] 0.4 固定新服务配置身份元组和 Auth/Memory 交接：business-review-service、business-review-agent、business-review、agent.workflow.invoke.business-review、六个 business.review.*.store 操作、唯一公钥 key ID；与协调会话确认共享文件合并责任。

## 1. 数据与指标边界

- [ ] 1.1 定义经营输入、来源引用、dataset 和 dataMode；固定样例覆盖带看、跟进、议价和房源维护，验证不从 employeeId、记录 ID 或模型文本推造事实。
- [ ] 1.2 实现 fixture 数据端口，保留 sourceSystem、sourceId、revision、observedAt、eventTime、dataMode 和 datasetId；验证模拟/真实隔离和来源未知传播。
- [ ] 1.3 定义指标版本、观察窗口、时区、去重单位、分子分母及有效下一步口径；覆盖零分母、缺反馈、迟到事件、跨窗口和未知来源，输出 UNKNOWN 及原因。
- [ ] 1.4 定义 TrustedStoreScopePort；测试已验证 actor/store、请求 storeId 一致性、employeeId 伪造、缺失/过期范围和跨店拒绝。测试替身只证明新服务消费可信范围的行为，不冒充 Auth 生产 STORE 支持。

## 2. 新服务领域状态与来源适配

- [ ] 2.1 在 bk_business_review 建立事项、证据、诊断/计划修订、动作、幂等回执和观察表；新增独立 schema migration/check、A2A 持久授权 migration/check 和 manifest 记录，不迁移 bk_business。
- [ ] 2.2 实现 ListingBusinessReadPort、PublishedKnowledgeReadPort、本地 wire DTO/mapper 和显式 fixture/http 模式；HTTP fixture 覆盖 domain-parallel-v1 版本、空匹配、拒绝、不可用、过期及撤回，无静默回退或空适配器。
- [ ] 2.3 实现带看后停滞和房源维护缺口的事实整理及有界模型解释；覆盖无客户反馈、无价格依据和知识未核验，确保缺口显式且不虚构事实/原因。
- [ ] 2.4 在计划确认和结果读取前重新检查房源/知识/范围版本；覆盖来源撤回、版本过期、授权变化，验证计划待复核、失效正文不可见而既有执行事实保留。

## 3. 推进与观察业务

- [ ] 3.1 实现不可变计划版本、负责人、期限、参数 hash 和确认动作；覆盖并发确认、回执丢失重试、同 requestId 不同 hash 冲突及旧版本批准拒绝。
- [ ] 3.2 实现动作状态与人工/来源回执；覆盖 USER_REPORTED、SOURCE_CONFIRMED 和非法状态迁移，验证 DONE 不自动改变房源、客户或成交事实。
- [ ] 3.3 实现效率与经营阶段观察查询；覆盖 SIMULATED、MIXED、UNKNOWN 与 REAL 的来源继承，不将前后变化解释为因果增长。
- [ ] 3.4 限制 MemoryExperienceOutcome 出口；验证模拟数据、USER_REPORTED、模型自评、Agent 工具受理和单纯 DONE 均不能发布为生产经营有效结果。

## 4. API、Auth、Agent 与 Memory

- [ ] 4.1 实现 /business-review/reviews 下的事项、计划、确认、动作反馈和观察 API；沿用 ApiResponse、有界 cursor 和 /business-review/** route audience，逐路由验证六个精确 business.review.*.store 操作、实际事项 store owner、跨店拒绝及 expected version/hash 语义。
- [ ] 4.2 使用稳定 CapabilityId.a2a(business-review-agent) 到 agent.workflow.invoke.business-review 映射；按 D9 proof 绑定真实 JSON-RPC body、caller/audience、actor、run/task、requestId、nonce 和期限，在任务持久化前本地验签。交付 Auth 六个领域操作和 STORE 签名范围登记片段；未登记时生产调用失败关闭。
- [ ] 4.3 通过 A2aMemoryHookFactory 使用唯一身份 business-review-service / business-review / business-review-agent；在任何模型或业务工具前完成 Memory 读取与授权核验，测试 Memory 不可用、错误公钥、未登记 agent/domain 均无副作用，并验证不读取 trade-agent 历史。
- [ ] 4.4 输出安全领域 artifact 和引用契约；验证 owner、来源、dataMode、授权摘要及门店边界；技能只形成未落库草稿，确认和反馈必须是用户明确触发的领域 API，不加入 MCP 写工具。

## 5. 服务配置与共享文件同步

- [ ] 5.1 新增根 pom.xml 的 business-review-service 模块项；确认通用 Dockerfile 会构建并复制新 jar，仅在发现固定模块清单时追加本模块。
- [ ] 5.2 新建本服务 application.yml 和 nacos/business-review-service.yaml：配置服务名、9015、独立 A2A audience/key ID、bk_business_review datasource、Memory 可选压缩默认关闭和实际 readiness；不复制或改写 business-service.yaml。
- [ ] 5.3 在 docker-compose.yml 只增加本服务块，使用 business-review-service-1.0.0-SNAPSHOT.jar、http://business-review-service:9015、专属只读私钥挂载、数据库与 Nacos 配置；只声明真实依赖，不因旧 business-service 依赖 Redis 就增加 Redis 依赖。
- [ ] 5.4 在 .env.example 增加 MYSQL_DB_BUSINESS_REVIEW=bk_business_review 与 BUSINESS_REVIEW_MEMORY_V2_COMPRESSION_ENABLED=false；若本地忽略的 .env 存在，只补缺失非敏感项、保留既有值和秘密、不提交该文件；公钥从生成的 public-keys.env 注入。
- [ ] 5.5 在 nacos/mysql-common.yaml 增加独立数据库别名；在 nacos/gateway.yaml 增加 /business-review/** 路由和 audience；在 nacos/agent-service.yaml 增加稳定 a2a:business-review-agent capability 映射，保留旧 /business/** 路由与 trade-agent 配置。
- [ ] 5.6 向 nacos/memory-service.yaml 交付独立 trusted caller 片段：business-review-service-current 公钥、service-delivery=true、business-review: [business-review-agent]；向 ServiceIdentityKeygen 服务名单交付新增项并生成 BUSINESS_REVIEW_SERVICE_IDENTITY_PUBLIC_KEY；将新服务加入 RagRuntimeProbe 的 A2A audience 检查，但不加入 Memory RAG domain-agent 投影。由协调会话合并 Memory 全局 caller 和 keygen 注册，不增加 RAG space 或 experience outcome publisher。
- [ ] 5.7 在 sql/mysql-init.sql 新建 bk_business_review；向 docker/mysql/init-manifest.tsv 增加本域 schema 与 A2A task authorization migration/check；更新 scripts/deploy/run-init-manifest.sh 的数据库 allowlist。按最小权限保持 MYSQL_MCP_DATABASES 默认排除 bk_business_review。
- [ ] 5.8 在 scripts/deploy/publish-nacos.py 的精确 ALLOWED Data ID 和 scripts/deploy/init-environment.sh 的 required config 清单增加 business-review-service.yaml；保持 NACOS_CONFIG_DATA_IDS 默认空，并记录部署时显式选择该 Data ID。
- [ ] 5.9 按配置矩阵逐项核对新增、废除和引用：应用模板、.env.example、本地 .env、Nacos 服务配置、Gateway、Agent、Memory、Compose、SQL/manifest、keygen、发布和启动 allowlist；不删除或复用 BUSINESS_MEMORY_V2_COMPRESSION_ENABLED、BUSINESS_MEMORY_EXPERIENCE_OUTCOME_PUBLISHER 或任何 business-service 配置键。

## 6. 独立验收与交付

- [ ] 6.1 仅使用本会话 fixture、Auth/Memory 精确测试登记、TrustedStoreScopePort 测试替身和新服务实际持久处理器，独立完成模拟带看停滞与维护缺口闭环；不依赖其它拆分分支或共享部署。
- [ ] 6.2 在本 change 独立构建 business-review-service 及必要上游模块，运行本域处理器、HTTP consumer、Auth/Memory 拒绝、数据库 manifest 和 Compose/Nacos 配置检查；分别记录模拟证据和未验证的生产互通，不运行 Docker 共享环境操作。
- [ ] 6.3 对根 POM、Compose、Gateway、Nacos、.env.example、keygen、SQL manifest、启动/发布脚本执行共享配置并集审查；确认 business-service 路径级 diff 为空，严格校验 OpenSpec 和 diff。
- [ ] 6.4 在本服务分支使用中文 commit 并 push；提交 head SHA、独立测试记录、配置差异、Auth/Memory 注册交接和未完成的生产 STORE/真实联机门禁；不推 dev-fix、不合并其它会话分支、不关闭 KI-114/128/166。
