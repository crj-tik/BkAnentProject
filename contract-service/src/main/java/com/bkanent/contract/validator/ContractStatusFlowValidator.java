package com.bkanent.contract.validator;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 合同状态流转校验器。
 */
@Component
public class ContractStatusFlowValidator {

    private static final Map<String, Set<String>> STATUS_FLOW_MAP = Map.of(
            "PENDING_SIGN", Set.of("SIGNED", "BOTH_SIGNED", "DISPUTE"),
            "SIGNED", Set.of("BOTH_SIGNED", "DISPUTE"),
            "BOTH_SIGNED", Set.of("ARCHIVED", "DISPUTE"),
            "ARCHIVED", Set.of("DISPUTE"),
            "DISPUTE", Set.of()
    );

    public void validateStatusTransition(String currentStatus, String targetStatus) {
        if (targetStatus == null || targetStatus.isBlank()) {
            throw new IllegalArgumentException("目标合同状态不能为空");
        }
        if (currentStatus == null || currentStatus.isBlank()) {
            return;
        }
        if (currentStatus.equalsIgnoreCase(targetStatus)) {
            return;
        }
        Set<String> allowedStatuses = STATUS_FLOW_MAP.getOrDefault(currentStatus.toUpperCase(), Set.of());
        if (!allowedStatuses.contains(targetStatus.toUpperCase())) {
            throw new IllegalArgumentException("不允许的合同状态流转: " + currentStatus + " -> " + targetStatus);
        }
    }

    public void validateArchiveAction(String currentStatus) {
        if (!"BOTH_SIGNED".equalsIgnoreCase(currentStatus)) {
            throw new IllegalArgumentException("只有双方签署完成的合同才允许归档");
        }
    }

    public void validateSealAction(String currentStatus, String sealStatus) {
        if (!"BOTH_SIGNED".equalsIgnoreCase(currentStatus) && !"SIGNED".equalsIgnoreCase(currentStatus)) {
            throw new IllegalArgumentException("当前合同状态不允许发起电子签章");
        }
        if ("SEALED".equalsIgnoreCase(sealStatus)) {
            throw new IllegalArgumentException("合同已经完成电子签章，请勿重复签章");
        }
    }
}
