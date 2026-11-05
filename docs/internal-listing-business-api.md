# 内部房源业务 API（implement-internal-listing-business）

> 2026-11-05 起：所有房源均为本系统内部登记和维护的数据，不接入外部房源系统。本系统是房源业务真源。
> 所有 JSON 返回使用 `{success,code,message,data}` 包络。鉴权沿用版本化授权（可信 actor + BROKER 映射 + 具体操作码）；请求中的 brokerId 仅作提交校验，不作为身份。

## 1. 房源管理（listing-master-service）

| 方法和路径 | 请求 | 成功 data | 说明 |
| --- | --- | --- | --- |
| POST `/listings` | `ListingUpsert`（下表） | `ListingDetail` | 登记房源；需 `listing.create` 与可信 BROKER 身份 |
| PUT `/listings/{id}` | `ListingUpsert` | `ListingDetail` | 维护房源；版本条件写，冲突返回明确错误 |
| PUT `/listings/{id}/status` | `{status, requestId?}` | `ListingDetail` | 状态转换（见 §3 状态机） |
| PUT `/listings/{id}/owner` | `{ownerCustomerId}` | `ListingDetail` | 存量房源业主确认（先经 Customer 验证） |
| DELETE `/listings/{id}/owner` | 路径 id | `ListingDetail` | 解除业主关联（保留历史，不删除记录） |
| PUT `/listings/{id}/assets` | `{imageUrls,floorPlanUrls,videoUrls}` | `ListingDetail` | 素材绑定（逐项落 `listing_asset` 行，见 §4；旧字段同事务双写兼容） |
| POST `/listings/{id}/assets/legacy-register` | 路径 id | `ListingDetail` | 存量逗号 URL 受控登记为 `PENDING_REVIEW` 素材行（幂等，不抓取） |
| DELETE `/listings/assets/{assetId}` | 路径 assetId | `ListingDetail` | 退役单条素材（逻辑删除） |
| GET `/listings/buyer/search` | query：`districtCode,communityName(模糊),layout,minArea,maxArea,minAskingPriceYuan,maxAskingPriceYuan(元),cursor,limit(默认20上限100)` | `ListingBuyerSearchResponse` | 买方可见在售分页搜索（见 §4a）；查询不创建咨询或档案 |
| GET `/listings/buyer/{id}` | 路径 id | `ListingBuyerViewResponse` | 买方可见详情（仅 BUYER 可见 ACTIVE 素材，无敏感字段） |
| POST `/listings/{id}/ocr` | `{propertyCertificateUrl,contractUrl}` | `ListingDetail` | OCR 占位（明确标注模拟，不填占位证号） |
| POST `/listings/{id}/verify` | `{externalSource?,certificateNo?,ownerName?}` | `ListingDetail` | 内部人工登记核验（非第三方权属核验）；证号加密保存 |
| GET `/listings/{id}` / `/listings` / `/listings/search` | 既有契约 | 既有响应 | 兼容保留；`totalPrice` 仍为万元 |

`ListingUpsert` 字段：`brokerId, ownerCustomerId, requestId, title, address, communityName, districtCode, layout, area, totalPrice, floorLevel, decoration, schoolZone, traffic, ownerName, certificateNo, propertyCertificateUrl, contractUrl, imageUrls, floorPlanUrls, videoUrls`。

- `ownerCustomerId`：**新登记必填**。Customer 域 `OWNER` 档案 ID；登记时经签名 RPC 实时验证（类型/存在/当前操作人范围），失败关闭不创建。存量房源可为空（待确认），不按姓名自动关联。
- `requestId`：登记幂等键。同一操作人 + 同一 `requestId` + 相同内容重试返回原房源；内容不同明确拒绝。
- `totalPrice`：兼容期万元字段。内部主值为 `askingPriceYuan`（CNY 元，见 §2）；无法无损换算为万元（除不尽）时兼容期拒绝该价格。
- `area` 单位平方米；`communityName`/`districtCode` 用于区域与小区筛选。

## 2. 金额单位

- **内部主值**：`askingPriceYuan`，CNY 元（DECIMAL(16,2)），所有新记录与买方输出使用。
- **兼容投影**：`totalPrice`/`ListingDTO.totalPrice` 保持万元；由统一领域命令在同一事务换算（元 = 万元 × 10000，恰好一次）。旧接口在兼容期继续返回万元，不重复换算。
- 存量数据已由迁移 `20261105_listing_internal_backfill.sql` 精确回填（×10000）并对账。

## 3. 房源状态机（无审批、无合同依赖）

```
PENDING_REVIEW(待上架) ──上架──> ON_SALE ──确认成交──> SOLD(终态)
       │                        │
       └──下架──────> OFFLINE <──┘
                        │
                        └──重新上架──> ON_SALE
```

- 允许转换：`PENDING_REVIEW→ON_SALE/OFFLINE`、`ON_SALE→OFFLINE/SOLD`、`OFFLINE→ON_SALE`；`SOLD` 终态；同状态为 no-op（不写历史）。
- 上架校验：title/address/layout 非空、面积>0、价格>0（asking 为空时用 total_price×10000 补齐）。**不校验**合同、委托、审批或核验结果。
- 每次转换追加 `listing_status_record`（from/to/action/reason/operator/requestId）；当前值与历史同事务更新，RAG outbox 同事务发布。
- 并发保护：行级 `version` 乐观锁，过期编辑返回版本冲突，不静默覆盖。

## 4. 素材分级

素材逐项落 `listing_asset` 行：`assetType`（IMAGE/FLOOR_PLAN/VIDEO/CERTIFICATE/CONTRACT）、`visibility`（BUYER/INTERNAL/SENSITIVE）、`sortOrder`、`status`（ACTIVE/PENDING_REVIEW/RETIRED）、`storageKey`（保留原值，不主动抓取；本期不实现任何服务端 URL 读取）。

- 买方输出仅返回 `visibility=BUYER` 且 `status=ACTIVE` 的素材；`PENDING_REVIEW` 待核对行不外露。
- 产权证/合同类素材为 SENSITIVE：仅具备私有详情权限的调用方可访问；知道存储地址不构成权限。
- 旧逗号拼接 URL 可经受权入口登记为 `PENDING_REVIEW` 行（幂等），迁移设计见变更目录 [backfill.md](../openspec/changes/implement-internal-listing-business/backfill.md)。

### 4a. 买方可见搜索与详情

- 分页：键集游标 `cursor=base64(lastUpdatedAt,lastId)`，排序 `updated_at DESC, id DESC`，同条件翻页不重不漏；limit 默认 20、上限 100。
- 价格过滤为 CNY 元；`asking_price_yuan` 为空的存量行回退 `total_price×10000` 比较。
- 输出前复核当前状态与权限：非在售/已删/无权不返回。
- 买方投影字段：id、title、address、communityName、districtCode、layout、area、askingPriceYuan（元）、status、floorLevel、decoration、schoolZone、traffic、BUYER 可见素材列表；**不含** ownerName、certificateNo、业主联系方式、证件与合同 URL。

### 4b. 产权证号受管加密

- AES-256-GCM（密文 = 12 字节随机 nonce ‖ 密文 ‖ tag）；密钥经 `LISTING_PRIVATE_INFO_ENCRYPTION_KEY`（Base64 ≥32 字节）注入，未配置时敏感写入失败关闭、不降级明文；`certificateKeyId` 记录密钥版本支持轮换，轮换失配或密文篡改明确拒绝。
- 核验通过即同事务加密保存并置空 `listing_info.certificate_no` 明文；存量明文批量清理属部署期受控操作。
- 日志全程脱敏，不打印明文证号或密钥。

## 5. 买方房源咨询（customer-service）

| 方法和路径 | 请求 | 成功 data | 说明 |
| --- | --- | --- | --- |
| POST `/customers/{id}/inquiries` | `{listingId, inquiryChannel, inquiryContent, requestId}` | `CustomerInquiryResponse` | 登记咨询；需 `customer.inquiry.write.own/.all` |
| GET `/customers/{id}/inquiries` | 路径 id | `CustomerInquiryResponse[]` | 咨询列表；需 `customer.inquiry.read.own/.all` |
| PUT `/customers/inquiries/{inquiryId}/status` | `{status}` | `CustomerInquiryResponse` | 状态变更 OPEN→FOLLOWING/CLOSED、FOLLOWING→CLOSED；CLOSED 终态 |
| POST `/customers/{id}/follow-records` | 既有字段 + 可选 `inquiryId` | `FollowRecord` | 跟进可关联同一客户的咨询；跨客户拒绝；历史空关联保持兼容 |

`CustomerInquiryResponse`：`id, customerId, listingId, brokerId, inquiryChannel, inquiryContent, status, validationState, checkedListingVersion, checkedAt`。

- **创建流程**：先验客户权限与类型 → 经签名 RPC 向 Listing 验证房源当前在售且可读（返回房源版本）→ 同事务落咨询 + 幂等回执。房源不存在/非在售/不可读 → 拒绝不落库；Listing 不可用 → 失败关闭。
- **验证状态** `validationState`：`CONFIRMED`（创建时验证通过，对外为创建成功）、`NEEDS_RECHECK`（提交后复查发现房源版本变化或不可用，补偿任务重验：同版本→CONFIRMED、版本变化→保持并更新版本、不可访问→REJECTED）、`REJECTED`（房源已不可访问，咨询保留为历史不携带在售事实）。
- **幂等**：`requestId` 同内容重试返回原咨询；不同内容拒绝。
- 咨询 ≠ 收藏 ≠ 成交：同一客户对同一房源可有多条独立咨询；浏览/查询房源不产生咨询。

## 6. 跨服务验证链路（内部 RPC）

| 链路 | 提供方 | 验证内容 | 拒绝语义 |
| --- | --- | --- | --- |
| Listing→Customer 卖方档案验证 | `CustomerProfileVerifyRpcService` | 档案存在/未删/类型 OWNER/操作人范围（customer.read 或 .all） | NOT_FOUND / NOT_ACCESSIBLE / INVALID_PROFILE_TYPE；验签失败抛异常，登记失败关闭 |
| Customer→Listing 咨询房源验证 | `ListingInquiryVerifyRpcService` | 房源存在/未删/ON_SALE/操作人可读（listing.read 公开范围，跨 broker 在售浏览） | NOT_ACCESSIBLE（不区分不存在与无权）；返回当前 status 与行级 version 供复查 |

两条链路均绑定 Auth 签名上下文 + 服务 HMAC 请求签名（`LISTING_CUSTOMER_VERIFY_SIGNING_SECRET` / `CUSTOMER_LISTING_INQUIRY_VERIFY_SECRET`，两侧同值，≥32 字节，未配置失败关闭）。规范哈希编码两侧逐字节一致（LR-177/LR-178，防漂移回归测试保护）。

## 7. 兼容性保证（不变项）

- `domain-parallel-v1` 契约、`legacy-local` 引用、规范化 contentHash 算法、UNKNOWN 数据模式、旧 `/compare/**` 兼容入口：全部保留，黄金样本 `ListingV1CompatibilityGoldenSampleTest` 固定 hash 防重构漂移。
- 旧万元接口行为不变；内部数据不因"内部化"批量改标 REAL。
- 外部房源 HTTP provider 已从正式配置退役（`LISTING_SOURCE_HTTP_*` 已删除）；fixture 仅限显式隔离的测试/演示环境（SIMULATED）。

## 8. 数据模型与迁移

表结构变更见受管迁移（manifest order 660/670/680/690）：`listing_info` 增量列（owner_customer_id/community_name/district_code/asking_price_yuan/version）+ `listing_asset`/`listing_private_info`/`listing_price_record`/`listing_status_record`/`listing_command_receipt` + `customer_listing_inquiry`/`customer_inquiry_command_receipt` + Auth 咨询操作码。回填与对账设计见变更目录 [backfill.md](../openspec/changes/implement-internal-listing-business/backfill.md)。
