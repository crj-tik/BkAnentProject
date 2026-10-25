package com.bkanent.auth.config;

import com.bkanent.auth.authorization.AuthorizationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@EnableConfigurationProperties(AuthAuthorizationProperties.class)
@ConditionalOnProperty(prefix = "auth.authorization", name = "enabled", havingValue = "true")
public class AuthAuthorizationConfiguration {
    @Bean
    public AuthorizationRepository authorizationRepository(JdbcTemplate jdbc,
                                                            PlatformTransactionManager transactionManager) {
        return new AuthorizationRepository(jdbc, transactionManager);
    }
}
