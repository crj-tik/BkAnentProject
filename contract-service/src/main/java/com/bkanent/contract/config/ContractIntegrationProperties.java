package com.bkanent.contract.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Contract integration configuration.
 */
@ConfigurationProperties(prefix = "contract.integration")
public class ContractIntegrationProperties {

    private String ocrProvider = ContractProviderNames.OCR_MOCK;

    private String esignProvider = ContractProviderNames.ESIGN_MOCK;

    public String getOcrProvider() {
        return ocrProvider;
    }

    public void setOcrProvider(String ocrProvider) {
        this.ocrProvider = ocrProvider;
    }

    public String getEsignProvider() {
        return esignProvider;
    }

    public void setEsignProvider(String esignProvider) {
        this.esignProvider = esignProvider;
    }
}
