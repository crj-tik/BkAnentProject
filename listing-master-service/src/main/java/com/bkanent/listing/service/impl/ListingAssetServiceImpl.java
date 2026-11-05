package com.bkanent.listing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.listing.entity.ListingAssetEntity;
import com.bkanent.listing.entity.ListingEntity;
import com.bkanent.listing.mapper.ListingAssetMapper;
import com.bkanent.listing.model.ListingAssetUpsertItem;
import com.bkanent.listing.service.ListingAssetService;
import com.bkanent.listing.service.ListingDomainService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ListingAssetServiceImpl 房源素材逐项管理服务实现。
 * 替换语义：同 listing+assetType 全删（逻辑删除）后按顺序插入；历史迁移幂等（同 storageKey 跳过）；
 * 不实现任何服务端 URL 读取辅助方法（本期只登记不抓取，SSRF 约束见 LR 设计决策 2）。
 */
@Service
public class ListingAssetServiceImpl implements ListingAssetService {

    /**
     * 常量：ASSET_TYPES（允许的素材类型）。
     */
    public static final Set<String> ASSET_TYPES = Set.of("IMAGE", "FLOOR_PLAN", "VIDEO", "CERTIFICATE", "CONTRACT");
    /**
     * 常量：VISIBILITIES（允许的可见性）。
     */
    public static final Set<String> VISIBILITIES = Set.of("BUYER", "INTERNAL", "SENSITIVE");
    /**
     * 常量：STATUSES（允许的素材状态）。
     */
    public static final Set<String> STATUSES = Set.of("ACTIVE", "PENDING_REVIEW", "RETIRED");
    /**
     * 常量：STORAGE_KEY_MAX_LENGTH（storage_key 列长度）。
     */
    private static final int STORAGE_KEY_MAX_LENGTH = 512;

    /**
     * 字段：log。
     */
    private static final Logger log = LoggerFactory.getLogger(ListingAssetServiceImpl.class);
    /**
     * 字段：mapper。
     */
    private final ListingAssetMapper mapper;
    /**
     * 字段：domain（读取房源旧字段用于历史素材迁移）。
     */
    private final ListingDomainService domain;

    /**
     * 构造 ListingAssetServiceImpl 实例。
     */
    public ListingAssetServiceImpl(ListingAssetMapper mapper, ListingDomainService domain) {
        this.mapper = mapper;
        this.domain = domain;
    }

    /**
     * 逐项替换素材：replacedTypes 内每种类型全删后插入，保持 items 顺序。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceAssets(Long listingId, List<ListingAssetUpsertItem> items, Set<String> replacedTypes, long createdBy) {
        if (listingId == null || listingId <= 0) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "listingId");
        }
        Set<String> types = new HashSet<>();
        if (replacedTypes != null) {
            for (String type : replacedTypes) {
                requireAssetType(type);
                types.add(type);
            }
        }
        List<ListingAssetUpsertItem> validated = new ArrayList<>();
        if (items != null) {
            for (ListingAssetUpsertItem item : items) {
                validated.add(requireValidItem(item));
            }
        }
        for (String type : types) {
            mapper.delete(new LambdaQueryWrapper<ListingAssetEntity>()
                    .eq(ListingAssetEntity::getListingId, listingId)
                    .eq(ListingAssetEntity::getAssetType, type));
        }
        Map<String, Integer> nextOrderByType = new HashMap<>();
        for (ListingAssetUpsertItem item : validated) {
            ListingAssetEntity row = new ListingAssetEntity();
            row.setListingId(listingId);
            row.setAssetType(item.assetType());
            row.setStorageKey(item.storageKey());
            row.setVisibility(item.visibility());
            int fallbackOrder = nextOrderByType.merge(item.assetType(), 0, (oldValue, newValue) -> oldValue + 1);
            row.setSortOrder(item.sortOrder() != null ? item.sortOrder() : fallbackOrder);
            row.setStatus(StringUtils.hasText(item.status()) ? item.status() : "ACTIVE");
            row.setCreatedBy(createdBy);
            mapper.insert(row);
        }
        log.info("替换房源素材完成，listingId={}，types={}，items={}", listingId, types, validated.size());
    }

    /**
     * 按可见性过滤返回素材行（排序 sortOrder、id）。
     */
    @Override
    public List<ListingAssetEntity> listAssets(Long listingId, String visibilityFilter) {
        if (listingId == null || listingId <= 0) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "listingId");
        }
        List<ListingAssetEntity> rows = mapper.selectList(new LambdaQueryWrapper<ListingAssetEntity>()
                .eq(ListingAssetEntity::getListingId, listingId)
                .orderByAsc(ListingAssetEntity::getSortOrder)
                .orderByAsc(ListingAssetEntity::getId));
        if (!StringUtils.hasText(visibilityFilter)) {
            return rows;
        }
        List<ListingAssetEntity> filtered = new ArrayList<>();
        for (ListingAssetEntity row : rows) {
            if (visibilityFilter.equals(row.getVisibility())) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    /**
     * 读取单条素材行。
     */
    @Override
    public ListingAssetEntity getAsset(Long assetId) {
        if (assetId == null || assetId <= 0) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "assetId");
        }
        return mapper.selectById(assetId);
    }

    /**
     * 逻辑删除单条素材行。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void retireAsset(Long assetId) {
        if (assetId == null || assetId <= 0) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "assetId");
        }
        mapper.deleteById(assetId);
        log.info("退役房源素材完成，assetId={}", assetId);
    }

    /**
     * 历史素材迁移登记（幂等，不抓取 URL，不修改旧字段）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int registerLegacyAssets(Long listingId, long createdBy) {
        if (listingId == null || listingId <= 0) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "listingId");
        }
        ListingEntity entity = domain.getById(listingId);
        if (entity == null || !Integer.valueOf(0).equals(entity.getDeleted())) {
            throw new IllegalArgumentException("房源不存在: " + listingId);
        }
        Set<String> existingKeys = new HashSet<>();
        for (ListingAssetEntity row : listAssets(listingId, null)) {
            if (row.getStorageKey() != null) {
                existingKeys.add(row.getStorageKey());
            }
        }
        List<ListingAssetUpsertItem> legacy = new ArrayList<>();
        appendLegacyItems(legacy, "IMAGE", "BUYER", splitLegacyValues(entity.getImageUrls()));
        appendLegacyItems(legacy, "FLOOR_PLAN", "BUYER", splitLegacyValues(entity.getFloorPlanUrls()));
        appendLegacyItems(legacy, "VIDEO", "BUYER", splitLegacyValues(entity.getVideoUrls()));
        if (StringUtils.hasText(entity.getPropertyCertificateUrl())) {
            legacy.add(new ListingAssetUpsertItem("CERTIFICATE", entity.getPropertyCertificateUrl().trim(),
                    "SENSITIVE", null, "PENDING_REVIEW"));
        }
        if (StringUtils.hasText(entity.getContractUrl())) {
            legacy.add(new ListingAssetUpsertItem("CONTRACT", entity.getContractUrl().trim(),
                    "SENSITIVE", null, "PENDING_REVIEW"));
        }
        int registered = 0;
        for (ListingAssetUpsertItem item : legacy) {
            if (existingKeys.contains(item.storageKey())) {
                continue;
            }
            ListingAssetEntity row = new ListingAssetEntity();
            row.setListingId(listingId);
            row.setAssetType(item.assetType());
            row.setStorageKey(item.storageKey());
            row.setVisibility(item.visibility());
            row.setSortOrder(item.sortOrder());
            row.setStatus("PENDING_REVIEW");
            row.setCreatedBy(createdBy);
            mapper.insert(row);
            existingKeys.add(item.storageKey());
            registered++;
        }
        log.info("历史素材迁移登记完成，listingId={}，新登记={}，跳过={}", listingId, registered, legacy.size() - registered);
        return registered;
    }

    /**
     * 校验单条素材条目（类型/可见性/状态/长度）。
     */
    private static ListingAssetUpsertItem requireValidItem(ListingAssetUpsertItem item) {
        if (item == null) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "asset");
        }
        requireAssetType(item.assetType());
        if (!StringUtils.hasText(item.storageKey()) || item.storageKey().trim().length() > STORAGE_KEY_MAX_LENGTH) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "storageKey");
        }
        if (!VISIBILITIES.contains(item.visibility())) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "visibility");
        }
        if (StringUtils.hasText(item.status()) && !STATUSES.contains(item.status())) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "status");
        }
        return new ListingAssetUpsertItem(item.assetType(), item.storageKey().trim(), item.visibility(),
                item.sortOrder(), StringUtils.hasText(item.status()) ? item.status() : null);
    }

    /**
     * 校验素材类型。
     */
    private static void requireAssetType(String assetType) {
        if (!ASSET_TYPES.contains(assetType)) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "assetType");
        }
    }

    /**
     * 拆分旧逗号拼接 URL（去空白）。
     */
    private static List<String> splitLegacyValues(String values) {
        if (!StringUtils.hasText(values)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : values.split(",")) {
            if (StringUtils.hasText(value)) {
                result.add(value.trim());
            }
        }
        return result;
    }

    /**
     * 追加历史素材条目（图片/户型图/视频=BUYER）。
     */
    private static void appendLegacyItems(List<ListingAssetUpsertItem> items, String assetType,
                                          String visibility, List<String> storageKeys) {
        for (String storageKey : storageKeys) {
            items.add(new ListingAssetUpsertItem(assetType, storageKey, visibility, null, "PENDING_REVIEW"));
        }
    }
}
