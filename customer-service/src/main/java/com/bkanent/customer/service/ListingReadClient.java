package com.bkanent.customer.service;

import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.AuthPermissionRpcService;
import com.bkanent.common.rpc.AuthenticatedPrincipal;
import com.bkanent.common.rpc.ListingMasterRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Dedicated HTTP access-token transport, never a user ID or Dubbo attachment identity. */
@Component
public class ListingReadClient {
    @DubboReference(check = false) private ListingMasterRpcService listings;
    @DubboReference(check = false) private AuthPermissionRpcService auth;
    private final String keyId;
    private final String secret;

    public ListingReadClient(@Value("${customer.listing-read.key-id:customer-read-current}") String keyId,
                             @Value("${customer.listing-read.signing-secret:}") String secret) {
        this.keyId = keyId; this.secret = secret;
    }

    public UserRead authenticate() {
        if (com.bkanent.common.authz.AuthzRequestScope.present()) {
            String operation = com.bkanent.common.authz.AuthzRequestScope.businessAdmin()
                    ? "listing.read.all" : "listing.read";
            com.bkanent.common.authz.AuthzRequestScope.requireOperation(operation);
            var response = com.bkanent.common.authz.AuthzRequestScope.requireCurrent();
            return new UserRead(response.context().actorId(), null, response);
        }
        var attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        String authorization = servlet.getRequest().getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.substring(7).isBlank()) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
        var credentials = new RagCredentialContext(authorization.substring(7));
        if (com.bkanent.common.authz.AuthzRequestScope.present()) {
            return new UserRead(Long.toString(com.bkanent.common.authz.AuthzRequestScope.actorId()), credentials);
        }
        if (auth == null) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        AuthenticatedPrincipal principal;
        try { principal = auth.resolvePrincipal(credentials.accessToken()); }
        catch (RuntimeException failure) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }
        if (principal == null || principal.userId() == null || principal.userId() <= 0) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        return new UserRead(principal.userId().toString(), credentials);
    }

    public List<ListingDTO> search(String keyword, UserRead user) {
        if (listings == null) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        var request = new RagListingReadRequest(1, UUID.randomUUID().toString(), null, keyword == null ? "" : keyword);
        long now = Instant.now().getEpochSecond();
        if (user.authorization() != null) {
            var authorizedRequest = new RagAuthorizedListingReadRequest(1, request,
                    com.bkanent.common.authz.AuthzCanonicalHash.signedResponseHash(user.authorization()));
            var signature = RagRequestSigner.sign(new RagAuthEnvelope(1, keyId, "listing-master-service", "customer-service",
                    RagOperation.READ, request.requestId(), RagCanonicalHash.requestHash(authorizedRequest),
                    RagActorMode.USER, user.actorId(), now, now + 60, null, null), secret.getBytes(StandardCharsets.UTF_8));
            try {
                var result = listings.readAuthorized(authorizedRequest, signature, user.authorization());
                if (result == null) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
                return result;
            } catch (RagException failure) { throw failure; }
            catch (RuntimeException failure) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }
        }
        var signature = RagRequestSigner.sign(new RagAuthEnvelope(1,keyId,"listing-master-service","customer-service",RagOperation.READ,
                request.requestId(),RagCanonicalHash.requestHash(request),RagActorMode.USER,user.actorId(),now,now+60,
                RagCanonicalHash.credentialDigest(user.credentials()),null),secret.getBytes(StandardCharsets.UTF_8));
        try {
            var result = listings.read(request, signature, user.credentials());
            if (result == null) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
            return result;
        } catch (RagException failure) { throw failure; }
        catch (RuntimeException failure) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }
    }

    public record UserRead(String actorId, RagCredentialContext credentials,
                           com.bkanent.common.authz.AuthzResponse authorization) {
        public UserRead(String actorId, RagCredentialContext credentials) { this(actorId, credentials, null); }
        @Override public String toString() { return "UserRead[REDACTED]"; }
    }
}
