package com.bkanent.agent.mcp;

import com.bkanent.agent.config.AgentAuthorizationProperties;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Validates the trusted static connection-to-service mapping used by the MCP proof transport. */
public final class AgentMcpAuthorizationSupport {
    public record Target(String connectionName, String audience, URI endpoint) { }

    private final Map<String, Target> targets;

    public AgentMcpAuthorizationSupport(AgentAuthorizationProperties authorization,
                                       McpStreamableHttpClientProperties connections) {
        Map<String, Target> resolved = new LinkedHashMap<>();
        if (authorization.isEnabled()) {
            authorization.getMcpTargetAudiences().forEach((name, audience) -> {
                var parameters = connections.getConnections().get(name);
                if (parameters == null || parameters.url() == null || parameters.url().isBlank()) {
                    throw new IllegalStateException("MCP delegation target is not a configured static connection");
                }
                URI base;
                URI endpoint;
                try {
                    base = URI.create(parameters.url());
                    endpoint = base.resolve(parameters.endpoint() == null ? "/mcp" : parameters.endpoint());
                } catch (RuntimeException failure) {
                    throw new IllegalStateException("Invalid configured MCP delegation target");
                }
                if (!SetOfHttp.SCHEMES.contains(base.getScheme()) || base.getHost() == null
                        || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                        || !base.getScheme().equals(endpoint.getScheme()) || !base.getHost().equals(endpoint.getHost())
                        || base.getPort() != endpoint.getPort() || endpoint.getUserInfo() != null
                        || endpoint.getQuery() != null || endpoint.getFragment() != null) {
                    throw new IllegalStateException("Invalid configured MCP delegation endpoint");
                }
                resolved.put(name, new Target(name, audience, endpoint.normalize()));
            });
        }
        targets = Map.copyOf(resolved);
    }

    public Target target(String connectionName) { return targets.get(connectionName); }
    public boolean supportsUserDelegation(String connectionName) { return targets.containsKey(connectionName); }

    private static final class SetOfHttp {
        private static final java.util.Set<String> SCHEMES = java.util.Set.of("http", "https");
    }
}
