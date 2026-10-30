# 四域后端检查记录

日期：2026-10-28；代码基线：`39136591`（`dev-fix`）。本次针对当时合并后的代码进行本机测试，并整理前端输入；当前页面与 API 清单见[平台经营域前端功能清单](../platform-operations-frontend-function-list.md)。

## 执行结果

| 检查 | 结果与范围 |
| --- | --- |
| 四域及上游 Maven 测试 | `mvn -gs .mvn-settings.xml -s .mvn-settings.xml -pl listing-master-service,marketing-content-service,knowledge-service,business-review-service -am test`：10 个 reactor 模块成功。四域分别运行 84、37、31、26 项，共 178 项，失败、错误和跳过均为 0；其中使用 H2 内存数据库和测试替身，不代表真实依赖已联通。 |
| 旧经营服务现有测试 | `mvn -gs .mvn-settings.xml -s .mvn-settings.xml -pl business-service -am test`：7 个 reactor 模块成功；business-service 现有 4 项通过。其覆盖不足以认定门店复盘 task 7.2 的旧业务全部回归已完成。 |
| 初始化清单静态检查 | `python scripts/deploy/test_init_manifest.py`：53 项 manifest 目标文件及规范 SHA-256 检查通过。 |
| 知识服务配置静态检查 | `python scripts/deploy/test_knowledge_config.py`：环境变量、Compose、Nacos、身份及来源视图门禁的静态检查通过。 |

测试报告位于各模块的 `target/surefire-reports/`，上述数量按本次运行生成的报告统计。H2 测试覆盖部分持久化和业务路径，但不等同于目标 MySQL 的迁移、权限、视图与隔离验证；静态清单检查不执行 SQL。

## 尚待完成的验收

- 房源：隔离 MySQL 上执行 450/460 迁移及 check；补齐房源与比较入口矩阵、调用迁移、历史/分享/PDF 和退役门禁，以及目标环境配置核对。见 [房源任务](../../openspec/changes/consolidate-listing-comparison-domain/tasks.md)。
- 知识：在独立环境完成来源、身份、Memory、生命周期闭环及 MySQL schema/view/grant 验收。KI-218 仍开放；本次合并后测试不能替代 task 5.1 指定的独立 worktree 验收，也不能证明 5.2–5.3 全部运行态条件。
- 门店复盘：完成旧经营服务全入口回归、Supervisor 确认审批，以及真实 STORE/Auth/Memory 接线。当前测试不证明真实门店授权可用。
- 营销：完成 Auth v2 各入口及真实身份关系的接入和验收；真实渠道、回执与效果依赖目标环境，不能把模拟渠道结果当作真实发布。
- 四域之间的真实 HTTP/RPC、Gateway、Nacos、Auth、Memory、Interview 来源及外部服务端到端联调尚未在本次执行。未对目标数据库执行迁移或变更共享环境。
