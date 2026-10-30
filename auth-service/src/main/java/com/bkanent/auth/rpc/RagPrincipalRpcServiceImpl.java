package com.bkanent.auth.rpc;

import com.bkanent.auth.config.AuthRagRpcProperties;
import com.bkanent.auth.authorization.AuthAccountMutationService;
import com.bkanent.auth.authorization.EffectivePermissionService;
import com.bkanent.auth.entity.UserAccountEntity;
import com.bkanent.auth.service.AuthTokenService;
import com.bkanent.auth.service.UserAccountService;
import com.bkanent.auth.service.UserManagementService;
import com.bkanent.common.rag.AuthRagPrincipal;
import com.bkanent.common.rag.RagActorMode;
import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagCanonicalHash;
import com.bkanent.common.rag.RagCredentialContext;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rag.RagOperation;
import com.bkanent.common.rag.RagRequestSigner;
import com.bkanent.common.rag.RagSigningKey;
import com.bkanent.common.rpc.RagPrincipalRpcService;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;

/**
 * Minimal RAG principal facts (KI-55/D3), not a resource-permission or token-issuing endpoint.
 * Both RPC directions use independent service keys; the existing auth RPC is unchanged.
 * Credentials are dedicated RPC arguments, never logged or copied into the response.
 */
@DubboService
public class RagPrincipalRpcServiceImpl implements RagPrincipalRpcService {

    private static final String AUTH_SERVICE = "auth-service";
    private static final Set<String> ALLOWED_CALLERS = Set.of("rag-service", "listing-master-service", "memory-service",
            "agent-service", "customer-service", "marketing-content-service", "business-service", "interview-service");

    private com.bkanent.auth.service.A2aBeaconStore beacons;
    @org.springframework.beans.factory.annotation.Autowired
    public void setBeacons(com.bkanent.auth.service.A2aBeaconStore beacons) { this.beacons = beacons; }

    private AuthAccountMutationService versionedAuthorization;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setVersionedAuthorization(org.springframework.beans.factory.ObjectProvider<AuthAccountMutationService> provider) {
        this.versionedAuthorization = provider.getIfAvailable();
    }

    private final AuthTokenService authTokenService;
    private final UserAccountService userAccountService;
    private final UserManagementService userManagementService;
    private final AuthRagRpcProperties properties;

    public RagPrincipalRpcServiceImpl(AuthTokenService authTokenService,
                                      UserAccountService userAccountService,
                                      UserManagementService userManagementService,
                                      AuthRagRpcProperties properties) {
        this.authTokenService = authTokenService;
        this.userAccountService = userAccountService;
        this.userManagementService = userManagementService;
        this.properties = properties;
    }

    @Override
    public AuthRagPrincipal resolveRagPrincipal(RagCredentialContext credentials, RagAuthEnvelope authentication) {
        if (authentication == null || credentials == null) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
        AuthRagRpcProperties.TrustedKey trustedKey = properties.getTrustedKeys().get(authentication.keyId());
        if (trustedKey == null || !StringUtils.hasText(trustedKey.getCallerService())
                || !ALLOWED_CALLERS.contains(trustedKey.getCallerService())
                || !StringUtils.hasText(trustedKey.getSecret()) || !StringUtils.hasText(properties.getResponseKeyId())
                || !StringUtils.hasText(properties.getResponseSecret())) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
        byte[] incomingSecret = trustedKey.getSecret().getBytes(StandardCharsets.UTF_8);
        byte[] responseSecret = properties.getResponseSecret().getBytes(StandardCharsets.UTF_8);
        if (incomingSecret.length < 32 || responseSecret.length < 32) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
        RagSigningKey incomingKey = new RagSigningKey(authentication.keyId(), trustedKey.getCallerService(), null,
                Set.of(RagOperation.RESOLVE_PRINCIPAL), incomingSecret);
        String credentialDigest = RagCanonicalHash.credentialDigest(credentials);
        // This RPC has no business payload: its request hash is the canonical credential digest.
        RagRequestSigner.verify(authentication, credentialDigest, AUTH_SERVICE, RagOperation.RESOLVE_PRINCIPAL,
                authentication.requestId(), credentials, incomingKey, Instant.now());

        if (credentials.beacon() != null) {
            if (beacons == null) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
            AuthRagPrincipal principal = beacons.principal(credentials, incomingKey.callerService());
            Instant now = Instant.now();
            RagAuthEnvelope response = RagRequestSigner.sign(new RagAuthEnvelope(CONTRACT_VERSION,
                    properties.getResponseKeyId(), incomingKey.callerService(), AUTH_SERVICE,
                    RagOperation.RESOLVE_PRINCIPAL_RESPONSE, authentication.requestId(), RagCanonicalHash.principalHash(principal),
                    RagActorMode.USER, principal.userId().toString(), now.getEpochSecond(),
                    Math.min(principal.expiresAtEpochSecond(), authentication.expiresAtEpochSecond()), credentialDigest, null), responseSecret);
            return principal.withAttestation(response);
        }

        AuthTokenService.TokenPrincipal tokenPrincipal;
        UserAccountEntity account;
        boolean roleActive;
        AuthRagPrincipal principal;
        try {
            tokenPrincipal = authTokenService.parse(credentials.accessToken());
            if (tokenPrincipal == null && authTokenService.isExpiredAccessToken(credentials.accessToken())) {
                throw new RagException(RagErrorCode.AUTHENTICATION_EXPIRED);
            }
            if (tokenPrincipal == null || !"access".equals(tokenPrincipal.tokenType())
                    || tokenPrincipal.userId() <= 0
                    || (authentication.actorMode() == RagActorMode.USER
                    && !Long.toString(tokenPrincipal.userId()).equals(authentication.actorId()))) {
                throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
            }
            account = userAccountService.getById(tokenPrincipal.userId());
            if (account == null || !Long.valueOf(tokenPrincipal.userId()).equals(account.getId())
                    || !Integer.valueOf(0).equals(account.getDeleted())
                    || !"ACTIVE".equalsIgnoreCase(account.getAccountStatus())) {
                throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
            }
            if (versionedAuthorization != null) {
                AuthAccountMutationService.AuthorizationInfo info = versionedAuthorization.authorizationInfo(account.getId());
                EffectivePermissionService.Snapshot snapshot = info.snapshot();
                if (!"ACTIVE".equals(snapshot.status())) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
                roleActive = snapshot.businessAdmin() || !info.roles().isEmpty();
                principal = new AuthRagPrincipal(account.getId(), account.getRoleCode(), true, roleActive,
                        tokenPrincipal.expiresAt(), AuthRagPrincipal.VERSIONED_AUTHORIZATION_VERSION,
                        snapshot.permissionRevision(), snapshot.credentialEpoch(), snapshot.businessAdmin(),
                        info.roles(), snapshot.permissions(), null);
            } else {
                if (!UserManagementService.isSupportedRole(account.getRoleCode())) {
                    throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
                }
                roleActive = userManagementService.isActiveRole(account.getRoleCode());
                if (!roleActive) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
                principal = new AuthRagPrincipal(account.getId(), account.getRoleCode(), true, true,
                        tokenPrincipal.expiresAt());
            }
        } catch (RagException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // Dependency exceptions can contain SQL/credentials: never propagate their text or cause.
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        }

        Instant now = Instant.now();
        long attestationExpiresAt = Math.min(tokenPrincipal.expiresAt(), authentication.expiresAtEpochSecond());
        if (attestationExpiresAt <= now.getEpochSecond()) {
            throw new RagException(RagErrorCode.AUTHENTICATION_EXPIRED);
        }
        RagAuthEnvelope unsigned = new RagAuthEnvelope(CONTRACT_VERSION, properties.getResponseKeyId(),
                incomingKey.callerService(),
                AUTH_SERVICE, RagOperation.RESOLVE_PRINCIPAL_RESPONSE, authentication.requestId(),
                RagCanonicalHash.principalHash(principal), RagActorMode.USER, Long.toString(principal.userId()),
                now.getEpochSecond(), attestationExpiresAt, credentialDigest, null);
        RagAuthEnvelope attestation = RagRequestSigner.sign(unsigned, responseSecret);
        return principal.withAttestation(attestation);
    }
}
