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
        throw new UnsupportedOperationException("真实电子签章服务尚未接入: " + ContractProviderNames.ESIGN_FADADA);
    }
}
