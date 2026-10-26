# 营销推广合并独立验收记录

## 会话与基线

- OpenSpec change：`consolidate-marketing-promotion-domain`
- 共同基线：`6d13117169fb6eeb3c15165b43a08cc08b142889`
- 分支：`codex/consolidate-marketing-promotion-domain`
- Worktree：`C:\Users\User\.codex\worktrees\marketing-promotion-domain\BkAnentProject`
- Maven 本地仓库：本 worktree 的 `.m2repo/`（由根 `.gitignore` 忽略，构建使用 `-Dmaven.repo.local=.m2repo`）
- 基线核对：worktree 从上述 SHA 创建；基线提交图没有房源、知识或经营 change 的实现提交。本会话只在自己的 worktree/分支构建，不使用其他 worktree 的 target/JAR，也不操作共享运行栈。

## 负责范围与隔离资源

- 负责：`marketing-content-service`、推广能力迁入及退役 `promotion-service`、`common/marketing/campaign/**`，以及营销自身所需的最小网关/部署/配置和 SQL manifest 片段。
- 编号：沿用 `LR-102`、维护 `KI-167`；本 change 保留 `LR-130–137` / `KI-194–201`，不挪用其它领域编号。
- 不负责：Auth 实现、房源/知识/经营 change、Studio 工作/命令/审批存储、媒体 Worker 或通知服务的迁移。
- 测试资源：服务逻辑使用本进程测试；持久化和迁移验证使用本地隔离 H2（MySQL mode）；跨域 HTTP 契约使用仅绑定 loopback 随机端口的本地 fixture；平台、媒体、通知及 Auth/Memory 依赖使用测试配置内显式 fixture/mock。真实模式必须独立验证缺依赖时失败关闭。不会启动或修改 Docker、共享数据库、Nacos 或共享服务。

本记录随后补充已实现的 fixture、执行命令、结果、部署回退说明及共享文件差异；规划的测试资源在相应测试完成前不视为已验收。

## 旧数据与切换清单

| 来源 | 旧表 | 保留/映射 | 切换前核对 |
| --- | --- | --- | --- |
| `bk_marketing` | `marketing_content` | 原内容 ID 保留为来源引用；campaign 修订以不可变内容 hash/version 识别 | 内容数、ID 唯一性、已绑定媒体 URL、内容/审核/发布摘要 |
| `bk_promotion` | `promotion_publish_record` | 新尝试分配营销域自己的 ID；按 `(sourceSystem, entityType, legacyId)` 写唯一映射；保留平台账号、旧状态、外部发布 ID、时间、成本及操作者 | 按状态盘点；`PENDING`/`REVIEWING` 与未知状态先隔离核对，`SUCCESS`/`TAKEN_DOWN` 不重放 |
| `bk_promotion` | `promotion_effect_stat` | 原始计数、比例和 ROI 原样留档，历史来源性质记 `UNKNOWN`；不能并入 REAL 指标 | 按发布记录/日期找孤儿及重复项；核对 KI-167 的样例算法来源 |
| `bk_promotion` | `brand_asset` | 新素材 ID 与旧 ID 通过来源映射关联；保留 URL、标签、平台范围、状态和备注 | 数量、ID 冲突、引用和 URL 可用性 |

映射键按实体类型隔离；目标表使用新生成 ID，不把两个数据库的自增 ID 直接合并。未知/在途尝试沿用旧 `legacyId` 与 `externalPublishId` 做人工对账，迁移不得创建可自动派发的新 outbox 任务。共享库行数、导出哈希和实际冲突尚未读取；协调迁移前须用 `sql/checks/marketing-promotion-inventory.sql` 生成快照，并将两库 `mysqldump --single-transaction` 的文件名、行数和 SHA-256 写入迁移记录。旧 `bk_promotion`、其初始化 DDL 和回滚制品在核对完成前保留。本会话没有访问或改写共享数据库。

### 切换前只读盘点

运行环境：由协调迁移人员在获准的隔离/维护终端执行；工作目录为仓库根目录。先用只读 SQL 核对计数、状态、孤儿和重复；通过后再按项目既有安全流程备份两个数据库，**不要在该步骤停服务、写库或切换路由**。输出中含渠道账号标识，按现有数据访问规则保管。

## 初步调用方与退役边界

- `nacos/gateway.yaml` 的 `/promotions/**` 当前路由到 `promotion-service`；迁移后同路径只路由至 `marketing-content-service` 的授权适配器。
- `marketing-content-service` 的 `MarketingMcpTools`、`MarketingRpcReferenceConfiguration` 与 `PromotionRpcService` 消费旧发布 RPC；迁移后撤掉营销运行时消费者，业务发布由同域 campaign handler 受控执行。
- `promotion-service` 提供旧 REST controller、`PromotionRpcServiceImpl`、发布 provider、素材/效果/publish-record 表访问；独立模块退出 reactor 与 Compose 部署。
- `marketing-content-service` 的 `MarketingContentRpcService.updatePublishStatus` 是旧跨服务回写目的地；campaign attempt 成为唯一发布摘要来源后不再从推广服务调用它。
- `auth-service` 中现有 `promotion-service` service identity 登记、共享旧 `bk_promotion` MCP 数据库枚举和 `nacos/promotion-service.yaml` 作为协调回退/迁移材料保留；本 change 不改 Auth/公共框架，也不再把这些旧入口作为营销发布能力。
