package com.bkanent.agent.client;

import io.a2a.client.A2AClient;

/** Raw SDK only for deterministic non-auth protocol fixtures; never a production bean. */
public final class ProtocolFixtureA2aClient {
    private ProtocolFixtureA2aClient() { }
    public static OfficialA2aAgentClient create(OfficialA2aResponseNormalizer normalizer) {
        return new OfficialA2aAgentClient(normalizer, A2AClient::new);
    }
}
