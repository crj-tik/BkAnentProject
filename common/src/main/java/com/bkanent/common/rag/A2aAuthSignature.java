package com.bkanent.common.rag;

import com.bkanent.common.security.ServiceIdentityCrypto;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Versioned, domain-separated signatures for service-to-Auth A2A beacon RPCs. */
public final class A2aAuthSignature {
    public static final int ED25519_VERSION = 2;
    public static final String SIGNATURE_DOMAIN = "BK-ANENT:A2A-AUTH:v1";

    private A2aAuthSignature() { }

    public static RagAuthEnvelope sign(RagAuthEnvelope unsigned, PrivateKey privateKey) {
        RagSignatureSupport.authenticate(unsigned != null && privateKey != null);
        RagAuthEnvelope v2 = copy(unsigned, ED25519_VERSION, null, ServiceIdentityCrypto.ED25519);
        String signature = ServiceIdentityCrypto.encodeSignature(ServiceIdentityCrypto.sign(SIGNATURE_DOMAIN,
                ServiceIdentityCrypto.ED25519, canonical(v2), privateKey));
        return copy(v2, ED25519_VERSION, signature, ServiceIdentityCrypto.ED25519);
    }

    public static void verify(RagAuthEnvelope envelope, String requestHash, String expectedAudience,
                              RagOperation expectedOperation, String expectedRequestId, String expectedCaller,
                              String expectedKeyId, RagCredentialContext credentials, Set<RagOperation> allowedOperations,
                              PublicKey publicKey, Instant now) {
        RagSignatureSupport.authenticate(envelope != null && envelope.contractVersion() == ED25519_VERSION
                && ServiceIdentityCrypto.ED25519.equals(envelope.algorithm()) && publicKey != null);
        RagSignatureSupport.equal(expectedKeyId, envelope.keyId());
        RagSignatureSupport.equal(expectedCaller, envelope.callerService());
        RagSignatureSupport.equal(expectedAudience, envelope.audience());
        RagSignatureSupport.equal(expectedOperation, envelope.operation());
        RagSignatureSupport.equal(expectedRequestId, envelope.requestId());
        RagSignatureSupport.hash(requestHash);
        RagSignatureSupport.equal(requestHash, envelope.requestHash());
        RagSignatureSupport.authorize(allowedOperations != null && allowedOperations.contains(expectedOperation));
        RagSignatureSupport.time(envelope.issuedAtEpochSecond(), envelope.expiresAtEpochSecond(), now);
        RagSignatureSupport.text(envelope.actorId());
        RagSignatureSupport.text(envelope.requestId());
        RagSignatureSupport.authenticate(envelope.actorMode() != null);
        if (envelope.actorMode() == RagActorMode.USER) {
            RagSignatureSupport.authenticate(credentials != null);
            RagSignatureSupport.equal(RagCanonicalHash.credentialDigest(credentials), envelope.credentialDigest());
        } else {
            RagSignatureSupport.equal(expectedCaller, envelope.actorId());
            RagSignatureSupport.authorize(expectedOperation != RagOperation.SEARCH
                    && expectedOperation != RagOperation.READ
                    && expectedOperation != RagOperation.RESOLVE_PRINCIPAL_RESPONSE);
            if (expectedOperation == RagOperation.RESOLVE_PRINCIPAL
                    || expectedOperation == RagOperation.ISSUE_BEACON
                    || expectedOperation == RagOperation.RESOLVE_BEACON) {
                RagSignatureSupport.authenticate(credentials != null);
                String digest = RagCanonicalHash.credentialDigest(credentials);
                RagSignatureSupport.equal(digest, envelope.credentialDigest());
                if (expectedOperation == RagOperation.RESOLVE_PRINCIPAL) {
                    RagSignatureSupport.equal(digest, envelope.requestHash());
                }
            } else {
                RagSignatureSupport.authenticate(credentials == null && envelope.credentialDigest() == null);
            }
        }
        byte[] signature;
        try { signature = ServiceIdentityCrypto.decodeSignature(envelope.signature()); }
        catch (IllegalArgumentException exception) { throw new RagException(RagErrorCode.AUTHENTICATION_FAILED); }
        RagSignatureSupport.authenticate(ServiceIdentityCrypto.verify(SIGNATURE_DOMAIN, ServiceIdentityCrypto.ED25519,
                canonical(envelope), signature, publicKey));
    }

    private static byte[] canonical(RagAuthEnvelope envelope) {
        try {
            byte[] fields = RagCanonicalHash.envelopeBytes(envelope);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(fields.length + 24);
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(ED25519_VERSION);
            byte[] algorithm = ServiceIdentityCrypto.ED25519.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            output.writeInt(algorithm.length);
            output.write(algorithm);
            output.writeInt(fields.length);
            output.write(fields);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
    }

    private static RagAuthEnvelope copy(RagAuthEnvelope source, int version, String signature, String algorithm) {
        return new RagAuthEnvelope(version, source.keyId(), source.audience(), source.callerService(), source.operation(),
                source.requestId(), source.requestHash(), source.actorMode(), source.actorId(),
                source.issuedAtEpochSecond(), source.expiresAtEpochSecond(), source.credentialDigest(), signature, algorithm);
    }
}
