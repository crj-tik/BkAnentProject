package com.bkanent.common.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import jakarta.annotation.PreDestroy;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DynamicMcpClientManager {

    private static final Logger log = LoggerFactory.getLogger(DynamicMcpClientManager.class);

    private final Map<String, McpSyncClient> dynamicClients = new ConcurrentHashMap<>();
    private final Set<String> reservedNames = ConcurrentHashMap.newKeySet();
    private final Object lifecycleMonitor = new Object();

    private volatile SyncMcpToolCallbackProvider cachedProvider;

    public void register(DynamicMcpConnectionConfig config) {
        validateConfig(config);
        synchronized (lifecycleMonitor) {
            if (reservedNames.contains(config.name())) {
                throw new IllegalArgumentException("MCP connection name is already used by a static client: " + config.name());
            }
            if (dynamicClients.containsKey(config.name())) {
                throw new IllegalArgumentException("Dynamic MCP connection already registered: " + config.name());
            }
            McpSyncClient client = buildClient(config);
            boolean registered = false;
            try {
                client.initialize();
                int toolCount = client.listTools().tools().size();
                dynamicClients.put(config.name(), client);
                refreshProvider();
                registered = true;
                log.info("Dynamic MCP connection '{}' registered (type={}, {} tools)", config.name(), config.type(), toolCount);
            } catch (Exception e) {
                if (!registered) {
                    dynamicClients.remove(config.name(), client);
                    closeQuietly(client);
                }
                throw new IllegalStateException("Failed to initialize dynamic MCP connection '" + config.name() + "': " + e.getMessage(), e);
            }
        }
    }

    public void unregister(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("MCP connection name must not be blank");
        }
        synchronized (lifecycleMonitor) {
            McpSyncClient client = dynamicClients.remove(name);
            if (client == null) {
                throw new IllegalArgumentException("Dynamic MCP connection not found: " + name);
            }
            try {
                refreshProvider();
            } catch (Exception refreshFailure) {
                dynamicClients.put(name, client);
                throw new IllegalStateException("Failed to refresh dynamic MCP tools after removing '" + name + "'", refreshFailure);
            }
            closeQuietly(client);
            log.info("Dynamic MCP connection '{}' unregistered", name);
        }
    }

    public List<String> listConnectionNames() {
        List<String> names = new ArrayList<>(dynamicClients.keySet());
        names.sort(Comparator.naturalOrder());
        return names;
    }

    public Map<String, McpSyncClient> dynamicClients() {
        return Map.copyOf(dynamicClients);
    }

    public ToolCallback[] getDynamicToolCallbacks() {
        SyncMcpToolCallbackProvider provider = cachedProvider;
        if (provider != null) {
            return provider.getToolCallbacks();
        }
        return new ToolCallback[0];
    }

    private void refreshProvider() {
        List<McpSyncClient> clients = dynamicClients.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
        if (clients.isEmpty()) {
            cachedProvider = null;
        } else {
            cachedProvider = SyncMcpToolCallbackProvider.builder()
                    .mcpClients(clients)
                    .build();
        }
    }

    /**
     * Prevents runtime connections from shadowing statically configured clients.
     * The HTTP client calls this once it has indexed the startup connections.
     */
    public void reserveNames(Collection<String> names) {
        if (names != null) {
            names.stream()
                    .filter(name -> name != null && !name.isBlank())
                    .forEach(reservedNames::add);
        }
    }

    @PreDestroy
    public void shutdown() {
        synchronized (lifecycleMonitor) {
            dynamicClients.values().forEach(this::closeQuietly);
            dynamicClients.clear();
            cachedProvider = null;
        }
    }

    private McpSyncClient buildClient(DynamicMcpConnectionConfig config) {
        Duration requestTimeout = Duration.ofSeconds(15);
        Duration initTimeout = Duration.ofSeconds(10);
        if ("STDIO".equalsIgnoreCase(config.type())) {
            ServerParameters.Builder builder = ServerParameters.builder(config.command());
            if (config.args() != null) {
                builder.args(config.args());
            }
            if (config.env() != null) {
                config.env().forEach(builder::addEnvVar);
            }
            StdioClientTransport transport = new StdioClientTransport(builder.build(), McpJsonMapper.getDefault());
            return McpClient.sync(transport)
                    .requestTimeout(requestTimeout)
                    .initializationTimeout(initTimeout)
                    .build();
        } else if ("STREAMABLE".equalsIgnoreCase(config.type())) {
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(config.url())
                    .jsonMapper(McpJsonMapper.getDefault())
                    .build();
            return McpClient.sync(transport)
                    .requestTimeout(requestTimeout)
                    .initializationTimeout(initTimeout)
                    .build();
        }
        throw new IllegalArgumentException("Unsupported MCP transport type: " + config.type() + " (supported: STDIO, STREAMABLE)");
    }

    private void validateConfig(DynamicMcpConnectionConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("MCP connection config must not be null");
        }
        if (config.name() == null || config.name().isBlank() || !config.name().equals(config.name().trim())) {
            throw new IllegalArgumentException("MCP connection name must be non-blank and trimmed");
        }
        if (config.name().length() > 100 || config.name().chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("MCP connection name is invalid");
        }
        if (config.type() == null || config.type().isBlank()) {
            throw new IllegalArgumentException("MCP connection type must not be blank");
        }
        if ("STDIO".equalsIgnoreCase(config.type())) {
            if (config.command() == null || config.command().isBlank()) {
                throw new IllegalArgumentException("STDIO MCP connection requires a command");
            }
            return;
        }
        if ("STREAMABLE".equalsIgnoreCase(config.type())) {
            if (config.url() == null || config.url().isBlank()) {
                throw new IllegalArgumentException("STREAMABLE MCP connection requires a URL");
            }
            URI uri;
            try {
                uri = URI.create(config.url());
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("STREAMABLE MCP connection URL is invalid", exception);
            }
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException("STREAMABLE MCP connection URL must use http or https");
            }
            return;
        }
        throw new IllegalArgumentException("Unsupported MCP transport type: " + config.type() + " (supported: STDIO, STREAMABLE)");
    }

    private void closeQuietly(McpSyncClient client) {
        if (client == null) {
            return;
        }
        try {
            client.closeGracefully();
        } catch (Exception gracefulFailure) {
            try {
                client.close();
            } catch (Exception closeFailure) {
                log.debug("Failed to close MCP client", closeFailure);
            }
        }
    }
}
