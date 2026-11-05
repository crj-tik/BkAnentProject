package com.bkanent.listing.service;

import com.bkanent.listing.entity.ListingAssetEntity;
import com.bkanent.listing.model.ListingAssetUpsertItem;

import java.util.List;
import java.util.Set;

/**
 * ListingAssetService 房源素材逐项管理服务。
 * 数据操作不重复做权限判断：绑定/退役/迁移入口由 ListingManagementService 按
 * listing.assets.bind.own/.all + requireOwner 模式完成授权后委托调用。
 * 服务端不抓取任何 URL；storageKey 仅保留原值。
 */
public interface ListingAssetService {

    /**
     * 逐项替换素材行（同事务）：replacedTypes 内每种 assetType 先全删（逻辑删除）后按 items 顺序插入，
     * 保持排序；createdBy 记录当前操作人。items 中该类型为空集即清空该类型。
     */
    void replaceAssets(Long listingId, List<ListingAssetUpsertItem> items, Set<String> replacedTypes, long createdBy);

    /**
     * 按可见性过滤返回素材行（visibilityFilter 为空返回全部；已逻辑删除的行不返回）。
     * 调用方需已持有对应房源读取权限；买方输出仅应传入 BUYER。
     */
    List<ListingAssetEntity> listAssets(Long listingId, String visibilityFilter);

    /**
     * 读取单条素材行（已逻辑删除返回 null）。
     */
    ListingAssetEntity getAsset(Long assetId);

    /**
     * 逻辑删除单条素材行。
     */
    void retireAsset(Long assetId);

    /**
     * 历史素材迁移登记：把旧逗号拼接 URL 与证件/合同 URL 逐条登记为 status=PENDING_REVIEW 的行
     * （图片/户型图/视频=BUYER，产权证/合同=SENSITIVE），已存在同 storageKey 的行跳过（幂等），
     * 返回本次新登记行数。不主动抓取任何 URL，不修改旧字段。
     */
    int registerLegacyAssets(Long listingId, long createdBy);
}
