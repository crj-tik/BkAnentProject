package com.bkanent.media.service;

import com.bkanent.common.a2a.VerifiedDomainAuthorization;
import com.bkanent.common.authz.AuthzCanonicalHash;
import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.authz.AuthzSignature;
import com.bkanent.common.authz.AuthorizationContext;
import com.bkanent.common.model.MediaGenerateTaskRequest;
import com.bkanent.media.config.MediaAuthorizationProperties;
import com.bkanent.media.model.MediaBackgroundPermit;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Mints and verifies one-use, exact-payload worker permits derived from Auth-signed scopes. */
@Component
public class MediaBackgroundPermitSigner {
    public static final String BACKGROUND_OPERATION = "media.generate.background";
    private static final String LISTING_OWN = "listing.assets.bind.own";
    private static final String LISTING_ALL = "listing.assets.bind.all";
    private static final String SUBMIT_OWN = "media.task.submit.own";
    private static final String SUBMIT_ALL = "media.task.submit.all";
    private static final Set<String> CONTEXT_AUDIENCES = Set.of("agent-service", "media-worker-service");
    private final MediaAuthorizationProperties properties;

    public MediaBackgroundPermitSigner(MediaAuthorizationProperties properties) {
        this.properties = properties;
    }

    public MediaBackgroundPermit create(String taskId, MediaGenerateTaskRequest request,
                                        VerifiedDomainAuthorization.VerifiedContext verified) {
        requireEnabled();
        AuthorizationContext context = verified.context();
        String ownerActorId = verified.actorId();
        Long brokerId = context.businessIdentities().get("BROKER");
        boolean listingWide = context.allowedOperations().contains(LISTING_ALL);
        boolean listingOwn = context.allowedOperations().contains(LISTING_OWN);
        boolean submitAllowed = context.allowedOperations().contains(SUBMIT_OWN)
                || context.allowedOperations().contains(SUBMIT_ALL);
        if (!context.allowedOperations().contains(BACKGROUND_OPERATION) || !(listingWide || listingOwn)
                || !submitAllowed || listingOwn && !listingWide && brokerId == null
                || request == null || request.listingId() == null) {
            throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
        }
        long expiry = verified.expiresAtEpochSecond();
        String hash = requestHash(request);
        MediaBackgroundPermit unsigned = new MediaBackgroundPermit(taskId, hash, ownerActorId, brokerId,
                request.listingId(), expiry, context, verified.freshness(), properties.getSigningKeyId(), null);
        return withSignature(unsigned, sign(unsigned));
    }

    public void verify(MediaBackgroundPermit permit, String expectedTaskId, MediaGenerateTaskRequest request) {
        requireEnabled();
        if (permit == null || request == null || expectedTaskId == null
                || permit.authorizationContext() == null || permit.freshness() == null
                || permit.ownerActorId() == null || permit.requestHash() == null || permit.signature() == null
                || request.listingId() == null
                || !expectedTaskId.equals(permit.taskId())
                || !request.listingId().equals(permit.listingId())
                || !permit.ownerActorId().equals(permit.authorizationContext().actorId())
                || !permit.requestHash().equals(requestHash(request))
                || !properties.getSigningKeyId().equals(permit.keyId())
                || !MessageDigest.isEqual(sign(permit), decodeSignature(permit.signature()))) {
            throw new AuthzException(AuthzErrorCode.AUTHENTICATION_FAILED);
        }
        var context = permit.authorizationContext();
        var freshness = permit.freshness();
        long now = Instant.now().getEpochSecond();
        if (!CONTEXT_AUDIENCES.contains(context.audience())
                || !context.actorId().equals(freshness.userId())
                || context.permissionRevision() != freshness.permissionRevision()
                || context.credentialEpoch() != freshness.credentialEpoch()
                || !context.permissionHash().equals(freshness.permissionHash())
                || context.issuedAtEpochSecond() < freshness.checkedAtEpochSecond()
                || freshness.status() != com.bkanent.common.authz.AuthorizationStatus.ACTIVE
                || now >= permit.expiresAtEpochSecond()
                || permit.expiresAtEpochSecond() > context.expiresAtEpochSecond()
                || permit.expiresAtEpochSecond() > context.runHardDeadlineEpochSecond()
                || permit.expiresAtEpochSecond() > freshness.notAfterEpochSecond()
                || !context.allowedOperations().contains(BACKGROUND_OPERATION)
                || !(context.allowedOperations().contains(LISTING_OWN)
                    || context.allowedOperations().contains(LISTING_ALL))
                || !(context.allowedOperations().contains(SUBMIT_OWN)
                    || context.allowedOperations().contains(SUBMIT_ALL))) {
            throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
        }
        Long signedBroker = context.businessIdentities().get("BROKER");
        if (permit.brokerId() != null && !permit.brokerId().equals(signedBroker)
                || permit.brokerId() == null && !context.allowedOperations().contains(LISTING_ALL)) {
            throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
        }
        AuthzSignature.verifyContext(context, context.audience(), properties.decodedAuthorityPublicKeys(),
                Instant.ofEpochSecond(now));
        AuthzSignature.verifyFreshness(freshness, properties.decodedAuthorityPublicKeys(), Instant.ofEpochSecond(now));
    }

    /** The permit's hard bound is checked again by the task store before it grants a worker claim. */
    public boolean isExpired(MediaBackgroundPermit permit) {
        return permit == null || Instant.now().getEpochSecond() >= permit.expiresAtEpochSecond();
    }

    public static String requestHash(MediaGenerateTaskRequest request) {
        return HexFormat.of().formatHex(sha256(encode(output -> {
            write(output, "media-task-request.v1");
            write(output, request.listingId());
            write(output, request.contentId());
            write(output, request.callerService());
            write(output, request.taskType());
            write(output, request.prompt());
            write(output, request.angles());
            write(output, request.expectedAssetCount());
        })));
    }

    private byte[] sign(MediaBackgroundPermit permit) {
        return hmac(encode(output -> {
            write(output, "media-background-permit.v1");
            write(output, permit.taskId());
            write(output, permit.requestHash());
            write(output, permit.ownerActorId());
            write(output, permit.brokerId());
            write(output, permit.listingId());
            output.writeLong(permit.expiresAtEpochSecond());
            write(output, AuthzCanonicalHash.signedContextHash(permit.authorizationContext()));
            write(output, AuthzCanonicalHash.signedFreshnessHash(permit.freshness()));
            write(output, permit.keyId());
        }));
    }

    private byte[] hmac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.signingSecretBytes(), "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (Exception failure) {
            throw new IllegalStateException("Media task permit signing is unavailable", failure);
        }
    }

    private void requireEnabled() {
        if (!properties.isEnabled() || properties.signingSecretBytes().length < 32
                || properties.getAuthorityPublicKeys().isEmpty()) {
            throw new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
    }

    private static MediaBackgroundPermit withSignature(MediaBackgroundPermit permit, byte[] signature) {
        return new MediaBackgroundPermit(permit.taskId(), permit.requestHash(), permit.ownerActorId(),
                permit.brokerId(), permit.listingId(), permit.expiresAtEpochSecond(),
                permit.authorizationContext(), permit.freshness(), permit.keyId(),
                HexFormat.of().formatHex(signature));
    }

    private static byte[] decodeSignature(String signature) {
        try { return HexFormat.of().parseHex(signature); }
        catch (RuntimeException failure) { throw new AuthzException(AuthzErrorCode.AUTHENTICATION_FAILED); }
    }

    private static byte[] encode(IoWriter writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            writer.write(output);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("Media permit encoding failed", failure);
        }
    }

    private static void write(DataOutputStream output, Object value) throws IOException {
        if (value == null) {
            output.writeInt(-1);
        } else if (value instanceof List<?> list) {
            output.writeInt(list.size());
            for (Object item : list) write(output, item);
        } else {
            byte[] text = value.toString().getBytes(StandardCharsets.UTF_8);
            output.writeInt(text.length);
            output.write(text);
        }
    }

    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (Exception failure) { throw new IllegalStateException("SHA-256 is unavailable", failure); }
    }

    @FunctionalInterface private interface IoWriter { void write(DataOutputStream output) throws IOException; }
}
