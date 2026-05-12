package com.bkanent.listing.model;

/**
 * ListingOcrRecognizeRequest 房源 OCR 识别请求对象。
 */
public record ListingOcrRecognizeRequest(
        /** 业务属性：propertyCertificateUrl。 */
        String propertyCertificateUrl,
        /** 业务属性：contractUrl。 */
        String contractUrl
) {
}
