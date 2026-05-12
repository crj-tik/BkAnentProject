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
        String plainText = "Third-party OCR placeholder extracted attachment, type="
                + attachmentType + ", fileName=" + fileName;
        String structuredData = "{\"provider\":\"" + ContractProviderNames.OCR_VENDOR
                + "\",\"attachmentType\":\"" + attachmentType
                + "\",\"fileName\":\"" + fileName + "\"}";
        return new ContractOcrExtractResult(ContractProviderNames.OCR_VENDOR, plainText, structuredData);
    }
}
