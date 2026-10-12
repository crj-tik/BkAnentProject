package com.bkanent.memory.service;

import com.bkanent.common.rag.*;
import com.bkanent.memory.config.MemoryRagProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MemoryRagSecurityTest {
    @Test
    void signedAuthResponseProvidesRealOwnerAndManagedRolesOnly() {
        var f = new MemoryRagTestSupport();
        var principal = f.security.authenticate(f.credentials);
        assertEquals(42L, principal.userId());
        assertThrows(RagException.class, () -> f.security.requireOwner(principal, "43"));
        assertThrows(RagException.class, () -> f.security.requireAdmin(MemoryRagTestSupport.principal("AGENT")));
        f.properties.setConstraintAdminRoles(Set.of());
        assertThrows(RagException.class, () -> f.security.requireAdmin(principal));
        f.properties.setConstraintReadRoles(Set.of());
        assertThrows(RagException.class, () -> f.security.requireConstraintRead(principal));
    }
    @Test
    void missingKeysUnsignedAuthAndServiceModeSearchDeny() {
        var properties = new MemoryRagProperties();
        var calls = new AtomicInteger();
        var security = new MemoryRagSecurity(properties, (c, a) -> { calls.incrementAndGet(); return MemoryRagTestSupport.principal("ADMIN"); });
        assertThrows(RagException.class, () -> security.authenticate(new RagCredentialContext("token")));
        assertEquals(0, calls.get());
        properties.setSigningSecret(MemoryRagTestSupport.MEMORY_SECRET);
        assertThrows(RagException.class, () -> security.authenticate(new RagCredentialContext("token")));
        var f = new MemoryRagTestSupport();
        var business = new MemoryRagSearchRequest(RagSearchRequest.defaults("request", "memory.preference", "query"), 5, null, null);
        assertThrows(RagException.class, () -> f.security.verifyCaller(business, "request", null, f.credentials));
    }
    @Test
    void verifiedCallerActorCannotDisagreeWithPrincipalOrBindDifferentPayload() {
        var f = new MemoryRagTestSupport();
        var caller = new MemoryRagProperties.TrustedCaller(); caller.setCallerService("agent-service"); caller.setSecret("agent-test-independent-secret-32-bytes");
        f.properties.setTrustedCallers(java.util.Map.of("agent-current", caller));
        var business = new MemoryRagSearchRequest(RagSearchRequest.defaults("request", "memory.preference", "query"), 5, null, null);
        long now = Instant.now().getEpochSecond();
        var unsigned = new RagAuthEnvelope(1, "agent-current", "memory-service", "agent-service", RagOperation.SEARCH, "request", RagCanonicalHash.requestHash(business), RagActorMode.USER, "99", now, now + 60, RagCanonicalHash.credentialDigest(f.credentials), null);
        var signed = RagRequestSigner.sign(unsigned, caller.getSecret().getBytes(StandardCharsets.UTF_8));
        assertEquals(RagErrorCode.FORBIDDEN, assertThrows(RagException.class, () -> f.security.verifyCaller(business, "request", signed, f.credentials)).code());
    }
    @Test
    void authRequestUsesMemoryIdentityNotSharedRagKey() {
        var f = new MemoryRagTestSupport();
        var security = new MemoryRagSecurity(f.properties, (cred, request) -> {
            assertEquals("memory-service", request.callerService());
            assertEquals("memory-service", request.actorId());
            assertEquals("memory-current", request.keyId());
            assertEquals(RagActorMode.SERVICE, request.actorMode());
            assertEquals(RagCanonicalHash.credentialDigest(cred), request.requestHash());
            return MemoryRagTestSupport.principal("ADMIN");
        });
        assertThrows(RagException.class, () -> security.authenticate(f.credentials));
    }
}
