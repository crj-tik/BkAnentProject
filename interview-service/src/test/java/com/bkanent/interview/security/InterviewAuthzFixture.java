package com.bkanent.interview.security;

import com.bkanent.common.authz.AuthzCanonicalHash;
import com.bkanent.common.authz.AuthzContract;
import com.bkanent.common.authz.AuthzFreshness;
import com.bkanent.common.authz.AuthzResponse;
import com.bkanent.common.authz.AuthzSignature;
import com.bkanent.common.authz.AuthorizationContext;
import com.bkanent.common.authz.AuthorizationSnapshot;
import com.bkanent.common.authz.AuthorizationStatus;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

public final class InterviewAuthzFixture {
    private InterviewAuthzFixture() { }

    public static AuthzResponse response(String actorId, List<String> operations) {
        try {
            KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            String keyId = "interview-test-key";
            long now = Instant.now().getEpochSecond();
            String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
            String hash = AuthzCanonicalHash.bodyHash(new byte[0]);
            AuthorizationSnapshot snapshot = AuthzSignature.sign(new AuthorizationSnapshot(actorId, 1, 2, false,
                    operations, null, AuthzContract.ALGORITHM, keyId, null), key.getPrivate());
            AuthorizationContext context = AuthzSignature.sign(new AuthorizationContext(actorId, 1, 0,
                    AuthzCanonicalHash.snapshotHash(snapshot), operations, "interview-service", "request-1",
                    "run-1", "session-1", hash, hash, nonce, now, now + 30, now + 30,
                    AuthzContract.ALGORITHM, keyId, null), key.getPrivate());
            AuthzFreshness freshness = AuthzSignature.sign(new AuthzFreshness(actorId, 1, 0,
                    AuthorizationStatus.ACTIVE, AuthzCanonicalHash.snapshotHash(snapshot), now, now + 30,
                    nonce, AuthzContract.ALGORITHM, keyId, null), key.getPrivate());
            return new AuthzResponse(snapshot, context, freshness);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
