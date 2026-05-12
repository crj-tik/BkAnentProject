package com.bkanent.common.model;

/**
 * 合同概要传输对象。
 */
public record ContractSummaryDTO(
        /** 业务属性：id。 */
        Long id,
        /** 业务属性：contractType。 */
        String contractType,
        /** 业务属性：status。 */
        String status,
        /** 业务属性：expiryDate。 */
        String expiryDate
) {
}
