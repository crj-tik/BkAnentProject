package com.bkanent.agent.service;

import com.bkanent.agent.memory.MemoryStoreClient;
import com.bkanent.agent.security.AgentPrincipalException;
import com.bkanent.common.agent.SessionMemoryResponse;
import com.bkanent.common.agent.SessionMemoryUpsertRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;

/**
 * Persists and checks the owner of a Supervisor session before any session
 * events can be read or emitted.
 */
@Service
public class SupervisorSessionOwnershipService {

    private final MemoryStoreClient memoryStoreClient;

    public SupervisorSessionOwnershipService(MemoryStoreClient memoryStoreClient) {
        this.memoryStoreClient = memoryStoreClient;
    }

    public void claim(String sessionId, String userId) {
        requireIdentifiers(sessionId, userId);
        Optional<SessionMemoryResponse> existing = memoryStoreClient.getSessionMemory(sessionId);
        if (existing.isPresent()) {
            assertOwner(existing.get(), userId);
            return;
        }

        memoryStoreClient.upsertSessionMemory(new SessionMemoryUpsertRequest(
                sessionId, userId, Map.of(), null, null));
        assertOwned(sessionId, userId);
    }

    public void assertOwned(String sessionId, String userId) {
        requireIdentifiers(sessionId, userId);
        SessionMemoryResponse session = memoryStoreClient.getSessionMemory(sessionId)
                .orElseThrow(() -> new AgentPrincipalException("supervisor session not found"));
        assertOwner(session, userId);
    }

    private void assertOwner(SessionMemoryResponse session, String requesterUserId) {
        if (!requesterUserId.equals(session.userId())) {
            throw new AgentPrincipalException("supervisor session access denied");
        }
    }

    private void requireIdentifiers(String sessionId, String userId) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(userId)) {
            throw new AgentPrincipalException("session id and authenticated user are required");
        }
    }
}
