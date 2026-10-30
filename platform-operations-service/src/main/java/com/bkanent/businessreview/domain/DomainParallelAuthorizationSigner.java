package com.bkanent.businessreview.domain;

import com.bkanent.common.a2a.VerifiedDomainAuthorization;
import com.bkanent.common.authz.*;
import com.bkanent.common.security.ServiceIdentityCrypto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Signs each domain-parallel REST request with the locally verified user context and this service identity. */
@Component
public class DomainParallelAuthorizationSigner implements DomainParallelRequestAuthenticator {
    public static final String HEADER = "X-Bkanent-Domain-Authorization";
    public static final int MAX_HEADER_CHARS = 64 * 1024;
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private final String keyId;
    private final Path privateKeyPath;
    private final ObjectMapper mapper;
    private volatile PrivateKey privateKey;

    public DomainParallelAuthorizationSigner(
            @Value("${service.identity.key-id:}") String keyId,
            @Value("${service.identity.private-key-file:}") String privateKeyFile,
            ObjectMapper mapper) {
        this.keyId = keyId;
        this.privateKeyPath = privateKeyFile == null || privateKeyFile.isBlank() ? null : Path.of(privateKeyFile);
        this.mapper = mapper;
    }

    @Override
    public String authorizationHeader(byte[] body, String targetAudience, String operation,
                                      String method, String targetPath) {
        if (body == null || body.length == 0 || targetAudience == null || operation == null
                || method == null || !method.matches("[A-Z]{1,16}")) {
            throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
        }
        try {
            String path = AuthzWireCodec.validatePath(targetPath);
            VerifiedDomainAuthorization.requireOperation(operation);
            VerifiedDomainAuthorization.VerifiedContext verified =
                    VerifiedDomainAuthorization.requireVerifiedContext();
            AuthorizationContext context = verified.context();
            AuthzFreshness freshness = verified.freshness();
            String callerService = "platform-operations-service";
            if (!callerService.equals(context.audience()) || !context.allowedOperations().contains(operation)) {
                throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
            }
            Instant now = Instant.now();
            long issuedAt = now.getEpochSecond();
            long expiresAt = Math.min(issuedAt + AuthzDeadlines.REQUEST_SECONDS,
                    Math.min(context.expiresAtEpochSecond(), Math.min(context.runHardDeadlineEpochSecond(),
                            freshness.notAfterEpochSecond())));
            if (expiresAt <= issuedAt || issuedAt < freshness.checkedAtEpochSecond()) {
                throw new AuthzException(AuthzErrorCode.EXPIRED);
            }
            byte[] nonceBytes = new byte[32];
            RANDOM.nextBytes(nonceBytes);
            String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
            String requestId = "dp1|" + method + "|" + path + "|" + UUID.randomUUID();
            if (requestId.length() > 128) throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
            DelegationBinding binding = new DelegationBinding(callerService, targetAudience, context.actorId(),
                    List.of(operation), context.runId(), null, requestId,
                    AuthzCanonicalHash.bodyHash(body), context.policyDigest(), nonce, issuedAt, expiresAt);
            String requestHash = AuthzCanonicalHash.delegationRequestHash(context, binding);
            CallerProof proof = AuthzSignature.sign(new CallerProof(callerService, targetAudience,
                    AuthzRpcOperation.VALIDATE_DELEGATION, requestId, requestHash, nonce, issuedAt, expiresAt,
                    AuthzContract.ALGORITHM, requireKeyId(), null), signingKey());
            String encoded = AuthzWireCodec.encodeDelegatedAuthorizationRequest(
                    new DelegatedAuthorizationRequest(context, freshness, binding, proof));
            if (encoded.length() > MAX_HEADER_CHARS) throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
            return encoded;
        } catch (AuthzException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new AuthzException(AuthzErrorCode.AUTHENTICATION_FAILED);
        } catch (Exception failure) {
            throw new AuthzException(AuthzErrorCode.AUTHENTICATION_FAILED);
        }
    }

    public byte[] serialize(Object request) {
        try {
            return mapper.writeValueAsBytes(request);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not serialize domain source request", failure);
        }
    }

    private String requireKeyId() {
        if (keyId == null || !keyId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}"))
            throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        return keyId;
    }

    private PrivateKey signingKey() {
        PrivateKey current = privateKey;
        if (current != null) return current;
        synchronized (this) {
            if (privateKey == null) privateKey = ServiceIdentityCrypto.loadPrivateKey(privateKeyPath);
            return privateKey;
        }
    }
}
