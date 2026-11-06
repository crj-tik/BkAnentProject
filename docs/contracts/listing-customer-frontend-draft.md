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
| POST `/listings/{id}/ocr` | `{propertyCertificateUrl,contractUrl}` | `ListingDetail` |
| POST `/listings/{id}/verify` | `{externalSource,certificateNo,ownerName}` | `ListingDetail` |
| POST `/listings/comparisons` | `ListingComparisonCreateRequest` | `ListingComparisonReportResponse` |
| GET `/listings/comparisons/{reportId}` | 路径 reportId | `ListingComparisonReportResponse` |
| GET `/listings/comparisons/{reportId}/pdf` | 路径 reportId | PDF 二进制 |

`ListingUpsert`：`brokerId,title,address,layout,area,totalPrice,floorLevel,decoration,schoolZone,traffic,ownerName,certificateNo,propertyCertificateUrl,contractUrl,imageUrls,floorPlanUrls,videoUrls`。`ListingDetail` 在上述基础上提供 `id,status,ocrStatus,verificationStatus,verificationSource,verificationRemark,createdAt,updatedAt`。`ListingSummary` 包含 `id,title,address,layout,area,totalPrice,status,floorLevel,decoration,schoolZone,traffic,verificationStatus`。面积和价格为十进制数；`ListingDTO.totalPrice` 以万元计。时间字段为本地日期时间。

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

`CustomerUpsert`：`profileType,name,mobile,wechatNo,gender,intention,preferredArea,preferredLayout,budgetMin,budgetMax,preferredAreaMin,preferredAreaMax,brokerId,sourceChannel,remark`；`CustomerProfile` 增加 `id`。`CustomerDetail={profile,followRecords,entrustRecords,favoriteListings}`。

`FollowRequest={brokerId,followType,content,resultTag,nextFollowTime}`；`FollowRecord` 增加 `id,customerId,createdAt`。`EntrustRequest={listingId,contractNo,entrustStartDate,entrustEndDate,reminderDays,status,remark}`；`Entrust` 增加 `id,customerId,daysToExpire`。`Favorite={id,customerId,listingId,favoriteSource,remark}`。客户读写、跟进、委托、收藏和匹配分别按具体操作及本人/全局资源范围授权，不能用请求中的 `brokerId` 替代可信身份。
