package com.bkanent.agent.config;

import com.bkanent.agent.authorization.AgentAuthorizationConsumer;
import com.bkanent.agent.authorization.AgentAuthorizationRedisReader;
import com.bkanent.common.authz.AuthorizationCache;
import com.bkanent.common.authz.AuthorizationVersionMonitor;
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
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentAuthorizationProperties.class)
public class AgentAuthorizationConfiguration {
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableConfigurationProperties(AgentAuthorizationRedisProperties.class)
    @ConditionalOnProperty(prefix = "agent.authorization", name = "enabled", havingValue = "true")
    public static class Enabled {
        @Bean(defaultCandidate = false)
        public Clock agentAuthorizationClock() { return Clock.systemUTC(); }

        @Bean(defaultCandidate = false)
        public LettuceConnectionFactory agentAuthorizationRedisConnectionFactory(
                AgentAuthorizationRedisProperties redis, Environment environment) {
            redis.validateTransport(environment.acceptsProfiles(Profiles.of("distributed")));
            var server = new RedisStandaloneConfiguration(redis.getHost(), redis.getPort());
            server.setUsername(redis.getUsername());
            server.setPassword(RedisPassword.of(redis.getPassword()));
            var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2))
                    .shutdownTimeout(Duration.ofSeconds(2));
            if (redis.isTls()) client.useSsl();
            return new LettuceConnectionFactory(server, client.build());
        }

        @Bean(defaultCandidate = false)
        public StringRedisTemplate agentAuthorizationReadTemplate(
                @Qualifier("agentAuthorizationRedisConnectionFactory") LettuceConnectionFactory connection) {
            return new StringRedisTemplate(connection);
        }

        @Bean
        public AgentAuthorizationRedisReader agentAuthorizationRedisReader(
                @Qualifier("agentAuthorizationReadTemplate") StringRedisTemplate redis,
                AgentAuthorizationRedisProperties properties) {
            return new AgentAuthorizationRedisReader(redis, properties.getNamespace(), properties.getChannel());
        }

        @Bean
        public AuthorizationCache agentAuthorizationCache(AgentAuthorizationProperties properties) {
            return new AuthorizationCache(properties.getCacheEntries(), properties.getCacheBytes(),
                    Duration.ofMinutes(5), Math.min(100, properties.getCacheEntries()));
        }

        @Bean
        public AuthorizationVersionMonitor agentAuthorizationVersionMonitor(AgentAuthorizationRedisReader reader,
                AgentAuthorizationProperties properties, @Qualifier("agentAuthorizationClock") Clock clock) {
            var monitor = new AuthorizationVersionMonitor(reader, properties.authorityKeys(), clock,
                    properties.getMaxUsers(), properties.getMaxSessions(), properties.getMaxSessionsPerUser());
            reader.attach(monitor);
            return monitor;
        }

        @Bean
        public AgentAuthorizationConsumer agentAuthorizationConsumer(AgentAuthorizationProperties properties,
                AuthorizationCache cache, AuthorizationVersionMonitor monitor,
                @Qualifier("agentAuthorizationClock") Clock clock) {
            return new AgentAuthorizationConsumer(properties, cache, monitor, clock);
        }

        @Bean(defaultCandidate = false)
        public RedisMessageListenerContainer agentAuthorizationListener(
                @Qualifier("agentAuthorizationRedisConnectionFactory") LettuceConnectionFactory connection,
                AgentAuthorizationRedisReader reader, AgentAuthorizationRedisProperties properties) {
            var container = new RedisMessageListenerContainer();
            container.setConnectionFactory(connection);
            container.addMessageListener(reader, new ChannelTopic(properties.getChannel()));
            return container;
        }
    }
}
