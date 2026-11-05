package com.bkanent.listing.converter;

import com.bkanent.common.model.ListingDTO;
import com.bkanent.listing.entity.ListingAssetEntity;
import com.bkanent.listing.entity.ListingEntity;
import com.bkanent.listing.model.ListingDetailResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ListingConverter 对象转换类。
 */
@Component
public class ListingConverter {

    /**
     * 转换dto。
     */
    public ListingDTO toDto(ListingEntity entity) {
        if (entity == null) {
            return null;
        }
        return new ListingDTO(
                entity.getId(),
                entity.getTitle(),
                entity.getAddress(),
                entity.getLayout(),
                entity.getArea(),
                entity.getTotalPrice(),
                entity.getStatus(),
                entity.getFloorLevel(),
                entity.getDecoration(),
                entity.getSchoolZone(),
                entity.getTraffic(),
                entity.getVerificationStatus()
        );
    }

    /**
     * 转换detailResponse：素材数组（图片/户型图/视频）从 listing_asset 行读取（过渡期新真源），
     * certificateNo 由调用方传入（已迁移行传解密值，未迁移行传实体明文）。
     */
    public ListingDetailResponse toDetailResponse(ListingEntity entity, List<ListingAssetEntity> assets,
                                                  String certificateNo) {
        if (entity == null) {
            return null;
        }
        return new ListingDetailResponse(
                entity.getId(),
                entity.getBrokerId(),
                entity.getTitle(),
                entity.getAddress(),
                entity.getLayout(),
                entity.getArea(),
                entity.getTotalPrice(),
                entity.getStatus(),
                entity.getFloorLevel(),
                entity.getDecoration(),
                entity.getSchoolZone(),
                entity.getTraffic(),
                entity.getOwnerName(),
                certificateNo,
                entity.getPropertyCertificateUrl(),
                entity.getContractUrl(),
                assetValues(assets, "IMAGE"),
                assetValues(assets, "FLOOR_PLAN"),
                assetValues(assets, "VIDEO"),
                entity.getOcrStatus(),
                entity.getVerificationStatus(),
                entity.getVerificationSource(),
                entity.getVerificationRemark(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    /**
     * 按素材类型提取 storageKey（保持传入顺序）。
     */
    private List<String> assetValues(List<ListingAssetEntity> assets, String assetType) {
        if (assets == null || assets.isEmpty()) {
            return List.of();
        }
        return assets.stream()
                .filter(asset -> assetType.equals(asset.getAssetType()))
                .map(ListingAssetEntity::getStorageKey)
                .toList();
    }

    /**
     * 拼接values。
     */
    public String joinValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .reduce((left, right) -> left + "," + right)
                .orElse(null);
    }
}
