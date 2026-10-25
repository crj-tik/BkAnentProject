package com.bkanent.auth.rpc;

import com.bkanent.auth.authorization.AuthorizationRepository;
import com.bkanent.auth.authorization.EffectivePermissionService;
import com.bkanent.auth.config.AuthAuthorizationProperties;
import com.bkanent.auth.service.AuthTokenService;
import com.bkanent.common.authz.*;
import com.bkanent.common.security.ServiceIdentityCrypto;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@DubboService(version = "1.0", group = "authorization")
@ConditionalOnProperty(prefix = "auth.authorization", name = "enabled", havingValue = "true")
public class AuthAuthorizationRpcServiceImpl implements AuthAuthorizationRpcService {
    private static final String AUTH_AUDIENCE = "auth-service";
    private final AuthTokenService tokens;
    private final AuthorizationRepository repository;
    private final AuthAuthorizationProperties properties;
    private final PrivateKey signingKey;
    private final Map<String, PublicKey> authorityKeys;

    public AuthAuthorizationRpcServiceImpl(AuthTokenService tokens, AuthorizationRepository repository,
                                           AuthAuthorizationProperties properties) {
        this.tokens = tokens;
        this.repository = repository;
        this.properties = properties;
        this.signingKey = properties.signingKey();
        this.authorityKeys = Map.of(properties.getResponseKeyId(),
                ServiceIdentityCrypto.decodePublicKey(properties.getResponsePublicKey()));
    }

    @Override
    public AuthzResponse resolveRequestAuthorization(String token, RequestBinding binding, CallerProof proof) {
        try {
            Instant checkedAt = Instant.now();
            require(binding != null && proof != null, AuthzErrorCode.AUTHENTICATION_REQUIRED);
            require(binding.issuedAtEpochSecond() <= checkedAt.getEpochSecond()
                    && binding.expiresAtEpochSecond() > checkedAt.getEpochSecond(), AuthzErrorCode.EXPIRED);
            String requestHash = AuthzCanonicalHash.resolveRequestHash(token, binding);
            AuthAuthorizationProperties.TrustedCaller caller = verifyProof(proof, binding.callerService(),
                    AUTH_AUDIENCE, AuthzRpcOperation.RESOLVE_REQUEST_AUTHORIZATION, binding.requestId(),
                    requestHash, binding.nonce(), checkedAt);
            require(caller.getAudiences().contains(binding.audience()), AuthzErrorCode.PERMISSION_DENIED);
            AuthTokenService.TokenPrincipal principal = tokens.parse(token);
            require(principal != null && "access".equals(principal.tokenType()), AuthzErrorCode.AUTHENTICATION_FAILED);
            var facts = load(principal.userId(), checkedAt);
            long hardDeadline = AuthzDeadlines.entryRunHardDeadline(checkedAt.getEpochSecond(),
                    principal.expiresAt(), deadline(facts));
            requireCurrent(facts);
            consume(proof, checkedAt);
            return response(facts, facts.permissions(), binding.audience(), binding.requestId(), binding.runId(),
                    binding.sessionId(), binding.policyDigest(), requestHash, binding.nonce(), checkedAt,
                    hardDeadline, hardDeadline);
        } catch (AuthzException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
    }

    @Override
    public AuthzResponse validateDelegation(AuthorizationContext parent, DelegationBinding binding,
                                           CallerProof originProof, CallerProof receiverProof) {
        try {
            Instant checkedAt = Instant.now();
            require(parent != null && binding != null, AuthzErrorCode.AUTHENTICATION_REQUIRED);
            AuthzSignature.verifyDelegationBinding(parent, binding, binding, authorityKeys, checkedAt);
            String requestHash = AuthzCanonicalHash.delegationRequestHash(parent, binding);
            var origin = verifyProof(originProof, binding.callerService(), binding.audience(),
                    AuthzRpcOperation.VALIDATE_DELEGATION, binding.requestId(), requestHash,
                    binding.nonce(), checkedAt);
            var receiver = verifyProof(receiverProof, binding.audience(), AUTH_AUDIENCE,
                    AuthzRpcOperation.VALIDATE_DELEGATION, binding.requestId(), requestHash,
                    receiverProof == null ? null : receiverProof.nonce(), checkedAt);
            require(origin.getAudiences().contains(binding.audience())
                    && receiver.getAudiences().contains(binding.audience()), AuthzErrorCode.PERMISSION_DENIED);
            require(origin.getPermissionCodes().containsAll(binding.operations())
                    && receiver.getPermissionCodes().containsAll(binding.operations()), AuthzErrorCode.PERMISSION_DENIED);
            var facts = load(Long.parseLong(parent.actorId()), checkedAt);
            requireCurrent(facts);
            require(facts.permissionRevision() == parent.permissionRevision()
                    && facts.credentialEpoch() == parent.credentialEpoch(), AuthzErrorCode.CONTEXT_CONFLICT);
            AuthorizationSnapshot snapshot = snapshot(facts);
            require(AuthzCanonicalHash.snapshotHash(snapshot).equals(parent.permissionHash()),
                    AuthzErrorCode.CONTEXT_CONFLICT);
            require(facts.permissions().containsAll(binding.operations()), AuthzErrorCode.PERMISSION_DENIED);
            consume(originProof, checkedAt);
            consume(receiverProof, checkedAt);
            long expiry = AuthzDeadlines.contextExpiresAt(checkedAt.getEpochSecond(),
                    parent.expiresAtEpochSecond(), parent.runHardDeadlineEpochSecond(), deadline(facts));
            return response(facts, binding.operations(), binding.audience(), binding.requestId(), parent.runId(),
                    parent.sessionId(), parent.policyDigest(), requestHash, binding.nonce(), checkedAt,
                    expiry, parent.runHardDeadlineEpochSecond());
        } catch (AuthzException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
    }

    private AuthAuthorizationProperties.TrustedCaller verifyProof(CallerProof proof, String callerService,
                                                                  String audience, AuthzRpcOperation operation,
                                                                  String requestId, String requestHash,
                                                                  String nonce, Instant now) {
        require(proof != null, AuthzErrorCode.AUTHENTICATION_REQUIRED);
        var caller = properties.getTrustedCallers().get(proof.keyId());
        require(caller != null && callerService.equals(caller.getService()), AuthzErrorCode.AUTHENTICATION_FAILED);
        Set<AuthzRpcOperation> operations = caller.getOperations().stream()
                .map(AuthzRpcOperation::valueOf).collect(Collectors.toUnmodifiableSet());
        PublicKey key = ServiceIdentityCrypto.decodePublicKey(caller.getPublicKey());
        AuthzSignature.verifyCallerProof(proof, callerService, audience, operation, requestId, requestHash,
                nonce, operations, Map.of(proof.keyId(), key), now);
        return caller;
    }

    private EffectivePermissionService.Snapshot load(long userId, Instant now) {
        var snapshot = repository.snapshot(userId, now)
                .orElseThrow(() -> new AuthzException(AuthzErrorCode.AUTHENTICATION_FAILED));
        require("ACTIVE".equals(snapshot.status()), AuthzErrorCode.AUTHENTICATION_FAILED);
        require(snapshot.catalogVersion() > 0, AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
        return snapshot;
    }

    private void requireCurrent(EffectivePermissionService.Snapshot facts) {
        var version = repository.currentVersion(facts.userId())
                .orElseThrow(() -> new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE));
        require(version.permissionRevision() == facts.permissionRevision()
                && version.credentialEpoch() == facts.credentialEpoch()
                && "ACTIVE".equals(version.status()), AuthzErrorCode.CONTEXT_CONFLICT);
    }

    private AuthzResponse response(EffectivePermissionService.Snapshot facts, List<String> operations,
                                   String audience, String requestId, String runId, String sessionId,
                                   String policyDigest, String requestHash, String nonce, Instant checkedAt,
                                   long expiry, long hardDeadline) {
        long now = checkedAt.getEpochSecond();
        AuthorizationSnapshot snapshot = snapshot(facts);
        String hash = AuthzCanonicalHash.snapshotHash(snapshot);
        AuthorizationContext context = AuthzSignature.sign(new AuthorizationContext(Long.toString(facts.userId()),
                facts.permissionRevision(), facts.credentialEpoch(), hash, operations, audience, requestId,
                runId, sessionId, policyDigest, requestHash, nonce, now, expiry, hardDeadline,
                AuthzContract.ALGORITHM, properties.getResponseKeyId(), null), signingKey);
        AuthzFreshness freshness = AuthzSignature.sign(new AuthzFreshness(Long.toString(facts.userId()),
                facts.permissionRevision(), facts.credentialEpoch(), AuthorizationStatus.ACTIVE, hash, now,
                AuthzDeadlines.freshnessNotAfter(now), nonce, AuthzContract.ALGORITHM,
                properties.getResponseKeyId(), null), signingKey);
        require(Instant.now().getEpochSecond() < freshness.notAfterEpochSecond(), AuthzErrorCode.EXPIRED);
        return new AuthzResponse(snapshot, context, freshness);
    }

    private AuthorizationSnapshot snapshot(EffectivePermissionService.Snapshot facts) {
        return AuthzSignature.sign(new AuthorizationSnapshot(Long.toString(facts.userId()),
                facts.permissionRevision(), facts.catalogVersion(), facts.businessAdmin(), facts.permissions(),
                deadline(facts), AuthzContract.ALGORITHM, properties.getResponseKeyId(), null), signingKey);
    }

    private static Long deadline(EffectivePermissionService.Snapshot facts) {
        return facts.grantDeadline() == null ? null : facts.grantDeadline().getEpochSecond();
    }

    private void consume(CallerProof proof, Instant now) {
        require(repository.consumeNonce(proof.callerService(), proof.keyId(), nonceHash(proof.nonce()), now,
                Instant.ofEpochSecond(proof.expiresAtEpochSecond())), AuthzErrorCode.REPLAY_DETECTED);
    }

    private static String nonceHash(String nonce) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(nonce.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static void require(boolean valid, AuthzErrorCode code) {
        if (!valid) throw new AuthzException(code);
    }
}
