package com.bkanent.contract.ocr;

import com.bkanent.contract.config.ContractProviderNames;
import org.springframework.stereotype.Component;

/**
 * Generic third-party OCR extractor placeholder.
 */
@Component
public class VendorContractOcrExtractor implements ContractOcrExtractor {

    @Override
    public boolean supports(String provider) {
        if (provider == null || provider.isBlank()) {
            return false;
        }
        return ContractProviderNames.OCR_VENDOR.equalsIgnoreCase(provider)
                || ContractProviderNames.ALIAS_VENDOR.equalsIgnoreCase(provider)
                || ContractProviderNames.ALIAS_THIRD_PARTY_OCR.equalsIgnoreCase(provider)
                || ContractProviderNames.ALIAS_DASHSCOPE.equalsIgnoreCase(provider);
    }

    @Override
    public ContractOcrExtractResult extract(String attachmentType, String fileName, String fileUrl) {
        throw new UnsupportedOperationException("真实 OCR 服务尚未接入: " + ContractProviderNames.OCR_VENDOR);
    }
}
