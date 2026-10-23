package com.bkanent.common.memory;

import com.bkanent.common.rpc.AuthenticatedPrincipal;
import com.bkanent.common.security.ServiceIdentityCrypto;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Objects;

/** Ed25519 request binding for the dedicated memory v2 authentication transport. */
public final class MemoryRequestSigner {
    public static final String SIGNATURE_DOMAIN = "BK-ANENT:MEMORY-V2:v1";
    public static final long MAX_TTL_SECONDS = 60;
    public static final long MAX_FUTURE_SKEW_SECONDS = 5;

    private MemoryRequestSigner() { }

    /** Ed25519 v2 signer used by services after their public key is registered with Memory. */
    public static MemoryRequestProof sign(MemoryV2Request request, AuthenticatedPrincipal principal,
                                          String keyId, long issuedAtEpochSecond, long expiresAtEpochSecond,
                                          String nonce, PrivateKey privateKey) {
        MemoryContextKey context = request == null ? null : request.contextKey();
        requireRequest(request, context);
        authenticate(privateKey != null && principal != null && principal.userId() != null && principal.userId() > 0
                && Long.toString(principal.userId()).equals(context.ownerId()));
        return signBound(request, keyId, issuedAtEpochSecond, expiresAtEpochSecond, nonce, privateKey,
                MemoryRequestProof.Actor.USER);
    }

    /** Service credentials grant only delivery; the server must additionally verify its durable permit. */
    public static MemoryRequestProof signServiceDelivery(MemoryDeliveryRequest request, String keyId,
                                                         long issued, long expires, String nonce, PrivateKey privateKey) {
        requireRequest(request, request == null ? null : request.contextKey());
        authenticate(privateKey != null);
        return signBound(request, keyId, issued, expires, nonce, privateKey, MemoryRequestProof.Actor.SERVICE);
    }

    /** Domain result publishers have a separate operation-bound signature; it grants no read or delivery access. */
    public static MemoryRequestProof signServiceExperienceOutcome(MemoryExperienceOutcomeRequest request, String keyId,
                                                                  long issued, long expires, String nonce, PrivateKey privateKey) {
        requireRequest(request, request == null ? null : request.contextKey());
        authenticate(privateKey != null && MemoryV2Contract.OPERATION_EXPERIENCE_OUTCOME.equals(request.envelope().operation()));
        return signBound(request, keyId, issued, expires, nonce, privateKey, MemoryRequestProof.Actor.SERVICE);
    }

    private static MemoryRequestProof signBound(MemoryV2Request request, String keyId, long issuedAtEpochSecond,
                                                long expiresAtEpochSecond, String nonce, PrivateKey privateKey,
                                                MemoryRequestProof.Actor actor) {
        MemoryRequestEnvelope envelope = request.envelope();
        authenticate(equalHash(envelope.bodyHash(), MemoryCanonicalHash.requestBodyHash(request)));
        byte[] payload = canonical(envelope, request.contextKey(), keyId, issuedAtEpochSecond,
                expiresAtEpochSecond, nonce, actor);
        byte[] versioned = canonicalV2(payload);
        String signature = ServiceIdentityCrypto.encodeSignature(ServiceIdentityCrypto.sign(
                SIGNATURE_DOMAIN, ServiceIdentityCrypto.ED25519, versioned, privateKey));
        return new MemoryRequestProof(keyId, issuedAtEpochSecond, expiresAtEpochSecond, nonce, signature, actor,
                MemoryRequestProof.ED25519_V2, ServiceIdentityCrypto.ED25519);
    }

    public static void verifyEd25519(MemoryV2Request request, MemoryContextKey context, MemoryRequestProof proof,
                                     String expectedCaller, String expectedAudience, String expectedOperation,
                                     String expectedKeyId, Instant now, PublicKey publicKey) {
        requireRequest(request, context);
        authenticate(publicKey != null);
        verifyFacts(request, context, proof, expectedCaller, expectedAudience, expectedOperation, expectedKeyId, now);
        authenticate(isEd25519Proof(proof));
        byte[] supplied;
        try { supplied = ServiceIdentityCrypto.decodeSignature(proof.signature()); }
        catch (IllegalArgumentException exception) { throw new MemoryException(MemoryErrorCode.AUTHENTICATION_FAILED); }
        byte[] payload = canonical(request.envelope(), context, proof.keyId(), proof.issuedAtEpochSecond(),
                proof.expiresAtEpochSecond(), proof.nonce(), proof.actor());
        authenticate(ServiceIdentityCrypto.verify(SIGNATURE_DOMAIN, ServiceIdentityCrypto.ED25519,
                canonicalV2(payload), supplied, publicKey));
    }

    public static boolean isEd25519Proof(MemoryRequestProof proof) {
        return proof != null && proof.signatureVersion() == MemoryRequestProof.ED25519_V2
                && ServiceIdentityCrypto.ED25519.equals(proof.algorithm());
    }

    private static void verifyFacts(MemoryV2Request request, MemoryContextKey context, MemoryRequestProof proof,
                                    String expectedCaller, String expectedAudience, String expectedOperation,
                                    String expectedKeyId, Instant now) {
        requireRequest(request, context);
        MemoryRequestEnvelope envelope = request.envelope();
        authenticate(equalHash(envelope.bodyHash(), MemoryCanonicalHash.requestBodyHash(request)));
        authenticate(proof != null && now != null);
        authenticate(Objects.equals(expectedCaller, envelope.caller())
                && Objects.equals(expectedAudience, envelope.audience())
                && Objects.equals(expectedOperation, envelope.operation())
                && Objects.equals(expectedKeyId, proof.keyId()));
        long issued = proof.issuedAtEpochSecond();
        long expires = proof.expiresAtEpochSecond();
        authenticate(issued >= 0 && expires > issued && expires - issued <= MAX_TTL_SECONDS);
        if (expires <= now.getEpochSecond()) throw new MemoryException(MemoryErrorCode.AUTHENTICATION_EXPIRED);
        authenticate(issued <= now.getEpochSecond() + MAX_FUTURE_SKEW_SECONDS);
        authenticate(proof.actor() != null);
    }

    private static void requireRequest(MemoryV2Request request, MemoryContextKey context) {
        authenticate(request != null && request.envelope() != null && context != null);
    }

    private static byte[] canonicalV2(byte[] canonicalFields) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(canonicalFields.length + 32);
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(MemoryRequestProof.ED25519_V2);
            write(output, ServiceIdentityCrypto.ED25519);
            output.writeInt(canonicalFields.length);
            output.write(canonicalFields);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new MemoryException(MemoryErrorCode.AUTHENTICATION_FAILED);
        }
    }

    private static boolean equalHash(String expected, String actual) {
        return expected != null && actual != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] canonical(MemoryRequestEnvelope envelope,
                                    MemoryContextKey context,
                                    String keyId,
                                    long issuedAt,
                                    long expiresAt,
                                    String nonce, MemoryRequestProof.Actor actor) {
        authenticate(keyId != null && !keyId.isBlank() && nonce != null && !nonce.isBlank());
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
            DataOutputStream output = new DataOutputStream(bytes);
            write(output, keyId);
            write(output, actor.name());
            write(output, envelope.caller());
            write(output, envelope.audience());
            write(output, envelope.operation());
            write(output, envelope.requestId());
            write(output, envelope.bodyHash());
            output.writeInt(envelope.contractVersion());
            write(output, context.ownerId());
            write(output, context.domainId());
            write(output, context.agentId());
            write(output, context.conversationId());
            output.writeLong(issuedAt);
            output.writeLong(expiresAt);
            write(output, nonce);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new MemoryException(MemoryErrorCode.AUTHENTICATION_FAILED);
        }
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static void authenticate(boolean condition) {
        if (!condition) throw new MemoryException(MemoryErrorCode.AUTHENTICATION_FAILED);
    }
}
