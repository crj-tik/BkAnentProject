package com.bkanent.customer.model;

import java.time.LocalDate;

/**
 * 业主委托书新增或更新请求。
 */
public record OwnerEntrustUpsertRequest(
        /** 业务属性：listingId。 */
        Long listingId,
        /** 业务属性：contractNo。 */
        String contractNo,
        /** 业务属性：entrustStartDate。 */
        LocalDate entrustStartDate,
        /** 业务属性：entrustEndDate。 */
        LocalDate entrustEndDate,
        /** 业务属性：reminderDays。 */
        Integer reminderDays,
        /** 业务属性：status。 */
        String status,
        /** 业务属性：remark。 */
        String remark
) {
}
