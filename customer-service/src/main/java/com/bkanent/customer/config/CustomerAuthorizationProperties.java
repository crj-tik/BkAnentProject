package com.bkanent.customer.config;

import com.bkanent.common.authz.AuthzIngressProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "customer.authorization")
public class CustomerAuthorizationProperties extends AuthzIngressProperties {
    public CustomerAuthorizationProperties() { setAudience("customer-service"); }
    @PostConstruct public void validateProperties() { validate("customer-service"); }
}
