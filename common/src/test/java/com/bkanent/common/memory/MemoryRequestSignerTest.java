package com.bkanent.common.memory;

import com.bkanent.common.serialize.Fastjson2ConstructorSerialization;
import com.bkanent.common.rpc.AuthenticatedPrincipal;
import com.bkanent.common.authz.AuthzCanonicalHash;
import com.bkanent.common.authz.AuthzContract;
import com.bkanent.common.authz.AuthzFreshness;
import com.bkanent.common.authz.AuthzSignature;
import com.bkanent.common.authz.AuthorizationContext;
import com.bkanent.common.authz.AuthorizationSnapshot;
import com.bkanent.common.authz.AuthorizationStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryRequestSignerTest {
    private static final Instant NOW = Instant.parse("2026-10-19T00:00:00Z");
    private static final String KEY_ID = "marketing-service-identity-2026";
    private static final MemoryContextKey CONTEXT =
            new MemoryContextKey("17", "marketing", "copy-agent", "conversation-3");
    private static KeyPair identity;

    @BeforeAll
    static void createIdentity() throws Exception {
        identity = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private MemoryWorkingSetRequest request() {
        MemoryRequestEnvelope placeholder = new MemoryRequestEnvelope(MemoryV2Contract.OPERATION_WORKING_SET,
                "marketing-content-service", MemoryV2Contract.MEMORY_AUDIENCE, "request-77",
                "a".repeat(64), MemoryV2Contract.VERSION);
        MemoryWorkingSetRequest body = new MemoryWorkingSetRequest(placeholder, CONTEXT);
        String hash = MemoryCanonicalHash.requestBodyHash(body);
        return new MemoryWorkingSetRequest(new MemoryRequestEnvelope(placeholder.operation(), placeholder.caller(),
                placeholder.audience(), placeholder.requestId(), hash, placeholder.contractVersion()), CONTEXT);
    }

    private MemoryRequestProof proof(MemoryWorkingSetRequest request, MemoryContextKey context) {
        return MemoryRequestSigner.sign(request, new AuthenticatedPrincipal(Long.parseLong(context.ownerId())), KEY_ID,
                NOW.getEpochSecond(), NOW.plusSeconds(60).getEpochSecond(), "2d65e053d5164f8696e9132c0eb7412e",
                identity.getPrivate());
    }

    private void verify(MemoryWorkingSetRequest request, MemoryContextKey context, MemoryRequestProof proof,
                        String caller, String audience, String operation, String keyId, Instant now) {
        MemoryRequestSigner.verifyEd25519(request, context, proof, caller, audience, operation, keyId, now,
                identity.getPublic());
    }

    @Test
    void signsAndVerifiesTheCompleteRequestAndContext() {
        MemoryWorkingSetRequest request = request();
        MemoryRequestProof signed = proof(request, CONTEXT);

        verify(request, CONTEXT, signed, "marketing-content-service", MemoryV2Contract.MEMORY_AUDIENCE,
                MemoryV2Contract.OPERATION_WORKING_SET, KEY_ID, NOW);

        assertThat(signed.toString()).isEqualTo("MemoryRequestProof[REDACTED]");
        assertThat(signed.signature()).hasSize(86);
        assertThat(signed.signatureVersion()).isEqualTo(MemoryRequestProof.ED25519_V2);
        assertThat(signed.algorithm()).isEqualTo("Ed25519");
    }

    @Test
    void ownerDomainAgentConversationAndBodyChangesInvalidateTheProof() {
        MemoryWorkingSetRequest request = request();
        MemoryRequestProof signed = proof(request, CONTEXT);
        for (MemoryContextKey changed : new MemoryContextKey[]{
                new MemoryContextKey("18", "marketing", "copy-agent", "conversation-3"),
                new MemoryContextKey("17", "interview", "copy-agent", "conversation-3"),
                new MemoryContextKey("17", "marketing", "interview-agent", "conversation-3"),
                new MemoryContextKey("17", "marketing", "copy-agent", "conversation-4")}) {
            MemoryWorkingSetRequest changedRequest = new MemoryWorkingSetRequest(request.envelope(), changed);
            assertThatThrownBy(() -> verify(changedRequest, changed, signed, "marketing-content-service",
                    MemoryV2Contract.MEMORY_AUDIENCE, MemoryV2Contract.OPERATION_WORKING_SET, KEY_ID, NOW))
                    .isInstanceOf(MemoryException.class).hasMessage("AUTHENTICATION_FAILED");
        }
        MemoryRequestEnvelope changedEnvelope = new MemoryRequestEnvelope(request.envelope().operation(),
                request.envelope().caller(), request.envelope().audience(), request.envelope().requestId(),
                "b".repeat(64), request.envelope().contractVersion());
        MemoryWorkingSetRequest changedBody = new MemoryWorkingSetRequest(changedEnvelope, CONTEXT);
        assertThatThrownBy(() -> verify(changedBody, CONTEXT, signed, "marketing-content-service",
                MemoryV2Contract.MEMORY_AUDIENCE, MemoryV2Contract.OPERATION_WORKING_SET, KEY_ID, NOW))
                .isInstanceOf(MemoryException.class).hasMessage("AUTHENTICATION_FAILED");
    }

    @Test
    void signingRequiresTheResolvedPrincipalToMatchTheOwner() {
        MemoryWorkingSetRequest request = request();
        assertThatThrownBy(() -> MemoryRequestSigner.sign(request, new AuthenticatedPrincipal(18L), KEY_ID,
                NOW.getEpochSecond(), NOW.plusSeconds(30).getEpochSecond(),
                "2d65e053d5164f8696e9132c0eb7412e", identity.getPrivate()))
                .isInstanceOf(MemoryException.class).hasMessage("AUTHENTICATION_FAILED");
    }

    @Test
    void expectedCallerAudienceOperationAndKeyMustMatchTheTrustedMapping() {
        MemoryWorkingSetRequest request = request();
        MemoryRequestProof signed = proof(request, CONTEXT);
        assertRejected(request, CONTEXT, signed, "agent-service", MemoryV2Contract.MEMORY_AUDIENCE,
                MemoryV2Contract.OPERATION_WORKING_SET, KEY_ID);
        assertRejected(request, CONTEXT, signed, "marketing-content-service", "rag-service",
                MemoryV2Contract.OPERATION_WORKING_SET, KEY_ID);
        assertRejected(request, CONTEXT, signed, "marketing-content-service", MemoryV2Contract.MEMORY_AUDIENCE,
                MemoryV2Contract.OPERATION_DELIVER_MESSAGES, KEY_ID);
        assertRejected(request, CONTEXT, signed, "marketing-content-service", MemoryV2Contract.MEMORY_AUDIENCE,
                MemoryV2Contract.OPERATION_WORKING_SET, "rotated-key");
    }

    @Test
    void shortLivedProofRejectsExpiredFutureAndInvalidTtl() {
        MemoryWorkingSetRequest request = request();
        MemoryRequestProof signed = proof(request, CONTEXT);
        assertThatThrownBy(() -> verify(request, CONTEXT, signed, "marketing-content-service",
                MemoryV2Contract.MEMORY_AUDIENCE, MemoryV2Contract.OPERATION_WORKING_SET, KEY_ID, NOW.plusSeconds(60)))
                .isInstanceOf(MemoryException.class).hasMessage("AUTHENTICATION_EXPIRED");
        MemoryRequestProof futureIssued = MemoryRequestSigner.sign(request, new AuthenticatedPrincipal(17L), KEY_ID,
                NOW.getEpochSecond() + 6, NOW.getEpochSecond() + 60,
                "2d65e053d5164f8696e9132c0eb7412e", identity.getPrivate());
        assertThatThrownBy(() -> verify(request, CONTEXT, futureIssued, "marketing-content-service",
                MemoryV2Contract.MEMORY_AUDIENCE, MemoryV2Contract.OPERATION_WORKING_SET, KEY_ID, NOW))
                .isInstanceOf(MemoryException.class).hasMessage("AUTHENTICATION_FAILED");
        assertThatThrownBy(() -> MemoryRequestSigner.sign(request, new AuthenticatedPrincipal(17L), KEY_ID,
                NOW.getEpochSecond(), NOW.getEpochSecond() + 61,
                "2d65e053d5164f8696e9132c0eb7412e", identity.getPrivate()))
                .isInstanceOf(MemoryException.class).hasMessage("INVALID_ARGUMENT (IDENTITY)");
    }

    @Test
    void proofRoundTripsThroughSelectedRpcSerialization() throws Exception {
        MemoryRequestProof signed = proof(request(), CONTEXT);
        var serializer = new Fastjson2ConstructorSerialization();
        var bytes = new ByteArrayOutputStream();
        var output = serializer.serialize(null, bytes);
        output.writeObject(signed);
        output.flushBuffer();

        var decoded = serializer.deserialize(null, new ByteArrayInputStream(bytes.toByteArray()))
                .readObject(MemoryRequestProof.class);
        assertThat(decoded).isEqualTo(signed);
    }

    @Test
    void authzV3BindsTheSignedContextAndFreshnessIntoTheMemoryRequestProof() throws Exception {
        var request = request();
        var evidence = authorizationEvidence();
        var signed = MemoryRequestSigner.sign(request, new AuthenticatedPrincipal(17L), KEY_ID,
                NOW.getEpochSecond(), NOW.plusSeconds(20).getEpochSecond(),
                "memory-nonce-1234567890123456", identity.getPrivate(), evidence.context(), evidence.freshness());

        assertThat(signed.signatureVersion()).isEqualTo(MemoryRequestProof.ED25519_AUTHZ_V3);
        assertThat(signed.authorizationContext()).isEqualTo(evidence.context());
        assertThat(signed.authorizationFreshness()).isEqualTo(evidence.freshness());
        verify(request, CONTEXT, signed, "marketing-content-service", MemoryV2Contract.MEMORY_AUDIENCE,
                MemoryV2Contract.OPERATION_WORKING_SET, KEY_ID, NOW);

        var context = evidence.context();
        var changedContext = new AuthorizationContext(context.actorId(), context.permissionRevision(),
                context.credentialEpoch(), context.permissionHash(), context.allowedOperations(), context.audience(),
                context.requestId(), context.runId(), "another-session", context.policyDigest(), context.requestHash(),
                context.nonce(), context.issuedAtEpochSecond(), context.expiresAtEpochSecond(),
                context.runHardDeadlineEpochSecond(), context.algorithm(), context.keyId(), context.signature());
        var changedProof = new MemoryRequestProof(signed.keyId(), signed.issuedAtEpochSecond(), signed.expiresAtEpochSecond(),
                signed.nonce(), signed.signature(), signed.actor(), signed.signatureVersion(), signed.algorithm(),
                changedContext, signed.authorizationFreshness());
        assertThatThrownBy(() -> verify(request, CONTEXT, changedProof, "marketing-content-service",
                MemoryV2Contract.MEMORY_AUDIENCE, MemoryV2Contract.OPERATION_WORKING_SET, KEY_ID, NOW))
                .isInstanceOf(MemoryException.class).hasMessage("AUTHENTICATION_FAILED");

        var serializer = new Fastjson2ConstructorSerialization();
        var bytes = new ByteArrayOutputStream();
        var output = serializer.serialize(null, bytes);
        output.writeObject(signed);
        output.flushBuffer();
        var decoded = serializer.deserialize(null, new ByteArrayInputStream(bytes.toByteArray()))
                .readObject(MemoryRequestProof.class);
        assertThat(decoded).isEqualTo(signed);
    }

    @Test
    void actorModeIsCryptographicallyBoundAndCannotBeRelabelled() {
        var request = request();
        var user = proof(request, CONTEXT);
        var changed = new MemoryRequestProof(user.keyId(), user.issuedAtEpochSecond(), user.expiresAtEpochSecond(),
                user.nonce(), user.signature(), MemoryRequestProof.Actor.SERVICE, user.signatureVersion(), user.algorithm());
        assertRejected(request, CONTEXT, changed, "marketing-content-service", MemoryV2Contract.MEMORY_AUDIENCE,
                MemoryV2Contract.OPERATION_WORKING_SET, user.keyId());
    }

    private void assertRejected(MemoryWorkingSetRequest request, MemoryContextKey context, MemoryRequestProof proof,
                                String caller, String audience, String operation, String keyId) {
        assertThatThrownBy(() -> verify(request, context, proof, caller, audience, operation, keyId, NOW))
                .isInstanceOf(MemoryException.class).hasMessage("AUTHENTICATION_FAILED");
    }

    private AuthzEvidence authorizationEvidence() throws Exception {
        var authority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        long now = NOW.getEpochSecond();
        String keyId = "auth-memory-test";
        var snapshot = new AuthorizationSnapshot("17", 9, 3, false, List.of("agent.chat.use"), null,
                AuthzContract.ALGORITHM, keyId, null);
        String permissionHash = AuthzCanonicalHash.snapshotHash(snapshot);
        String nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        var context = AuthzSignature.sign(new AuthorizationContext("17", 9, 4, permissionHash,
                List.of("agent.chat.use"), "agent-service", "entry-1", "run-1", "session-1",
                "c".repeat(64), "d".repeat(64), nonce, now, now + 300, now + 300,
                AuthzContract.ALGORITHM, keyId, null), authority.getPrivate());
        var freshness = AuthzSignature.sign(new AuthzFreshness("17", 9, 4, AuthorizationStatus.ACTIVE,
                permissionHash, now, now + 30, nonce, AuthzContract.ALGORITHM, keyId, null), authority.getPrivate());
        return new AuthzEvidence(context, freshness);
    }

    private record AuthzEvidence(AuthorizationContext context, AuthzFreshness freshness) { }
}
