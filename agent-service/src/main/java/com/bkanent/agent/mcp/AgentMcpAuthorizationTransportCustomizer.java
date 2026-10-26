package com.bkanent.agent.mcp;

import com.bkanent.agent.config.AgentAuthorizationProperties;
import com.bkanent.common.agent.CapabilityId;
import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.authz.AuthzFreshness;
import com.bkanent.common.authz.AuthorizationContext;
import com.bkanent.common.mcp.authz.McpAuthorizationContract;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.customizer.McpSyncClientCustomizer;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.Clock;
import java.util.Map;

/** MCP SDK hook that adds proof outside model-visible tool arguments and binds exact wire JSON. */
public final class AgentMcpAuthorizationTransportCustomizer
        implements McpSyncClientCustomizer, McpSyncHttpClientRequestCustomizer {
    private record TransportInvocation(AgentMcpAuthorizationInvocation invocation,
                                       AgentMcpAuthorizationSupport.Target target) { }

    private final AgentAuthorizationProperties properties;
    private final AgentMcpAuthorizationSupport support;
    private final Clock clock;
    private volatile PrivateKey signingKey;

    public AgentMcpAuthorizationTransportCustomizer(AgentAuthorizationProperties properties,
                                                    AgentMcpAuthorizationSupport support, Clock clock) {
        this.properties = properties;
        this.support = support;
        this.clock = clock;
    }

    @Override
    public void customize(String connectionName, McpClient.SyncSpec spec) {
        spec.transportContextProvider(() -> {
            AgentMcpAuthorizationInvocation invocation = AgentMcpAuthorizationScope.current();
            if (invocation == null) return McpTransportContext.EMPTY;
            if (!connectionName.equals(invocation.serverName())) {
                throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
            }
            AgentMcpAuthorizationSupport.Target target = support.target(connectionName);
            if (invocation.delegated() && (target == null || !target.audience().equals(invocation.audience()))) {
                throw new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
            }
            return McpTransportContext.create(Map.of(McpAuthorizationContract.TRANSPORT_CONTEXT_KEY,
                    new TransportInvocation(invocation, target)));
        });
    }

    @Override
    public void customize(HttpRequest.Builder builder, String method, URI endpoint, String body,
                          McpTransportContext transportContext) {
        if (!"POST".equalsIgnoreCase(method) || body == null) return;
        byte[] exactBody = body.getBytes(StandardCharsets.UTF_8);
        McpAuthorizationContract.Request parsed = McpAuthorizationContract.parse(exactBody);
        if (!parsed.isToolCall()) return;

        Object raw = transportContext == null ? null
                : transportContext.get(McpAuthorizationContract.TRANSPORT_CONTEXT_KEY);
        if (!(raw instanceof TransportInvocation transport)) {
            throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        }
        AgentMcpAuthorizationInvocation invocation = transport.invocation();
        if (!invocation.toolName().equals(parsed.toolName())
                || (transport.target() != null && !invocation.serverName().equals(transport.target().connectionName()))) {
            throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
        }
        if (transport.target() != null && !transport.target().endpoint().equals(endpoint.normalize())) {
            throw new AuthzException(AuthzErrorCode.AUTHENTICATION_FAILED);
        }
        if (invocation.publicData()) {
            String capabilityId = CapabilityId.mcp(invocation.serverName(), invocation.toolName()).value();
            if (!properties.isPublicMcpCapability(capabilityId)) {
                throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
            }
            return;
        }
        if (!invocation.delegated() || transport.target() == null) {
            throw new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
        }

        var entry = invocation.entry();
        entry.requireRunAssociation();
        String actor = entry.requireActor(invocation.entry().session().context().actorId());
        if (!actor.equals(invocation.entry().session().context().actorId())) {
            throw new AuthzException(AuthzErrorCode.AUTHENTICATION_FAILED);
        }
        entry.session().requirePermission(invocation.operation());
        AuthorizationContext context = entry.session().context();
        AuthzFreshness freshness = entry.session().freshness();
        PrivateKey signingKey = signingKey();
        String encoded = McpAuthorizationContract.sign(exactBody, context, freshness,
                properties.getCallerService(), invocation.audience(), invocation.toolName(),
                invocation.operation(), properties.getKeyId(), signingKey, clock.instant());
        builder.setHeader(McpAuthorizationContract.HEADER, encoded);
    }

    private PrivateKey signingKey() {
        PrivateKey current = signingKey;
        if (current != null) return current;
        synchronized (this) {
            if (signingKey == null) signingKey = properties.signingKey();
            return signingKey;
        }
    }
}
