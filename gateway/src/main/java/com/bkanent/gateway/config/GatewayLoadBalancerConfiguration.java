package com.bkanent.gateway.config;

import com.bkanent.gateway.loadbalancer.HttpInstanceListSupplier;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@LoadBalancerClients(defaultConfiguration = GatewayLoadBalancerConfiguration.HttpDiscoveryConfiguration.class)
public class GatewayLoadBalancerConfiguration {

    // Loaded in each service's LoadBalancer child context, outside component scanning.
    public static class HttpDiscoveryConfiguration {

        @Bean
        public ServiceInstanceListSupplier httpInstanceListSupplier(ConfigurableApplicationContext context) {
            return new HttpInstanceListSupplier(ServiceInstanceListSupplier.builder()
                    .withBlockingDiscoveryClient()
                    .build(context));
        }
    }
}
