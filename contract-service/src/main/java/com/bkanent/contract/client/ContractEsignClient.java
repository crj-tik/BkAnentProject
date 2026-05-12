package com.bkanent.contract.client;

import com.bkanent.contract.entity.ContractEntity;

/**
 * 合同电子签章适配器接口。
 */
public interface ContractEsignClient {

    /**
     * 业务方法：supports。
     */
    boolean supports(String provider);

    /**
     * 业务方法：sealContract。
     */
    ContractEsignResult sealContract(ContractEntity contract, String signedDocumentUrl);
}
