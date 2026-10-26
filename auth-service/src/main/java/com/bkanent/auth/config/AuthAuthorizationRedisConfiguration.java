package com.bkanent.auth.config;

import com.bkanent.auth.authorization.AuthorizationRepository;
import com.bkanent.auth.authorization.redis.AuthorizationOutboxPublisher;
import com.bkanent.auth.authorization.redis.RedisActiveAuthorizationActors;
import com.bkanent.auth.authorization.redis.RedisAuthorizationWriter;
import com.bkanent.common.security.ServiceIdentityCrypto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;

/** Dedicated opt-in connection: non-default candidates leave token-revocation auto-configuration intact. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(AuthAuthorizationRedisProperties.class)
@ConditionalOnProperty(prefix = "auth.authorization.redis", name = "enabled", havingValue = "true")
public class AuthAuthorizationRedisConfiguration {
    @Bean(defaultCandidate = false)
    public LettuceConnectionFactory authAuthorizationRedisConnectionFactory(AuthAuthorizationRedisProperties redis,
            AuthAuthorizationProperties authority, AuthA2aIdentityProperties identity, Environment environment) {
        redis.validateTransport(environment.acceptsProfiles(Profiles.of("distributed")));
        if (!authority.isEnabled() || !authority.getResponseKeyId().equals(identity.getResponseKeyId())
                || !Arrays.equals(authority.signingKey().getEncoded(), identity.responseSigningKey().getEncoded())) {
            throw new IllegalStateException("Authorization must use Auth's registered service identity");
        }
        byte[] probe = "auth-authorization-identity-check".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (!ServiceIdentityCrypto.verify("bkanent-authz-v2", "Ed25519", probe,
                ServiceIdentityCrypto.sign("bkanent-authz-v2", "Ed25519", probe, authority.signingKey()),
                ServiceIdentityCrypto.decodePublicKey(authority.getResponsePublicKey()))) {
            throw new IllegalStateException("Authorization public/private identity mismatch");
        }
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(redis.getHost(), redis.getPort());
        server.setUsername(redis.getUsername());
        server.setPassword(RedisPassword.of(redis.getPassword()));
        LettuceClientConfiguration.LettuceClientConfigurationBuilder client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2)).shutdownTimeout(Duration.ofSeconds(2));
        if (redis.isTls()) client.useSsl();
        return new LettuceConnectionFactory(server, client.build());
    }

    @Bean(defaultCandidate = false)
    public StringRedisTemplate authAuthorizationWriterTemplate(
            @Qualifier("authAuthorizationRedisConnectionFactory") LettuceConnectionFactory connection) {
        return new StringRedisTemplate(connection);
    }

    @Bean(defaultCandidate = false)
    public StringRedisTemplate authAuthorizationReadTemplate(
            @Qualifier("authAuthorizationRedisConnectionFactory") LettuceConnectionFactory connection) {
        return new StringRedisTemplate(connection);
    }

    @Bean
    public RedisAuthorizationWriter redisAuthorizationWriter(
            @Qualifier("authAuthorizationWriterTemplate") StringRedisTemplate redis, ObjectMapper json,
            AuthAuthorizationProperties authority, AuthAuthorizationRedisProperties settings) {
        return new RedisAuthorizationWriter(redis, json, authority, settings, Clock.systemUTC());
    }

    @Bean
    public RedisActiveAuthorizationActors redisActiveAuthorizationActors(
            @Qualifier("authAuthorizationWriterTemplate") StringRedisTemplate redis,
            AuthAuthorizationRedisProperties settings) {
        return new RedisActiveAuthorizationActors(redis, settings, Clock.systemUTC());
    }

    @Bean
    public AuthorizationOutboxPublisher authorizationOutboxPublisher(AuthorizationRepository repository,
            RedisAuthorizationWriter writer, RedisActiveAuthorizationActors active,
            AuthAuthorizationRedisProperties settings, AuthAuthorizationProperties authority, ObjectMapper json) {
        return new AuthorizationOutboxPublisher(repository, writer, active, settings, authority, json, Clock.systemUTC());
    }
}
