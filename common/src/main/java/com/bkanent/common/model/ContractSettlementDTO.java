package com.bkanent.common.model;

import java.math.BigDecimal;

/**
 * 合同结算传输对象。
 */
public record ContractSettlementDTO(
        /** 业务属性：contractId。 */
        Long contractId,
        /** 业务属性：contractNo。 */
        String contractNo,
        /** 业务属性：contractType。 */
        String contractType,
        /** 业务属性：status。 */
        String status,
        /** 业务属性：brokerId。 */
        Long brokerId,
        /** 业务属性：listingId。 */
        Long listingId,
        /** 业务属性：customerName。 */
        String customerName,
        /** 业务属性：dealAmount。 */
        BigDecimal dealAmount,
        /** 业务属性：bothSignedTime。 */
        String bothSignedTime,
        /** 业务属性：sealStatus。 */
        String sealStatus
) {
}
