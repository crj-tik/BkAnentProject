# 营销接口请求参数（源码核对稿）

统一入口见[前后端接口契约 v1](README.md)。本文根据当前营销 Controller、请求 DTO 和服务校验整理 Gateway 路径；响应各字段、全部错误码及目标环境运行行为仍需逐项验收。受保护接口携带 Bearer token，服务端按当前用户及资源范围授权。

## 内容接口

| 方法和路径 | 参数 |
| --- | --- |
| `POST /marketing/contents` | 必填 JSON body `MarketingContentUpsertRequest` |
| `POST /marketing/contents/{id}/variants` | `id:int64`；必填 JSON body `MarketingContentUpsertRequest` |
| `PUT /marketing/contents/{id}/publish-status` | `id:int64`；必填 JSON body `MarketingPublishStatusUpdateRequest`。当前服务拒绝该旧写入，发布状态由 campaign 回执管理 |
| `GET /marketing/contents` | 必填 query `listingId:int64` |
| `GET /marketing/contents/search` | 可选 query `listingId:int64`、`platform:string`、`keyword:string`、`tag:string`、`contentType:string`、`auditStatus:string`、`publishStatus:string` |

`MarketingContentUpsertRequest={listingId:int64,platform:string,title:string,contentType:string,copywriting:string,assetUrls:string[],coverImageUrl:string,videoUrl:string,platformVariant:string,tags:string[],auditStatus:string,parentContentId:int64,source:string}`。`listingId` 不可为 null，`platform` 须非空白，`contentType` 须为 `TEXT`、`IMAGE`、`VIDEO` 或 `MIXED`（大小写不敏感）；非空 `auditStatus` 只接受 `DRAFT`。`platformVariant` 为空时使用 `DEFAULT`，`source` 为空时使用 `manual`。创建变体时服务端将 `parentContentId` 设为路径中的原内容 ID。

`MarketingPublishStatusUpdateRequest={publishStatus:string,publishMessage:string,externalPublishId:string,publishTime:LocalDateTime}`。字段存在于旧 DTO，但当前入口始终拒绝直接更新，不应当作可用的发布执行接口。

## Campaign 接口

| 方法和路径 | 参数 |
| --- | --- |
| `POST /marketing/campaigns/prepare` | 必填 JSON body `CampaignPlanPrepareRequest` |
| `POST /marketing/campaigns/contents/{contentId}/review` | `contentId:int64`；必填 body `{auditStatus:string}`，值为 `APPROVED`、`REJECTED`、`REVIEWING`，大小写不敏感 |
| `GET /marketing/campaigns/plans/{planId}` | `planId:string` |
| `POST /marketing/campaigns/plans/{planId}/confirm` | `planId:string`；必填 body `CampaignConfirmRequest` |
| `GET /marketing/campaigns/plans/{planId}/receipts/{requestId}` | `planId:string`、`requestId:string` |
| `GET /marketing/campaigns/receipts/{receiptId}` | `receiptId:string` |
| `POST /marketing/campaigns/attempts/{attemptId}/reconcile` | `attemptId:string`，无 body |
| `GET /marketing/campaigns/attempts/{attemptId}/effects` | `attemptId:string` |
| `POST /marketing/campaigns/attempts/{attemptId}/effects/sync` | `attemptId:string`，无 body |
| `GET /marketing/campaigns/roi-report` | 可选 query `platform:string` |
| `POST /marketing/campaigns/brand-assets` | 必填 JSON body `CampaignBrandAssetRequest` |
| `GET /marketing/campaigns/brand-assets` | 可选 query `assetType:string`、`keyword:string` |

`CampaignPlanPrepareRequest={contentId:int64,brandAssetIds:int64[],targets:CampaignTargetRequest[],requestId:string}`。`contentId` 必须为正数并指向已审核通过、当前用户可用的内容；`brandAssetIds` 可省略或为 null（按空数组处理），提供时各项为正数且不重复；`targets` 必须有 1–20 项，同一平台和账号不能重复。`requestId` 当前被接受但准备逻辑未读取，每次准备会新建计划，不能依赖它去重，见 KI-254。

`CampaignTargetRequest={platform:string,accountId:int64,arguments:object<string,string>}`。`platform` 匹配 `[A-Za-z0-9_-]{2,32}`，`accountId` 为正数且须是该平台的有效授权账号；`arguments` 可省略或为 null（按空对象处理）。

`CampaignConfirmRequest={requestId:string,expectedPlanVersion:int32,expectedSnapshotHash:string}`。`requestId` 非空白且最多 128 字符；`expectedSnapshotHash` 非空白，版本和摘要须与准备结果一致。确认接口按当前用户与 `requestId` 处理幂等，重复请求的计划和参数不一致时冲突。

`CampaignBrandAssetRequest={assetType:string,assetName:string,assetUrl:string,platformScope:string,tags:string[],status:string,remark:string}`。`assetType`、`assetName` 和 `assetUrl` 必须非空白；名称最多 128 字符，地址最多 1024 字符且以 `https://` 开头；`tags` 可省略或为 null，最多 32 项，每项最多 64 字符且不能含逗号；`status` 为 `ACTIVE` 或 `INACTIVE`，省略时为 `ACTIVE`。

## Promotions 兼容路径

| 方法和路径 | 参数 |
| --- | --- |
| `POST /promotions/publish` | `CampaignPlanPrepareRequest`；缺少 `contentId` 且携带旧 `listingId/platforms/prompt` 时拒绝旧请求 |
| `POST /promotions/{planId}/confirm` | `planId:string`、`CampaignConfirmRequest` body |
| `GET /promotions/receipts/{receiptId}` | `receiptId:string` |
| `POST /promotions/effects/sync` | 必填 body `{publishRecordId:int64,statDate:string}`；`publishRecordId` 必填，`statDate` 当前未使用，见 KI-254 |
| `GET /promotions/effects` | 必填 query `listingId:int64` |
| `GET /promotions/roi-report` | 无参数 |
| `POST /promotions/brand-assets` | 必填 `CampaignBrandAssetRequest` body |
| `GET /promotions/brand-assets` | 可选 query `assetType:string`、`keyword:string` |

准备计划、审核、确认和效果读取有不同授权及业务状态要求；模拟数据和真实效果必须按服务返回的数据模式区分。接口路径存在不代表对应 provider 已配置或运行验收通过。
