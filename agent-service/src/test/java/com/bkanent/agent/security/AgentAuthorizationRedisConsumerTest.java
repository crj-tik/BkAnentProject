package com.bkanent.agent.security;

import com.bkanent.agent.authorization.AgentAuthorizationRedisReader;
import com.bkanent.agent.config.AgentAuthorizationConfiguration;
import com.bkanent.agent.config.AgentAuthorizationProperties;
import com.bkanent.agent.config.AgentAuthorizationRedisProperties;
import com.bkanent.common.authz.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentAuthorizationRedisConsumerTest {
    @Test void readsOnlyExactHeadKeysWithMockTransportAndStrictTypedJson() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var reader = new AgentAuthorizationRedisReader(redis, "authz", "authz:versions");
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        var head = new AuthzFreshness("42", 1, 0, AuthorizationStatus.ACTIVE,
                AuthzCanonicalHash.bodyHash(new byte[0]), 1700000000, 1700000030, nonce, "Ed25519", "auth-key", null);
        String json = new ObjectMapper().writeValueAsString(head);
        when(values.multiGet(List.of("authz:{42}:head", "authz:{7}:head"))).thenReturn(Arrays.asList(json, null));
        assertThat(reader.readHeads(List.of("42", "7"))).containsOnlyKeys("42").containsEntry("42", head);
        verify(values).multiGet(List.of("authz:{42}:head", "authz:{7}:head"));
        assertThatThrownBy(() -> AgentAuthorizationRedisReader.decodeHead(json.replace("\"userId\":\"42\"",
                "\"userId\":\"42\",\"userId\":\"7\""))).isInstanceOf(AuthzException.class);
        assertThatThrownBy(() -> AgentAuthorizationRedisReader.decodeHead(json + " {}"))
                .isInstanceOf(AuthzException.class).hasMessage("INVALID_ARGUMENT");
        when(values.multiGet(anyList())).thenThrow(new IllegalStateException("secret endpoint"));
        assertThatThrownBy(() -> reader.readHeads(List.of("42")))
                .isInstanceOf(AuthzException.class).hasMessage("AUTHORIZATION_UNAVAILABLE");
        verify(redis, times(2)).opsForValue();
        verifyNoMoreInteractions(redis);
    }

    @Test void trustedChannelFullWriterEnvelopeDispatchesExactRevisionAndOtherChannelsDoNothing() {
        var reader = new AgentAuthorizationRedisReader(mock(StringRedisTemplate.class), "authz", "authz:versions");
        // Monitor is final; mockito supports final types via its default inline mock maker.
        var monitor = mock(AuthorizationVersionMonitor.class);
        reader.attach(monitor);
        byte[] json = "{\"eventId\":\"event-1\",\"userId\":42,\"newRevision\":7,\"credentialEpoch\":3,\"reason\":\"ROLE_CHANGED\"}".getBytes(StandardCharsets.UTF_8);
        reader.onMessage(new org.springframework.data.redis.connection.DefaultMessage("other".getBytes(StandardCharsets.UTF_8), json), null);
        verifyNoInteractions(monitor);
        reader.onMessage(new org.springframework.data.redis.connection.DefaultMessage("authz:versions".getBytes(StandardCharsets.UTF_8), json), null);
        verify(monitor).onNotification("42", 7, 3);
        reader.onMessage(new org.springframework.data.redis.connection.DefaultMessage("authz:versions".getBytes(StandardCharsets.UTF_8),
                new String(json, StandardCharsets.UTF_8).replace("\"newRevision\":7", "\"newRevision\":7,\"newRevision\":1")
                        .getBytes(StandardCharsets.UTF_8)), null);
        verifyNoMoreInteractions(monitor);
    }

    @Test void configurationIsDisabledByDefaultDedicatedNonDefaultAndDistributedRequiresTls() throws Exception {
        assertThat(new AgentAuthorizationProperties().isEnabled()).isFalse();
        var redis = new AgentAuthorizationRedisProperties();
        redis.setHost("redis.internal"); redis.setUsername("agent-authz-reader"); redis.setPassword("test-only");
        assertThatThrownBy(() -> redis.validateTransport(true)).hasMessageContaining("protected read-only");
        redis.setTls(true);
        redis.validateTransport(true);
        var connection = new AgentAuthorizationConfiguration.Enabled()
                .agentAuthorizationRedisConnectionFactory(redis, new MockEnvironment().withProperty("spring.profiles.active", "distributed"));
        assertThat(connection.getStandaloneConfiguration().getUsername()).isEqualTo("agent-authz-reader");
        assertThat(connection.getClientConfiguration().isUseSsl()).isTrue();
        assertThat(AgentAuthorizationConfiguration.Enabled.class.getMethod("agentAuthorizationRedisConnectionFactory",
                AgentAuthorizationRedisProperties.class, org.springframework.core.env.Environment.class)
                .getAnnotation(Bean.class).defaultCandidate()).isFalse();
        var template = new AgentAuthorizationConfiguration.Enabled().agentAuthorizationReadTemplate(connection);
        assertThat(template.getConnectionFactory()).isSameAs(connection);
        redis.setUsername("default");
        assertThatThrownBy(() -> redis.validateTransport(false)).hasMessageContaining("protected read-only");
        assertThat(redis.toString()).doesNotContain("test-only");
        // No Redis connection is initialized or contacted by these configuration fixtures.
    }
}
