# 房源与客户接口契约（待完成）

**统一契约入口**：[前后端接口契约 v1](README.md)。本文仍是待完成草稿，不作为字段级契约权威来源。

按当前控制器和数据模型整理；字段必填条件、错误码及全部授权分支尚需与服务实现逐项核对。所有 JSON 返回一般使用 `{success,code,message,data}`，比较报告 PDF 返回 `application/pdf`。当前仓库模板已补齐 Gateway 路由受众；目标环境发布与逐路由运行态验收仍按 [KI-236](../known-issues.md) 跟踪。

## 房源

| 方法和路径 | 请求 | 成功 data |
| --- | --- | --- |
| POST `/listings` | `ListingUpsert` | `ListingDetail` |
| PUT `/listings/{id}` | `ListingUpsert` | `ListingDetail` |
| DELETE `/listings/{id}` | 路径 id | null |
| GET `/listings/{id}` | 路径 id | `ListingDetail` |
| GET `/listings/search` | 可选 query：`keyword,status,brokerId,minArea,maxArea,minTotalPrice,maxTotalPrice,verifiedOnly` | `ListingDetail[]` |
| GET `/listings` | 可选 query `keyword`，默认空串 | `ListingSummary[]` |
| PUT `/listings/{id}/status` | `{status}` | `ListingDetail` |
| PUT `/listings/{id}/assets` | `{imageUrls,floorPlanUrls,videoUrls}`，各为字符串数组 | `ListingDetail` |
| POST `/listings/{id}/assets/legacy-register` | 路径 id；无 body | integer |
| DELETE `/listings/assets/{assetId}` | 路径 assetId | null |
| GET `/listings/buyer/search` | 可选 query `districtCode,communityName,layout,minArea,maxArea,minAskingPriceYuan,maxAskingPriceYuan,cursor,limit` | `ListingBuyerSearchResponse` |
| GET `/listings/buyer/{id}` | 路径 id | `ListingBuyerViewResponse` |
| POST `/listings/{id}/ocr` | `{propertyCertificateUrl,contractUrl}` | `ListingDetail` |
| POST `/listings/{id}/verify` | `{externalSource,certificateNo,ownerName}` | `ListingDetail` |
| PUT `/listings/{id}/owner` | `{ownerCustomerId}` | `ListingDetail` |
| DELETE `/listings/{id}/owner` | 路径 id；无 body | `ListingDetail` |
| POST `/listings/comparisons` | `ListingComparisonCreateRequest` | `ListingComparisonReportResponse` |
| GET `/listings/comparisons/{reportId}` | 路径 reportId | `ListingComparisonReportResponse` |
| GET `/listings/comparisons/{reportId}/pdf` | 路径 reportId | PDF 二进制 |

`ListingUpsert` 实际请求字段：`brokerId`、`ownerCustomerId` 为 int64；`requestId`、`title`、`address`、`communityName`、`districtCode`、`layout`、`floorLevel`、`decoration`、`schoolZone`、`traffic`、`ownerName`、`certificateNo`、`propertyCertificateUrl`、`contractUrl` 为字符串；`area`、`totalPrice` 为十进制数；`imageUrls`、`floorPlanUrls`、`videoUrls` 为字符串数组。创建时 `ownerCustomerId` 必须为正整数并通过业主档案校验；`brokerId` 可省略，提供时须与可信经纪人身份一致；`requestId` 可省略，提供非空值时用于登记幂等。更新时提供的 `brokerId` 须与房源现有归属一致，`ownerCustomerId` 和 `requestId` 不用于更新；业主绑定使用独立 owner 接口。`totalPrice` 使用万元；买方搜索的 `minAskingPriceYuan` / `maxAskingPriceYuan` 使用元。更新规则和其余字段约束应参照 [ListingUpsertRequest](../../listing-master-service/src/main/java/com/bkanent/listing/model/ListingUpsertRequest.java) 与服务实现。

买方搜索 query 的 `districtCode`、`communityName`、`layout`、`cursor` 为字符串，`minArea`、`maxArea`、`minAskingPriceYuan`、`maxAskingPriceYuan` 为十进制数，`limit` 为整数；均可省略。`limit` 为空或小于 1 时使用 20，大于 100 时取 100。路径中的 `id` 和 `assetId` 为 int64。`ListingSummary` 包含 `id,title,address,layout,area,totalPrice,status,floorLevel,decoration,schoolZone,traffic,verificationStatus`。

`ListingComparisonCreateRequest={contractVersion:string,refs:ListingSourceVersionRef[],includeConclusion:boolean}`；`contractVersion` 为 `domain-parallel-v1`，`refs` 为 2–6 项且来源身份不能重复。每项为 `{listingRef:{sourceSystem:string,sourceId:string},expectedRevision:string}`，比较创建时 `expectedRevision` 必填且非空；`includeConclusion` 省略时 Java 原始类型默认为 `false`。

授权服务使用 `listing.create`、`listing.read`/`listing.read.all`、`listing.update.own`/`listing.update.all` 等具体操作和资源归属校验；比较创建与读取分别使用 `compare.report.create` 和 `compare.report.read.own`。比较请求和响应的字段仍须按 [请求模型](../../common/src/main/java/com/bkanent/common/listing/ListingComparisonCreateRequest.java) 与 [响应模型](../../listing-master-service/src/main/java/com/bkanent/listing/comparison/model/ListingComparisonReportResponse.java) 核对。

## 客户

| 方法和路径 | 请求 | 成功 data |
| --- | --- | --- |
| POST `/customers` | `CustomerUpsert` | `CustomerProfile` |
| PUT `/customers/{id}` | `CustomerUpsert` | `CustomerProfile` |
| DELETE `/customers/{id}` | 路径 id | null |
| GET `/customers/{id}` | 路径 id | `CustomerDetail` |
| GET `/customers` | 可选 query `profileType,keyword,brokerId,intention,budgetMin,budgetMax` | `CustomerProfile[]` |
| POST `/customers/{id}/follow-records` | `FollowRequest` | `FollowRecord` |
| GET `/customers/{id}/follow-records` | 路径 id | `FollowRecord[]` |
| GET `/customers/{id}/matched-listings` | 路径 id | `{customerId,listing,matchReason,matchScore}[]` |
| POST `/customers/{id}/entrusts` | `EntrustRequest` | `Entrust` |
| GET `/customers/{id}/entrusts` | 路径 id | `Entrust[]` |
| GET `/customers/entrusts/expiring` | query `days` 默认 7 | `Entrust[]` |
| POST `/customers/entrusts/remind` | query `days` 默认 7 | integer；需要业务管理员和 `customer.reminder.send` |
| POST `/customers/{id}/favorites` | `{listingId,favoriteSource,remark}` | `Favorite` |
| DELETE `/customers/{id}/favorites/{listingId}` | 路径 id、listingId | null |
| GET `/customers/{id}/favorites` | 路径 id | `Favorite[]` |
| POST `/customers/{id}/inquiries` | `{listingId,inquiryChannel,inquiryContent,requestId}` | `CustomerInquiryResponse` |
| GET `/customers/{id}/inquiries` | 路径 id | `CustomerInquiryResponse[]` |
| PUT `/customers/inquiries/{inquiryId}/status` | `{status}` | `CustomerInquiryResponse` |
| POST `/customers/inquiries/{inquiryId}/comparison-reports` | `{reportId}` | `CustomerInquiryComparisonView` |

`CustomerUpsert`：`profileType,name,mobile,wechatNo,gender,intention,preferredArea,preferredLayout,sourceChannel,remark` 为字符串；`budgetMin,budgetMax,preferredAreaMin,preferredAreaMax` 为十进制数；`brokerId` 为 int64。`profileType` 必须为 `CUSTOMER` 或 `OWNER`（大小写不敏感），`name`、`mobile` 必须非空白；创建时提交的 `brokerId` 与可信经纪人身份比较，更新时与客户现有归属比较。`CustomerProfile` 增加 `id`。`CustomerDetail={profile,followRecords,entrustRecords,favoriteListings}`。

`FollowRequest={brokerId:int64,followType:string,content:string,resultTag:string,nextFollowTime:LocalDateTime,inquiryId:int64}`；`inquiryId` 可省略，提供时须属于同一客户；`followType` 须为 `PHONE`、`WECHAT`、`SHOWING` 或 `VISIT`（大小写不敏感）。`EntrustRequest={listingId:int64,contractNo:string,entrustStartDate:LocalDate,entrustEndDate:LocalDate,reminderDays:int32,status:string,remark:string}`；`FavoriteRequest={listingId:int64,favoriteSource:string,remark:string}`。

咨询创建请求的 `listingId:int64` 必须为正数，`requestId:string` 必须非空白且最多 128 字符；`inquiryChannel:string` 可省略，非空时最多 32 字符，`inquiryContent:string` 可省略。状态更新请求 `{status:string}` 的流转为 `OPEN → FOLLOWING/CLOSED`、`FOLLOWING → CLOSED`；报告关联 `{reportId:string}` 使用已有报告 ID。`id`、`listingId`、`inquiryId` 路径参数均为 int64。客户读写、跟进、委托、收藏和匹配分别按具体操作及本人/全局资源范围授权，不能用请求中的 `brokerId` 替代可信身份。
