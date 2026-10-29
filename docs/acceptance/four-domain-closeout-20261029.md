# 四域后端收尾验收（2026-10-29）

代码提交：`95c83c9`（`dev-fix`）。

## 收尾范围

- 删除独立 `compare-engine-service` Maven 模块、源码、维护测试和公共 RPC/DTO；Compose、根 Maven 清单、活动 Nacos 服务模板及活动 RAG 空间不再注册此服务。
- 比较报告与 PDF 留在 Listing 域。保留旧 `/compare/listings` 和 `/compare/reports` 兼容入口，但继续执行已签名身份、报告 owner 与当前来源版本校验；匿名分享已退役。
- 保留归档 OpenSpec、旧迁移与审计记录作为历史依据，不将其当作活动服务配置。

## 配置与部署工件

- 应用 YAML 与 16 份活动 Nacos 服务模板完成 key/prefix 核验，缺失项均为 0。已同步 Agent 编排、Memory 行为、RAG 并发、Auth 撤权前缀、Media→Listing 签名密钥、Gateway 请求体上限及当前服务身份配置。
- Compose 使用到的 183 个环境占位符均可在 `.env.example` 找到；模板共定义 210 个键。`docker compose config -q` 通过。
- `scripts/deploy/test_knowledge_config.py` 通过。此次提交更新 Nacos 仓库模板，不代表新值已发布至运行态 Nacos；生产 Nacos 未修改。

## 开发数据库

受管迁移 540 已应用于获准的开发 MySQL。迁移逐表确认 `bk_promotion` 为空后移除该旧 schema，并停用公开分享权限、清除角色关联及记录迁移账本。再次核验结果：退役 SQL check 返回 `1`；旧 schema `0`、公开分享活动行 `0`、停用行 `1`、角色关联 `0`、迁移标记 `1`。SQL manifest 有 55 个目标，规范 hash 与迁移记录核验通过。

Auth 基础记录为：账号 9、角色 3、权限 167（活动 140）、角色权限关联 22、用户角色关联 9。主要业务表当前记录为：Listing 4、Customer profile 0、Marketing campaign account/content 0、Knowledge case 0、Business review matter 0、Contract template 1、Settlement rule 2。项目尚无正式上线历史，零业务记录作为空状态保留，没有补造业务样例。

## 后端回归

- `mvn -gs .mvn-settings.xml -s .mvn-settings.xml test`：23 个模块均成功，1,853 项测试，0 失败、0 错误、14 项按条件跳过。
- `python scripts/deploy/test_init_manifest.py`：55 个目标与规范 SHA-256 核验通过。
- `python scripts/deploy/test_knowledge_config.py`：通过。
- `docker compose config -q`：通过。
- `git diff --check`：通过。

## 前端交付基线

页面与接口能力、当前缺口、鉴权和数据状态提示见 [四域前端功能清单](../four-domain-frontend-function-list.md)。Listing 比较切换与旧入口限制见 [Listing 比较切换清单](../listing-comparison-cutover.md)。
