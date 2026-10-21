package com.bkanent.memory.service;

import com.bkanent.common.memory.MemoryContextKey;
import com.bkanent.common.memory.MemoryErrorCode;
import com.bkanent.common.memory.MemoryException;
import com.bkanent.common.memory.MemoryRequestProof;
import com.bkanent.common.memory.MemoryRequestSigner;
import com.bkanent.common.memory.MemoryV2Contract;
import com.bkanent.common.memory.MemoryV2Request;
import com.bkanent.common.memory.MemoryDeliveryRequest;
import com.bkanent.common.memory.MemoryExperienceOutcomeRequest;
import com.bkanent.common.rpc.AuthPermissionRpcService;
import com.bkanent.memory.config.MemoryV2SecurityProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/** Authenticates the signed current-user assertion from one deployment-trusted domain caller. */
@Service
public class MemoryV2RequestSecurity {
    private final MemoryV2SecurityProperties properties;
    private final AuthPermissionRpcService authPermissionRpcService;
    private final MemoryV2ReplayGuard replayGuard;

    public MemoryV2RequestSecurity(MemoryV2SecurityProperties properties,
                                   AuthPermissionRpcService authPermissionRpcService,
                                   MemoryV2ReplayGuard replayGuard) {
        this.properties = properties;
        this.authPermissionRpcService = authPermissionRpcService;
        this.replayGuard = replayGuard;
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

    /** Output check for an already consumed request; it never admits a new request or skips current Auth. */
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
        if (caller == null || caller.getKeyId() == null || caller.getSigningSecret() == null
                || caller.getSigningSecret().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new MemoryException(MemoryErrorCode.AUTHENTICATION_FAILED);
        }

        MemoryContextKey context = request.contextKey();
        if (!caller.allows(context.domainId(), context.agentId())) {
            throw new MemoryException(MemoryErrorCode.FORBIDDEN);
        }
        if (service && !caller.isServiceDelivery()) throw new MemoryException(MemoryErrorCode.FORBIDDEN);
        byte[] secret = caller.getSigningSecret().getBytes(StandardCharsets.UTF_8);
        MemoryRequestSigner.verify(request, context, proof, envelope.caller(),
                MemoryV2Contract.MEMORY_AUDIENCE, expectedOperation, caller.getKeyId(), Instant.now(), secret);

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
                || MemoryV2Contract.OPERATION_SHARE_GRANT_MANAGE.equals(operation)
                || MemoryV2Contract.OPERATION_SHARED_EXPORT_SUBMIT.equals(operation)
                || MemoryV2Contract.OPERATION_SHARED_EXPORT_COMMIT.equals(operation)
                || MemoryV2Contract.OPERATION_SHARED_EXPORT_STATUS.equals(operation);
    }
}
