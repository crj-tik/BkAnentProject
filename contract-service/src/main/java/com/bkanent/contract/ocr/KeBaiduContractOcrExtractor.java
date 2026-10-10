package com.bkanent.contract.ocr;

import com.bkanent.contract.config.ContractIntegrationProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * KE 网关（Bella OpenAPI）的百度通用文字识别 OCR 抽取器。
 *
 * <p>调用 POST {gateway}/v1/ocr/general，请求体 {model, image_base64}，响应
 * {data:{words:[...]}}。百度后端只拉取公网 URL，MinIO 内网地址不可达，
 * 因此本实现先在服务端下载附件再以 base64 上送；密钥复用 KE_API_KEY。</p>
 */
@Component
public class KeBaiduContractOcrExtractor implements ContractOcrExtractor {

    private static final Logger log = LoggerFactory.getLogger(KeBaiduContractOcrExtractor.class);
    /** 上送网关的图片上限（base64 前），与百度 OCR 通用限制对齐。 */
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;

    private final ContractIntegrationProperties integrationProperties;
    private final String gatewayBaseUrl;
    private final String model;
    private final String apiKey;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KeBaiduContractOcrExtractor(ContractIntegrationProperties integrationProperties,
                                       @Value("${contract.integration.ocr-gateway-base-url:https://open-chatgpt.ke.com/v1}") String gatewayBaseUrl,
                                       @Value("${contract.integration.ocr-model:baidu-general}") String model,
                                       @Value("${KE_API_KEY:${DEEPSEEK_API_KEY:}}") String apiKey) {
        this.integrationProperties = integrationProperties;
        this.gatewayBaseUrl = gatewayBaseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.restClient = RestClient.builder().build();
    }

    @Override
    public boolean supports(String provider) {
        return model.equalsIgnoreCase(provider);
    }

    @Override
    public ContractOcrExtractResult extract(String attachmentType, String fileName, String fileUrl) {
        if (integrationProperties.isLocalMode()) {
            // local 模式显式拦截：真实验别需要 KE 网关与真实附件，避免开发环境误产生真实调用。
            throw new IllegalStateException("KE 百度 OCR 仅用于 real 集成模式（contract.integration.mode=real）");
        }
        if (!StringUtils.hasText(fileUrl)) {
            throw new IllegalArgumentException("fileUrl is required for KE OCR extraction");
        }
        byte[] image = restClient.get().uri(fileUrl).retrieve().body(byte[].class);
        if (image == null || image.length == 0) {
            throw new IllegalStateException("attachment download returned empty content: " + fileName);
        }
        if (image.length > MAX_IMAGE_BYTES) {
            throw new IllegalStateException("attachment too large for OCR: " + image.length + " bytes");
        }
        OcrResponse response = restClient.post()
                .uri(gatewayBaseUrl + "/ocr/general")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (StringUtils.hasText(apiKey)) {
                        headers.setBearerAuth(apiKey);
                    }
                })
                .body(Map.of("model", model, "image_base64", Base64.getEncoder().encodeToString(image)))
                .retrieve()
                .body(OcrResponse.class);
        if (response == null || response.data() == null || response.data().words() == null) {
            throw new IllegalStateException("KE OCR returned empty result for " + fileName);
        }
        String plainText = String.join("\n", response.data().words());
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("provider", model);
        structured.put("attachmentType", attachmentType);
        structured.put("fileName", fileName);
        structured.put("lines", response.data().words());
        log.info("KE OCR extracted {} lines from attachment {} (type={})", response.data().words().size(), fileName, attachmentType);
        return new ContractOcrExtractResult(model, plainText, toJson(structured));
    }

    private String toJson(Map<String, Object> structured) {
        try {
            return objectMapper.writeValueAsString(structured);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to serialize OCR structured data", exception);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OcrResponse(Data data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Data(java.util.List<String> words) {
        }
    }
}
