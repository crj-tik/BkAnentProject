package com.bkanent.auth.rpc;

import com.bkanent.auth.authorization.AuthorizationRepository;
import com.bkanent.auth.authorization.EffectivePermissionService;
import com.bkanent.auth.config.AuthAuthorizationProperties;
import com.bkanent.auth.service.AuthTokenService;
import com.bkanent.common.authz.*;
import com.bkanent.common.security.ServiceIdentityCrypto;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@DubboService(version = "1.0", group = "authorization", serialization = "fastjson2ctor",
        preferSerialization = "fastjson2ctor")
@ConditionalOnProperty(prefix = "auth.authorization", name = "enabled", havingValue = "true")
public class AuthAuthorizationRpcServiceImpl implements AuthAuthorizationRpcService {
    private static final String AUTH_AUDIENCE = "auth-service";
    private final AuthTokenService tokens;
    private final AuthorizationRepository repository;
    private final AuthAuthorizationProperties properties;
    private final PrivateKey signingKey;
    private final Map<String, PublicKey> authorityKeys;
    private final Clock clock;
    private org.springframework.beans.factory.ObjectProvider<com.bkanent.auth.authorization.redis.AuthorizationOutboxPublisher> publisher;

    @Autowired
    public void setPublisher(org.springframework.beans.factory.ObjectProvider<com.bkanent.auth.authorization.redis.AuthorizationOutboxPublisher> publisher) {
        this.publisher = publisher;
    }

    @Autowired
    public AuthAuthorizationRpcServiceImpl(AuthTokenService tokens, AuthorizationRepository repository,
                                           AuthAuthorizationProperties properties) {
        this(tokens, repository, properties, Clock.systemUTC());
    }

    /** Clock injection keeps deadline and authority-race tests deterministic. */
    public AuthAuthorizationRpcServiceImpl(AuthTokenService tokens, AuthorizationRepository repository,
                                           AuthAuthorizationProperties properties, Clock clock) {
        this.clock = Objects.requireNonNull(clock);
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
            Instant checkedAt = clock.instant();
            require(binding != null && proof != null, AuthzErrorCode.AUTHENTICATION_REQUIRED);
            require(binding.issuedAtEpochSecond() <= checkedAt.getEpochSecond()
                    && binding.expiresAtEpochSecond() > checkedAt.getEpochSecond(), AuthzErrorCode.EXPIRED);
            String requestHash = AuthzCanonicalHash.resolveRequestHash(token, binding);
            AuthAuthorizationProperties.TrustedCaller caller = verifyProof(proof, binding.callerService(),
                    AUTH_AUDIENCE, AuthzRpcOperation.RESOLVE_REQUEST_AUTHORIZATION, binding.requestId(),
                    requestHash, binding.nonce(), checkedAt);
            require(caller.getAudiences().contains(binding.audience()), AuthzErrorCode.PERMISSION_DENIED);
            AuthTokenService.TokenPrincipal principal = accessPrincipal(token, checkedAt);
            var facts = load(principal.userId(), checkedAt);
            long hardDeadline = AuthzDeadlines.entryRunHardDeadline(checkedAt.getEpochSecond(),
                    principal.expiresAt(), deadline(facts));
            requireCurrent(facts);
            consume(proof, checkedAt);
            return registerActive(response(facts, facts.permissions(), binding.audience(), binding.requestId(), binding.runId(),
                    binding.sessionId(), binding.policyDigest(), requestHash, binding.nonce(), checkedAt,
                    hardDeadline, hardDeadline));
        } catch (AuthzException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
    }

    @Override
    public AuthzResponse bindExecution(AuthzResponse ingress, ExecutionBinding binding, CallerProof proof) {
        try {
            Instant now = clock.instant();
            require(ingress != null && binding != null && proof != null, AuthzErrorCode.AUTHENTICATION_REQUIRED);
            AuthzSignature.verifyExecutionBinding(ingress, binding, authorityKeys, now);
            String requestHash = AuthzCanonicalHash.executionBindingRequestHash(ingress, binding);
            var caller = verifyProof(proof, binding.callerService(), AUTH_AUDIENCE, AuthzRpcOperation.BIND_EXECUTION,
                    binding.requestId(), requestHash, binding.nonce(), now);
            require(caller.getAudiences().contains(binding.callerService()), AuthzErrorCode.PERMISSION_DENIED);
            require(caller.getPermissionCodes().containsAll(binding.operations()), AuthzErrorCode.PERMISSION_DENIED);
            // Nonce admission, immutable ingress fact, version recheck and signing join one writer transaction.
            // No token lookup, publication, network or business-resource operation occurs under this lock.
            AuthzResponse bound = repository.withWriterLock(() -> {
                Instant checkedAt = clock.instant();
                AuthzSignature.verifyExecutionBinding(ingress, binding, authorityKeys, checkedAt);
                AuthorizationContext original = ingress.context();
                var facts = load(Long.parseLong(original.actorId()), checkedAt);
                require(facts.permissionRevision() == original.permissionRevision()
                        && facts.credentialEpoch() == original.credentialEpoch()
                        && AuthzCanonicalHash.snapshotHash(snapshot(facts)).equals(original.permissionHash()),
                        AuthzErrorCode.CONTEXT_CONFLICT);
                require(facts.permissions().containsAll(binding.operations()), AuthzErrorCode.PERMISSION_DENIED);
                requireCurrent(facts);
                long expiry = AuthzDeadlines.contextExpiresAt(checkedAt.getEpochSecond(),
                        original.expiresAtEpochSecond(), original.runHardDeadlineEpochSecond(), deadline(facts));
                require(expiry > checkedAt.getEpochSecond(), AuthzErrorCode.EXPIRED);
                AuthzResponse response = response(facts, binding.operations(), original.audience(), binding.requestId(),
                        binding.runId(), binding.sessionId(), original.policyDigest(), requestHash, binding.nonce(),
                        checkedAt, expiry, original.runHardDeadlineEpochSecond());
                Instant admittedAt = clock.instant();
                AuthzSignature.verifyExecutionBindingResponse(ingress, binding, response, authorityKeys, admittedAt);
                verifyProof(proof, binding.callerService(), AUTH_AUDIENCE, AuthzRpcOperation.BIND_EXECUTION,
                        binding.requestId(), requestHash, binding.nonce(), admittedAt);
                consume(proof, admittedAt);
                var result = repository.bindIngressExecution(binding.ingressRequestHash(),
                        AuthzCanonicalHash.executionBindingTupleHash(ingress, binding), facts.userId(),
                        binding.callerService(), binding.runId(), binding.sessionId(), binding.policyDigest(),
                        AuthzCanonicalHash.executionOperationsHash(binding.operations()), binding.executionHash(),
                        expiry, original.runHardDeadlineEpochSecond(), facts.permissionRevision(), facts.credentialEpoch());
                require(result == AuthorizationRepository.BindingResult.CREATED
                        || result == AuthorizationRepository.BindingResult.IDEMPOTENT, AuthzErrorCode.CONTEXT_CONFLICT);
                requireCurrent(facts);
                Instant completedAt = clock.instant();
                AuthzSignature.verifyExecutionBindingResponse(ingress, binding, response, authorityKeys, completedAt);
                verifyProof(proof, binding.callerService(), AUTH_AUDIENCE, AuthzRpcOperation.BIND_EXECUTION,
                        binding.requestId(), requestHash, binding.nonce(), completedAt);
                return response;
            });
            return registerActive(bound);
        } catch (AuthzException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
    }

    @Override
    public AuthzResponse refreshAuthorization(String token, AuthorizationContext original,
                                              RequestBinding binding, CallerProof proof) {
        try {
            Instant checkedAt = clock.instant();
            require(original != null && binding != null && proof != null, AuthzErrorCode.AUTHENTICATION_REQUIRED);
            AuthzSignature.verifyRefreshBinding(original, binding, authorityKeys, checkedAt);
            String requestHash = AuthzCanonicalHash.refreshRequestHash(token, original, binding);
            var caller = verifyProof(proof, binding.callerService(), AUTH_AUDIENCE,
                    AuthzRpcOperation.REFRESH_AUTHORIZATION, binding.requestId(), requestHash,
                    binding.nonce(), checkedAt);
            require(caller.getAudiences().contains(original.audience()), AuthzErrorCode.PERMISSION_DENIED);
            var principal = accessPrincipal(token, checkedAt);
            require(Long.toString(principal.userId()).equals(original.actorId()), AuthzErrorCode.CONTEXT_CONFLICT);
            // Start the freshness window before the authoritative read, not at response completion.
            checkedAt = clock.instant();
            var facts = load(principal.userId(), checkedAt);
            require(facts.credentialEpoch() == original.credentialEpoch()
                    && facts.permissionRevision() >= original.permissionRevision(), AuthzErrorCode.CONTEXT_CONFLICT);
            require(facts.businessIdentities().equals(original.businessIdentities()), AuthzErrorCode.CONTEXT_CONFLICT);
            require(facts.permissionRevision() != original.permissionRevision()
                    || AuthzCanonicalHash.snapshotHash(snapshot(facts)).equals(original.permissionHash()),
                    AuthzErrorCode.CONTEXT_CONFLICT);
            List<String> operations = original.allowedOperations().stream().filter(facts.permissions()::contains).toList();
            long expiry = Math.min(principal.expiresAt(), AuthzDeadlines.contextExpiresAt(checkedAt.getEpochSecond(),
                    original.expiresAtEpochSecond(), original.runHardDeadlineEpochSecond(), deadline(facts)));
            require(expiry > checkedAt.getEpochSecond(), AuthzErrorCode.EXPIRED);
            requireCurrent(facts);
            // Validate all deadlines before consuming the durable proof. No business work occurs here.
            AuthzResponse response = response(facts, operations, original.audience(), binding.requestId(),
                    original.runId(), original.sessionId(), original.policyDigest(), requestHash, binding.nonce(),
                    checkedAt, expiry, original.runHardDeadlineEpochSecond());
            requireCurrent(facts);
            Instant admittedAt = clock.instant();
            AuthzSignature.verifyRefreshBinding(original, binding, authorityKeys, admittedAt);
            verifyProof(proof, binding.callerService(), AUTH_AUDIENCE, AuthzRpcOperation.REFRESH_AUTHORIZATION,
                    binding.requestId(), requestHash, binding.nonce(), admittedAt);
            AuthzSignature.verifyResponse(response, original.audience(), binding.requestId(), requestHash,
                    binding.nonce(), authorityKeys, admittedAt);
            consume(proof, admittedAt);
            return registerActive(response);
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
            Instant checkedAt = clock.instant();
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

    private AuthzResponse registerActive(AuthzResponse response) {
        if (publisher != null) {
            var service = publisher.getIfAvailable();
            if (service != null) {
                var context = response.context();
                service.registerActive(Long.parseLong(context.actorId()), Instant.ofEpochSecond(
                        Math.min(context.expiresAtEpochSecond(), context.runHardDeadlineEpochSecond())));
            }
        }
        return response;
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

    private AuthTokenService.TokenPrincipal accessPrincipal(String token, Instant now) {
        require(!repository.isLogoutRecorded(sha256Utf8(token)), AuthzErrorCode.AUTHENTICATION_FAILED);
        var principal = tokens.parse(token);
        require(principal != null && "access".equals(principal.tokenType()), AuthzErrorCode.AUTHENTICATION_FAILED);
        require(principal.expiresAt() > now.getEpochSecond(), AuthzErrorCode.EXPIRED);
        return principal;
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
                facts.businessIdentities(), AuthzContract.ALGORITHM, properties.getResponseKeyId(), null), signingKey);
        AuthzFreshness freshness = AuthzSignature.sign(new AuthzFreshness(Long.toString(facts.userId()),
                facts.permissionRevision(), facts.credentialEpoch(), AuthorizationStatus.ACTIVE, hash, now,
                AuthzDeadlines.freshnessNotAfter(now), nonce, AuthzContract.ALGORITHM,
                properties.getResponseKeyId(), null), signingKey);
        require(clock.instant().getEpochSecond() < Math.min(expiry, freshness.notAfterEpochSecond()), AuthzErrorCode.EXPIRED);
        return new AuthzResponse(snapshot, context, freshness);
    }

    private AuthorizationSnapshot snapshot(EffectivePermissionService.Snapshot facts) {
        return AuthzSignature.sign(new AuthorizationSnapshot(Long.toString(facts.userId()),
                facts.permissionRevision(), facts.catalogVersion(), facts.businessAdmin(), facts.permissions(),
                deadline(facts), facts.businessIdentities(), AuthzContract.ALGORITHM, properties.getResponseKeyId(), null), signingKey);
    }

    private static Long deadline(EffectivePermissionService.Snapshot facts) {
        return facts.grantDeadline() == null ? null : facts.grantDeadline().getEpochSecond();
    }

    private void consume(CallerProof proof, Instant now) {
        require(repository.consumeNonce(proof.callerService(), proof.keyId(), sha256Utf8(proof.nonce()), now,
                Instant.ofEpochSecond(proof.expiresAtEpochSecond())), AuthzErrorCode.REPLAY_DETECTED);
    }

    /** Raw SHA-256 UTF-8 storage digest; not the framed canonical credential/proof hash. */
    private static String sha256Utf8(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static void require(boolean valid, AuthzErrorCode code) {
        if (!valid) throw new AuthzException(code);
    }
}
