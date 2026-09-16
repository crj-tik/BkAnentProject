package com.bkanent.common.readiness;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Safe TCP preflight checks for dependencies explicitly required by a profile.
 */
public class DependencyReadinessHealthIndicator implements HealthIndicator {

    private static final int CONNECT_TIMEOUT_MILLIS = 350;

    private final ReadinessProperties properties;

    public DependencyReadinessHealthIndicator(ReadinessProperties properties) {
        this.properties = properties;
    }

    @Override
    public Health health() {
        List<String> dependencies = requiredDependencies();
        if (!properties.isEnabled() || dependencies.isEmpty()
                || "local".equalsIgnoreCase(properties.getMode())) {
            return Health.up()
                    .withDetail("mode", properties.getMode())
                    .withDetail("dependencies", dependencies.isEmpty() ? "none" : dependencies)
                    .build();
        }

        Map<String, String> failures = new LinkedHashMap<>();
        for (String dependency : dependencies) {
            String endpoint = properties.getEndpoints().get(dependency);
            if (endpoint == null || endpoint.isBlank()) {
                failures.put(dependency, "missing readiness endpoint configuration");
                continue;
            }
            Endpoint parsed = parse(endpoint);
            if (parsed == null) {
                failures.put(dependency, "invalid readiness endpoint configuration");
                continue;
            }
            if (!isReachable(parsed)) {
                failures.put(dependency, "endpoint is unreachable");
            }
        }

        if (!failures.isEmpty()) {
            return Health.down()
                    .withDetail("mode", properties.getMode())
                    .withDetail("failedDependencies", failures)
                    .build();
        }
        return Health.up()
                .withDetail("mode", properties.getMode())
                .withDetail("dependencies", dependencies)
                .build();
    }

    private List<String> requiredDependencies() {
        if (properties.getRequiredDependencies() == null || properties.getRequiredDependencies().isBlank()) {
            return List.of();
        }
        List<String> dependencies = new ArrayList<>();
        for (String value : properties.getRequiredDependencies().split(",")) {
            if (value != null && !value.isBlank()) {
                dependencies.add(value.trim());
            }
        }
        return List.copyOf(dependencies);
    }

    private Endpoint parse(String value) {
        try {
            String candidate = value.contains("://") ? value : "tcp://" + value;
            URI uri = new URI(candidate);
            int port = uri.getPort();
            if (port < 1) {
                port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            }
            return uri.getHost() == null ? null : new Endpoint(uri.getHost(), port);
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    private boolean isReachable(Endpoint endpoint) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), CONNECT_TIMEOUT_MILLIS);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private record Endpoint(String host, int port) {
    }
}
