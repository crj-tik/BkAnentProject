# Linux 部署环境初始化

本仓库的编排（`docker-compose.yml`）本身跨平台，但此前的构建、预检与启动脚本均为 PowerShell（`scripts/local/`、`scripts/start-mysql-mcp.ps1`），Linux 主机上缺少一套部署环境初始化过程。`scripts/deploy/` 下的脚本补齐这一层：宿主机预检 → `.env` 密钥引导 → 数据卷迁移 → 启动/状态/停止。全部脚本只依赖 bash 与 Docker，不依赖 PowerShell。

## 前置条件

- Linux x86_64（或 arm64），bash 4+；建议 Ubuntu 22.04 / Rocky Linux 9 或同代发行版
- Docker Engine >= 24 且含 Compose V2 插件（`docker compose version` 可用；不支持 docker-compose v1）
- 资源建议：`minimal` 档位 >= 4GB 内存；`full` 档位 >= 16GB 内存、>= 30GB 可用磁盘（ES/Milvus/九个业务服务同机）
- 内核参数：`full` 档位包含 Elasticsearch 与 Milvus，宿主机必须满足 `vm.max_map_count >= 262144`

## 初始化过程

```bash
./scripts/deploy/init-environment.sh --profile full
```

该脚本依次执行：

1. **工件完整性**：确认 `docker-compose.yml`、`Dockerfile`、`docker/mysql/001-manifest.sh`、`docker/mysql/init-manifest.tsv`、对应 SQL/schema checks 和 `nacos/` 就位。版本清单由共享 runner 验证，不扫描历史迁移目录。
2. **Docker/Compose 校验**（`--skip-docker` 可跳过，用于在无 docker 的中转机上预生成配置）。
3. **内核参数**：`vm.max_map_count` 不达标时给出修复命令；加 `--fix-kernel` 则通过 sudo 修正并持久化到 `/etc/sysctl.d/99-bkagent.conf`。
4. **资源预检**：内存与磁盘只告警不阻断。
5. **`.env` 引导**：不存在则从 `.env.example` 复制；随后仅为**空缺**的密钥项生成随机值（`MYSQL_ROOT_PASSWORD`、`AUTH_TOKEN_SECRET`、MinIO 内外部凭据、MySQL MCP 密码与 Token），**绝不覆盖已有非空值**；文件权限设为 600。`--bind 0.0.0.0` 可把 `HOST_BIND_ADDRESS` 改为对外绑定。
6. **端口冲突检查**：按档位检查宿主机端口（本环境容器已在运行时跳过）。MySQL 默认不在宿主机开 3306，需要时用 `docker-compose.mysql-client.yml` 覆盖。
7. **Nacos 配置完整性**：确认档位所需的 `nacos/*.yaml` 齐全，缺文件会导致 `config-init` 阶段失败。

## RAG 迁移部署与发布门禁

按本项目现有 Dockerfile 模块构建约定执行：`bk-anent-rag:dev` 使用 `MAVEN_MODULES=rag-service` 与 `SERVICE_ARTIFACT=rag-service-1.0.0-SNAPSHOT.jar`。这是沿用开发固定 tag 的规则，不是不可变发布版本；切流前须另行保存实际镜像 ID/digest 与配置版本，禁止套用外部 reranker 项目的镜像规则。

- RAG 是显式 `rag` profile，`start.sh --profile full --with-rag` 才加入；不默认为 full 启动，避免尚未建立的 RAG 控制 DB 阻断结构化业务。RAG 依赖 config-init/MySQL，消费者不依赖 RAG 的 `service_healthy`，agent 不再依赖 Milvus、listing 不再依赖 ES 初始化/readiness；营销保留自身 ES。
- HTTP 管理端口 9018 仅绑定 loopback，Dubbo 20898 仅容器网络发现，QoS 关闭；实施前未发现仓库旧声明冲突，但仍需部署时核对实际宿主/容器占用。本轮无搜索公网发布。
- 控制 DB 为 `MYSQL_DB_RAG` 默认 `bk_rag`，使用独立 `RAG_MYSQL_USER`/`RAG_MYSQL_PASSWORD`。`ragBaseReadiness` 检查安全配置、`rag_index_event`/`rag_document_state` 两表查询权限和至少三个已导出 RPC provider，不直接探测 Nacos 或检索后端；通过仍不证明真实注册发现、空间能力或数据水位。先审查并受权应用 RAG 迁移/权限，不能凭数据库 TCP/healthy 或两表查询证明完整表结构和敏感 payload 权限已满足。既有卷的全量迁移入口仍有 KI-37/38，首启全量结构仍有 KI-39；不要为本次部署顺手修全部历史 SQL。
- 根 `.env` 现有秘密不盲删、不打印，当前实施使用仓库外 ACL 受限 runtime.env；Compose RAG 不继承全量环境锚点。只有 RAG 获得 `RAG_MILVUS_*`、`RAG_EMBEDDING_*`、`RAG_RERANK_*`、`RAG_ELASTICSEARCH_URIS` 及三项 HTTP 策略配置。营销单独使用 `MARKETING_ELASTICSEARCH_URIS`，chat 的 `DEEPSEEK_*`/`KE_API_KEY` 保留原业务归属。旧 `AGENT_RAG_RERANK_*`/`MILVUS_*` 不会自动变成新 RAG 配置。
- 签名配置分协议管理：RAG RPC 继续使用 `RAG_AUTH_RPC_SECRET`、`LISTING_RAG_RPC_SECRET`、`MEMORY_RAG_RPC_SECRET`、`AGENT_RAG_RPC_SECRET` 与 `AUTH_RAG_RPC_RESPONSE_*`；A2A/Auth 与 Memory v2 使用 `.secrets/service-identities/` 下每服务 Ed25519 身份，Auth 响应 key ID 由 `AUTH_SERVICE_IDENTITY_KEY_ID` 指定。Memory v2 proof 只接受 Ed25519，直接复用调用服务的身份私钥，不配置 Memory 请求共享 secret；Auth/A2A 旧调用端迁移独立管理。RAG RPC 凭据继续按其自身协议配置。应用空角色默认拒绝，选定开发 Nacos 模板明确 ADMIN/BROKER（MANAGER不准入）；ADMIN业务全读不扩大个人 owner-only。agent未知通用知识保持关闭，dispatcher/worker在真实门禁通过前关闭。INSPECT/PROVISION/REPLAY 要求 USER 真实凭据、签名与空间 adminRoles，SERVICE writer不能搜索/读取或管理。
- `nacos/rag-service.yaml` 三个候选空间用 bracket map key 保留点号：`listing.knowledge`、`memory.preference`、`memory.constraint`；物理名字和 1024 维都是待核对模板，无真实 provision、旧身份映射、过滤等价或 ready 证据。未知通用知识空间不注册，重排 scoring profile 缺失时能力关闭。

### 显式配置发布（仅授权后执行）

`config-init` 不再扫描全目录发布 public：必须主动设置 `NACOS_CONFIG_DATA_IDS` 为**本次获授权的空格分隔 YAML data ID 文件名列表**，使用实际 `NACOS_NAMESPACE` 的 namespace ID 作 `tenant`，group 固定 `DEFAULT_GROUP` 与应用 import 一致。未选择 data ID 时退出 64，首次开发启动同样要求主动选择；仅调端口或配置文件存在不等于已发布。`mysql-common.yaml` 无人 import（KI-50），不应作为 RAG 共享配置来源。

更新只允许所选 data ID 与受影响服务；覆盖前保留受限配置导出、版本校验和和回滚快照。本轮 BK-AGENT/DEFAULT_GROUP 的 auth/rag/listing/memory/agent/customer/marketing 七模板已使用管理认证发布，并在十一域 A2A key/env扩展后再次逐项读回 SHA-256一致；原Nacos未重建。模板发布不是应用实际读取。当前应用镜像、启动、默认检索及切流仍待验证；不进行全目录覆盖或公网发布。

### 真实切流阻塞与验收

以下项目尚未满足，因此 tasks 8.2/8.3/8.5/8.8 不得标完成：

1. BK-AGENT/DEFAULT_GROUP七模板最终认证读回已有实证，原配置/镜像快照已保留；应用实际namespace/dataID读取、consumer/RPC发现及新镜像digest仍待运行验证。memory明确同namespace Dubbo接线，listing有独立caller key，但模板及类路径不等于真实鉴权通过；memory实际库名仍核对运行态。
2. HTTP策略仅 `rag.http.mode/allowed-hosts/internal-networks`：生产 PUBLIC_ONLY 和 MANAGED_INTERNAL 均要求非空精确host集合。公开公司host走公开地址分支；内网 ES 及宿主重排使用显式MANAGED_INTERNAL与各host自己的RFC1918/ULA CIDR，不能共享跨host授权。Docker项目网172.24.0.0/16、host.docker.internal容器192.168.65.254/32与Windows10.117.102.54/32分别核对；模式开关不豁免localhost/环回/链接本地/mapped/组播/保留地址、全部DNS答案、地址绑定、TLS或proxy/redirect禁令。Milvus gRPC不属于HTTP守卫。当前镜像的java.naming/jdk.naming.dns及实际受保护模型调用仍需运行验证。
3. 真实 owner/权限真源、集合/ES schema、旧文档 ID/PK、模型实测维度、完整投影、两路过滤等价、更新/删除/partial 写补偿、默认双路+重排能力均未验收。单路、stub、TCP/running 和基础 readiness 均不能替代。
4. 领域 outbox/backfill/持久删除水位、旧 writer 和在途 RPC 排空、增量追平、受权短暂停写与消费者切流未执行；未过安全/身份/数据/能力 gate 不能恢复写入口。
5. 仓库最小SDK patch `0.2.5.Beta2-bk-transport1` 的官方A2AClient/JdkA2AHttpClient两源构造器已编译，专用transport禁止redirect；当前同步/流式/查询/取消/resubscribe和任务恢复真实联调仍待验。不得把token/beacon塞入metadata、工具参数或checkpoint，不反射/改缓存/增加第三JSON-RPC。
6. 全量源码搜索已确认无消费者旧Agent索引RPC与signed listing reconcile/证明链并直接删除，不再要求虚构RPC兼容周期；正常REST/工具、其他Agent四方法及outbox/backfill/worker物理补偿保留。按版本/config/事件水位恢复新兼容版本或必要时退旧前重放差异仍待演练，不能只改路由。所有旧索引、集合和数据卷保留。

KI-42 的等待/状态成功误报、KI-48 的 Redis readiness 与真实客户端不一致继续列门禁，不借此关闭无关依赖或声称全量环境验证完成。真实验收须由实施者在获授权且合规目标具备后执行：携带真实 USER 管理凭据调用 common 的 `RagSpaceAdminRpcService.inspect/provision`，通过领域检索入口发送缺省双路/重排请求，并核对 requestedMode/effectiveMode、scoreKind/scoringVersion；按相同 eventId 调用 `RagIndexRpcService.getStatus` 核对 APPLIED 及更新/删除补偿。没有额外 RAG 公网管理 HTTP 端点。诊断脚本为 `./scripts/deploy/status.sh`，但其退出值不能替代上述能力验收。授权反例、水位追平及恢复演练逐项留证，失败或未执行保留非零/阻塞事实。

## 数据卷：全新 vs 已有

- **全新数据卷**：Compose 挂载 `docker/mysql/001-manifest.sh`，通过同一 runner 按顺序执行清单内全部 `fresh` 与 `upgrade` 项；fresh-only 种子只在首次初始化执行。每项都绑定目标数据库、SQL/schema check 的规范 LF SHA-256 与 migration history。
- **已有数据卷**：入口只执行清单中的 `upgrade` 项，不重放 fresh 种子，不枚举历史 SQL，也不支持任意 `--file`。截至 2026-10-27，清单有 44 项；新增版本只能追加，不能静默改已应用 SQL/check：

```bash
./scripts/deploy/apply-migrations.sh --dry-run
./scripts/deploy/apply-migrations.sh --container bk-anent-dev-mysql-1
```

先验证完整清单再执行 DDL；已应用版本的 checksum 漂移必须拒绝，不能把报错当成功或直接跳过。历史 MySQL 8.4.11 首启/升级/重复实测仅覆盖当时清单中的版本，不覆盖 2026-10-26 新增的授权迁移。授权迁移与回滚顺序见[版本化鉴权发布与回滚](authz-versioned-rollout-rollback.md)。KI-37/38/39涉及的其他历史初始化债务不因此全部关闭。

## 启动 / 状态 / 停止

```bash
./scripts/deploy/start.sh --profile minimal            # 最小链路：MySQL+Nacos+配置导入+认证+网关
./scripts/deploy/start.sh --profile full --with-mcp    # 全量 + MySQL MCP；首次启动含容器内 Maven 构建，耗时较长
./scripts/deploy/status.sh                             # 容器状态 + HTTP 健康探测
./scripts/deploy/stop.sh                               # 停止并保留容器与数据卷
./scripts/deploy/stop.sh --down                        # 连容器一起删除（数据卷保留）
# 本次迁移保留数据卷，不执行 --down-volumes、prune 或 drop。
```

`start.sh` 超时默认600秒（`--wait-timeout`可调），核对选中预期实例、healthcheck和一次性任务退出状态；`--no-wait`仅提交，不证明就绪。`--service NAME`可限定目标，`--no-deps`要求显式目标；`--with-rag`不能被服务子集遗漏。`status.sh --service mysql --service nacos --no-http`可限定容器门禁，`--with-rag`仍要求rag实例。当前实测缺rag/停止auth非零，错误host超时非零，mysql+nacos正常为0；不能以该退出值或TCP证明默认检索、配置或模型能力。网关/认证/Nacos入口保留；RAG9018只绑定loopback。

## 裸机分布式启动

不使用 Docker 直接 `mvn spring-boot:run` 启动分布式 profile 前，运行基础设施可达性检查（对应 `check-environment.ps1 -Mode distributed` 的 Linux 版）：

```bash
./scripts/deploy/check-environment.sh   # 从 .env 读取 Nacos/MySQL/Redis/RocketMQ/Milvus/MinIO/ES 地址并探测端口
```

## 安全基线

- `.env` 含真实密钥：权限 600、已被 `.gitignore` 与 `.dockerignore` 排除，禁止提交或打进镜像。
- Compose 默认只绑定 `127.0.0.1`；`--bind 0.0.0.0` 对外开放前确认防火墙/安全组，并同步复查 `A2A_PUBLIC_BASE_URL`、`MINIO_PUBLIC_BASE_URL`（不能用对端不可达的 loopback 地址）。
- 真实模型调用前须核验 `DEEPSEEK_API_KEY`（chat）、`RAG_EMBEDDING_API_KEY`（embedding）和 `RAG_RERANK_API_KEY`（独立 RAG 重排）；占位值不证明模型能力。旧 `AGENT_RAG_RERANK_API_KEY` 不再有应用消费者，已有 `.env` 值无需盲删，也不会自动注入新变量；不得擅用其他 reranker 项目的凭据。
- `.env` 只放端口、基础设施连接、密钥及启动引导参数；集成模式、Token TTL、模型参数、Redis 排行榜、流式 provider 和 Milvus/ES 搜索开关均在对应 Nacos data ID 中维护，不再放入 `.env`。
- 分布式生产部署必须在 Nacos 将 `contract.integration.mode`、`notification.integration.mode`、`media.integration.mode`、`promotion.integration.mode` 显式设为 `real`，并接入/选择真实 provider（合同同时配置真实 OCR/电子签 provider）；仅改 `real` 而没有真实实现仍会报告未实现，不会伪造成功。仓库 YAML 默认保持 Docker 开发模式 `local`，导入前准备环境专属配置；`config-init` 只发布显式选择的 data ID 到实际 namespace，默认不发布；它仍会覆盖所选配置，不能用开发模板覆盖生产设置。依赖 readiness 保持不变。
- Compose 已允许 `.env` 中的 `NACOS_USERNAME`、`NACOS_PASSWORD`、`MINIO_PUBLIC_BASE_URL` 覆盖应用容器取值。Nacos Admin 账号初始化及目标命名空间 Card 更新权限须另行配置，设置或生成密码并不代表已有授权（LR-28）。
- 旧库 `user_account.password_hash` 为明文的，先按 README 完成 BCrypt 迁移再开放认证。
- 初始化随机生成的 `MYSQL_MCP_PASSWORD`/`MYSQL_MCP_TOKEN` 已就位，启用 MCP 只读服务：`docker compose --profile mcp run --rm mysql-mcp-init` 后 `./scripts/deploy/start.sh --profile mcp`。

## 常见问题

- **Elasticsearch/Milvus 起不来**：`sysctl -n vm.max_map_count` 低于 262144；按上文修复或用 `--fix-kernel`。
- **端口冲突**：释放端口或改 `.env` 中对应 `*_EXPOSED_PORT`；容器已运行时 init 会自动跳过该检查。
- **full 首次启动很慢**：镜像构建在容器内执行 Maven 打包；重复启动加 `--no-build`。
- **config-init 上传失败**：Nacos 未就绪时任务自带重试；配置文件缺失属初始化校验范围，init 阶段即会报错。
- **内存不足**：降级用 `minimal` 档位，或拆分基础设施到独立主机。裸机工具仍从 `.env` 的基础设施地址读取；Compose 容器内默认使用服务名连接，外置基础设施需显式调整容器连接配置，不能仅改宿主机 `*_HOST` 就认为已生效。

## 关联

- [Supervisor 工具循环部署](supervisor-orchestration-deployment.md)：九服务 + Supervisor 的发布顺序与运行面约束
- [README「分布式环境启动」](../README.md)：配置约定、集成模式与 `.env` 变量清单
