package com.bkanent.media.service;

import com.bkanent.common.a2a.A2aCredentialContext;
import com.bkanent.common.a2a.VerifiedDomainAuthorization;
import com.bkanent.common.authz.AuthzCanonicalHash;
import com.bkanent.common.authz.AuthzRequestScope;
import com.bkanent.common.mcp.authz.McpAuthorizationScope;
import com.bkanent.common.rag.RagActorMode;
import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagAuthorizedListingAssetWriteRequest;
import com.bkanent.common.rag.RagCanonicalHash;
import com.bkanent.common.rag.RagContextAuthorizedListingAssetWriteRequest;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rag.RagListingReadRequest;
import com.bkanent.common.rag.RagOperation;
import com.bkanent.common.rag.RagRequestSigner;
import com.bkanent.common.rpc.ListingMasterRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** Confirms the Auth-mapped broker owns a Listing before a background media write is accepted. */
@Component
public class MediaListingAuthorizationClient {
    private static final String CALLER = "media-worker-service";
    private static final String AUDIENCE = "listing-master-service";

    @DubboReference(check = false)
    private ListingMasterRpcService listings;

    private final String keyId;
    private final String secret;

    @Autowired
    public MediaListingAuthorizationClient(
            @Value("${media.listing-authorization.key-id:media-listing-current}") String keyId,
            @Value("${media.listing-authorization.signing-secret:}") String secret) {
        this.keyId = keyId;
        this.secret = secret;
    }

    public MediaListingAuthorizationClient(ListingMasterRpcService listings, String keyId, String secret) {
        this(keyId, secret);
        this.listings = listings;
    }

    public void requireAssetWrite(Long listingId) {
        if (listingId == null || listingId <= 0) throw new RagException(RagErrorCode.INVALID_ARGUMENT);
        if (listings == null) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        if (AuthzRequestScope.present()) {
            authorizeHttp(listingId);
            return;
        }
        var lease = A2aCredentialContext.snapshot();
        if (lease != null && lease.versioned()) {
            authorizeContext(listingId, lease.authorizationContext(), lease.freshness(), lease::verify);
            return;
        }
        if (McpAuthorizationScope.current() != null) {
            var verified = McpAuthorizationScope.requireCurrent();
            authorizeContext(listingId, verified.context(), verified.freshness(),
                    () -> McpAuthorizationScope.requireCurrent());
            return;
        }
        throw new com.bkanent.common.authz.AuthzException(
                com.bkanent.common.authz.AuthzErrorCode.AUTHENTICATION_REQUIRED);
    }

    private void authorizeHttp(Long listingId) {
        var authorization = AuthzRequestScope.requireCurrent();
        String operation = authorization.snapshot().businessAdmin()
                ? "listing.assets.bind.all" : "listing.assets.bind.own";
        AuthzRequestScope.requireOperation(operation);
        var request = new RagListingReadRequest(1, UUID.randomUUID().toString(), listingId, null);
        var authorized = new RagAuthorizedListingAssetWriteRequest(1, request,
                AuthzCanonicalHash.signedResponseHash(authorization));
        var envelope = envelope(request, authorized, authorization.context().actorId(),
                Math.min(authorization.context().expiresAtEpochSecond(),
                        Math.min(authorization.context().runHardDeadlineEpochSecond(),
                                authorization.freshness().notAfterEpochSecond())));
        if (!listings.authorizeAssetWrite(authorized, envelope, authorization)) {
            throw new com.bkanent.common.authz.AuthzException(
                    com.bkanent.common.authz.AuthzErrorCode.PERMISSION_DENIED);
        }
    }

    private void authorizeContext(Long listingId, com.bkanent.common.authz.AuthorizationContext context,
                                  com.bkanent.common.authz.AuthzFreshness freshness, Runnable validate) {
        validate.run();
        String operation = context.allowedOperations().contains("listing.assets.bind.all")
                ? "listing.assets.bind.all" : "listing.assets.bind.own";
        VerifiedDomainAuthorization.requirePermission(operation);
        var request = new RagListingReadRequest(1, UUID.randomUUID().toString(), listingId, null);
        var authorized = new RagContextAuthorizedListingAssetWriteRequest(1, request, context, freshness);
        var envelope = envelope(request, authorized, context.actorId(),
                Math.min(context.expiresAtEpochSecond(), Math.min(context.runHardDeadlineEpochSecond(),
                        freshness.notAfterEpochSecond())));
        if (!listings.authorizeAssetWriteContext(authorized, envelope)) {
            throw new com.bkanent.common.authz.AuthzException(
                    com.bkanent.common.authz.AuthzErrorCode.PERMISSION_DENIED);
        }
    }

    private RagAuthEnvelope envelope(RagListingReadRequest request, Object authorized, String actorId,
                                     long contextDeadline) {
        byte[] key = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        long now = Instant.now().getEpochSecond();
        long expiresAt = Math.min(now + 60, contextDeadline);
        if (expiresAt <= now) throw new RagException(RagErrorCode.AUTHENTICATION_EXPIRED);
        return RagRequestSigner.sign(new RagAuthEnvelope(1, keyId, AUDIENCE, CALLER, RagOperation.READ,
                request.requestId(), RagCanonicalHash.requestHash(authorized), RagActorMode.USER,
                actorId, now, expiresAt, null, null), key);
    }
}
