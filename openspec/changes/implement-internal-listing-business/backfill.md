# 幂等回填与对账设计（任务 2.4）

> 配合 [design.md](design.md) Migration Plan 第 3 步。SQL 侧已作为受管迁移落地；Java 侧为部署期受控操作，不自动抓取、不自动关联。

## 1. SQL 侧（已登记受管迁移，自动执行）

| 迁移 | 内容 | 幂等性 |
|---|---|---|
| `20261105_listing_internal_business.sql`（order 660，bk_listing） | 加列/索引 + 5 张新表 | information_schema 条件 DDL + `CREATE TABLE IF NOT EXISTS` |
| `20261105_customer_listing_inquiry.sql`（order 670，bk_customer） | 咨询表 + 跟进 `inquiry_id` + 回执表 | 同上 |
| `20261105_listing_internal_backfill.sql`（order 680，bk_listing） | ① `asking_price_yuan = total_price × 10000`（仅 NULL 时回填，精确十进制）；② 每套有价房源一条 `listing_price_record` 基线（old=NULL，operator=0 系统，request_id=`migration-baseline-20261105`）；③ 每套未删房源一条 `listing_status_record` 基线（action=`MIGRATION_BASELINE`） | `UPDATE … WHERE asking_price_yuan IS NULL`；`INSERT IGNORE` 依赖 `(listing_id, operator_user_id, request_id)` 唯一键 |

**对账 check**（`listing-internal-backfill.sql`）：断言 ① 有价房源全部满足 `asking_price_yuan = total_price × 10000`；② 有价房源均有基线价格记录；③ 未删房源均有基线状态记录。

**不做的事**：不回填 `owner_customer_id`（存量保持 NULL 待确认）；不改 `verification_status`/`ocr_status` 语义（模拟核验不得转正）；不迁移委托数据。

## 2. Java 侧受控操作（部署期执行，非自动迁移）

### 2.1 旧素材分级登记（任务 3.6 实现运行路径后执行）

- `image_urls`/`floor_plan_urls`/`video_urls` 按逗号拆分为 `listing_asset` 行：`asset_type` 分别为 IMAGE/FLOOR_PLAN/VIDEO，`visibility=BUYER`，`status=PENDING_REVIEW`（待核对），`storage_key` 原样保留旧 URL，`created_by=0`（系统）。
- `property_certificate_url`/`contract_url` → `asset_type=CERTIFICATE/CONTRACT`，`visibility=SENSITIVE`，`status=PENDING_REVIEW`。
- **不主动抓取**任何 URL；如后续需要服务端读取，仅允许 http/https 且拒绝 localhost/环回/私网/保留地址（安全约束）。
- 旧字段保留兼容读取；全部行核对完成后才考虑退役旧字段。

### 2.2 产权证号受管加密（任务 3.7 实现加密路径后执行）

- 读取存量 `certificate_no` 明文 → 认证加密（受管密钥 + 唯一 nonce + 密钥版本）→ 写入 `listing_private_info.certificate_no_ciphertext`。
- 加密完成后清空 `listing_info.certificate_no` 明文（一次性、备份后执行）；加密失败或密钥未配置的行保留明文并进入待处理清单。
- 全程脱敏日志；不将明文或密钥写日志。

### 2.3 业主待确认清单

- 产出清单：`id, owner_name, broker_id, created_at`（`owner_customer_id IS NULL AND owner_name IS NOT NULL AND deleted=0`）。
- 运营人员经授权接口逐条确认关联（任务 3.1 的存量确认入口）；不按姓名/手机号/经纪人自动匹配。

### 2.4 客户预算单位盘点

- 只读盘点 `customer_profile.budget_min/max` 历史输入与匹配结果，确认实际单位（当前匹配代码按万元比较）。
- 兼容期维持旧约定；迁元需单独变更与对账，不在本期自动换算。

## 3. 回滚

- SQL 迁移按 manifest 版本管理，回滚优先恢复兼容版本与受管备份；新表/新列均为增量，旧代码不受影响。
- 回填不删除任何旧数据；基线记录带 `MIGRATION_BASELINE` 标识可辨识、可清理。
- 证号明文清理（2.2）执行前必须完成备份，且为唯一需要专门回滚演练的步骤。
