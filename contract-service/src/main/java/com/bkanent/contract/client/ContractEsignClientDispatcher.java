package com.bkanent.contract.client;

import com.bkanent.contract.entity.ContractEntity;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 合同电子签章适配器分发器。
 */
@Component
public class ContractEsignClientDispatcher {

    private final List<ContractEsignClient> clients;

    public ContractEsignClientDispatcher(List<ContractEsignClient> clients) {
        this.clients = clients;
    }

    public ContractEsignResult sealContract(String provider, ContractEntity contract, String signedDocumentUrl) {
        return clients.stream()
                .filter(client -> client.supports(provider))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到可用的电子签章适配器: " + provider))
                .sealContract(contract, signedDocumentUrl);
    }
}
