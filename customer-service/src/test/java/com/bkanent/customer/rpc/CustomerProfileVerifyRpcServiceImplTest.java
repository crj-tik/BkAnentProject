package com.bkanent.customer.rpc;

import com.bkanent.common.authz.AuthzContract;
import com.bkanent.common.authz.AuthzSignature;
import com.bkanent.common.authz.AuthorizationContext;
import com.bkanent.common.authz.AuthorizationStatus;
import com.bkanent.common.authz.AuthzFreshness;
import com.bkanent.common.rag.RagActorMode;
import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagContextAuthorizedCustomerProfileVerifyRequest;
import com.bkanent.common.rag.RagCustomerProfileVerifyRequest;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rag.RagOperation;
import com.bkanent.common.rag.RagRequestSigner;
import com.bkanent.common.rpc.CustomerProfileVerifyResponse;
import com.bkanent.common.security.ServiceIdentityCrypto;
import com.bkanent.customer.config.CustomerAuthorizationProperties;
import com.bkanent.customer.config.CustomerProfileVerifyProperties;
import com.bkanent.customer.entity.CustomerEntity;
import com.bkanent.customer.service.CustomerDomainService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Listing→Customer 卖方档案验证 RPC 提供方测试：
 * VERIFIED / NOT_FOUND / INVALID_PROFILE_TYPE / NOT_ACCESSIBLE 分支与验签失败。
 */
class CustomerProfileVerifyRpcServiceImplTest {

    private static final String CALLER_KEY_ID = "customer-verify-current";
    private static final String CALLER_SECRET = "listing-customer-verify-test-secret-0123456789abcdef";
    private static final String AUTHORITY_KEY_ID = "auth-service-current";
    private static final String ACTOR = "123";
    private static final long BROKER_ID = 41L;
    private static final String HASH_64 = "ab".repeat(32);
    private static final String NONCE = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

    private final KeyPair authorityKey = authorityKey();
    private final CustomerDomainService customers = mock(CustomerDomainService.class);

    private static KeyPair authorityKey() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private CustomerProfileVerifyRpcServiceImpl service() {
        CustomerProfileVerifyProperties verifyProperties = new CustomerProfileVerifyProperties();
        CustomerProfileVerifyProperties.TrustedCaller caller = new CustomerProfileVerifyProperties.TrustedCaller();
        caller.setCallerService("listing-master-service");
        caller.setSecret(CALLER_SECRET);
        verifyProperties.setTrustedCallers(Map.of(CALLER_KEY_ID, caller));
        CustomerAuthorizationProperties authorizationProperties = new CustomerAuthorizationProperties();
        authorizationProperties.setAuthorityPublicKeys(Map.of(AUTHORITY_KEY_ID,
                ServiceIdentityCrypto.encodePublicKey(authorityKey.getPublic())));
        return new CustomerProfileVerifyRpcServiceImpl(
                new CustomerRpcIdentity(verifyProperties, authorizationProperties), customers);
    }

    private record SignedContext(AuthorizationContext context, AuthzFreshness freshness) {
    }

    /**
     * 构造 Auth 授权签名的执行绑定上下文：时间窗以真实时钟为中心，
     * freshness 窗口 30 秒、context 窗口 75 秒、envelope 到期不超过 freshness.notAfter。
     */
    private SignedContext signedContext(String audience, List<String> operations, Map<String, Long> identities) {
        long now = Instant.now().getEpochSecond();
        try {
            AuthorizationContext context = AuthzSignature.sign(new AuthorizationContext(ACTOR, 1, 0, HASH_64,
                    operations, audience, "profile-verify-request", "run-1", "session-1",
                    HASH_64, HASH_64, NONCE, now - 15, now + 60, now + 240, identities,
                    AuthzContract.ALGORITHM, AUTHORITY_KEY_ID, null), authorityKey.getPrivate());
            AuthzFreshness freshness = AuthzSignature.sign(new AuthzFreshness(ACTOR, 1, 0,
                    AuthorizationStatus.ACTIVE, HASH_64, now - 20, now + 10, NONCE,
                    AuthzContract.ALGORITHM, AUTHORITY_KEY_ID, null), authorityKey.getPrivate());
            return new SignedContext(context, freshness);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static RagContextAuthorizedCustomerProfileVerifyRequest request(SignedContext signed, long customerId) {
        return new RagContextAuthorizedCustomerProfileVerifyRequest(1,
                new RagCustomerProfileVerifyRequest(1, "verify-" + customerId, customerId, "OWNER"),
                signed.context(), signed.freshness());
    }

    private static RagAuthEnvelope envelope(RagContextAuthorizedCustomerProfileVerifyRequest signedRequest,
                                            String keyId, String secret) {
        long now = Instant.now().getEpochSecond();
        RagAuthEnvelope unsigned = new RagAuthEnvelope(1, keyId, "customer-service", "listing-master-service",
                RagOperation.READ, signedRequest.request().requestId(),
                CustomerVerifyContractHash.customerProfileVerifyRequestHash(signedRequest),
                RagActorMode.USER, ACTOR, now - 5, now + 8, null, null);
        return RagRequestSigner.sign(unsigned, secret.getBytes(StandardCharsets.UTF_8));
    }

    private CustomerEntity ownerProfile(long id, long brokerId) {
        CustomerEntity profile = new CustomerEntity();
        profile.setId(id);
        profile.setBrokerId(brokerId);
        profile.setProfileType("OWNER");
        profile.setUpdatedAt(LocalDateTime.now());
        when(customers.getById(id)).thenReturn(profile);
        return profile;
    }

    @Test
    void verifiedOwnerProfileReturnsTrustedFacts() {
        CustomerEntity profile = ownerProfile(9L, BROKER_ID);
        var signed = signedContext("agent-service", List.of("customer.read"), Map.of("BROKER", BROKER_ID));
        var signedRequest = request(signed, 9L);

        CustomerProfileVerifyResponse response =
                service().verifyOwnerProfileContext(signedRequest, envelope(signedRequest, CALLER_KEY_ID, CALLER_SECRET));

        assertEquals(CustomerProfileVerifyResponse.VERIFIED, response.result());
        assertEquals("verify-9", response.requestId());
        assertEquals(9L, response.customerId());
        assertEquals("OWNER", response.profileType());
        assertEquals(BROKER_ID, response.brokerId());
        assertEquals(profile.getUpdatedAt().atZone(ZoneId.systemDefault()).toEpochSecond(),
                response.updatedAtEpochSecond());
    }

    @Test
    void callerServiceAudienceContextIsAlsoAccepted() {
        ownerProfile(9L, BROKER_ID);
        var signed = signedContext("listing-master-service", List.of("customer.read"),
                Map.of("BROKER", BROKER_ID));
        var signedRequest = request(signed, 9L);

        CustomerProfileVerifyResponse response =
                service().verifyOwnerProfileContext(signedRequest, envelope(signedRequest, CALLER_KEY_ID, CALLER_SECRET));

        assertEquals(CustomerProfileVerifyResponse.VERIFIED, response.result());
    }

    @Test
    void missingOrDeletedProfileReturnsNotFoundWithoutFacts() {
        var signed = signedContext("agent-service", List.of("customer.read"), Map.of("BROKER", BROKER_ID));
        var signedRequest = request(signed, 404L);
        when(customers.getById(404L)).thenReturn(null);

        CustomerProfileVerifyResponse response =
                service().verifyOwnerProfileContext(signedRequest, envelope(signedRequest, CALLER_KEY_ID, CALLER_SECRET));

        assertEquals(CustomerProfileVerifyResponse.NOT_FOUND, response.result());
        assertEquals("verify-404", response.requestId());
        assertNull(response.customerId());
        assertNull(response.profileType());
        assertNull(response.brokerId());
        assertNull(response.updatedAtEpochSecond());
    }

    @Test
    void mismatchedProfileTypeReturnsInvalidProfileType() {
        CustomerEntity buyer = new CustomerEntity();
        buyer.setId(9L);
        buyer.setBrokerId(BROKER_ID);
        buyer.setProfileType("CUSTOMER");
        when(customers.getById(9L)).thenReturn(buyer);
        var signed = signedContext("agent-service", List.of("customer.read"), Map.of("BROKER", BROKER_ID));
        var signedRequest = request(signed, 9L);

        CustomerProfileVerifyResponse response =
                service().verifyOwnerProfileContext(signedRequest, envelope(signedRequest, CALLER_KEY_ID, CALLER_SECRET));

        assertEquals(CustomerProfileVerifyResponse.INVALID_PROFILE_TYPE, response.result());
    }

    @Test
    void foreignBrokerProfileIsNotAccessibleForScopedCaller() {
        ownerProfile(9L, 82L);
        var signed = signedContext("agent-service", List.of("customer.read"), Map.of("BROKER", BROKER_ID));
        var signedRequest = request(signed, 9L);

        CustomerProfileVerifyResponse response =
                service().verifyOwnerProfileContext(signedRequest, envelope(signedRequest, CALLER_KEY_ID, CALLER_SECRET));

        assertEquals(CustomerProfileVerifyResponse.NOT_ACCESSIBLE, response.result());
    }

    @Test
    void missingBrokerMappingIsNotAccessibleForScopedCaller() {
        ownerProfile(9L, BROKER_ID);
        var signed = signedContext("agent-service", List.of("customer.read"), Map.of());
        var signedRequest = request(signed, 9L);

        CustomerProfileVerifyResponse response =
                service().verifyOwnerProfileContext(signedRequest, envelope(signedRequest, CALLER_KEY_ID, CALLER_SECRET));

        assertEquals(CustomerProfileVerifyResponse.NOT_ACCESSIBLE, response.result());
    }

    @Test
    void businessWideCallerVerifiesAnyBrokerProfile() {
        ownerProfile(9L, 82L);
        var signed = signedContext("agent-service", List.of("customer.read.all"), Map.of());
        var signedRequest = request(signed, 9L);

        CustomerProfileVerifyResponse response =
                service().verifyOwnerProfileContext(signedRequest, envelope(signedRequest, CALLER_KEY_ID, CALLER_SECRET));

        assertEquals(CustomerProfileVerifyResponse.VERIFIED, response.result());
        assertEquals(82L, response.brokerId());
    }

    @Test
    void tamperedEnvelopeSecretFailsVerification() {
        ownerProfile(9L, BROKER_ID);
        var signed = signedContext("agent-service", List.of("customer.read"), Map.of("BROKER", BROKER_ID));
        var signedRequest = request(signed, 9L);
        RagAuthEnvelope tampered = envelope(signedRequest, CALLER_KEY_ID,
                "wrong-secret-value-that-is-long-enough-0123456789abcdef");

        RagException failure = assertThrows(RagException.class,
                () -> service().verifyOwnerProfileContext(signedRequest, tampered));
        assertEquals(RagErrorCode.AUTHENTICATION_FAILED, failure.code());
    }

    @Test
    void unknownCallerKeyFailsVerification() {
        ownerProfile(9L, BROKER_ID);
        var signed = signedContext("agent-service", List.of("customer.read"), Map.of("BROKER", BROKER_ID));
        var signedRequest = request(signed, 9L);
        RagAuthEnvelope unknown = envelope(signedRequest, "unknown-caller-key", CALLER_SECRET);

        RagException failure = assertThrows(RagException.class,
                () -> service().verifyOwnerProfileContext(signedRequest, unknown));
        assertEquals(RagErrorCode.AUTHENTICATION_FAILED, failure.code());
    }

    @Test
    void contextSignedByRogueAuthorityFailsVerification() {
        ownerProfile(9L, BROKER_ID);
        long now = Instant.now().getEpochSecond();
        try {
            KeyPair rogue = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            AuthorizationContext context = AuthzSignature.sign(new AuthorizationContext(ACTOR, 1, 0, HASH_64,
                    List.of("customer.read"), "agent-service", "profile-verify-request", "run-1", "session-1",
                    HASH_64, HASH_64, NONCE, now - 15, now + 60, now + 240, Map.of("BROKER", BROKER_ID),
                    AuthzContract.ALGORITHM, AUTHORITY_KEY_ID, null), rogue.getPrivate());
            AuthzFreshness freshness = AuthzSignature.sign(new AuthzFreshness(ACTOR, 1, 0,
                    AuthorizationStatus.ACTIVE, HASH_64, now - 20, now + 10, NONCE,
                    AuthzContract.ALGORITHM, AUTHORITY_KEY_ID, null), rogue.getPrivate());
            var signedRequest = new RagContextAuthorizedCustomerProfileVerifyRequest(1,
                    new RagCustomerProfileVerifyRequest(1, "verify-9", 9L, "OWNER"), context, freshness);

            RagException failure = assertThrows(RagException.class,
                    () -> service().verifyOwnerProfileContext(signedRequest,
                            envelope(signedRequest, CALLER_KEY_ID, CALLER_SECRET)));
            assertEquals(RagErrorCode.AUTHENTICATION_FAILED, failure.code());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void missingCustomerReadOperationFailsClosed() {
        ownerProfile(9L, BROKER_ID);
        var signed = signedContext("agent-service", List.of("customer.inquiry.write.own"),
                Map.of("BROKER", BROKER_ID));
        var signedRequest = request(signed, 9L);

        RagException failure = assertThrows(RagException.class,
                () -> service().verifyOwnerProfileContext(signedRequest,
                        envelope(signedRequest, CALLER_KEY_ID, CALLER_SECRET)));
        assertEquals(RagErrorCode.FORBIDDEN, failure.code());
    }
}
