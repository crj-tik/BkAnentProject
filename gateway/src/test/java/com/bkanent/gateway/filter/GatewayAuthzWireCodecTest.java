package com.bkanent.gateway.filter;

import com.bkanent.common.authz.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Transport unit fixtures live in gateway; no network or authority implementation is involved. */
class GatewayAuthzWireCodecTest {
    @Test
    void bindingRoundTripsAllCorrelationsAndRetainsDuplicatePairs() {
        RequestBinding binding = binding();
        String wire = AuthzWireCodec.encodeBinding(binding);
        assertFalse(wire.contains("="));
        assertEquals(binding, AuthzWireCodec.decodeBinding(wire));
        assertEquals(3, binding.query().size());
        assertEquals(binding.query(), AuthzWireCodec.parseQuery("a=1&x=2&a=1"));
    }

    @Test
    void duplicateUnknownTrailingAndNullPrimitiveJsonReject() {
        String json = json(binding());
        assertThrows(AuthzException.class, () -> AuthzWireCodec.decodeBinding(encoded(
                json.replace("\"method\":\"POST\"", "\"method\":\"POST\",\"method\":\"GET\""))));
        assertThrows(AuthzException.class, () -> AuthzWireCodec.decodeBinding(encoded(
                json.substring(0, json.length() - 1) + ",\"actorId\":\"999\"}")));
        assertThrows(AuthzException.class, () -> AuthzWireCodec.decodeBinding(encoded(json + " {}")));
        assertThrows(AuthzException.class, () -> AuthzWireCodec.decodeBinding(encoded(
                json.replace("\"issuedAtEpochSecond\":1700000000", "\"issuedAtEpochSecond\":null"))));
        assertThrows(AuthzException.class, () -> AuthzWireCodec.decodeResponse(encoded("null")));
    }

    @Test
    void headerBoundsAlphabetPaddingAndNonCanonicalTailReject() {
        assertThrows(AuthzException.class, () -> AuthzWireCodec.decodeBinding("A".repeat(
                AuthzWireCodec.MAX_ENCODED_HEADER_CHARS + 1)));
        assertThrows(AuthzException.class, () -> AuthzWireCodec.decodeBinding(AuthzWireCodec.encodeBinding(binding()) + "="));
        assertThrows(AuthzException.class, () -> AuthzWireCodec.decodeBinding("e30/"));
        assertThrows(AuthzException.class, () -> AuthzWireCodec.decodeBinding("Zh"));
    }

    @Test
    void queryAndPathCanonicalizationRejectAliasesButAllowUtf8AndEmptyValues() {
        assertEquals(List.of(new RequestBinding.QueryParameter("a", ""),
                        new RequestBinding.QueryParameter("q", "你好")),
                AuthzWireCodec.parseQuery("q=%E4%BD%A0%E5%A5%BD&a="));
        for (String raw : List.of("userId=1&USER_ID=2", "actor=1&actor=2", "q=%252f",
                "q=%2B", "q=%C0%AF", "q=a+b", "q=1&&r=2", "q")) {
            assertThrows(AuthzException.class, () -> AuthzWireCodec.parseQuery(raw));
        }
        for (String raw : List.of("//agent/run", "/a/./b", "/a/%2e/b", "/a;v=1", "/a\\b")) {
            assertThrows(AuthzException.class, () -> AuthzWireCodec.validatePath(raw));
        }
        assertEquals("/agent/run", AuthzWireCodec.validatePath("/agent/run"));
    }

    private RequestBinding binding() {
        return new RequestBinding("gateway", "agent-service", "POST", "/agent/run", "/agent/run",
                AuthzWireCodec.parseQuery("x=2&a=1&a=1"), "gateway-request", null, null,
                AuthzCanonicalHash.bodyHash(new byte[0]), AuthzCanonicalHash.bodyHash(new byte[0]),
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]), 1700000000L, 1700000060L);
    }

    private String json(RequestBinding binding) {
        return new String(Base64.getUrlDecoder().decode(AuthzWireCodec.encodeBinding(binding)), StandardCharsets.UTF_8);
    }

    private String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
