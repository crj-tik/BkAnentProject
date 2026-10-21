# Domain Agent Memory 隐私生命周期验收

**日期**：2026-10-21
**OpenSpec**：`integrate-domain-agent-memory`，`spec-driven`，本模块完成 3.5、4.7；总进度 30/66。
**Git**：分支 `dev-fix`；提交号待最终提交后回填。

## 本次交付

- 恢复的 Supervisor checkpoint 在重新使用模型窗口前重新验证来源授权；撤回来源后，旧 checkpoint 不会继续进入模型。
- Memory v2 Dubbo 合约升级到 `2.2.0`，增加仅返回生命周期元数据的 `memory.privacy-cleanup.status` 操作。
- 撤回/删除立即禁止读取与装配，并保存无正文的 source-hash 清理清单。删除会擦除本地来源、派生正文与回执正文；撤回冻结相应内容但保留可恢复的本地历史。
- 有界 worker 使用租约、attempt fence、退避重试和逐投影清理回执；只在所有已注册 projection cleaner 完成后报告 `COMPLETE`。迟到交付不能复活已撤回或已删除来源。
- 迁移 190 新增 `memory_privacy_deletion`、`memory_privacy_cleanup_target`，可重复应用并通过 schema 检查。

## 验证证据

- 全仓 Maven：1240 项测试，0 失败、0 错误、8 跳过；覆盖全部 23 个 reactor 模块。
- 使用本地 `.env` 配置的现有开发 MySQL：`MemoryArchiveMySqlTest`、`MemoryMatterMySqlTest`、`PreferenceHistoryMySqlTest`、`PreferenceProcessingMySqlTest` 各 1 项通过。fixture 使用随机 context，并清理其 source、job、清理目标和 manifest。
- 迁移脚本 dry-run 校验全部升级清单 checksum；随后迁移 040–190 均报告 `Verified`，190 号表结构检查通过。
- 定向 `SupervisorMemoryIntegrationTest`：26 项通过；新增恢复 checkpoint 撤权后模型调用次数为零的回归覆盖。
- 全量全仓 suite 结果包含上述四个真实 MySQL 集成类；归档与 RPC 定向测试另有 43 项通过（Memory 33、RPC 合约 10）。

## 开发栈部署

- 先前容器引擎不可用；按用户已给出的授权启动 Docker Desktop。应用部署前 `bk-anent-dev` 项目共有 31 个容器。
- 只构建并替换 `agent-service` 与 `memory-service` 两个现有服务，使用 `--no-deps`；部署后项目容器数量仍为 31，没有新建副本容器。
- 两个应用进程均处于运行状态并输出 Spring Boot started 记录。Compose 未为它们配置 Docker healthcheck，因此不把进程运行误报成 healthcheck=healthy。
- 部署前后检查均显示 `AGENT_MEMORY_V2_ENABLED=false`、`AGENT_MEMORY_V2_COMPRESSION_ENABLED=false`、`MEMORY_V2_ARCHIVE_ENABLED=false`、`MEMORY_V2_PREFERENCE_PROCESSING_ENABLED=false`。本次没有启用 V2 归档、偏好加工或索引。

## 运行边界

当前开发配置未注册外部 V2 projection cleaner，V2 索引派发保持关闭。因此本次真实库验收覆盖了无正文清单、状态查询、本地派生内容处理与 worker 租约/完成流程；没有宣称对未接入的外部索引做了联机删除验证。未来启用任何 V2 投影前，必须注册对应的受管幂等 cleaner，并在写入前对账 tombstone。
