package com.bkanent.common.memory;

import com.bkanent.common.serialize.Fastjson2ConstructorSerialization;
import com.bkanent.common.rpc.AuthenticatedPrincipal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryRequestSignerTest {
    private static final byte[] SECRET = "memory-v2-test-key-0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-10-19T00:00:00Z");
    private static final MemoryContextKey CONTEXT =
            new MemoryContextKey("17", "marketing", "copy-agent", "conversation-3");

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
        return MemoryRequestSigner.sign(request, new AuthenticatedPrincipal(Long.parseLong(context.ownerId())), "marketing-memory-v2-current",
                NOW.getEpochSecond(), NOW.plusSeconds(60).getEpochSecond(), "2d65e053d5164f8696e9132c0eb7412e", SECRET);
    }

    @Test
    void signsAndVerifiesTheCompleteRequestAndContext() {
        MemoryWorkingSetRequest request = request();
        MemoryRequestProof signed = proof(request, CONTEXT);

        MemoryRequestSigner.verify(request, CONTEXT, signed, "marketing-content-service",
                MemoryV2Contract.MEMORY_AUDIENCE, MemoryV2Contract.OPERATION_WORKING_SET,
                "marketing-memory-v2-current", NOW, SECRET);

        assertThat(signed.toString()).isEqualTo("MemoryRequestProof[REDACTED]");
        assertThat(signed.signature()).hasSize(43);
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
            assertThatThrownBy(() -> MemoryRequestSigner.verify(changedRequest, changed, signed,
                    "marketing-content-service", MemoryV2Contract.MEMORY_AUDIENCE,
                    MemoryV2Contract.OPERATION_WORKING_SET, "marketing-memory-v2-current", NOW, SECRET))
                    .isInstanceOf(MemoryException.class).hasMessage("AUTHENTICATION_FAILED");
        }
        MemoryRequestEnvelope changedEnvelope = new MemoryRequestEnvelope(request.envelope().operation(),
                request.envelope().caller(), request.envelope().audience(), request.envelope().requestId(),
                "b".repeat(64), request.envelope().contractVersion());
        MemoryWorkingSetRequest changedBody = new MemoryWorkingSetRequest(changedEnvelope, CONTEXT);
        assertThatThrownBy(() -> MemoryRequestSigner.verify(changedBody, CONTEXT, signed,
                "marketing-content-service", MemoryV2Contract.MEMORY_AUDIENCE,
                MemoryV2Contract.OPERATION_WORKING_SET, "marketing-memory-v2-current", NOW, SECRET))
                .isInstanceOf(MemoryException.class).hasMessage("AUTHENTICATION_FAILED");
    }

    @Test
    void signingRequiresTheResolvedPrincipalToMatchTheOwner() {
        MemoryWorkingSetRequest request = request();

        assertThatThrownBy(() -> MemoryRequestSigner.sign(request, new AuthenticatedPrincipal(18L),
                "marketing-memory-v2-current", NOW.getEpochSecond(), NOW.plusSeconds(30).getEpochSecond(),
                "2d65e053d5164f8696e9132c0eb7412e", SECRET))
                .isInstanceOf(MemoryException.class).hasMessage("AUTHENTICATION_FAILED");
    }

    @Test
    void expectedCallerAudienceOperationAndKeyMustMatchTheTrustedMapping() {
        MemoryWorkingSetRequest request = request();
        MemoryRequestProof signed = proof(request, CONTEXT);
        assertRejected(request, CONTEXT, signed, "agent-service", MemoryV2Contract.MEMORY_AUDIENCE,
                MemoryV2Contract.OPERATION_WORKING_SET, "marketing-memory-v2-current");
        assertRejected(request, CONTEXT, signed, "marketing-content-service", "rag-service",
                MemoryV2Contract.OPERATION_WORKING_SET, "marketing-memory-v2-current");
        assertRejected(request, CONTEXT, signed, "marketing-content-service", MemoryV2Contract.MEMORY_AUDIENCE,
                MemoryV2Contract.OPERATION_DELIVER_MESSAGES, "marketing-memory-v2-current");
        assertRejected(request, CONTEXT, signed, "marketing-content-service", MemoryV2Contract.MEMORY_AUDIENCE,
                MemoryV2Contract.OPERATION_WORKING_SET, "rotated-key");
    }

    @Test
    void shortLivedProofRejectsExpiredFutureAndMalformedSignatures() {
        MemoryWorkingSetRequest request = request();
        MemoryRequestProof signed = proof(request, CONTEXT);
        assertThatThrownBy(() -> MemoryRequestSigner.verify(request, CONTEXT, signed,
                "marketing-content-service", MemoryV2Contract.MEMORY_AUDIENCE,
                MemoryV2Contract.OPERATION_WORKING_SET, "marketing-memory-v2-current", NOW.plusSeconds(60), SECRET))
                .isInstanceOf(MemoryException.class).hasMessage("AUTHENTICATION_EXPIRED");
        MemoryRequestProof futureIssued = MemoryRequestSigner.sign(request, new AuthenticatedPrincipal(17L), "marketing-memory-v2-current",
                NOW.getEpochSecond() + 6, NOW.getEpochSecond() + 60,
                "2d65e053d5164f8696e9132c0eb7412e", SECRET);
        assertThatThrownBy(() -> MemoryRequestSigner.verify(request, CONTEXT, futureIssued,
                "marketing-content-service", MemoryV2Contract.MEMORY_AUDIENCE,
                MemoryV2Contract.OPERATION_WORKING_SET, "marketing-memory-v2-current", NOW, SECRET))
                .isInstanceOf(MemoryException.class).hasMessage("AUTHENTICATION_FAILED");
        assertThatThrownBy(() -> MemoryRequestSigner.sign(request, new AuthenticatedPrincipal(17L), "key", NOW.getEpochSecond(),
                NOW.getEpochSecond() + 61, "2d65e053d5164f8696e9132c0eb7412e", SECRET))
                .isInstanceOf(MemoryException.class).hasMessage("INVALID_ARGUMENT (IDENTITY)");
        assertThatThrownBy(() -> MemoryRequestSigner.sign(request, new AuthenticatedPrincipal(17L), "key", NOW.getEpochSecond(),
                NOW.getEpochSecond() + 30, "short", SECRET))
                .isInstanceOf(MemoryException.class).hasMessage("INVALID_ARGUMENT (IDENTITY)");
        assertThatThrownBy(() -> MemoryRequestSigner.sign(request, new AuthenticatedPrincipal(17L), "k".repeat(65), NOW.getEpochSecond(),
                NOW.getEpochSecond() + 30, "2d65e053d5164f8696e9132c0eb7412e", SECRET))
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
    void actorModeIsCryptographicallyBoundAndCannotBeRelabelled() {
        var request = request();
        var user = proof(request, CONTEXT);
        var changed = new MemoryRequestProof(user.keyId(), user.issuedAtEpochSecond(), user.expiresAtEpochSecond(),
                user.nonce(), user.signature(), MemoryRequestProof.Actor.SERVICE);
        assertRejected(request, CONTEXT, changed, "marketing-content-service", MemoryV2Contract.MEMORY_AUDIENCE,
                MemoryV2Contract.OPERATION_WORKING_SET, user.keyId());
    }

    private void assertRejected(MemoryWorkingSetRequest request, MemoryContextKey context, MemoryRequestProof proof,
                                String caller, String audience, String operation, String keyId) {
        assertThatThrownBy(() -> MemoryRequestSigner.verify(request, context, proof, caller, audience,
                operation, keyId, NOW, SECRET)).isInstanceOf(MemoryException.class)
                .hasMessage("AUTHENTICATION_FAILED");
    }
}
