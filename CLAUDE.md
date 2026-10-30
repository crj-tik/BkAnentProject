# 仓库协作说明

开始修改前先阅读 [AGENTS.md](AGENTS.md)，按其中的模块结构、编码、测试、临时文件、逻辑清单和提交/推送规则工作。还需先查阅 [docs/logic-rationale.md](docs/logic-rationale.md) 与 [docs/known-issues.md](docs/known-issues.md)。

## 活动模块

当前活动服务包括 Gateway、Auth、Agent、Listing、Customer、Marketing Content、Media Worker、Notification、Interview、Platform Operations、Memory 和 RAG。合同、结算及旧的独立经营服务已退役；旧 OpenSpec 和验收文件仅供历史追溯，不作为当前运行指引。

平台经营域由 `platform-operations-service` 提供一个 A2A Agent 和 `/platform-operations/**` API，覆盖经营分析、门店经营复盘、知识运营。Agent 依据签名授权上下文中的精确 `platformCapability` 路由到 analytics、review 或 knowledge，并分别使用工具、权限及 Memory 身份。门店范围只能来自 Auth 签发的可信用户—门店关系；Knowledge 来源必须通过 Interview 限权视图读取。

房源比较属于 Listing 域。营销、媒体、通知和访谈仍各自归属当前服务。平台经营功能、接口及前端缺口见 [功能清单](docs/platform-operations-frontend-function-list.md)，服务职责见 [当前架构](docs/current-architecture.md)。

## 构建与验证

```bash
mvn -gs .mvn-settings.xml -s .mvn-settings.xml compile
mvn -gs .mvn-settings.xml -s .mvn-settings.xml test
```

单模块构建示例：

```bash
mvn -pl platform-operations-service -am test
```

数据库初始化与升级统一使用受检 manifest：`docker/mysql/init-manifest.tsv`。不要单独运行未纳入受管清单的 SQL。

## 配置

- `application.yml` 保留本地启动、Nacos 导入和环境连接边界；分布式业务运行值维护在 `nacos/*.yaml`。
- `.env` 只保存本地连接、密钥和启动参数；真实秘密不得提交。
- Nacos 发布只允许显式选择受管 Data ID；模板更新后核对目标 namespace 的发布回读和服务 readiness。
- 本机服务身份私钥放在 Git 忽略的 `.secrets/service-identities/`，只发布公钥。

## OpenSpec

实施或继续变更时按仓库安装的 `openspec-apply-change` 指引读取 change 上下文、更新任务状态并完成验证。已应用迁移不可重写，变更通过新的版本化迁移前向实施。
