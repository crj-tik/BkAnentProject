package com.bkanent.businessreview;

import com.bkanent.common.authz.AuthzRestExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.bkanent.businessreview.domain.DomainParallelProperties;
import com.bkanent.businessreview.security.BusinessReviewAuthorizationProperties;
import com.bkanent.businessreview.a2a.BusinessReviewAgentProperties;

@EnableTransactionManagement
@EnableConfigurationProperties({DomainParallelProperties.class, BusinessReviewAuthorizationProperties.class,
        BusinessReviewAgentProperties.class})
@Import(AuthzRestExceptionHandler.class)
@SpringBootApplication
public class BusinessReviewServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(BusinessReviewServiceApplication.class, args);
    }
}
