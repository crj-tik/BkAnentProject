package com.bkanent.contract.client;

import com.bkanent.contract.config.ContractProviderNames;
import com.bkanent.contract.entity.ContractEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Mock e-sign provider.
 */
@Component
public class MockContractEsignClient implements ContractEsignClient {

    @Override
    public boolean supports(String provider) {
        return !StringUtils.hasText(provider)
                || ContractProviderNames.ESIGN_MOCK.equalsIgnoreCase(provider)
                || ContractProviderNames.ALIAS_MOCK.equalsIgnoreCase(provider);
    }

    @Override
    public ContractEsignResult sealContract(ContractEntity contract, String signedDocumentUrl) {
        String targetUrl = StringUtils.hasText(signedDocumentUrl)
                ? signedDocumentUrl
                : "https://minio.local/contracts/signed/" + contract.getContractNo() + ".pdf";
        return new ContractEsignResult(
                ContractProviderNames.ESIGN_MOCK,
                targetUrl,
                "MOCK-SEAL-" + contract.getId(),
                "Mock e-sign succeeded"
        );
    }
}
