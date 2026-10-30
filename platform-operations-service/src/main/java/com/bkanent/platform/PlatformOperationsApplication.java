package com.bkanent.platform;

import com.bkanent.business.config.AnalyticsAuthorizationProperties;
import com.bkanent.businessreview.a2a.PlatformOperationsReviewAgentProperties;
import com.bkanent.businessreview.domain.DomainParallelProperties;
import com.bkanent.businessreview.security.BusinessReviewAuthorizationProperties;
import com.bkanent.common.authz.AuthzRestExceptionHandler;
import com.bkanent.knowledge.config.KnowledgeAuthorizationProperties;
import com.bkanent.knowledge.config.KnowledgeDataSourceProperties;
import com.bkanent.knowledge.config.KnowledgeSourceProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication(scanBasePackages = {"com.bkanent.platform", "com.bkanent.business",
        "com.bkanent.businessreview", "com.bkanent.knowledge"})
@MapperScan("com.bkanent.business.mapper")
@EnableTransactionManagement
@EnableConfigurationProperties({AnalyticsAuthorizationProperties.class, DomainParallelProperties.class,
        BusinessReviewAuthorizationProperties.class, PlatformOperationsReviewAgentProperties.class,
        KnowledgeAuthorizationProperties.class, KnowledgeDataSourceProperties.class, KnowledgeSourceProperties.class})
@Import(AuthzRestExceptionHandler.class)
public class PlatformOperationsApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlatformOperationsApplication.class, args);
    }
}
