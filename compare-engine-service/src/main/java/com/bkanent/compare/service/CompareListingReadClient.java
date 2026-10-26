package com.bkanent.compare.service;

import com.bkanent.common.a2a.A2aCredentialContext;
import com.bkanent.common.authz.AuthzCanonicalHash;
import com.bkanent.common.authz.AuthzRequestScope;
import com.bkanent.common.a2a.VerifiedDomainAuthorization;
import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.rag.RagActorMode;
import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagAuthorizedListingReadRequest;
import com.bkanent.common.rag.RagCanonicalHash;
import com.bkanent.common.rag.RagContextAuthorizedListingReadRequest;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rag.RagListingReadRequest;
import com.bkanent.common.rag.RagOperation;
import com.bkanent.common.rag.RagRequestSigner;
import com.bkanent.common.rpc.ListingMasterRpcService;
import com.bkanent.common.mcp.authz.McpAuthorizationScope;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Reads current Listing summaries with one local user context and an exact request proof. */
@Component
public class CompareListingReadClient {
    private static final String CALLER = "compare-engine-service";
    private static final String AUDIENCE = "listing-master-service";

    @DubboReference(check = false)
    private ListingMasterRpcService listings;

    private final String keyId;
    private final String secret;

    @Autowired
    public CompareListingReadClient(
            @Value("${compare.listing-read.key-id:compare-read-current}") String keyId,
            @Value("${compare.listing-read.signing-secret:}") String secret) {
        this.keyId = keyId;
        this.secret = secret;
    }

    public CompareListingReadClient(ListingMasterRpcService listings, String keyId, String secret) {
        this(keyId, secret);
        this.listings = listings;
    }

    public List<ListingDTO> read(List<Long> listingIds) {
        if (listingIds == null || listingIds.isEmpty() || listingIds.size() > 20
                || listingIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT);
        }
        List<ListingDTO> results = new ArrayList<>();
        for (Long listingId : listingIds.stream().distinct().toList()) {
            ListingDTO listing = readOne(listingId);
            if (listing == null) throw new RagException(RagErrorCode.FORBIDDEN);
            results.add(listing);
        }
        return List.copyOf(results);
    }

    private ListingDTO readOne(Long listingId) {
        if (listings == null) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        if (AuthzRequestScope.present()) return readHttp(listingId);
        A2aCredentialContext.Lease lease = A2aCredentialContext.snapshot();
        if (lease != null && lease.versioned()) return readContext(listingId,
                lease.authorizationContext(), lease.freshness(), lease::verify);
        if (McpAuthorizationScope.current() != null) {
            var verified = McpAuthorizationScope.requireCurrent();
            return readContext(listingId, verified.context(), verified.freshness(),
                    () -> McpAuthorizationScope.requireCurrent());
        }
        throw new com.bkanent.common.authz.AuthzException(
                com.bkanent.common.authz.AuthzErrorCode.AUTHENTICATION_REQUIRED);
    }

    private ListingDTO readHttp(Long listingId) {
        if (secretBytes().length < 32) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        var authorization = AuthzRequestScope.requireCurrent();
        String operation = authorization.snapshot().businessAdmin() ? "listing.read.all" : "listing.read";
        AuthzRequestScope.requireOperation(operation);
        var request = new RagListingReadRequest(1, UUID.randomUUID().toString(), listingId, null);
        var authorized = new RagAuthorizedListingReadRequest(1, request,
                AuthzCanonicalHash.signedResponseHash(authorization));
        long now = Instant.now().getEpochSecond();
        var envelope = RagRequestSigner.sign(new RagAuthEnvelope(1, keyId, AUDIENCE, CALLER,
                        RagOperation.READ, request.requestId(), RagCanonicalHash.requestHash(authorized),
                        RagActorMode.USER, authorization.context().actorId(), now, now + 60, null, null), secretBytes());
        try {
            return requireSingle(listingId, listings.readAuthorized(authorized, envelope, authorization));
        } catch (RagException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private ListingDTO readContext(Long listingId, com.bkanent.common.authz.AuthorizationContext context,
                                   com.bkanent.common.authz.AuthzFreshness freshness, Runnable validate) {
        validate.run();
        String operation = context.allowedOperations().contains("listing.read.all")
                ? "listing.read.all" : "listing.read";
        VerifiedDomainAuthorization.requirePermission(operation);
        byte[] signingKey = secretBytes();
        if (signingKey.length < 32) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        var request = new RagListingReadRequest(1, UUID.randomUUID().toString(), listingId, null);
        var authorized = new RagContextAuthorizedListingReadRequest(1, request, context, freshness);
        long now = Instant.now().getEpochSecond();
        long expiresAt = Math.min(now + 60, Math.min(context.expiresAtEpochSecond(),
                Math.min(context.runHardDeadlineEpochSecond(), freshness.notAfterEpochSecond())));
        if (expiresAt <= now) throw new RagException(RagErrorCode.AUTHENTICATION_EXPIRED);
        var envelope = RagRequestSigner.sign(new RagAuthEnvelope(1, keyId, AUDIENCE, CALLER,
                        RagOperation.READ, request.requestId(), RagCanonicalHash.requestHash(authorized),
                        RagActorMode.USER, context.actorId(), now, expiresAt, null, null), signingKey);
        try {
            return requireSingle(listingId, listings.readAuthorizedContext(authorized, envelope));
        } catch (RagException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private ListingDTO requireSingle(Long listingId, List<ListingDTO> found) {
        if (found == null || found.size() > 1
                || !found.isEmpty() && !listingId.equals(found.get(0).id())) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return found.isEmpty() ? null : found.get(0);
    }

    private byte[] secretBytes() {
        return secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    }
}
