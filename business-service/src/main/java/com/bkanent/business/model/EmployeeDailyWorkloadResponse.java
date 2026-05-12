package com.bkanent.business.model;

/**
 * 员工日报响应。
 */
public record EmployeeDailyWorkloadResponse(
        /** 业务属性：employeeId。 */
        Long employeeId,
        /** 业务属性：employeeName。 */
        String employeeName,
        /** 业务属性：storeName。 */
        String storeName,
        /** 业务属性：regionName。 */
        String regionName,
        /** 业务属性：statDate。 */
        String statDate,
        /** 业务属性：viewingCount。 */
        Integer viewingCount,
        /** 业务属性：newListings。 */
        Integer newListings,
        /** 业务属性：newCustomers。 */
        Integer newCustomers,
        /** 业务属性：followUpCount。 */
        Integer followUpCount
) {
}
