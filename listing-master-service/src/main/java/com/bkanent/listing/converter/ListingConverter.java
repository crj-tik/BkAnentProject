package com.bkanent.listing.converter;

import com.bkanent.common.model.ListingDTO;
import com.bkanent.listing.entity.ListingEntity;
import com.bkanent.listing.model.ListingDetailResponse;
import com.bkanent.listing.search.ListingSearchDocument;
import org.springframework.stereotype.Component;

import java.util.Arrays;
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
     * 转换detailResponse。
     */
    public ListingDetailResponse toDetailResponse(ListingEntity entity) {
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
                entity.getCertificateNo(),
                entity.getPropertyCertificateUrl(),
                entity.getContractUrl(),
                splitValues(entity.getImageUrls()),
                splitValues(entity.getFloorPlanUrls()),
                splitValues(entity.getVideoUrls()),
                entity.getOcrStatus(),
                entity.getVerificationStatus(),
                entity.getVerificationSource(),
                entity.getVerificationRemark(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
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

    /**
     * 转换searchDocument。
     */
    public ListingSearchDocument toSearchDocument(ListingEntity entity) {
        if (entity == null) {
            return null;
        }
        return new ListingSearchDocument(
                entity.getId(),
                entity.getTitle(),
                entity.getAddress(),
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
                entity.getVerificationStatus()
        );
    }

    /**
     * 拆分values。
     */
    private List<String> splitValues(String values) {
        if (values == null || values.isBlank()) {
            return List.of();
        }
        return Arrays.stream(values.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }
}
