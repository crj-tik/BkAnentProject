# 四域开发环境配置与数据库核对

> 2026-10-29 后续：用户确认没有需保留的正式历史资产。开发库 `bk_promotion` 的三张表逐表核对均为 0 条后已移除；旧 Compare 与 Promotion 容器已删除。Auth 与 Memory 的开发 Nacos 已移除旧 Compare caller，并登记 Knowledge 和 Business Review 的精确服务身份；配置回读通过，运行应用是否加载新配置仍待验证。以下此前保留旧库和回滚配置的说明为操作时的历史记录。

## MySQL

目标为本机 Compose 项目 `bk-anent-dev` 的 `mysql` 服务和原 `bk-anent-dev_mysql-data` 卷。操作前确认容器健康，并对现有 Auth、Listing、Marketing、Promotion 和 Interview 库做逻辑备份；备份保留在本机忽略目录 `.work/2026-10-28/four-domain-closeout/`，文件访问限制为当前用户、SYSTEM 与 Administrators。备份已做压缩流完整读取检查，尚未执行恢复演练。

按版本化 manifest 执行原缺失项 460–530，并在同一目标环境重新核对 460、470、480、490、500、510、520、530 的 schema check，均返回 1；再次执行 upgrade 正常跳过已登记版本。51 项 upgrade 账本摘要与清单一致。Knowledge 的 6 张业务表和来源视图、复盘的 8 张业务表、Marketing 的 13 张 campaign 表均已核对。

已使用本地既有独立凭据配置并解锁 `knowledge_app`、`knowledge_source_ro`；两者分别可读取预期业务表或来源视图，直接读取 Interview 表被拒绝，视图 definer 保持锁定。Knowledge、复盘和营销账户表均使用明确标记的临时测试数据执行写入、读取和事务回滚，测试行未保留。

Auth 新增 12 项 Knowledge 操作、6 项复盘操作与 2 项 Agent 调用能力，角色权限关联数量保持 22，未自动授予角色；权限版本与 outbox 已更新。新 Knowledge 案例、复盘事项和营销渠道账号原本为空；这些业务数据需要经真实业务操作或经过明确标记的演示流程创建。STORE 身份映射和角色分配仍未实现，目录登记不意味着普通用户已有权限。

## 开发 Nacos

在 `BK-AGENT/DEFAULT_GROUP` 新增 `knowledge-service.yaml`、`business-review-service.yaml` 并回读；对 Gateway、Agent、Memory、Listing、Marketing 五份已存在配置仅增补本次缺失的四域条目并回读，保留原有值。修正复盘到房源服务的默认地址为 `listing-master-service:9004`，同步本地 `.env`、应用配置、模板和 Compose。

现有旧 `/compare/**`、`/promotions/**` 网关路由分别调整到 Listing 和 Marketing 的兼容入口，修改前的开发网关配置保留在上述访问受限目录。旧 Compare 与 Promotion 容器此前已停止；当前 Listing 与 Marketing 容器也未运行，因此路由已登记不等于接口已联通，旧路径中尚未迁移的能力也不能视为已完成。

已生成或核对本机 15 组服务身份，未覆盖已有密钥；Knowledge 和 Business Review 的公钥以开发环境回退值登记到 Memory 的 Nacos 配置。运行中的 Memory 容器是在新公钥生成前创建的，其环境变量不包含这两个新公钥；Nacos 发布后未验证应用已动态加载。以后使用仓库模板重新发布 Memory 配置时，需确保运行容器已加载新的 `public-keys.env`，否则这些回退值会被覆盖。

## 仍需完成

- 现有四域应用未全部启动或部署到最终镜像，跨服务真实 HTTP、Auth、Memory 及 Interview 来源链路尚未验收。
- 旧 Compare 源码、可选回滚配置、历史报告卷和旧 Promotion 数据库仍需按 [切换清单](../listing-comparison-cutover.md) 核对消费者、在途任务、历史读取和回滚后再退役。默认 full 环境已不再强制要求两个旧 Nacos 模板。
- Auth 尚未签发可信 STORE 身份；Knowledge 的普通角色、复盘角色和营销渠道账号不能依据空库或角色名自动推定。
