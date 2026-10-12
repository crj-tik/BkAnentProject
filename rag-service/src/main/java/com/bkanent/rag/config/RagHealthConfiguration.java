package com.bkanent.rag.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.apache.dubbo.config.spring.ServiceBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Base readiness inspects only the control DB and local wiring; no backend/model HTTP calls. */
@Configuration(proxyBeanMethods = false)
public class RagHealthConfiguration {
    @Bean HealthIndicator ragBaseReadiness(DataSource dataSource, RagSecurityProperties security,
            ObjectProvider<ServiceBean> providers) {
        return baseIndicator(dataSource, security, () -> providers.stream().filter(ServiceBean::isExported).count() >= 3);
    }
    static HealthIndicator baseIndicator(DataSource dataSource, RagSecurityProperties security, BooleanSupplier exported) {
        return () -> {
            if (!security.configured()) return Health.down().withDetail("gate", "authenticationConfiguration").build();
            if (!exported.getAsBoolean()) return Health.down().withDetail("gate", "rpcProviders").build();
            try (Connection connection = dataSource.getConnection()) {
                for (String table : new String[]{"rag_index_event", "rag_document_state"}) {
                    try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM " + table + " WHERE 1 = 0")) {
                        query.setQueryTimeout(5);
                        query.executeQuery().close();
                    }
                }
                return Health.up().build();
            } catch (Exception unavailable) {
                return Health.down().withDetail("gate", "controlDatabase").build();
            }
        };
    }
    @Bean HealthIndicator ragCapabilities(ManagedRagSpaceRegistry registry) {
        return () -> {
            var spaces = registry.snapshot();
            boolean ready = !spaces.isEmpty() && spaces.stream().allMatch(s -> s.ready());
            return Health.status(ready ? Status.UP : new Status("NOT_READY"))
                    .withDetail("spaces", spaces).build();
        };
    }
}
