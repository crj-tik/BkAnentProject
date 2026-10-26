package com.bkanent.memory.service;

import com.bkanent.common.memory.MemoryContextKey;
import com.bkanent.common.memory.MemoryErrorCode;
import com.bkanent.common.memory.MemoryException;
import com.bkanent.common.memory.MemoryRequestProof;
import com.bkanent.common.memory.MemoryRequestSigner;
import com.bkanent.common.memory.MemoryV2Contract;
import com.bkanent.common.memory.MemoryV2Request;
import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.authz.AuthzFreshness;
import com.bkanent.common.authz.AuthzSignature;
import com.bkanent.common.authz.AuthorizationContext;
import com.bkanent.common.security.ServiceIdentityCrypto;
import com.bkanent.common.memory.MemoryDeliveryRequest;
import com.bkanent.common.memory.MemoryExperienceOutcomeRequest;
import com.bkanent.common.memory.MemoryDomainExperienceManagementRequest;
import com.bkanent.common.rpc.AuthPermissionRpcService;
import com.bkanent.memory.config.MemoryV2SecurityProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/** Authenticates the signed current-user assertion from one deployment-trusted domain caller. */
@Service
public class MemoryV2RequestSecurity {
    private final MemoryV2SecurityProperties properties;
    private final AuthPermissionRpcService authPermissionRpcService;
    private final MemoryV2ReplayGuard replayGuard;
    private final MeterRegistry meterRegistry;

    /** Spring-injected constructor; the registry records accepted signatures by protocol and algorithm. */
    @Autowired
    public MemoryV2RequestSecurity(MemoryV2SecurityProperties properties,
                                   AuthPermissionRpcService authPermissionRpcService,
                                   MemoryV2ReplayGuard replayGuard,
                                   MeterRegistry meterRegistry) {
        this.properties = properties;
        this.authPermissionRpcService = authPermissionRpcService;
        this.replayGuard = replayGuard;
        this.meterRegistry = meterRegistry;
    }

    /** Direct-construction convenience for isolated service tests. */
    public MemoryV2RequestSecurity(MemoryV2SecurityProperties properties,
                                   AuthPermissionRpcService authPermissionRpcService,
                                   MemoryV2ReplayGuard replayGuard) {
        this(properties, authPermissionRpcService, replayGuard, new SimpleMeterRegistry());
    }

    /**
     * The trusted caller must authenticate the interactive user before signing. Memory
     * independently rechecks that the asserted account is active and may use Agents.
     * Returns the verified owner id for downstream ownership checks.
     */
    public long authorize(MemoryV2Request request, MemoryRequestProof proof, String expectedOperation) {
        long ownerId = verifyCurrentUser(request, proof, expectedOperation);
        replayGuard.consume(proof.keyId(), proof.nonce(), proof.expiresAtEpochSecond());
        return ownerId;
    }

    /** Output check for an already consumed request; signed contexts are rechecked locally. */
    void revalidate(MemoryV2Request request, MemoryRequestProof proof, long expectedOwner, String expectedOperation) {
        if (verifyCurrentUser(request, proof, expectedOperation) != expectedOwner) {
            throw new MemoryException(MemoryErrorCode.FORBIDDEN);
        }
    }

    private long verifyCurrentUser(MemoryV2Request request, MemoryRequestProof proof, String expectedOperation) {
        if (proof == null || proof.actor() != MemoryRequestProof.Actor.USER) {
            throw new MemoryException(MemoryErrorCode.AUTHENTICATION_FAILED);
        }
        long ownerId = verifyCaller(request, proof, expectedOperation, false);
        if (MemoryRequestSigner.hasAuthorizationContext(proof)) {
            if (!properties.isAuthorizationContextEnabled()) {
                throw new MemoryException(MemoryErrorCode.AUTHENTICATION_FAILED);
            }
            verifyAuthorizationContext(proof, ownerId, properties.getCurrentUserPermission());
            requireUnexpired(proof);
            return ownerId;
        }
        if (properties.isAuthorizationContextEnabled()) {
            // Once enabled, a caller cannot silently downgrade to the legacy Auth RPC path.
            throw new MemoryException(MemoryErrorCode.AUTHENTICATION_FAILED);
        }
        try {
            if (!authPermissionRpcService.hasPermission(ownerId, properties.getCurrentUserPermission())) {
                throw new MemoryException(MemoryErrorCode.FORBIDDEN);
            }
        } catch (MemoryException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
        }
        requireUnexpired(proof);
        return ownerId;
    }

    /** Verifies the Auth-signed actor/scope locally; the per-request Memory proof still supplies nonce and body binding. */
    private AuthorizationContext verifyAuthorizationContext(MemoryRequestProof proof, long expectedOwner,
                                                              String requiredPermission) {
        AuthorizationContext context = proof.authorizationContext();
        AuthzFreshness freshness = proof.authorizationFreshness();
        var now = Instant.now();
        try {
            var authorityKeys = properties.authorizationAuthorityKeys();
            AuthzSignature.verifyContext(context, "agent-service", authorityKeys, now);
            AuthzSignature.verifyFreshness(freshness, authorityKeys, now);
        } catch (AuthzException failure) {
            throw mapAuthorizationFailure(failure.code());
        } catch (RuntimeException failure) {
            throw new MemoryException(MemoryErrorCode.AUTHENTICATION_FAILED);
        }
        String actor = Long.toString(expectedOwner);
        if (!actor.equals(context.actorId()) || !actor.equals(freshness.userId())
                || freshness.permissionRevision() != context.permissionRevision()
                || freshness.credentialEpoch() != context.credentialEpoch()
                || !freshness.permissionHash().equals(context.permissionHash())
                || context.runId() == null || context.runId().isBlank()
                || context.sessionId() == null || context.sessionId().isBlank()
                || freshness.notAfterEpochSecond() > context.expiresAtEpochSecond()
                || proof.expiresAtEpochSecond() > freshness.notAfterEpochSecond()
                || proof.expiresAtEpochSecond() > context.expiresAtEpochSecond()
                || proof.expiresAtEpochSecond() > context.runHardDeadlineEpochSecond()) {
            throw new MemoryException(MemoryErrorCode.AUTHENTICATION_FAILED);
        }
        if (requiredPermission == null || !context.allowedOperations().contains(requiredPermission)) {
            throw new MemoryException(MemoryErrorCode.FORBIDDEN);
        }
        return context;
    }

    private static MemoryException mapAuthorizationFailure(AuthzErrorCode code) {
        return new MemoryException(switch (code) {
            case EXPIRED -> MemoryErrorCode.AUTHENTICATION_EXPIRED;
            case PERMISSION_DENIED -> MemoryErrorCode.FORBIDDEN;
            default -> MemoryErrorCode.AUTHENTICATION_FAILED;
        });
    }

    /** SERVICE only enters this delivery-specific gate; its identity never becomes a user principal. */
    public void authorizeDelivery(MemoryDeliveryRequest request, MemoryRequestProof proof) {
        if (proof != null && proof.actor() == MemoryRequestProof.Actor.SERVICE) {
            verifyCaller(request, proof, MemoryV2Contract.OPERATION_DELIVER_MESSAGES, true);
            replayGuard.consume(proof.keyId(), proof.nonce(), proof.expiresAtEpochSecond());
        } else {
            authorize(request, proof, MemoryV2Contract.OPERATION_DELIVER_MESSAGES);
        }
    }

    /** Domain publishers may assert only exact outcome events under an explicit deployment allowlist. */
    public long authorizeExperienceOutcome(MemoryExperienceOutcomeRequest request, MemoryRequestProof proof) {
        if (proof == null || proof.actor() != MemoryRequestProof.Actor.SERVICE) {
            throw new MemoryException(MemoryErrorCode.AUTHENTICATION_FAILED);
        }
        long ownerId = verifyCaller(request, proof, MemoryV2Contract.OPERATION_EXPERIENCE_OUTCOME, false);
        var caller = properties.getTrustedCallers().get(request.envelope().caller());
        if (caller == null || !caller.isExperienceOutcomePublisher()) throw new MemoryException(MemoryErrorCode.FORBIDDEN);
        replayGuard.consume(proof.keyId(), proof.nonce(), proof.expiresAtEpochSecond());
        return ownerId;
    }

    /** Only a signed, currently authorized governance user may publish or withdraw a domain generalization. */
    public long authorizeDomainExperienceManagement(MemoryDomainExperienceManagementRequest request,
                                                     MemoryRequestProof proof) {
        long ownerId = verifyCurrentUser(request, proof, MemoryV2Contract.OPERATION_DOMAIN_EXPERIENCE_MANAGE);
        var caller = properties.getTrustedCallers().get(request.envelope().caller());
        if (caller == null || !caller.isExperienceGovernancePublisher()
                || !caller.allows(request.contextKey().domainId(), request.targetAgentId())) {
            throw new MemoryException(MemoryErrorCode.FORBIDDEN);
        }
        if (MemoryRequestSigner.hasAuthorizationContext(proof)) {
            if (!proof.authorizationContext().allowedOperations().contains(properties.getExperienceGovernancePermission())) {
                throw new MemoryException(MemoryErrorCode.FORBIDDEN);
            }
        } else {
            try {
                if (!authPermissionRpcService.hasPermission(ownerId, properties.getExperienceGovernancePermission())) {
                    throw new MemoryException(MemoryErrorCode.FORBIDDEN);
                }
            } catch (MemoryException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
            }
        }
        replayGuard.consume(proof.keyId(), proof.nonce(), proof.expiresAtEpochSecond());
        return ownerId;
    }

    static void requireUnexpired(MemoryRequestProof proof) {
        if (proof.expiresAtEpochSecond() <= Instant.now().getEpochSecond()) {
            throw new MemoryException(MemoryErrorCode.AUTHENTICATION_EXPIRED);
        }
    }

    private long verifyCaller(MemoryV2Request request, MemoryRequestProof proof, String expectedOperation, boolean service) {
        if (request == null || request.envelope() == null || request.contextKey() == null
                || !isOperation(expectedOperation)) {
            throw new MemoryException(MemoryErrorCode.AUTHENTICATION_FAILED);
        }
        var envelope = request.envelope();
        Map<String, MemoryV2SecurityProperties.TrustedCaller> callers = properties.getTrustedCallers();
        MemoryV2SecurityProperties.TrustedCaller caller = callers == null ? null : callers.get(envelope.caller());
        if (caller == null || proof == null) {
            throw new MemoryException(MemoryErrorCode.AUTHENTICATION_FAILED);
        }

        MemoryContextKey context = request.contextKey();
        if (!caller.allows(context.domainId(), context.agentId())) {
            throw new MemoryException(MemoryErrorCode.FORBIDDEN);
        }
        if (service && !caller.isServiceDelivery()) throw new MemoryException(MemoryErrorCode.FORBIDDEN);
        if (!MemoryRequestSigner.isEd25519Proof(proof)) {
            throw new MemoryException(MemoryErrorCode.AUTHENTICATION_FAILED);
        }
        String encodedKey = caller.getPublicKeys().get(proof.keyId());
        if (encodedKey == null || encodedKey.isBlank()) throw new MemoryException(MemoryErrorCode.AUTHENTICATION_FAILED);
        try {
            MemoryRequestSigner.verifyEd25519(request, context, proof, envelope.caller(),
                    MemoryV2Contract.MEMORY_AUDIENCE, expectedOperation, proof.keyId(), Instant.now(),
                    ServiceIdentityCrypto.decodePublicKey(encodedKey));
            meterRegistry.counter("service.identity.signature.verified", "protocol", "memory-v2",
                    "algorithm", ServiceIdentityCrypto.ED25519).increment();
        } catch (IllegalArgumentException exception) {
            throw new MemoryException(MemoryErrorCode.AUTHENTICATION_FAILED);
        }

        long ownerId;
        try {
            ownerId = Long.parseLong(context.ownerId());
        } catch (NumberFormatException exception) {
            throw new MemoryException(MemoryErrorCode.FORBIDDEN);
        }
        if (ownerId <= 0 || !Long.toString(ownerId).equals(context.ownerId())) throw new MemoryException(MemoryErrorCode.FORBIDDEN);
        return ownerId;
    }

    private static boolean isOperation(String operation) {
        return MemoryV2Contract.OPERATION_WORKING_SET.equals(operation)
                || MemoryV2Contract.OPERATION_RECEIVE_PERMIT.equals(operation)
                || MemoryV2Contract.OPERATION_DELIVER_MESSAGES.equals(operation)
                || MemoryV2Contract.OPERATION_SUBMIT_SUMMARY.equals(operation)
                || MemoryV2Contract.OPERATION_QUERY_MATTERS.equals(operation)
                || MemoryV2Contract.OPERATION_QUERY_ENTITIES.equals(operation)
                || MemoryV2Contract.OPERATION_SEARCH_PREFERENCES.equals(operation)
                || MemoryV2Contract.OPERATION_SEARCH_EXPERIENCES.equals(operation)
                || MemoryV2Contract.OPERATION_SEARCH_COLLABORATION.equals(operation)
                || MemoryV2Contract.OPERATION_GET_GRANT.equals(operation)
                || MemoryV2Contract.OPERATION_WITHDRAW.equals(operation)
                || MemoryV2Contract.OPERATION_WRITER.equals(operation)
                || MemoryV2Contract.OPERATION_BOUNDARY.equals(operation)
                || MemoryV2Contract.OPERATION_PREFERENCE_WRITE.equals(operation)
                || MemoryV2Contract.OPERATION_MATTER_WRITE.equals(operation)
                || MemoryV2Contract.OPERATION_PREFERENCE_DETAIL.equals(operation)
                || MemoryV2Contract.OPERATION_ASSEMBLE_PREFERENCES.equals(operation)
                || MemoryV2Contract.OPERATION_PRIVACY_STATUS.equals(operation)
                || MemoryV2Contract.OPERATION_EXPERIENCE_OUTCOME.equals(operation)
                || MemoryV2Contract.OPERATION_EXPERIENCE_FEEDBACK.equals(operation)
                || MemoryV2Contract.OPERATION_EXPERIENCE_STATUS.equals(operation)
                || MemoryV2Contract.OPERATION_EXPERIENCE_LIFECYCLE.equals(operation)
                || MemoryV2Contract.OPERATION_DOMAIN_EXPERIENCE_MANAGE.equals(operation)
                || MemoryV2Contract.OPERATION_DOMAIN_EXPERIENCE_SEARCH.equals(operation)
                || MemoryV2Contract.OPERATION_DOMAIN_EXPERIENCE_CHECK.equals(operation)
                || MemoryV2Contract.OPERATION_SHARE_GRANT_MANAGE.equals(operation)
                || MemoryV2Contract.OPERATION_SHARED_EXPORT_SUBMIT.equals(operation)
                || MemoryV2Contract.OPERATION_SHARED_EXPORT_COMMIT.equals(operation)
                || MemoryV2Contract.OPERATION_SHARED_EXPORT_STATUS.equals(operation);
    }
}
