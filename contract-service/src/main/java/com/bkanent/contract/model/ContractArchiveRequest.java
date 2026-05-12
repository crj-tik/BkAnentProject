package com.bkanent.contract.model;

/**
 * 合同归档请求。
 */
public record ContractArchiveRequest(
        /** 业务属性：archiveRemark。 */
        String archiveRemark
) {
}
