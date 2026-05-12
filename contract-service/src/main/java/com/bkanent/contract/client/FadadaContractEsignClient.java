package com.bkanent.contract.client;

import com.bkanent.contract.config.ContractProviderNames;
import com.bkanent.contract.entity.ContractEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Fadada provider placeholder.
 */
@Component
public class FadadaContractEsignClient implements ContractEsignClient {

    @Override
    public boolean supports(String provider) {
        return ContractProviderNames.ESIGN_FADADA.equalsIgnoreCase(provider);
    }

    @Override
    public ContractEsignResult sealContract(ContractEntity contract, String signedDocumentUrl) {
        String targetUrl = StringUtils.hasText(signedDocumentUrl)
                ? signedDocumentUrl
                : "https://fadada.example.com/contracts/" + contract.getContractNo() + ".pdf";
        return new ContractEsignResult(
                ContractProviderNames.ESIGN_FADADA,
                targetUrl,
                "FADADA-" + contract.getId(),
                "Fadada sealing succeeded"
        );
    }
}
