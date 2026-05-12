package com.bkanent.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * GatewayConfiguration 网关配置类。
 */
@Configuration
@EnableConfigurationProperties({
        GatewayAccessProperties.class,
        GatewayCorsProperties.class,
        GatewayRateLimitProperties.class
})
public class GatewayConfiguration {

    @Bean
    public CorsWebFilter corsWebFilter(GatewayCorsProperties gatewayCorsProperties) {
        CorsConfiguration corsConfiguration = new CorsConfiguration();
        corsConfiguration.setAllowedOriginPatterns(gatewayCorsProperties.getAllowedOriginPatterns());
        corsConfiguration.setAllowedMethods(gatewayCorsProperties.getAllowedMethods());
        corsConfiguration.setAllowedHeaders(gatewayCorsProperties.getAllowedHeaders());
        corsConfiguration.setAllowCredentials(gatewayCorsProperties.isAllowCredentials());
        corsConfiguration.setMaxAge(gatewayCorsProperties.getMaxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfiguration);
        return new CorsWebFilter(source);
    }
}
