package com.bkanent.listing.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * ListingDetailResponse 房源详情响应对象。
 */
public record ListingDetailResponse(
        /** 业务属性：id。 */
        Long id,
        /** 业务属性：brokerId。 */
        Long brokerId,
        /** 业务属性：title。 */
        String title,
        /** 业务属性：address。 */
        String address,
        /** 业务属性：layout。 */
        String layout,
        /** 业务属性：area。 */
        BigDecimal area,
        /** 业务属性：totalPrice。 */
        BigDecimal totalPrice,
        /** 业务属性：status。 */
        String status,
        /** 业务属性：floorLevel。 */
        String floorLevel,
        /** 业务属性：decoration。 */
        String decoration,
        /** 业务属性：schoolZone。 */
        String schoolZone,
        /** 业务属性：traffic。 */
        String traffic,
        /** 业务属性：ownerName。 */
        String ownerName,
        /** 业务属性：certificateNo。 */
        String certificateNo,
        /** 业务属性：propertyCertificateUrl。 */
        String propertyCertificateUrl,
        /** 业务属性：contractUrl。 */
        String contractUrl,
        /** 业务属性：imageUrls。 */
        List<String> imageUrls,
        /** 业务属性：floorPlanUrls。 */
        List<String> floorPlanUrls,
        /** 业务属性：videoUrls。 */
        List<String> videoUrls,
        /** 业务属性：ocrStatus。 */
        String ocrStatus,
        /** 业务属性：verificationStatus。 */
        String verificationStatus,
        /** 业务属性：verificationSource。 */
        String verificationSource,
        /** 业务属性：verificationRemark。 */
        String verificationRemark,
        /** 业务属性：createdAt。 */
        LocalDateTime createdAt,
        /** 业务属性：updatedAt。 */
        LocalDateTime updatedAt
) {
}
