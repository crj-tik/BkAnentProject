package com.bkanent.auth.authorization;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;

/** Only publishes durable change facts; actual Redis publication belongs to the outbox dispatcher. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "auth.authorization", name = "enabled", havingValue = "true")
public class AuthorizationGrantBoundaryScheduler {
    private final AuthorizationRepository repository;

    public AuthorizationGrantBoundaryScheduler(AuthorizationRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void processBoundaries() {
        repository.processGrantBoundaries(Instant.now(), 100);
    }
}
