package com.bkanent.contract.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 合同服务配置类。
 */
@Configuration
@EnableConfigurationProperties({ContractReminderProperties.class, ContractIntegrationProperties.class})
public class ContractConfiguration {

    private final ContractIntegrationProperties integrationProperties;

    public ContractConfiguration(ContractIntegrationProperties integrationProperties) {
        this.integrationProperties = integrationProperties;
    }

    @jakarta.annotation.PostConstruct
    public void validateIntegrationMode() {
        if (!StringUtils.hasText(integrationProperties.getMode())
                || "unconfigured".equalsIgnoreCase(integrationProperties.getMode())) {
            throw new IllegalStateException("contract.integration.mode must be explicitly set to local or real");
        }
        if (!integrationProperties.isLocalMode()
                && (isMock(integrationProperties.getOcrProvider()) || isMock(integrationProperties.getEsignProvider()))) {
            throw new IllegalStateException("contract mock providers are only allowed in explicit local integration mode");
        }
    }

    private boolean isMock(String provider) {
        return ContractProviderNames.OCR_MOCK.equalsIgnoreCase(provider)
                || ContractProviderNames.ESIGN_MOCK.equalsIgnoreCase(provider)
                || ContractProviderNames.ALIAS_MOCK.equalsIgnoreCase(provider);
    }
}
