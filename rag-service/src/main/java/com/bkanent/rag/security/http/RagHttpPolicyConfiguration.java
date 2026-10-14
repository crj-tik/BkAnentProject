package com.bkanent.rag.security.http;

import java.util.List;
import java.util.Map;

/** Three deployment-owned settings; bound afresh for each validation/physical DNS resolution. */
public record RagHttpPolicyConfiguration(String mode, List<String> allowedHosts,
                                         Map<String, List<String>> internalNetworks) {
    public RagHttpPolicyConfiguration {
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        internalNetworks = internalNetworks == null ? Map.of() : internalNetworks.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue())));
    }
}
