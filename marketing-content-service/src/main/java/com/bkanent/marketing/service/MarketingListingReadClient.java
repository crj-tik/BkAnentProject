package com.bkanent.marketing.service;

import com.bkanent.common.a2a.A2aCredentialContext;
import com.bkanent.common.authz.AuthzCanonicalHash;
import com.bkanent.common.authz.AuthzRequestScope;
import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.AuthPermissionRpcService;
import com.bkanent.common.rpc.ListingMasterRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** Signed USER summary read; absence of a real HTTP/A2A credential never selects SERVICE. */
@Component
public class MarketingListingReadClient {
    private final ListingMasterRpcService listings;
    @DubboReference(check = false) private AuthPermissionRpcService auth;
    private final String keyId;
    private final String secret;
    public MarketingListingReadClient(ListingMasterRpcService listings,
                                      @Value("${marketing.listing-read.key-id:marketing-read-current}") String keyId,
                                      @Value("${marketing.listing-read.signing-secret:}") String secret) {
        this.listings = listings; this.keyId = keyId; this.secret = secret;
    }
    public ListingDTO get(Long id) {
        if (id == null || id <= 0) throw new RagException(RagErrorCode.INVALID_ARGUMENT);
        if (AuthzRequestScope.present()) return getWithSignedAuthorization(id);
        A2aCredentialContext.Lease lease = A2aCredentialContext.snapshot();
        if (lease != null && lease.versioned()) return getWithVersionedContext(id, lease);
        RagCredentialContext credentials;
        String actor;
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet && servlet.getRequest().getHeader("Authorization") != null) {
            String header = servlet.getRequest().getHeader("Authorization");
            if (!header.startsWith("Bearer ") || header.substring(7).isBlank()) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
            credentials = new RagCredentialContext(header.substring(7));
            if (auth == null) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
            com.bkanent.common.rpc.AuthenticatedPrincipal principal;
            try { principal = auth.resolvePrincipal(credentials.accessToken()); }
            catch (RuntimeException failure) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }
            if (principal == null || principal.userId() == null || principal.userId() <= 0) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
            actor = principal.userId().toString();
        } else {
            credentials = A2aCredentialContext.currentCredentials();
            actor = A2aCredentialContext.currentActorId();
        }
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        var request = new RagListingReadRequest(1,UUID.randomUUID().toString(),id,null);
        long now = Instant.now().getEpochSecond();
        var envelope = RagRequestSigner.sign(new RagAuthEnvelope(1,keyId,"listing-master-service","marketing-content-service",RagOperation.READ,
                request.requestId(),RagCanonicalHash.requestHash(request),RagActorMode.USER,actor,now,now+60,
                RagCanonicalHash.credentialDigest(credentials),null),secret.getBytes(StandardCharsets.UTF_8));
        try {
            var results = listings.read(request,envelope,credentials);
            return requireMatchingResult(id, results);
        } catch (RagException failure) { throw failure; }
        catch (RuntimeException failure) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }
    }

    private ListingDTO getWithVersionedContext(Long id, A2aCredentialContext.Lease lease) {
        lease.verify();
        var context = lease.authorizationContext();
        var freshness = lease.freshness();
        String operation = context.allowedOperations().contains("listing.read.all")
                ? "listing.read.all" : "listing.read";
        lease.requireOperation(operation);
        if (listings == null) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
        var request = new RagListingReadRequest(1, UUID.randomUUID().toString(), id, null);
        var authorizedRequest = new RagContextAuthorizedListingReadRequest(1, request, context, freshness);
        long now = Instant.now().getEpochSecond();
        long expiresAt = Math.min(now + 60, Math.min(context.expiresAtEpochSecond(),
                Math.min(context.runHardDeadlineEpochSecond(), freshness.notAfterEpochSecond())));
        if (expiresAt <= now) throw new RagException(RagErrorCode.AUTHENTICATION_EXPIRED);
        var envelope = RagRequestSigner.sign(new RagAuthEnvelope(1, keyId, "listing-master-service",
                        "marketing-content-service", RagOperation.READ, request.requestId(),
                        RagCanonicalHash.requestHash(authorizedRequest), RagActorMode.USER,
                        context.actorId(), now, expiresAt, null, null),
                secret.getBytes(StandardCharsets.UTF_8));
        try {
            return requireMatchingResult(id, listings.readAuthorizedContext(authorizedRequest, envelope));
        } catch (RagException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private ListingDTO getWithSignedAuthorization(Long id) {
        if (listings == null) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
        var authorization = AuthzRequestScope.requireCurrent();
        String operation = authorization.snapshot().businessAdmin() ? "listing.read.all" : "listing.read";
        AuthzRequestScope.requireOperation(operation);
        var request = new RagListingReadRequest(1, UUID.randomUUID().toString(), id, null);
        var authorizedRequest = new RagAuthorizedListingReadRequest(1, request,
                AuthzCanonicalHash.signedResponseHash(authorization));
        long now = Instant.now().getEpochSecond();
        var envelope = RagRequestSigner.sign(new RagAuthEnvelope(1, keyId, "listing-master-service",
                        "marketing-content-service", RagOperation.READ, request.requestId(),
                        RagCanonicalHash.requestHash(authorizedRequest), RagActorMode.USER,
                        authorization.context().actorId(), now, now + 60, null, null),
                secret.getBytes(StandardCharsets.UTF_8));
        try {
            return requireMatchingResult(id, listings.readAuthorized(authorizedRequest, envelope, authorization));
        } catch (RagException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private ListingDTO requireMatchingResult(Long id, java.util.List<ListingDTO> results) {
        if (results == null || results.size() > 1 || !results.isEmpty() && !id.equals(results.get(0).id())) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return results.isEmpty() ? null : results.get(0);
    }
}
