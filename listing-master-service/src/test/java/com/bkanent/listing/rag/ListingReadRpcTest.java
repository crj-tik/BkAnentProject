package com.bkanent.listing.rag;

import com.bkanent.common.rag.*;
import com.bkanent.common.authz.*;
import com.bkanent.common.security.ServiceIdentityCrypto;
import com.bkanent.listing.config.ListingAuthorizationProperties;
import com.bkanent.listing.config.ListingRagProperties;
import com.bkanent.listing.converter.ListingConverter;
import com.bkanent.listing.entity.ListingEntity;
import com.bkanent.listing.rpc.ListingMasterRpcServiceImpl;
import com.bkanent.listing.service.ListingDomainService;
import com.bkanent.listing.service.ListingManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListingReadRpcTest {
    private static final String SECRET = "consumer-independent-read-test-secret-0123456789";
    private static final String AUTH_KEY_ID = "auth-forwarded-test";
    private RagAuthEnvelope sign(RagListingReadRequest request, RagActorMode mode, RagCredentialContext token) {
        long now = Instant.now().getEpochSecond();
        return RagRequestSigner.sign(new RagAuthEnvelope(1,"consumer-read","listing-master-service","customer-service",RagOperation.READ,
                request.requestId(),RagCanonicalHash.requestHash(request),mode,mode == RagActorMode.USER ? "7" : "customer-service",
                now,now+60,token == null ? null : RagCanonicalHash.credentialDigest(token),null),SECRET.getBytes(StandardCharsets.UTF_8));
    }
    private ListingRagIdentity identity() {
        var p = new ListingRagProperties(); var key = new ListingRagProperties.TrustedCaller();
        key.setCallerService("customer-service"); key.setSecret(SECRET); p.setTrustedCallers(Map.of("consumer-read",key));
        var marketing = new ListingRagProperties.TrustedCaller();
        marketing.setCallerService("marketing-content-service"); marketing.setSecret(SECRET);
        p.getTrustedCallers().put("marketing-read", marketing);
        return new ListingRagIdentity(p,new StaticListableBeanFactory().getBeanProvider(com.bkanent.common.rpc.RagPrincipalRpcService.class));
    }
    @Test void serviceReadAndTamperedOrMissingUserProofAreRejectedBeforeSql() {
        var domain = mock(ListingDomainService.class); var management = mock(ListingManagementService.class);
        var rpc = new ListingMasterRpcServiceImpl(management,identity(),domain,new ListingConverter());
        var request = new RagListingReadRequest(1,"read",11L,null);
        assertEquals(RagErrorCode.FORBIDDEN, assertThrows(RagException.class,
                () -> rpc.read(request,sign(request,RagActorMode.SERVICE,null),null)).code());
        var token = new RagCredentialContext("real-access");
        assertThrows(RagException.class, () -> rpc.read(new RagListingReadRequest(1,"read",12L,null),sign(request,RagActorMode.USER,token),token));
        assertThrows(RagException.class, () -> rpc.read(request,sign(request,RagActorMode.USER,token),new RagCredentialContext("other-token")));
        assertThrows(RagException.class, () -> rpc.read(request,null,null));
        assertThrows(RagException.class, () -> rpc.getListingById(11L));
        verifyNoInteractions(management,domain);
    }
    @Test void userReadUsesDedicatedContextAndActorCannotBeSpoofed() {
        var identity = mock(ListingRagIdentity.class); var management = mock(ListingManagementService.class);
        var token = new RagCredentialContext("real-access");
        when(identity.requireReader(token)).thenReturn(new AuthRagPrincipal(7L,"ADMIN",true,true,Long.MAX_VALUE));
        when(management.searchListingSummaries("region")).thenAnswer(call -> {
            assertSame(token,ListingRagCredentialContext.current()); return List.of();
        });
        var rpc = new ListingMasterRpcServiceImpl(management,identity,mock(ListingDomainService.class),new ListingConverter());
        var request = new RagListingReadRequest(1,"read",null,"region");
        assertTrue(rpc.read(request,sign(request,RagActorMode.USER,token),token).isEmpty());
        assertThrows(RagException.class,ListingRagCredentialContext::current);
        when(identity.requireReader(token)).thenReturn(new AuthRagPrincipal(8L,"ADMIN",true,true,Long.MAX_VALUE));
        assertThrows(RagException.class, () -> rpc.read(request,sign(request,RagActorMode.USER,token),token));
        verify(management,times(1)).searchListingSummaries("region");
    }

    @Test void forwardedSignedContextIsLocallyVerifiedAndUsedForListingRead() throws Exception {
        var signing = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var fixture = authorization(signing, List.of("listing.read"), List.of("listing.read"));
        var request = new RagListingReadRequest(1, "internal-read", null, "region");
        var authorized = new RagAuthorizedListingReadRequest(1, request,
                AuthzCanonicalHash.signedResponseHash(fixture.response()));
        var proof = signAuthorized(authorized, fixture.response(), "7");
        var authorizationProperties = new ListingAuthorizationProperties();
        authorizationProperties.setAuthorityPublicKeys(Map.of(AUTH_KEY_ID,
                ServiceIdentityCrypto.encodePublicKey(signing.getPublic())));
        var management = mock(ListingManagementService.class);
        when(management.searchListingSummaries("region")).thenAnswer(call -> {
            assertTrue(AuthzRequestScope.present());
            assertEquals(7L, AuthzRequestScope.actorId());
            assertEquals(41L, AuthzRequestScope.requireBrokerIdentity());
            return List.of();
        });
        var rpc = new ListingMasterRpcServiceImpl(management, identity(), mock(ListingDomainService.class),
                new ListingConverter(), authorizationProperties);

        assertTrue(rpc.readAuthorized(authorized, proof, fixture.response()).isEmpty());
        assertFalse(AuthzRequestScope.present());
        verify(management).searchListingSummaries("region");
    }

    @Test void forwardedContextRejectsSpoofedActorAndMissingListingPermissionBeforeRead() throws Exception {
        var signing = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var fixture = authorization(signing, List.of("listing.read"), List.of("listing.read"));
        var request = new RagListingReadRequest(1, "internal-read", null, "region");
        var authorized = new RagAuthorizedListingReadRequest(1, request,
                AuthzCanonicalHash.signedResponseHash(fixture.response()));
        var authorizationProperties = new ListingAuthorizationProperties();
        authorizationProperties.setAuthorityPublicKeys(Map.of(AUTH_KEY_ID,
                ServiceIdentityCrypto.encodePublicKey(signing.getPublic())));
        var management = mock(ListingManagementService.class);
        var rpc = new ListingMasterRpcServiceImpl(management, identity(), mock(ListingDomainService.class),
                new ListingConverter(), authorizationProperties);

        assertEquals(RagErrorCode.AUTHENTICATION_FAILED, assertThrows(RagException.class,
                () -> rpc.readAuthorized(authorized, signAuthorized(authorized, fixture.response(), "8"), fixture.response())).code());
        var changedQuery = new RagAuthorizedListingReadRequest(1,
                new RagListingReadRequest(1, "internal-read", null, "different-region"),
                authorized.authorizationEvidenceHash());
        assertEquals(RagErrorCode.AUTHENTICATION_FAILED, assertThrows(RagException.class,
                () -> rpc.readAuthorized(changedQuery, signAuthorized(authorized, fixture.response(), "7"), fixture.response())).code());

        var restricted = authorization(signing, List.of("customer.match.own"), List.of("customer.match.own"));
        var restrictedRead = new RagAuthorizedListingReadRequest(1, request,
                AuthzCanonicalHash.signedResponseHash(restricted.response()));
        assertEquals(RagErrorCode.FORBIDDEN, assertThrows(RagException.class,
                () -> rpc.readAuthorized(restrictedRead,
                        signAuthorized(restrictedRead, restricted.response(), "7"), restricted.response())).code());
        verifyNoInteractions(management);
    }

    @Test void a2aExecutionContextAndBodyProofAuthorizeOnlyCurrentListingSummary() throws Exception {
        var signing = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var context = executionContext(signing, List.of("listing.read"));
        var request = new RagContextAuthorizedListingReadRequest(1,
                new RagListingReadRequest(1, "internal-a2a-read", 11L, null), context.context(), context.freshness());
        var authorizationProperties = new ListingAuthorizationProperties();
        authorizationProperties.setEnabled(true);
        authorizationProperties.setAuthorityPublicKeys(Map.of(AUTH_KEY_ID,
                ServiceIdentityCrypto.encodePublicKey(signing.getPublic())));
        var domain = mock(ListingDomainService.class);
        var listing = new ListingEntity(); listing.setId(11L); listing.setDeleted(0); listing.setStatus("ON_SALE");
        when(domain.getById(11L)).thenReturn(listing);
        var rpc = new ListingMasterRpcServiceImpl(mock(ListingManagementService.class), identity(), domain,
                new ListingConverter(), authorizationProperties);

        var results = rpc.readAuthorizedContext(request, signContextAuthorized(request, "42"));
        assertEquals(1, results.size());
        assertEquals(11L, results.get(0).id());
        verify(domain).getById(11L);

        listing.setStatus("OFFLINE");
        assertTrue(rpc.readAuthorizedContext(request, signContextAuthorized(request, "42")).isEmpty());
    }

    @Test void a2aExecutionContextRejectsTamperingAndRequiresExactListingReadBeforeSql() throws Exception {
        var signing = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var authorized = executionContext(signing, List.of("listing.read"));
        var request = new RagContextAuthorizedListingReadRequest(1,
                new RagListingReadRequest(1, "internal-a2a-read", 11L, null), authorized.context(), authorized.freshness());
        var authorizationProperties = new ListingAuthorizationProperties();
        authorizationProperties.setEnabled(true);
        authorizationProperties.setAuthorityPublicKeys(Map.of(AUTH_KEY_ID,
                ServiceIdentityCrypto.encodePublicKey(signing.getPublic())));
        var domain = mock(ListingDomainService.class);
        var rpc = new ListingMasterRpcServiceImpl(mock(ListingManagementService.class), identity(), domain,
                new ListingConverter(), authorizationProperties);

        assertThrows(RagException.class, () -> rpc.readAuthorizedContext(request,
                signContextAuthorized(request, "43")));
        var alteredTarget = new RagContextAuthorizedListingReadRequest(1,
                new RagListingReadRequest(1, request.request().requestId(), 12L, null),
                request.authorizationContext(), request.freshness());
        var originalProof = signContextAuthorized(request, "42");
        assertEquals(RagErrorCode.AUTHENTICATION_FAILED, assertThrows(RagException.class,
                () -> rpc.readAuthorizedContext(alteredTarget, originalProof)).code());
        var restricted = executionContext(signing, List.of("customer.match.own"));
        var noListingPermission = new RagContextAuthorizedListingReadRequest(1, request.request(),
                restricted.context(), restricted.freshness());
        assertEquals(RagErrorCode.FORBIDDEN, assertThrows(RagException.class,
                () -> rpc.readAuthorizedContext(noListingPermission,
                        signContextAuthorized(noListingPermission, "42"))).code());
        verifyNoInteractions(domain);
    }

    private AuthFixture authorization(KeyPair key, List<String> permissions, List<String> operations) {
        long now = Instant.now().getEpochSecond();
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String hash = "a".repeat(64);
        var identities = Map.of("BROKER", 41L);
        var snapshot = AuthzSignature.sign(new AuthorizationSnapshot("7", 9, 3, false,
                permissions, null, identities, AuthzContract.ALGORITHM, AUTH_KEY_ID, null), key.getPrivate());
        String permissionHash = AuthzCanonicalHash.snapshotHash(snapshot);
        var context = AuthzSignature.sign(new AuthorizationContext("7", 9, 0, permissionHash, operations,
                "customer-service", "customer-request", null, null, hash, hash, nonce,
                now, now + 60, now + 300, identities, AuthzContract.ALGORITHM, AUTH_KEY_ID, null), key.getPrivate());
        var freshness = AuthzSignature.sign(new AuthzFreshness("7", 9, 0, AuthorizationStatus.ACTIVE,
                permissionHash, now, now + 30, nonce, AuthzContract.ALGORITHM, AUTH_KEY_ID, null), key.getPrivate());
        return new AuthFixture(new AuthzResponse(snapshot, context, freshness));
    }

    private RagAuthEnvelope signAuthorized(RagAuthorizedListingReadRequest request, AuthzResponse authorization,
                                           String actorId) {
        long now = Instant.now().getEpochSecond();
        return RagRequestSigner.sign(new RagAuthEnvelope(1, "consumer-read", "listing-master-service",
                "customer-service", RagOperation.READ, request.request().requestId(),
                RagCanonicalHash.requestHash(request), RagActorMode.USER, actorId, now, now + 60, null, null),
                SECRET.getBytes(StandardCharsets.UTF_8));
    }

    private ExecutionFixture executionContext(KeyPair key, List<String> operations) {
        long now = Instant.now().getEpochSecond();
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String hash = "d".repeat(64);
        var context = AuthzSignature.sign(new AuthorizationContext("42", 10, 1, hash, operations,
                "agent-service", "parent-request", "run-1", "session-1", "e".repeat(64),
                "f".repeat(64), nonce, now, now + 60, now + 300, Map.of("BROKER", 84L),
                AuthzContract.ALGORITHM, AUTH_KEY_ID, null), key.getPrivate());
        var freshness = AuthzSignature.sign(new AuthzFreshness("42", 10, 1, AuthorizationStatus.ACTIVE,
                hash, now, now + 30, nonce, AuthzContract.ALGORITHM, AUTH_KEY_ID, null), key.getPrivate());
        return new ExecutionFixture(context, freshness);
    }

    private RagAuthEnvelope signContextAuthorized(RagContextAuthorizedListingReadRequest request, String actorId) {
        long now = Instant.now().getEpochSecond();
        long expiry = Math.min(now + 30, request.freshness().notAfterEpochSecond());
        return RagRequestSigner.sign(new RagAuthEnvelope(1, "marketing-read", "listing-master-service",
                "marketing-content-service", RagOperation.READ, request.request().requestId(),
                RagCanonicalHash.requestHash(request), RagActorMode.USER, actorId, now, expiry, null, null),
                SECRET.getBytes(StandardCharsets.UTF_8));
    }

    private record AuthFixture(AuthzResponse response) { }
    private record ExecutionFixture(AuthorizationContext context, AuthzFreshness freshness) { }
}
