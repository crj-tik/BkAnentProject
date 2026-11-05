package com.bkanent.customer.rpc;

import com.bkanent.common.authz.AuthzCanonicalHash;
import com.bkanent.common.rag.RagContextAuthorizedCustomerProfileVerifyRequest;
import com.bkanent.common.rag.RagContextAuthorizedListingInquiryVerifyRequest;
import com.bkanent.common.rag.RagCustomerProfileVerifyRequest;
import com.bkanent.common.rag.RagListingInquiryVerifyRequest;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 受权验证请求的规范哈希。
 *
 * <p>common 的 {@code RagCanonicalHash} 尚未登记本变更新增的两个验证请求类型，
 * customer 侧按与 listing-master-service 约定的编码（域分隔 + 长度前缀 + 类型标记）
 * 固定计算；两侧实现必须保持一致，任何编码变化都需要协议版本升级。
 * 字段顺序与既有 {@code rag.context-authorized-listing-business-batch-read.v1} 分支保持同构：
 * 契约版本、内层请求、签名上下文哈希、签名新鲜度哈希。
 */
public final class CustomerVerifyContractHash {

    private CustomerVerifyContractHash() {
    }

    /**
     * Listing→Customer 卖方档案验证请求的规范哈希。
     */
    public static String customerProfileVerifyRequestHash(RagContextAuthorizedCustomerProfileVerifyRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request");
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
            throw new IllegalArgumentException("request");
        }
        RagListingInquiryVerifyRequest selector = request.request();
        return digest("rag.context-authorized-listing-inquiry-verify.v1",
                selector.contractVersion(), selector.requestId(), selector.listingId(),
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
                        throw new IllegalArgumentException("field");
                    }
                }
            }
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("受权验证请求摘要计算失败", failure);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }
}
