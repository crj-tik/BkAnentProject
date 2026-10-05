# Linux 部署初始化可用性与兼容性检查

检查日期：2026-10-05。检查对象：`c849c2c` 新增的 `scripts/deploy/`、初始化指南，以及它们实际依赖的 Compose 和 SQL。基于 `9cfb06b` 工作区完成，未修改部署脚本或业务实现。

结论：配置引导可以使用，但当前部署流程尚不能按文档可靠完成已有库升级和全量业务初始化。原有 Compose/SQL 的缺口也会使新脚本报告成功后，相关功能仍不可用。

## 已通过的检查

| 检查 | 环境与结果 |
| --- | --- |
| Shell 语法 | Ubuntu/WSL 的真实 Bash，7 个脚本全部通过 `bash -n` |
| Linux Git 兼容 | 六个入口脚本 Git mode 为 100755；`.gitattributes` 为 Shell 文件指定 LF |
| 三个初始化档位 | 独立仓库工件副本中，minimal/full/mcp 的 `--skip-docker` 配置初始化全部成功 |
| 重复初始化 | 三个档位逐个重复执行，`.env` 内容字节一致，已有非空值未被覆盖 |
| 密钥引导 | 8 个需补齐的配置项全部非空，`.env` 文件权限为 600 |
| Compose 配置 | minimal 和 full+mcp 均通过 `docker compose config --quiet` |
| 首启 SQL 基础链 | 隔离 MySQL 8.4 按 Compose 六个原始挂载启动成功；两张 Supervisor 表及 cancel_requested 字段存在 |

配置引导测试使用 `--skip-docker`，不代表宿主机 Docker、容器构建和全量服务均已验收。检查在 x86_64 完成，未验证文档声明的 arm64 支持。

## 已确认的问题

| 编号 | 优先级 | 影响及复现 |
| --- | --- | --- |
| KI-37 | P1 | 营销来源和两份 Supervisor 迁移在独立连接中返回 `ERROR 1046: No database selected` |
| KI-38 | P1 | 两份历史 ALTER 迁移即使选定 bk_agent，仍因 `ADD COLUMN IF NOT EXISTS` 在 MySQL 8.4 返回 ERROR 1064 |
| KI-39 | P1 | 全新首启库缺营销 source 字段和访谈导演指令表，不能覆盖现有实体/服务所需结构 |
| KI-40 | P1 | `.env` 中四种 real 集成模式、Nacos 密码和 MinIO 公共地址没有进入应用容器有效配置 |
| KI-41 | P2 | 可达性检查始终连接本机，显示的远端 host 与实际探测目标不同 |
| KI-42 | P2 | 等待逻辑遗漏退出容器；状态检查未正确返回 Compose/HTTP 失败 |
| KI-43 | P2 | minimal 实际启动 Redis，初始化却未检查其端口冲突 |
| KI-44 | P2 | 行尾注释等合法 Compose dotenv 写法被自定义解析器读错，可能导致数据库认证失败 |
| KI-45 | P3 | `--profile` 等选项缺值时直接抛 Bash unbound variable，未返回正常的用法错误 |

### 数据库证据

使用随机凭据、无宿主机端口、tmpfs 数据目录的独立 `mysql:8.4` 容器。没有访问或升级现有验收数据库。按原始首启挂载完成初始化后查询 information_schema：

```text
bk_agent 下 agent_orchestration_run / agent_tool_invocation 表数量：2
agent_orchestration_run.cancel_requested 字段数量：1
bk_marketing.marketing_content.source 字段数量：0
bk_interview.interview_director_command 表数量：0
```

以与迁移脚本相同的无默认数据库 mysql 连接逐份执行：

```text
20260929_marketing_content_source.sql      ERROR 1046
20261004_supervisor_orchestration.sql      ERROR 1046
20261004_supervisor_run_control.sql        ERROR 1046
```

显式选定 bk_agent 后：

```text
20260916_async_runtime_leases.sql          ERROR 1064
20260918_supervisor_stream_events.sql      ERROR 1064
```

因此不能只靠在迁移命令上补一个统一数据库名解决升级问题。迁移需要按文件定位目标库、兼容目标 MySQL 版本，并识别已存在的结构。

### 配置与检查证据

使用只含测试值的独立 env 文件渲染 full+mcp 有效配置。四类集成模式输入均为 real，应用容器环境输出均为 local；Nacos 密码和外部 MinIO 公共地址的匹配结果均为 false。原因是 Compose 的字面值覆盖了文档期待的 env 传入。该配置缺口来自原有 Compose，不属于新 Bash 脚本引入的业务行为变化。

在独立 Linux 环境将七项目标地址设为 `203.0.113.1`，在本机相同端口启动真实监听，可达性脚本仍将七项远端全部报为可达，退出码为 0。占用 minimal 档位的 Redis 发布端口时，初始化也返回 0。

真实的隔离 Compose 项目包含一个 running 容器和一个 exit 42 的一次性容器：默认 `compose ps` 仅列前者，`compose ps --all` 才列两者。根据该行为构造 Docker 输出夹具，直接调用生产 wait_all_healthy，返回“全部 1 个容器就绪”和 0。另一个夹具令 compose ps 返回 1，`status.sh --no-http` 仍返回 0。这里验证的是等待与状态函数；Compose up 自身的依赖失败检查仍然有效。

相同的带引号和行尾注释的测试密码，真实 Compose 解析正确，生产 env_value 读取错误。这会使迁移客户端密码与数据库容器的实际密码不一致。

## 验证范围与处理顺序

先修 KI-37/38 的迁移执行契约，再补齐 KI-39 的新库 schema；随后处理 KI-40 的环境传入及 KI-41/42 的结果可靠性，最后完善端口范围和 dotenv 兼容。

本次没有构建并启动整套 minimal/full Java 服务，没有连接真实模型/业务 Provider，也没有验证 arm64 镜像。不能用脚本语法、Compose 渲染或容器 healthy 替代这些验收。测试创建的独立 MySQL 容器和两个 Compose 夹具容器已删除，原有验收容器保留。

所有确认缺陷的状态与后续修复跟踪以 [已知问题清单](known-issues.md) KI-37 至 KI-45 为准。
