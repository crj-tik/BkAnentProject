package com.bkanent.common.rag;

import com.bkanent.common.authz.AuthzContract;
import com.bkanent.common.authz.AuthzSignature;
import com.bkanent.common.authz.AuthorizationContext;
import com.bkanent.common.authz.AuthorizationStatus;
import com.bkanent.common.authz.AuthzFreshness;
import com.bkanent.common.serialize.Fastjson2ConstructorSerialization;
import org.apache.dubbo.common.serialize.ObjectInput;
import org.apache.dubbo.common.serialize.ObjectOutput;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 使用仓库生产环境配置的 fastjson2ctor Dubbo 序列化器验证受限授权请求。
 */
class RagVerifyDubboSerializationTest {

    private AuthorizationContext context(long now, KeyPair authority, String nonce, String hash) throws Exception {
        var unsigned = new AuthorizationContext("123", 7, 2, hash,
                List.of("customer.read"), "listing-master-service", "req-1", "run-1", "sess-1",
                hash, hash, nonce, now, now + 60, now + 300,
                Map.of("BROKER", 1L), AuthzContract.ALGORITHM, "auth-test", null);
        return AuthzSignature.sign(unsigned, authority.getPrivate());
    }

    private RagContextAuthorizedCustomerProfileVerifyRequest request(AuthorizationContext ctx, AuthzFreshness fr) {
        return new RagContextAuthorizedCustomerProfileVerifyRequest(1,
                new RagCustomerProfileVerifyRequest(1, "req-2", 1L, "OWNER"), ctx, fr);
    }

    @Test
    void productionDubboSerializationRoundTrip() throws Exception {
        long now = Instant.now().getEpochSecond();
        KeyPair authority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String hash = "a".repeat(64);
        AuthorizationContext ctx = context(now, authority, nonce, hash);
        AuthzFreshness fr = AuthzSignature.sign(new AuthzFreshness("123", 7, 2, AuthorizationStatus.ACTIVE,
                hash, now, now + 30, nonce, AuthzContract.ALGORITHM, "auth-test", null), authority.getPrivate());
        RagContextAuthorizedCustomerProfileVerifyRequest req = request(ctx, fr);

        Fastjson2ConstructorSerialization serialization = new Fastjson2ConstructorSerialization();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ObjectOutput out = serialization.serialize(null, buffer);
        out.writeObject(req);
        out.flushBuffer();
        byte[] bytes = buffer.toByteArray();
        ObjectInput in = serialization.deserialize(null, new ByteArrayInputStream(bytes));
        RagContextAuthorizedCustomerProfileVerifyRequest back =
                in.readObject(RagContextAuthorizedCustomerProfileVerifyRequest.class);
        assertEquals("req-2", back.request().requestId());
        assertEquals("run-1", back.authorizationContext().runId());
        assertEquals(ctx, back.authorizationContext());
        assertEquals(fr, back.freshness());
    }
}
