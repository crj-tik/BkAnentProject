package com.bkanent.contract.ocr;

/**
 * 合同 OCR 抽取器接口。
 */
public interface ContractOcrExtractor {

    /**
     * 业务方法：supports。
     */
    boolean supports(String provider);

    /**
     * 业务方法：extract。
     */
    ContractOcrExtractResult extract(String attachmentType, String fileName, String fileUrl);
}
