package com.bkanent.contract.ocr;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 合同 OCR 抽取分发器。
 */
@Component
public class ContractOcrExtractorDispatcher {

    private final List<ContractOcrExtractor> extractors;

    public ContractOcrExtractorDispatcher(List<ContractOcrExtractor> extractors) {
        this.extractors = extractors;
    }

    public ContractOcrExtractResult extract(String provider, String attachmentType, String fileName, String fileUrl) {
        return extractors.stream()
                .filter(extractor -> extractor.supports(provider))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到可用的 OCR 抽取器: " + provider))
                .extract(attachmentType, fileName, fileUrl);
    }
}
