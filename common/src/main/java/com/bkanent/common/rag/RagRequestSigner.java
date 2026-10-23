package com.bkanent.common.rag;

import java.time.Instant;

/** HMAC request binding only; callers still must resolve principals and intersect managed space policy. */
public final class RagRequestSigner {
    private RagRequestSigner() { }

    public static RagAuthEnvelope sign(RagAuthEnvelope unsigned, byte[] secret) {
        RagContract.require(unsigned != null, "authentication");
        return new RagAuthEnvelope(unsigned.contractVersion(), unsigned.keyId(), unsigned.audience(),
                unsigned.callerService(), unsigned.operation(), unsigned.requestId(), unsigned.requestHash(),
                unsigned.actorMode(), unsigned.actorId(), unsigned.issuedAtEpochSecond(), unsigned.expiresAtEpochSecond(),
                unsigned.credentialDigest(), RagSignatureSupport.sign(RagCanonicalHash.envelopeBytes(unsigned), secret));
    }

    public static void verify(RagAuthEnvelope envelope, String requestHash, String expectedAudience,
                              RagOperation expectedOperation, String expectedRequestId,
                              RagCredentialContext credentials, RagSigningKey key, Instant now) {
        RagSignatureSupport.authenticate(envelope != null && key != null && envelope.contractVersion() == RagContract.VERSION
                && (envelope.algorithm() == null || RagAuthEnvelope.HMAC_SHA256.equals(envelope.algorithm())));
        RagSignatureSupport.equal(key.keyId(), envelope.keyId());
        RagSignatureSupport.equal(key.callerService(), envelope.callerService());
        RagSignatureSupport.verify(RagCanonicalHash.envelopeBytes(envelope), envelope.signature(), key.secret());
        RagSignatureSupport.equal(expectedAudience, envelope.audience());
        RagSignatureSupport.equal(expectedOperation, envelope.operation());
        RagSignatureSupport.equal(expectedRequestId, envelope.requestId());
        RagSignatureSupport.hash(requestHash);
        RagSignatureSupport.equal(requestHash, envelope.requestHash());
        RagSignatureSupport.authorize(key.allowedOperations().contains(expectedOperation));
        RagSignatureSupport.time(envelope.issuedAtEpochSecond(), envelope.expiresAtEpochSecond(), now);
        RagSignatureSupport.text(envelope.actorId());
        RagSignatureSupport.text(envelope.requestId());
        RagSignatureSupport.authenticate(envelope.actorMode() != null);
        if (envelope.actorMode() == RagActorMode.USER) {
            RagSignatureSupport.authenticate(credentials != null);
            RagSignatureSupport.equal(RagCanonicalHash.credentialDigest(credentials), envelope.credentialDigest());
        } else {
            RagSignatureSupport.equal(key.callerService(), envelope.actorId());
            // A background writer cannot promote itself to user search or user response authority.
            RagSignatureSupport.authorize(expectedOperation != RagOperation.SEARCH && expectedOperation != RagOperation.READ
                    && expectedOperation != RagOperation.RESOLVE_PRINCIPAL_RESPONSE);
            if (expectedOperation == RagOperation.RESOLVE_PRINCIPAL || expectedOperation == RagOperation.ISSUE_BEACON
                    || expectedOperation == RagOperation.RESOLVE_BEACON) {
                RagSignatureSupport.authenticate(credentials != null);
                String digest = RagCanonicalHash.credentialDigest(credentials);
                RagSignatureSupport.equal(digest, envelope.credentialDigest());
                if (expectedOperation == RagOperation.RESOLVE_PRINCIPAL) RagSignatureSupport.equal(digest, envelope.requestHash());
            } else {
                RagSignatureSupport.authenticate(credentials == null && envelope.credentialDigest() == null);
            }
        }
    }
}
