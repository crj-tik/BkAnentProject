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

1. **工件完整性**：确认 `docker-compose.yml`、`Dockerfile`、`sql/mysql-init.sql`、`docker/mysql/004-supervisor.sql`、`nacos/` 就位。
2. **Docker/Compose 校验**（`--skip-docker` 可跳过，用于在无 docker 的中转机上预生成配置）。
3. **内核参数**：`vm.max_map_count` 不达标时给出修复命令；加 `--fix-kernel` 则通过 sudo 修正并持久化到 `/etc/sysctl.d/99-bkagent.conf`。
4. **资源预检**：内存与磁盘只告警不阻断。
5. **`.env` 引导**：不存在则从 `.env.example` 复制；随后仅为**空缺**的密钥项生成随机值（`MYSQL_ROOT_PASSWORD`、`AUTH_TOKEN_SECRET`、MinIO 内外部凭据、MySQL MCP 密码与 Token），**绝不覆盖已有非空值**；文件权限设为 600。`--bind 0.0.0.0` 可把 `HOST_BIND_ADDRESS` 改为对外绑定。
6. **端口冲突检查**：按档位检查宿主机端口（本环境容器已在运行时跳过）。MySQL 默认不在宿主机开 3306，需要时用 `docker-compose.mysql-client.yml` 覆盖。
7. **Nacos 配置完整性**：确认档位所需的 `nacos/*.yaml` 齐全，缺文件会导致 `config-init` 阶段失败。

## 数据卷：全新 vs 已有

- **全新数据卷**：MySQL 首次初始化自动执行 `docker-entrypoint-initdb.d` 中的 `sql/mysql-init.sql`、两份 interview 脚本和 `docker/mysql/004-supervisor.sql`（后者内部 `SOURCE` 两份 supervisor 迁移），无需手工迁移。
- **已有数据卷**：初始化不会重跑，须先应用增量迁移：

```bash
./scripts/deploy/apply-migrations.sh            # 全部，按文件名顺序，逐个确认
./scripts/deploy/apply-migrations.sh --dry-run  # 只看清单
./scripts/deploy/apply-migrations.sh --file sql/migrations/20261004_supervisor_run_control.sql --yes
```

迁移文件不保证幂等（部分是不带 `IF NOT EXISTS` 的 `ALTER TABLE`）：重复应用会报重复列/表错误，这通常说明该文件已应用过，跳过即可。与 [Supervisor 部署说明](supervisor-orchestration-deployment.md)的口径一致：已有卷的迁移是运维动作，不依赖容器首启。

## 启动 / 状态 / 停止

```bash
./scripts/deploy/start.sh --profile minimal            # 最小链路：MySQL+Nacos+配置导入+认证+网关
./scripts/deploy/start.sh --profile full --with-mcp    # 全量 + MySQL MCP；首次启动含容器内 Maven 构建，耗时较长
./scripts/deploy/status.sh                             # 容器状态 + HTTP 健康探测
./scripts/deploy/stop.sh                               # 停止并保留容器与数据卷
./scripts/deploy/stop.sh --down                        # 连容器一起删除（数据卷保留）
./scripts/deploy/stop.sh --down-volumes --yes          # 连数据卷一起销毁（必须显式 --yes）
```

`start.sh` 会等待全部容器健康（带健康检查的容器到 `healthy`，`config-init`/`minio-init` 等一次性任务退出码 0 视为完成），超时默认 600 秒（`--wait-timeout` 可调）。验证入口与 README「Docker 开发部署」一致：网关 `http://127.0.0.1:5010/gateway/health`，认证 `http://127.0.0.1:9101/auth/health`，Nacos 控制台 `http://127.0.0.1:18080/`。

## 裸机分布式启动

不使用 Docker 直接 `mvn spring-boot:run` 启动分布式 profile 前，运行基础设施可达性检查（对应 `check-environment.ps1 -Mode distributed` 的 Linux 版）：

```bash
./scripts/deploy/check-environment.sh   # 从 .env 读取 Nacos/MySQL/Redis/RocketMQ/Milvus/MinIO/ES 地址并探测端口
```

## 安全基线

- `.env` 含真实密钥：权限 600、已被 `.gitignore` 与 `.dockerignore` 排除，禁止提交或打进镜像。
- Compose 默认只绑定 `127.0.0.1`；`--bind 0.0.0.0` 对外开放前确认防火墙/安全组，并同步复查 `A2A_PUBLIC_BASE_URL`、`MINIO_PUBLIC_BASE_URL`（不能用对端不可达的 loopback 地址）。
- 真实模型调用必须替换占位密钥：`DEEPSEEK_API_KEY`、`DASHSCOPE_API_KEY`、`AGENT_RAG_RERANK_API_KEY`（compose 中的占位值只保证容器可启动）。
- 生产/分布式集成模式必须显式设为 `real`：`CONTRACT_INTEGRATION_MODE`、`NOTIFICATION_INTEGRATION_MODE`、`MEDIA_INTEGRATION_MODE`、`PROMOTION_INTEGRATION_MODE`（见 README 的模拟层说明）。
- 旧库 `user_account.password_hash` 为明文的，先按 README 完成 BCrypt 迁移再开放认证。
- 初始化随机生成的 `MYSQL_MCP_PASSWORD`/`MYSQL_MCP_TOKEN` 已就位，启用 MCP 只读服务：`docker compose --profile mcp run --rm mysql-mcp-init` 后 `./scripts/deploy/start.sh --profile mcp`。

## 常见问题

- **Elasticsearch/Milvus 起不来**：`sysctl -n vm.max_map_count` 低于 262144；按上文修复或用 `--fix-kernel`。
- **端口冲突**：释放端口或改 `.env` 中对应 `*_EXPOSED_PORT`；容器已运行时 init 会自动跳过该检查。
- **full 首次启动很慢**：镜像构建在容器内执行 Maven 打包；重复启动加 `--no-build`。
- **config-init 上传失败**：Nacos 未就绪时任务自带重试；配置文件缺失属初始化校验范围，init 阶段即会报错。
- **内存不足**：降级用 `minimal` 档位，或拆分基础设施到独立主机（此时 `.env` 的 `*_HOST` 指向对应地址）。

## 关联

- [Supervisor 工具循环部署](supervisor-orchestration-deployment.md)：九服务 + Supervisor 的发布顺序与运行面约束
- [README「分布式环境启动」](../README.md)：配置约定、集成模式与 `.env` 变量清单
