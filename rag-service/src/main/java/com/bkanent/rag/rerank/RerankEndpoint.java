package com.bkanent.rag.rerank;

import java.net.URI;

/** Runtime-managed provider configuration, never accepted from a business request. */
public record RerankEndpoint(URI uri, String apiKey, String model, Protocol protocol,
        String expectedRevision, String expectedArtifactDigest) {
    public enum Protocol { NATIVE, DASHSCOPE, GOVERNED }
    public RerankEndpoint(URI uri, String apiKey, String model, Protocol protocol) {
        this(uri, apiKey, model, protocol, null, null);
    }
    @Override public String toString() { return "RerankEndpoint[protocol=" + protocol + "]"; }
}
