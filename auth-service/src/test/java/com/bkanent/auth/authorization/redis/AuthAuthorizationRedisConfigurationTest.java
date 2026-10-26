package com.bkanent.auth.authorization.redis;

import com.bkanent.auth.config.AuthA2aIdentityProperties;
import com.bkanent.auth.config.AuthAuthorizationProperties;
import com.bkanent.auth.config.AuthAuthorizationRedisConfiguration;
import com.bkanent.auth.config.AuthAuthorizationRedisProperties;
import com.bkanent.common.security.ServiceIdentityCrypto;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.env.MockEnvironment;

import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthAuthorizationRedisConfigurationTest {
    @Test
    void disabledAndUnprotectedDefaultsDoNotEnableTransport() {
        var settings = new AuthAuthorizationRedisProperties();
        assertThrows(IllegalStateException.class, () -> settings.validateTransport(true));
    }

    @Test
    void distributedRequiresNamedAuthenticationAndTls() {
        var settings = settings();
        settings.setTls(false);
        assertThrows(IllegalStateException.class, () -> settings.validateTransport(true));
        assertDoesNotThrow(() -> settings.validateTransport(false));
        settings.setTls(true);
        settings.setUsername("default");
        assertThrows(IllegalStateException.class, () -> settings.validateTransport(true));
        settings.setUsername("shared-redis-user");
        assertThrows(IllegalStateException.class, () -> settings.validateTransport(true));
        settings.setUsername("authz-writer");
        assertDoesNotThrow(() -> settings.validateTransport(true));
        settings.setPassword("");
        assertThrows(IllegalStateException.class, () -> settings.validateTransport(true));
    }

    @Test
    void registrationCannotSuppressExistingRevocationRedisAutoConfiguration() throws Exception {
        assertFalse(AuthAuthorizationRedisConfiguration.class.getMethod("authAuthorizationWriterTemplate",
                org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory.class)
                .getAnnotation(Bean.class).defaultCandidate());
        assertFalse(AuthAuthorizationRedisConfiguration.class.getMethod("authAuthorizationReadTemplate",
                org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory.class)
                .getAnnotation(Bean.class).defaultCandidate());
    }

    @Test
    void differentServiceIdentityAndWrongPublicKeyFailBeforeAnyConnection() throws Exception {
        var configuration = new AuthAuthorizationRedisConfiguration();
        var authority = mock(AuthAuthorizationProperties.class);
        var identity = mock(AuthA2aIdentityProperties.class);
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        when(authority.isEnabled()).thenReturn(true);
        when(authority.getResponseKeyId()).thenReturn("auth-current");
        when(identity.getResponseKeyId()).thenReturn("other-service");
        when(authority.signingKey()).thenReturn(keys.getPrivate());
        when(identity.responseSigningKey()).thenReturn(keys.getPrivate());
        var environment = new MockEnvironment();
        environment.setActiveProfiles("distributed");
        assertThrows(IllegalStateException.class, () -> configuration.authAuthorizationRedisConnectionFactory(
                settings(), authority, identity, environment));
        when(identity.getResponseKeyId()).thenReturn("auth-current");
        when(authority.getResponsePublicKey()).thenReturn(ServiceIdentityCrypto.encodePublicKey(other.getPublic()));
        assertThrows(IllegalStateException.class, () -> configuration.authAuthorizationRedisConnectionFactory(
                settings(), authority, identity, environment));
    }

    private static AuthAuthorizationRedisProperties settings() {
        var settings = new AuthAuthorizationRedisProperties();
        settings.setEnabled(true);
        settings.setProtectedWriterConfigured(true);
        settings.setHost("redis.example.test");
        settings.setUsername("authz-writer");
        settings.setPassword("synthetic-test-only");
        return settings;
    }
}
