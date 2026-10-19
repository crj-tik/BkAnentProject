package com.bkanent.common.memory;

import com.bkanent.common.rpc.AuthenticatedPrincipal;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/** HMAC request binding for the dedicated memory v2 authentication transport. */
public final class MemoryRequestSigner {
    public static final long MAX_TTL_SECONDS = 60;
    public static final long MAX_FUTURE_SKEW_SECONDS = 5;

    private MemoryRequestSigner() { }

    public static MemoryRequestProof sign(MemoryV2Request request,
                                          AuthenticatedPrincipal principal,
                                          String keyId,
                                          long issuedAtEpochSecond,
                                          long expiresAtEpochSecond,
                                          String nonce,
                                          byte[] secret) {
        MemoryContextKey context = request == null ? null : request.contextKey();
        requireInputs(request, context, secret);
        authenticate(principal != null && principal.userId() != null && principal.userId() > 0
                && Long.toString(principal.userId()).equals(context.ownerId()));
        return signBound(request, keyId, issuedAtEpochSecond, expiresAtEpochSecond, nonce, secret,
                MemoryRequestProof.Actor.USER);
    }

    /** Service credentials grant only delivery; the server must additionally verify its durable permit. */
    public static MemoryRequestProof signServiceDelivery(MemoryDeliveryRequest request, String keyId,
                                                         long issued, long expires, String nonce, byte[] secret) {
        requireInputs(request, request == null ? null : request.contextKey(), secret);
        return signBound(request, keyId, issued, expires, nonce, secret, MemoryRequestProof.Actor.SERVICE);
    }

    private static MemoryRequestProof signBound(MemoryV2Request request, String keyId, long issuedAtEpochSecond,
                                                long expiresAtEpochSecond, String nonce, byte[] secret,
                                                MemoryRequestProof.Actor actor) {
        MemoryRequestEnvelope envelope = request.envelope();
        authenticate(equalHash(envelope.bodyHash(), MemoryCanonicalHash.requestBodyHash(request)));
        byte[] payload = canonical(envelope, request.contextKey(), keyId, issuedAtEpochSecond, expiresAtEpochSecond, nonce, actor);
        return new MemoryRequestProof(keyId, issuedAtEpochSecond, expiresAtEpochSecond, nonce,
                encode(mac(payload, secret)), actor);
    }

    public static void verify(MemoryV2Request request,
                              MemoryContextKey context,
                              MemoryRequestProof proof,
                              String expectedCaller,
                              String expectedAudience,
                              String expectedOperation,
                              String expectedKeyId,
                              Instant now,
                              byte[] secret) {
        requireInputs(request, context, secret);
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

        byte[] supplied;
        try {
            supplied = Base64.getUrlDecoder().decode(proof.signature());
        } catch (IllegalArgumentException exception) {
            throw new MemoryException(MemoryErrorCode.AUTHENTICATION_FAILED);
        }
        authenticate(proof.actor() != null);
        byte[] expected = mac(canonical(envelope, context, proof.keyId(), issued, expires, proof.nonce(), proof.actor()), secret);
        authenticate(MessageDigest.isEqual(expected, supplied)
                && encode(supplied).equals(proof.signature()));
    }

    private static void requireInputs(MemoryV2Request request, MemoryContextKey context, byte[] secret) {
        authenticate(request != null && request.envelope() != null && context != null && secret != null && secret.length >= 32);
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

    private static byte[] mac(byte[] payload, byte[] secret) {
        authenticate(secret != null && secret.length >= 32);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new MemoryException(MemoryErrorCode.AUTHENTICATION_FAILED);
        }
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static void authenticate(boolean condition) {
        if (!condition) throw new MemoryException(MemoryErrorCode.AUTHENTICATION_FAILED);
    }
}
