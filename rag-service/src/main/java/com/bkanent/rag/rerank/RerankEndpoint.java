package com.bkanent.rag.rerank;

import java.net.URI;

/** Runtime-managed provider configuration, never accepted from a business request. */
public record RerankEndpoint(URI uri, String apiKey, String model, Protocol protocol) {
    public enum Protocol { NATIVE, DASHSCOPE }
    @Override public String toString() { return "RerankEndpoint[protocol=" + protocol + "]"; }
}
