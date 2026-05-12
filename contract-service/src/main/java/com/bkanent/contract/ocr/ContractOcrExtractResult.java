package com.bkanent.contract.ocr;

/**
 * 合同附件 OCR 结构化抽取结果。
 */
public record ContractOcrExtractResult(
        String provider,
        String plainText,
        String structuredData
) {
}
