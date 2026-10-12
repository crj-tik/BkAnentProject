package com.bkanent.auth;

import com.bkanent.auth.config.AuthRagRpcProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthRagRpcPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void defaultsDoNotGenerateAnyTrustedOrResponseKey() {
        contextRunner.run(context -> {
            AuthRagRpcProperties properties = context.getBean(AuthRagRpcProperties.class);
            assertTrue(properties.getTrustedKeys().isEmpty());
            assertNull(properties.getResponseKeyId());
            assertNull(properties.getResponseSecret());
        });
    }

    @Test
    void bindsManagedRotatingKeysWithIndependentCallerIdentitiesAndResponseCredentials() {
        contextRunner.withPropertyValues(
                "auth.rag.rpc.trusted-keys.rag-current.caller-service=rag-service",
                "auth.rag.rpc.trusted-keys.rag-current.secret=test-rag-secret",
                "auth.rag.rpc.trusted-keys.rag-next.caller-service=rag-service",
                "auth.rag.rpc.trusted-keys.rag-next.secret=test-rag-next-secret",
                "auth.rag.rpc.trusted-keys.listing-current.caller-service=listing-master-service",
                "auth.rag.rpc.trusted-keys.listing-current.secret=test-listing-secret",
                "auth.rag.rpc.trusted-keys.memory-current.caller-service=memory-service",
                "auth.rag.rpc.trusted-keys.memory-current.secret=test-memory-secret",
                "auth.rag.rpc.response-key-id=auth-next",
                "auth.rag.rpc.response-secret=test-outgoing-secret")
                .run(context -> {
                    AuthRagRpcProperties properties = context.getBean(AuthRagRpcProperties.class);
                    assertEquals(4, properties.getTrustedKeys().size());
                    assertEquals("rag-service", properties.getTrustedKeys().get("rag-next").getCallerService());
                    assertEquals("test-rag-secret", properties.getTrustedKeys().get("rag-current").getSecret());
                    assertEquals("listing-master-service",
                            properties.getTrustedKeys().get("listing-current").getCallerService());
                    assertEquals("memory-service", properties.getTrustedKeys().get("memory-current").getCallerService());
                    assertEquals("auth-next", properties.getResponseKeyId());
                    assertEquals("test-outgoing-secret", properties.getResponseSecret());
                    assertFalse(properties.toString().contains("test-outgoing"));
                    assertFalse(properties.getTrustedKeys().get("rag-current").toString().contains("test-rag"));
                });
    }

    @Test
    void environmentPlaceholdersHaveNoUsableFallback() {
        contextRunner.withPropertyValues(
                "auth.rag.rpc.trusted-keys.rag-current.caller-service=rag-service",
                "auth.rag.rpc.trusted-keys.rag-current.secret=${TEST_RAG_AUTH_RPC_SECRET:}",
                "auth.rag.rpc.response-key-id=${TEST_AUTH_RAG_RPC_RESPONSE_KEY_ID:}",
                "auth.rag.rpc.response-secret=${TEST_AUTH_RAG_RPC_RESPONSE_SECRET:}")
                .run(context -> {
                    AuthRagRpcProperties properties = context.getBean(AuthRagRpcProperties.class);
                    assertEquals("", properties.getTrustedKeys().get("rag-current").getSecret());
                    assertEquals("", properties.getResponseKeyId());
                    assertEquals("", properties.getResponseSecret());
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthRagRpcProperties.class)
    static class PropertiesConfiguration {
    }
}
