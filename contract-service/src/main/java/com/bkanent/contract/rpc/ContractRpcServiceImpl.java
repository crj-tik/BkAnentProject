package com.bkanent.contract.rpc;

import com.bkanent.common.model.ContractSettlementDTO;
import com.bkanent.common.rpc.ContractRpcService;
import com.bkanent.contract.entity.ContractEntity;
import com.bkanent.contract.service.ContractDomainService;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 合同 RPC 服务实现。
 */
@DubboService
public class ContractRpcServiceImpl implements ContractRpcService {

    private final ContractDomainService contractDomainService;

    public ContractRpcServiceImpl(ContractDomainService contractDomainService) {
        this.contractDomainService = contractDomainService;
    }

    @Override
    public String checkClauseRisk(Long contractId) {
        ContractEntity entity = contractDomainService.getById(contractId);
        if (entity == null) {
            return "合同不存在";
        }
        if ("DISPUTE".equalsIgnoreCase(entity.getStatus())) {
            return "高风险：合同当前处于争议状态，请优先核查签署过程与证据链";
        }
        if (!"SEALED".equalsIgnoreCase(entity.getSealStatus())) {
            return "中风险：合同尚未完成电子签章，请核对签署主体、证件附件和签章结果";
        }
        return "低风险：请继续核验签署双方身份、附件 OCR 结果与到期时间";
    }

    @Override
    public List<ContractSettlementDTO> listContractsEligibleForSettlement(String month) {
        return contractDomainService.listContracts(null, null).stream()
                .filter(this::isEligibleForSettlement)
                .filter(contract -> matchesMonth(contract.getBothSignedTime(), month))
                .map(contract -> new ContractSettlementDTO(
                        contract.getId(),
                        contract.getContractNo(),
                        contract.getContractType(),
                        contract.getStatus(),
                        contract.getBrokerId(),
                        contract.getListingId(),
                        contract.getCustomerName(),
                        contract.getDealAmount(),
                        contract.getBothSignedTime(),
                        contract.getSealStatus()
                ))
                .toList();
    }

    private boolean isEligibleForSettlement(ContractEntity contract) {
        return ("BOTH_SIGNED".equalsIgnoreCase(contract.getStatus()) || "ARCHIVED".equalsIgnoreCase(contract.getStatus()))
                && "SEALED".equalsIgnoreCase(contract.getSealStatus())
                && contract.getBrokerId() != null
                && contract.getDealAmount() != null;
    }

    private boolean matchesMonth(String bothSignedTime, String month) {
        return StringUtils.hasText(bothSignedTime) && StringUtils.hasText(month) && bothSignedTime.startsWith(month);
    }
}
