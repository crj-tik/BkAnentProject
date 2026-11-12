package com.bkanent.common.rag;

import com.bkanent.common.authz.AuthzContract;
import com.bkanent.common.authz.AuthzSignature;
import com.bkanent.common.authz.AuthorizationContext;
import com.bkanent.common.authz.AuthorizationStatus;
import com.bkanent.common.authz.AuthzFreshness;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 复现跨服务验证 RPC 的 Dubbo 反序列化失败：fastjson2 对含
 * AuthorizationContext/AuthzFreshness 的请求 record 的编解码。
 */
class RagVerifyRequestSerializationTest {

    @Test
    void fastjson2RoundTripCustomerProfileVerifyRequest() throws Exception {
        long now = Instant.now().getEpochSecond();
        KeyPair authority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String hash = "a".repeat(64);
        var unsigned = new AuthorizationContext("123", 7, 2, hash,
                List.of("customer.read"), "listing-master-service", "req-1", "run-1", "sess-1",
                hash, hash, nonce, now, now + 60, now + 300,
                Map.of("BROKER", 1L), AuthzContract.ALGORITHM, "auth-test", null);
        AuthorizationContext context = AuthzSignature.sign(unsigned, authority.getPrivate());
        AuthzFreshness freshness = AuthzSignature.sign(new AuthzFreshness("123", 7, 2, AuthorizationStatus.ACTIVE,
                hash, now, now + 30, nonce, AuthzContract.ALGORITHM, "auth-test", null), authority.getPrivate());

        RagCustomerProfileVerifyRequest inner = new RagCustomerProfileVerifyRequest(1, "req-2", 1L, "OWNER");
        RagContextAuthorizedCustomerProfileVerifyRequest request =
                new RagContextAuthorizedCustomerProfileVerifyRequest(1, inner, context, freshness);

        String json = com.alibaba.fastjson2.JSON.toJSONString(request);
        System.out.println("json-length=" + json.length());
        RagContextAuthorizedCustomerProfileVerifyRequest back =
                com.alibaba.fastjson2.JSON.parseObject(json, RagContextAuthorizedCustomerProfileVerifyRequest.class);
        assertNotNull(back);
        System.out.println("roundtrip ok: requestId=" + back.request().requestId()
                + " runId=" + back.authorizationContext().runId());
    }
}
