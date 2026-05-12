package com.bkanent.contract.ocr;

import com.bkanent.contract.config.ContractProviderNames;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 模拟 OCR 结构化抽取器。
 */
@Component
public class MockContractOcrExtractor implements ContractOcrExtractor {

    @Override
    public boolean supports(String provider) {
        return !StringUtils.hasText(provider)
                || ContractProviderNames.OCR_MOCK.equalsIgnoreCase(provider)
                || ContractProviderNames.ALIAS_MOCK.equalsIgnoreCase(provider);
    }

    @Override
    public ContractOcrExtractResult extract(String attachmentType, String fileName, String fileUrl) {
        String structuredData;
        if ("ID_CARD".equalsIgnoreCase(attachmentType)) {
            structuredData = "{\"name\":\"张三\",\"idNo\":\"310101199001011234\",\"address\":\"上海市浦东新区\"}";
        } else if ("PROPERTY_CERTIFICATE".equalsIgnoreCase(attachmentType)) {
            structuredData = "{\"ownerName\":\"张三\",\"certificateNo\":\"沪房权证浦字2026第0001号\",\"propertyAddress\":\"上海市浦东新区张江路88号\"}";
        } else if ("BUSINESS_LICENSE".equalsIgnoreCase(attachmentType)) {
            structuredData = "{\"companyName\":\"示例置业有限公司\",\"creditCode\":\"91310000MA1K123456\",\"legalPerson\":\"李四\"}";
        } else {
            structuredData = "{\"fileName\":\"" + fileName + "\",\"fileUrl\":\"" + fileUrl + "\"}";
        }
        String plainText = "已识别附件，类型=" + attachmentType + "，文件名=" + fileName;
        return new ContractOcrExtractResult(ContractProviderNames.OCR_MOCK, plainText, structuredData);
    }
}
