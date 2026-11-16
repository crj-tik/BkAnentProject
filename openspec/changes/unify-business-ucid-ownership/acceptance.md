# UCID 实施与开发数据核对

日期：2026-11-16。范围为当前 WSL Ubuntu 的 `bk-anent-dev` 开发栈；无生产数据操作。

## 代码与字段

- UCID 是 `bk_auth.user_account.id`。既有 principal、签名 context、BIGINT 用户字段和字符串 owner 字段使用同一用户编号；不创建新身份编号或修改 token 结构。
- 逐域写入/读取矩阵见 `docs/authorization-user-ownership-inventory.md` 的 UCID 实施核对。各域已有可信身份路径；对检查中发现的三处缺口作定向修复。
- 修复 KI-269：营销平台版本不再把原内容另一位用户的 brokerId 与当前创建者 UCID 组合。新 owner 来自当前 actor，broker 取该 actor 的受信映射；全量调用者无 broker 时为空。
- 修复 KI-270：客户跟进已通过 UCID 归属验证后，不再用客户历史 broker 重复拒绝本人；跟进记录的作者和 broker 仍来自当前受信上下文。
- 修复 KI-271：通知应用显式导入共享鉴权异常处理器，收件人 UCID 替换等业务拒绝返回 403。
- 新增 `MarketingVariantOwnershipTest` 5 项，Customer UCID 跟进正反例 2 项；新增通知实际应用配置的 MVC 错误映射回归。

## 开发库实查

通过 information_schema 枚举并扫描 **75 个用户语义字段**，对每列检查总数、空值、正数十进制格式及是否存在对应 Auth 用户。非空值全部格式有效并关联到现有用户；只有 `interview_asset.approved_by_user_id` 存在两处空值。

| 数据 | 当前结果 | 结论 |
| --- | --- | --- |
| Auth 用户 | UCID 1、2、3、4、5；状态 ACTIVE，未删除 | UCID 真源有效 |
| BROKER 映射 | UCID 1 ↔ BROKER 1 | 不因数字相同推断；有 Auth 受管映射及原审计证据 |
| Customer 档案 1/2/3、咨询 1 | brokerId=1、ownerUserId=1；4 条账本 APPLIED | 与 2026-11-13 负责人确认和审计证据一致 |
| Listing 房源 1/2/3 | brokerId=1、ownerUserId=1；3 条账本 APPLIED | 已正确，无须重复覆盖 |
| Listing 价格 3 条、状态 5 条 | operatorUserId=1，有各自请求标识 | 真实操作用户有效；不是需要将系统哨兵 0 替换为用户的记录 |
| Interview case/session 及 CREATE 参与关系 | 创建者、CREATE 用户与邀请人均为 UCID 2 | 同一实际用户关系一致 |
| Interview 两条资产 | PRIVATE，审批人 NULL | 未发布，无需虚构审批人回填 |
| Agent run 与 session memory | 8 个 run 均 UCID 4，与对应 session memory UCID 4 一致 | 没有跨用户绑定 |
| Knowledge | 3 条 OWN 条目 owner=2，4 个版本作者=2；2 条审核记录 reviewer=3 | 作者与审核人是不同实际用户，保留其各自 UCID |
| Customer 跟进、Marketing 内容/计划/发布请求、Notification 消息、Studio 记录、Memory 偏好/经验及多个 A2A 任务表 | 当前没有相应记录 | 空表没有可回填记录 |

Customer/Listing 账本证据为 `PROJECT_OWNER_CONFIRMATION:2026-11-13;AUTH_AUDIT:b5a9ea8c-e483-470f-a823-6275b5d57c84`，本次复核其状态及逐行值，没有修改确认日期或复核人。

**UCID 数据修正结果：0 行需要更改，UCID UPDATE 为 0。** 本轮直接核查发现已证实的历史负责人均已正确；两条空审批人符合当前 PRIVATE 状态。没有为了产生修改数量而覆盖正确数据。员工编号、工号不属于待填写 UCID 的用户列，仍按 KI-204 的独立业务映射限制处理。

真实 Marketing 查询发现既有实体依赖的 `source` 列缺失（KI-39）。该表 0 行，先保存 SHOW CREATE TABLE 和列/行数，再直接执行一次 ALTER，补齐 `source VARCHAR(16) NOT NULL DEFAULT 'manual'`，与已有来源字段 SQL 的定义一致；没有新增迁移脚本或改动已执行 manifest 校验值。修正前后内容行数均为 0。原结构与后结构保存在本机会话诊断中；回退仅能在确认没有后续来源数据写入后按结构快照处理。本次修复开发库，不代表首启 manifest 的既有缺口已关闭。

以上统计为验收前快照；后续登录、Graph 验收可能新增登出、执行绑定及 Memory 等验收记录，不应与前后行数混同。

## 测试与运行验收

- 当前工作区全量 `mvn -gs .mvn-settings.xml -s .mvn-settings.xml test` 成功：19 个模块，1404 项测试，失败/错误/跳过均为 0（通知 MVC 修复之前的全量基线）。
- 以 HEAD 加本次修改构建隔离工作区，Customer 6 项与 Marketing 5 项 UCID 定向测试和 package 均成功，避免把其他会话未提交的源码改动混入运行包。
- 原组织/身份真实 API 脚本成功，含两个非管理员用户的登录、本人资料、刷新与重放拒绝、组织关系、跨用户拒绝；Graph `c5a858a3-5a6d-40fb-b9fa-0ef1a3fba716` 实际 Listing subAgent 调用 2 次，run/session 绑定与正文/参数身份替换防护通过。
- `API_ACCEPTANCE_USERNAME=testUser` 当前库不存在；原 AUTH_BROKER 凭据失效，用户更新凭据后客户/房源本人读取和另一用户拒绝已通过。随后通知收件人替换返回 500，引出 KI-271，最终复验见后续记录。
- 通知修复最终以独立测试配置验证，不连接 Nacos：10 项测试全部通过，含实际通知应用加载共享 HTTP 鉴权异常处理器的 MVC 场景。首轮测试缺少 RPC mock、禁用 Nacos 时仍读取生产 YAML 的测试装配问题均已修正，最终 package 成功。
- Customer、Marketing、Notification 三个隔离运行包已构建并在开发 Compose 中重建，readiness 均为 UP。镜像构建使用仅含 JAR 的临时上下文并经 tar 流传给 Docker，避开 Windows 挂载目录上下文扫描停滞；未清理数据卷。
- 新增的 `scripts/acceptance/business-ucid-api-smoke.py --customer-id 1 --listing-id 2` 在最终新容器通过：broker01 本人客户/房源读取、运营账号读取他人私有详情拒绝、本人通知、篡改收件人 UCID 返回 403、营销查询、Agent 请求体 UCID 替换拒绝。脚本不修改业务记录，最后撤销本次登录令牌。
- 部署后再次扫描 75 列：非法编号 0、孤立引用 0，仅 PRIVATE 资产的两个审批人为空。其余既有 KI-39/55/204/206/243 的生产初始化、员工和通知投递等验收边界保持独立，不计作本变更已解决。
