package com.bkanent.agent.security;

import com.bkanent.agent.model.chat.AgentChatRequest;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.model.rag.ListingIndexRequest;
import com.bkanent.agent.model.rag.ListingRagQueryRequest;
import com.bkanent.common.rpc.AuthPrincipalContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.annotation.RequestScope;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Collections;

/**
 * Resolves the authenticated user injected by the API gateway and binds
 * identity-bearing request DTOs to that user.
 */
@Component
@RequestScope
public class AgentPrincipalContext {

    private final String userId;
    private com.bkanent.agent.orchestration.OrchestrationStore orchestrationStore;

    @org.springframework.beans.factory.annotation.Autowired
    public void setOrchestrationStore(com.bkanent.agent.orchestration.OrchestrationStore store) { orchestrationStore = store; }

    public AgentPrincipalContext(HttpServletRequest request) {
        String marker = request.getHeader(AuthPrincipalContext.MARKER_HEADER);
        String authenticatedUserId = request.getHeader(AuthPrincipalContext.USER_ID_HEADER);
        if (!AuthPrincipalContext.MARKER_VALUE.equals(marker) || !StringUtils.hasText(authenticatedUserId)) {
            throw new AgentPrincipalException("authenticated gateway principal is required");
        }
        try {
            long parsedUserId = Long.parseLong(authenticatedUserId);
            if (parsedUserId <= 0) {
                throw new NumberFormatException("user id must be positive");
            }
            this.userId = String.valueOf(parsedUserId);
        } catch (NumberFormatException exception) {
            throw new AgentPrincipalException("authenticated gateway user id is invalid");
        }
    }

    public String userId() {
        return userId;
    }

    public String resolveUserId(String requestedUserId) {
        if (StringUtils.hasText(requestedUserId) && !userId.equals(requestedUserId.trim())) {
            throw new AgentPrincipalException("requested user does not match authenticated principal");
        }
        return userId;
    }

    public AgentChatRequest bind(AgentChatRequest request) {
        requireRequest(request);
        resolveUserId(request.userId());
        return new AgentChatRequest(userId, request.message(), request.collectionName(),
                request.topK(), request.allowMcp(), request.skill(), request.continueRunId(), request.sessionId(), request.requestId());
    }

    public ListingIndexRequest bind(ListingIndexRequest request) {
        requireRequest(request);
        resolveUserId(request.userId());
        return new ListingIndexRequest(userId, request.listingId(), request.collectionName());
    }

    public ListingRagQueryRequest bind(ListingRagQueryRequest request) {
        requireRequest(request);
        resolveUserId(request.userId());
        return new ListingRagQueryRequest(userId, request.query(), request.topK(), request.keywordTopK(),
                request.vectorTopK(), request.collectionName(), request.region(), request.minTotalPrice(),
                request.maxTotalPrice(), request.layout(), request.minArea(), request.maxArea());
    }

    public SupervisorTaskRequest bind(SupervisorTaskRequest request) {
        if (request == null) {
            throw new AgentPrincipalException("supervisor request is required");
        }
        resolveUserId(request.userId());
        Map<String, Object> context = new LinkedHashMap<>(request.context() == null ? Map.of() : request.context());
        context.put("userId", userId);
        String sessionId = StringUtils.hasText(request.sessionId())
                ? request.sessionId().trim() : UUID.randomUUID().toString();
        if (StringUtils.hasText(request.continueRunId()) && orchestrationStore != null) {
            orchestrationStore.assertOwner(request.continueRunId(), userId);
            String originalSession = orchestrationStore.originalRequest(request.continueRunId()).sessionId();
            if (StringUtils.hasText(request.sessionId()) && !originalSession.equals(request.sessionId()))
                throw new AgentPrincipalException("continuation session does not match original run");
            sessionId = originalSession;
        }
        return new SupervisorTaskRequest(sessionId, userId, request.requestId(), request.traceId(),
                request.userMessage(), Collections.unmodifiableMap(context), request.channel(), request.stream(),
                request.skill(), request.continueRunId(), request.allowMcp());
    }

    private void requireRequest(Object request) {
        if (request == null) {
            throw new AgentPrincipalException("request body is required");
        }
    }
}
