package com.bkanent.media.client;

import com.bkanent.media.config.MediaIntegrationProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * KE 网关（Bella OpenAPI）豆包 Seedream 文生图客户端。
 *
 * <p>调用 POST {gateway}/images/generations，请求体 {model, prompt, n}（Seedream
 * 不接受 size 参数，分辨率由模型自定，实测 2048x2048）；响应 data[0] 返回
 * TOS 临时 URL，需下载转为 byte[] 以匹配 {@link GeneratedMediaFile} 契约。
 * 每个 angle 独立成图，延迟约 8–14s/张，由 RocketMQ 异步消费吸收。</p>
 */
@Component
public class KeSeedreamImageGenerationClient implements MediaImageGenerationClient {

    private static final Logger log = LoggerFactory.getLogger(KeSeedreamImageGenerationClient.class);
    /** Seedream 的 TOS 临时链接下载上限（对齐生成图体积量级）。 */
    private static final int MAX_IMAGE_BYTES = 32 * 1024 * 1024;

    private final MediaIntegrationProperties integrationProperties;
    private final String gatewayBaseUrl;
    private final String model;
    private final String apiKey;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KeSeedreamImageGenerationClient(MediaIntegrationProperties integrationProperties,
                                           @Value("${media.integration.image-gateway-base-url:https://open-chatgpt.ke.com/v1}") String gatewayBaseUrl,
                                           @Value("${media.integration.image-model:doubao-seedream-4.5-gen}") String model,
                                           @Value("${KE_API_KEY:${DEEPSEEK_API_KEY:}}") String apiKey) {
        this.integrationProperties = integrationProperties;
        this.gatewayBaseUrl = gatewayBaseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.restClient = RestClient.builder().build();
    }

    @Override
    public List<GeneratedMediaFile> generateListingImages(Long listingId, String prompt, List<String> angles) {
        if (integrationProperties.isLocalMode()) {
            // 真实生成需 KE 网关配额，local 模式显式拦截防开发环境误产生真实调用（同 OCR 策略）。
            throw new IllegalStateException("KE Seedream 文生图仅用于 real 集成模式（media.integration.mode=real）");
        }
        List<GeneratedMediaFile> files = new ArrayList<>();
        int index = 1;
        for (String angle : angles) {
            files.add(new GeneratedMediaFile(buildFileName(index, angle), generateOne(prompt, angle)));
            index++;
        }
        return files;
    }

    private byte[] generateOne(String prompt, String angle) {
        String composedPrompt = prompt == null || prompt.isBlank()
                ? "房产营销图，" + angle
                : prompt + "，" + angle;
        ImageResponse response = restClient.post()
                .uri(gatewayBaseUrl + "/images/generations")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (StringUtils.hasText(apiKey)) {
                        headers.setBearerAuth(apiKey);
                    }
                })
                .body(Map.of("model", model, "prompt", composedPrompt, "n", 1))
                .retrieve()
                .body(ImageResponse.class);
        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new IllegalStateException("KE image generation returned no data for angle: " + angle);
        }
        ImageItem item = response.data().get(0);
        byte[] image;
        if (StringUtils.hasText(item.url())) {
            image = restClient.get().uri(item.url()).retrieve().body(byte[].class);
        } else if (StringUtils.hasText(item.b64Json())) {
            image = java.util.Base64.getDecoder().decode(item.b64Json());
        } else {
            throw new IllegalStateException("KE image response has neither url nor b64_json for angle: " + angle);
        }
        if (image == null || image.length == 0) {
            throw new IllegalStateException("KE image download returned empty content for angle: " + angle);
        }
        if (image.length > MAX_IMAGE_BYTES) {
            throw new IllegalStateException("KE image too large: " + image.length + " bytes");
        }
        log.info("KE image generated for angle {} ({} bytes, model={})", angle, image.length, model);
        return image;
    }

    private String buildFileName(int index, String angle) {
        String normalizedAngle = angle == null ? "default" : angle.replaceAll("[^\\p{IsHan}a-zA-Z0-9]+", "-");
        return index + "-" + normalizedAngle + ".png";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ImageResponse(List<ImageItem> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ImageItem(String url, @com.fasterxml.jackson.annotation.JsonProperty("b64_json") String b64Json) {
    }
}
