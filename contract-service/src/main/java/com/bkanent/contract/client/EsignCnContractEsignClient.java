package com.bkanent.contract.client;

import com.bkanent.contract.config.ContractProviderNames;
import com.bkanent.contract.entity.ContractEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * E-Sign CN provider placeholder.
 */
@Component
public class EsignCnContractEsignClient implements ContractEsignClient {

    @Override
    public boolean supports(String provider) {
        return ContractProviderNames.ESIGN_CN.equalsIgnoreCase(provider)
                || ContractProviderNames.ALIAS_ESIGN_CN_ALT.equalsIgnoreCase(provider);
    }

    @Override
    public ContractEsignResult sealContract(ContractEntity contract, String signedDocumentUrl) {
        String targetUrl = StringUtils.hasText(signedDocumentUrl)
                ? signedDocumentUrl
                : "https://esign.example.com/contracts/" + contract.getContractNo() + ".pdf";
        return new ContractEsignResult(
                ContractProviderNames.ESIGN_CN,
                targetUrl,
                "ESIGN-" + contract.getId(),
                "E-Sign CN sealing succeeded"
        );
    }
}
