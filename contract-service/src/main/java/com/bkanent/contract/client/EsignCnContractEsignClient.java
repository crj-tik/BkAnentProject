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
        throw new UnsupportedOperationException("真实电子签章服务尚未接入: " + ContractProviderNames.ESIGN_CN);
    }
}
