package com.bkanent.listing.rag;

import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.RagPrincipalRpcService;
import com.bkanent.listing.config.ListingRagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ListingRagIdentityTest {
    static final String DOMAIN = "domain-independent-secret-01234567890123456789";
    static final String AUTH = "auth-independent-response-01234567890123456789";
    static ListingRagProperties properties() {
        ListingRagProperties p = new ListingRagProperties(); p.setSecret(DOMAIN); p.setAuthResponseSecret(AUTH); return p;
    }
    static AuthRagPrincipal response(RagCredentialContext credentials, RagAuthEnvelope request, String role, boolean active) {
        long now = Instant.now().getEpochSecond();
        AuthRagPrincipal base = new AuthRagPrincipal(7L, role, active, true, now + 3600);
        RagAuthEnvelope proof = RagRequestSigner.sign(new RagAuthEnvelope(1, "auth-response-current", "listing-master-service",
                "auth-service", RagOperation.RESOLVE_PRINCIPAL_RESPONSE, request.requestId(), RagCanonicalHash.principalHash(base),
                RagActorMode.USER, "7", now, now + 60, RagCanonicalHash.credentialDigest(credentials), null), AUTH.getBytes(StandardCharsets.UTF_8));
        return new AuthRagPrincipal(base.userId(), base.roleCode(), base.accountActive(), base.roleActive(), base.expiresAtEpochSecond(), proof);
    }
    @Test void requestUsesIndependentDomainKeyAndCredentialHashAndRealRole() {
        var p = properties(); p.setReadRoleCodes(Set.of("BROKER"));
        var factory = new StaticListableBeanFactory();
        factory.addBean("auth", (RagPrincipalRpcService) (credentials, request) -> {
            assertEquals("listing-master-service", request.callerService());
            assertEquals("auth-service", request.audience());
            assertEquals(RagCanonicalHash.credentialDigest(credentials), request.requestHash());
            RagRequestSigner.verify(request, request.requestHash(), "auth-service", RagOperation.RESOLVE_PRINCIPAL,
                    request.requestId(), credentials, new RagSigningKey("listing-current", "listing-master-service", null,
                            Set.of(RagOperation.RESOLVE_PRINCIPAL), DOMAIN.getBytes(StandardCharsets.UTF_8)), Instant.now());
            return response(credentials, request, "BROKER", true);
        });
        var identity = new ListingRagIdentity(p, factory.getBeanProvider(RagPrincipalRpcService.class));
        assertEquals(7L, identity.requireReader(new RagCredentialContext("access-token")).userId());
        assertEquals(RagErrorCode.FORBIDDEN, assertThrows(RagException.class,
                () -> identity.requireAdmin(new RagCredentialContext("access-token"))).code());
    }
    @Test void unsignedOrInactivePrincipalFailsClosedAndDefaultPolicyGrantsNothing() {
        var factory = new StaticListableBeanFactory();
        factory.addBean("auth", (RagPrincipalRpcService) (c, a) -> new AuthRagPrincipal(7L, "ADMIN", true, true, Long.MAX_VALUE));
        var identity = new ListingRagIdentity(properties(), factory.getBeanProvider(RagPrincipalRpcService.class));
        assertEquals(RagErrorCode.AUTHENTICATION_FAILED, assertThrows(RagException.class, () -> identity.resolve(new RagCredentialContext("token"))).code());
        factory.addBean("auth", (RagPrincipalRpcService) (c, a) -> response(c, a, "ADMIN", false));
        assertEquals(RagErrorCode.FORBIDDEN, assertThrows(RagException.class, () -> identity.resolve(new RagCredentialContext("token"))).code());
        factory.addBean("auth", (RagPrincipalRpcService) (c, a) -> response(c, a, "ADMIN", true));
        assertEquals(RagErrorCode.FORBIDDEN, assertThrows(RagException.class, () -> identity.requireAdmin(new RagCredentialContext("token"))).code());
    }
    @Test void credentialIsScopedAndNotInheritedOrGuessable() throws Exception {
        assertThrows(RagException.class, ListingRagCredentialContext::current);
        ListingRagCredentialContext.withCredential(new RagCredentialContext("token"), () -> {
            assertEquals("token", ListingRagCredentialContext.current().accessToken());
            Thread thread = new Thread(() -> assertThrows(RagException.class, ListingRagCredentialContext::current));
            thread.start(); try { thread.join(); } catch (InterruptedException e) { throw new AssertionError(e); }
            return null;
        });
        assertThrows(RagException.class, ListingRagCredentialContext::current);
        assertThrows(RagException.class, () -> ListingRagCredentialContext.bearer("gateway-userId:7"));
    }
}
