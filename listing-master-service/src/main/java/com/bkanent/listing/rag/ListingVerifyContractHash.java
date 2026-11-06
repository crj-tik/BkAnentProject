package com.bkanent.listing.rag;

import com.bkanent.common.authz.AuthzCanonicalHash;
import com.bkanent.common.rag.RagContextAuthorizedCustomerProfileVerifyRequest;
import com.bkanent.common.rag.RagContextAuthorizedListingInquiryVerifyRequest;
import com.bkanent.common.rag.RagContextAuthorizedComparisonReportVerifyRequest;
import com.bkanent.common.rag.RagCustomerProfileVerifyRequest;
import com.bkanent.common.rag.RagListingInquiryVerifyRequest;
import com.bkanent.common.rag.RagComparisonReportVerifyRequest;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Listing 侧受权验证请求的规范哈希，与 customer 侧
 * {@code CustomerVerifyContractHash} 采用完全相同的编码
 * （域分隔 + 长度前缀 + 类型标记：1=String、2=int、3=long；
 * 覆盖内层请求契约版本、请求 ID、业务目标、签名上下文哈希、签名新鲜度哈希）。
 * common 的 {@code RagCanonicalHash} 尚未登记这两个请求类型且 common 不可修改（见 KI-249），
 * 因此两侧独立实现必须保持一致，任何编码变化都需要协议版本升级。
 */
public final class ListingVerifyContractHash {

    private ListingVerifyContractHash() {
    }

    /**
     * Listing→Customer 卖方档案验证请求的规范哈希。
     */
    public static String customerProfileVerifyRequestHash(RagContextAuthorizedCustomerProfileVerifyRequest request) {
        if (request == null) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "request");
        }
        RagCustomerProfileVerifyRequest selector = request.request();
        return digest("rag.context-authorized-customer-profile-verify.v1",
                selector.contractVersion(), selector.requestId(), selector.customerId(),
                selector.expectedProfileType(),
                AuthzCanonicalHash.signedContextHash(request.authorizationContext()),
                AuthzCanonicalHash.signedFreshnessHash(request.freshness()));
    }

    /**
     * Customer→Listing 咨询房源验证请求的规范哈希。
     */
    public static String listingInquiryVerifyRequestHash(RagContextAuthorizedListingInquiryVerifyRequest request) {
        if (request == null) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "request");
        }
        RagListingInquiryVerifyRequest selector = request.request();
        return digest("rag.context-authorized-listing-inquiry-verify.v1",
                selector.contractVersion(), selector.requestId(), selector.listingId(),
                AuthzCanonicalHash.signedContextHash(request.authorizationContext()),
                AuthzCanonicalHash.signedFreshnessHash(request.freshness()));
    }

    /**
     * Customer→Listing 对比报告归属验证请求的规范哈希。
     */
    public static String comparisonReportVerifyRequestHash(
            RagContextAuthorizedComparisonReportVerifyRequest request) {
        if (request == null) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "request");
        }
        RagComparisonReportVerifyRequest selector = request.request();
        return digest("rag.context-authorized-comparison-report-verify.v1",
                selector.contractVersion(), selector.requestId(), selector.reportId(),
                AuthzCanonicalHash.signedContextHash(request.authorizationContext()),
                AuthzCanonicalHash.signedFreshnessHash(request.freshness()));
    }

    private static String digest(String domain, Object... fields) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeString(output, domain);
                for (Object field : fields) {
                    if (field instanceof Integer value) {
                        output.writeByte(2);
                        output.writeInt(value);
                    } else if (field instanceof Long value) {
                        output.writeByte(3);
                        output.writeLong(value);
                    } else if (field instanceof String value) {
                        output.writeByte(1);
                        writeString(output, value);
                    } else {
                        throw new RagException(RagErrorCode.INVALID_ARGUMENT, "requestHash");
                    }
                }
            }
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }
}
