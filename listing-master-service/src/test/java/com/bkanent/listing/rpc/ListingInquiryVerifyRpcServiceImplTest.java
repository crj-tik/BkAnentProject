package com.bkanent.listing.rpc;

import com.bkanent.common.authz.AuthzContract;
import com.bkanent.common.authz.AuthzFreshness;
import com.bkanent.common.authz.AuthorizationContext;
import com.bkanent.common.authz.AuthorizationStatus;
import com.bkanent.common.authz.AuthzSignature;
import com.bkanent.common.rag.RagActorMode;
import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagContextAuthorizedListingInquiryVerifyRequest;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rag.RagListingInquiryVerifyRequest;
import com.bkanent.common.rag.RagOperation;
import com.bkanent.common.rag.RagRequestSigner;
import com.bkanent.common.rpc.ListingInquiryVerifyResponse;
import com.bkanent.common.security.ServiceIdentityCrypto;
import com.bkanent.listing.config.ListingAuthorizationProperties;
import com.bkanent.listing.config.ListingRagProperties;
import com.bkanent.listing.entity.ListingEntity;
import com.bkanent.listing.rag.ListingRagIdentity;
import com.bkanent.listing.rag.ListingVerifyContractHash;
import com.bkanent.listing.service.ListingDomainService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 咨询房源验证 RPC：在售可读返回状态与版本；不可访问统一 NOT_ACCESSIBLE；验签失败拒绝。 */
class ListingInquiryVerifyRpcServiceImplTest {

    private static final String SECRET = "customer-inquiry-verify-test-secret-0123456789";
    private static final String KEY_ID = "customer-inquiry-verify-current";
    private static final String AUTH_KEY_ID = "auth-inquiry-test";

    private ListingRagIdentity identity() {
        var properties = new ListingRagProperties();
        var caller = new ListingRagProperties.TrustedCaller();
        caller.setCallerService("customer-service");
        caller.setSecret(SECRET);
        properties.setTrustedCallers(Map.of(KEY_ID, caller));
        return new ListingRagIdentity(properties,
                new StaticListableBeanFactory().getBeanProvider(com.bkanent.common.rpc.RagPrincipalRpcService.class));
    }

    private record Execution(AuthorizationContext context, AuthzFreshness freshness) {
    }

    private Execution execution(KeyPair authority, List<String> operations) {
        return execution(authority, operations, "agent-service");
    }

    /** audience 可选：agent-service（A2A 运行）或 customer-service（网关入口绑定，双收）。 */
    private Execution execution(KeyPair authority, List<String> operations, String audience) {
        long now = Instant.now().getEpochSecond();
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String hash = "d".repeat(64);
        var context = AuthzSignature.sign(new AuthorizationContext("42", 10, 1, hash, operations,
                audience, "parent-request", "run-1", "session-1", "e".repeat(64),
                "f".repeat(64), nonce, now, now + 60, now + 300, Map.of("BROKER", 84L),
                AuthzContract.ALGORITHM, AUTH_KEY_ID, null), authority.getPrivate());
        var freshness = AuthzSignature.sign(new AuthzFreshness("42", 10, 1, AuthorizationStatus.ACTIVE,
                hash, now, now + 30, nonce, AuthzContract.ALGORITHM, AUTH_KEY_ID, null), authority.getPrivate());
        return new Execution(context, freshness);
    }

    private RagAuthEnvelope sign(RagContextAuthorizedListingInquiryVerifyRequest request, String actorId) {
        long now = Instant.now().getEpochSecond();
        long expiry = Math.min(now + 30, request.freshness().notAfterEpochSecond());
        return RagRequestSigner.sign(new RagAuthEnvelope(1, KEY_ID, ListingRagIdentity.SERVICE,
                "customer-service", RagOperation.READ, request.request().requestId(),
                ListingVerifyContractHash.listingInquiryVerifyRequestHash(request), RagActorMode.USER, actorId, now, expiry, null, null),
                SECRET.getBytes(StandardCharsets.UTF_8));
    }

    private ListingInquiryVerifyRpcServiceImpl rpc(KeyPair authority, ListingDomainService domain,
                                                   boolean enabled) {
        var properties = new ListingAuthorizationProperties();
        properties.setEnabled(enabled);
        properties.setAuthorityPublicKeys(Map.of(AUTH_KEY_ID,
                ServiceIdentityCrypto.encodePublicKey(authority.getPublic())));
        return new ListingInquiryVerifyRpcServiceImpl(identity(), domain, properties);
    }

    private ListingEntity listing(String status, Long version) {
        var entity = new ListingEntity();
        entity.setId(11L);
        entity.setBrokerId(84L);
        entity.setDeleted(0);
        entity.setStatus(status);
        entity.setVersion(version);
        return entity;
    }

    @Test
    void onSaleListingIsAccessibleWithStatusAndVersion() throws Exception {
        var authority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var execution = execution(authority, List.of("listing.read"));
        var request = new RagContextAuthorizedListingInquiryVerifyRequest(1,
                new RagListingInquiryVerifyRequest(1, "inquiry-verify-11", 11L),
                execution.context(), execution.freshness());
        var domain = mock(ListingDomainService.class);
        when(domain.getById(11L)).thenReturn(listing("ON_SALE", 7L));
        var response = rpc(authority, domain, true)
                .verifyInquiryListingContext(request, sign(request, "42"));
        assertEquals(ListingInquiryVerifyResponse.ACCESSIBLE, response.result());
        assertEquals("inquiry-verify-11", response.requestId());
        assertEquals(11L, response.listingId());
        assertEquals("ON_SALE", response.status());
        assertEquals("7", response.listingVersion());
    }

    @Test
    void nullVersionIsReportedAsZero() throws Exception {
        var authority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var execution = execution(authority, List.of("listing.read"));
        var request = new RagContextAuthorizedListingInquiryVerifyRequest(1,
                new RagListingInquiryVerifyRequest(1, "inquiry-verify-11", 11L),
                execution.context(), execution.freshness());
        var domain = mock(ListingDomainService.class);
        when(domain.getById(11L)).thenReturn(listing("ON_SALE", null));
        var response = rpc(authority, domain, true)
                .verifyInquiryListingContext(request, sign(request, "42"));
        assertEquals(ListingInquiryVerifyResponse.ACCESSIBLE, response.result());
        assertEquals("0", response.listingVersion());
    }

    @Test
    void offlineMissingOrDeletedListingsAreNotAccessibleWithoutReasonDetail() throws Exception {
        var authority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var execution = execution(authority, List.of("listing.read"));
        var request = new RagContextAuthorizedListingInquiryVerifyRequest(1,
                new RagListingInquiryVerifyRequest(1, "inquiry-verify-11", 11L),
                execution.context(), execution.freshness());
        var domain = mock(ListingDomainService.class);
        var service = rpc(authority, domain, true);
        var offline = listing("OFFLINE", 3L);
        when(domain.getById(11L)).thenReturn(offline);
        var offlineResponse = service.verifyInquiryListingContext(request, sign(request, "42"));
        assertEquals(ListingInquiryVerifyResponse.NOT_ACCESSIBLE, offlineResponse.result());
        assertNull(offlineResponse.listingId());
        assertNull(offlineResponse.status());
        assertNull(offlineResponse.listingVersion());

        when(domain.getById(11L)).thenReturn(null);
        assertEquals(ListingInquiryVerifyResponse.NOT_ACCESSIBLE,
                service.verifyInquiryListingContext(request, sign(request, "42")).result());

        var deleted = listing("ON_SALE", 3L);
        deleted.setDeleted(1);
        when(domain.getById(11L)).thenReturn(deleted);
        assertEquals(ListingInquiryVerifyResponse.NOT_ACCESSIBLE,
                service.verifyInquiryListingContext(request, sign(request, "42")).result());
    }

    @Test
    void disabledAuthorizationOrTamperedProofRejectsBeforeSql() throws Exception {
        var authority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var execution = execution(authority, List.of("listing.read"));
        var request = new RagContextAuthorizedListingInquiryVerifyRequest(1,
                new RagListingInquiryVerifyRequest(1, "inquiry-verify-11", 11L),
                execution.context(), execution.freshness());
        var domain = mock(ListingDomainService.class);
        assertEquals(RagErrorCode.AUTHENTICATION_FAILED, assertThrows(RagException.class,
                () -> rpc(authority, domain, false).verifyInquiryListingContext(request, sign(request, "42"))).code());
        assertEquals(RagErrorCode.AUTHENTICATION_FAILED, assertThrows(RagException.class,
                () -> rpc(authority, domain, true).verifyInquiryListingContext(request, sign(request, "43"))).code());
        var restricted = execution(authority, List.of("customer.match.own"));
        var noListingPermission = new RagContextAuthorizedListingInquiryVerifyRequest(1,
                new RagListingInquiryVerifyRequest(1, "inquiry-verify-11", 11L),
                restricted.context(), restricted.freshness());
        assertEquals(RagErrorCode.FORBIDDEN, assertThrows(RagException.class,
                () -> rpc(authority, domain, true).verifyInquiryListingContext(noListingPermission,
                        sign(noListingPermission, "42"))).code());
        verifyNoInteractions(domain);
    }

    @Test
    void gatewayBoundCustomerAudienceIsAlsoAccepted() throws Exception {
        var authority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        // 网关入口绑定上下文（受众 customer-service）与 A2A 运行上下文（受众 agent-service）双收。
        var execution = execution(authority, List.of("listing.read"), "customer-service");
        var request = new RagContextAuthorizedListingInquiryVerifyRequest(1,
                new RagListingInquiryVerifyRequest(1, "inquiry-verify-11", 11L),
                execution.context(), execution.freshness());
        var domain = mock(ListingDomainService.class);
        when(domain.getById(11L)).thenReturn(listing("ON_SALE", 5L));
        var response = rpc(authority, domain, true)
                .verifyInquiryListingContext(request, sign(request, "42"));
        assertEquals(ListingInquiryVerifyResponse.ACCESSIBLE, response.result());
        assertEquals("5", response.listingVersion());
    }

    @Test
    void contractHashEncodingMatchesTheCustomerSideIndependentEncoding() throws Exception {
        var authority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var execution = execution(authority, List.of("listing.read"));
        var inquiry = new RagContextAuthorizedListingInquiryVerifyRequest(1,
                new RagListingInquiryVerifyRequest(1, "inquiry-verify-11", 11L),
                execution.context(), execution.freshness());
        // 独立复刻 customer 侧 CustomerVerifyContractHash 编码（域分隔 + 长度前缀 + 类型标记），
        // 防止两侧实现漂移（KI-249）。
        assertEquals(independentInquiryHash(inquiry),
                ListingVerifyContractHash.listingInquiryVerifyRequestHash(inquiry));

        var profileAuthority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var profileExecution = execution(profileAuthority, List.of("customer.read"));
        var profile = new com.bkanent.common.rag.RagContextAuthorizedCustomerProfileVerifyRequest(1,
                new com.bkanent.common.rag.RagCustomerProfileVerifyRequest(1, "owner-verify-55", 55L, "OWNER"),
                profileExecution.context(), profileExecution.freshness());
        assertEquals(independentProfileHash(profile),
                ListingVerifyContractHash.customerProfileVerifyRequestHash(profile));
    }

    private static String independentInquiryHash(RagContextAuthorizedListingInquiryVerifyRequest request)
            throws Exception {
        return independentDigest("rag.context-authorized-listing-inquiry-verify.v1",
                request.request().contractVersion(), request.request().requestId(),
                request.request().listingId(),
                com.bkanent.common.authz.AuthzCanonicalHash.signedContextHash(request.authorizationContext()),
                com.bkanent.common.authz.AuthzCanonicalHash.signedFreshnessHash(request.freshness()));
    }

    private static String independentProfileHash(
            com.bkanent.common.rag.RagContextAuthorizedCustomerProfileVerifyRequest request) throws Exception {
        return independentDigest("rag.context-authorized-customer-profile-verify.v1",
                request.request().contractVersion(), request.request().requestId(),
                request.request().customerId(), request.request().expectedProfileType(),
                com.bkanent.common.authz.AuthzCanonicalHash.signedContextHash(request.authorizationContext()),
                com.bkanent.common.authz.AuthzCanonicalHash.signedFreshnessHash(request.freshness()));
    }

    private static String independentDigest(String domain, Object... fields) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var output = new java.io.DataOutputStream(bytes)) {
            byte[] encoded = domain.getBytes(StandardCharsets.UTF_8);
            output.writeInt(encoded.length);
            output.write(encoded);
            for (Object field : fields) {
                if (field instanceof Integer value) {
                    output.writeByte(2);
                    output.writeInt(value);
                } else if (field instanceof Long value) {
                    output.writeByte(3);
                    output.writeLong(value);
                } else {
                    output.writeByte(1);
                    byte[] text = ((String) field).getBytes(StandardCharsets.UTF_8);
                    output.writeInt(text.length);
                    output.write(text);
                }
            }
        }
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    }
}
