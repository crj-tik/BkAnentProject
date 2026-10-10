package com.bkanent.contract.ocr;

import com.bkanent.contract.config.ContractIntegrationProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * KE 百度 OCR 抽取器：下载附件、base64 上送网关、行结果装配的协议回归。
 */
class KeBaiduContractOcrExtractorTest {

    private MockWebServer server;
    private ContractIntegrationProperties properties;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        properties = new ContractIntegrationProperties();
        properties.setMode("real");
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private KeBaiduContractOcrExtractor extractor(String model) {
        return new KeBaiduContractOcrExtractor(properties, server.url("/v1").toString(), model, "test-key");
    }

    @Test
    void supportsMatchesConfiguredModelName() {
        assertThat(extractor("baidu-general").supports("baidu-general")).isTrue();
        assertThat(extractor("baidu-general").supports("baidu-general-x")).isFalse();
        assertThat(extractor("baidu-general").supports(null)).isFalse();
    }

    @Test
    void extractsLinesFromGatewayResponse() throws Exception {
        byte[] png = {1, 2, 3, 4};
        server.enqueue(new MockResponse().setHeader("Content-Type", "image/png")
                .setBody(new okio.Buffer().write(okio.ByteString.of(png))));
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"request_id\":\"r1\",\"data\":{\"words\":[\"房屋买卖合同\",\"甲方：张三\"]}}"));

        ContractOcrExtractResult result = extractor("baidu-general")
                .extract("CONTRACT", "contract.png", server.url("/files/contract.png").toString());

        var download = server.takeRequest();
        assertThat(download.getMethod()).isEqualTo("GET");
        var ocr = server.takeRequest();
        assertThat(ocr.getPath()).isEqualTo("/v1/ocr/general");
        assertThat(ocr.getHeader("Authorization")).isEqualTo("Bearer test-key");
        String body = ocr.getBody().readUtf8();
        assertThat(body).contains("\"model\":\"baidu-general\"");
        assertThat(body).contains("\"image_base64\":\"" + Base64.getEncoder().encodeToString(png) + "\"");

        assertThat(result.provider()).isEqualTo("baidu-general");
        assertThat(result.plainText()).isEqualTo("房屋买卖合同\n甲方：张三");
        assertThat(result.structuredData())
                .contains("\"lines\":[\"房屋买卖合同\",\"甲方：张三\"]")
                .contains("\"attachmentType\":\"CONTRACT\"");
    }

    @Test
    void localModeIsRejectedExplicitly() {
        properties.setMode("local");
        KeBaiduContractOcrExtractor extractor = extractor("baidu-general");
        assertThatThrownBy(() -> extractor.extract("CONTRACT", "a.png", "http://example.com/a.png"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("real");
    }

    @Test
    void emptyDownloadIsRejected() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "image/png").setBody(""));
        KeBaiduContractOcrExtractor extractor = extractor("baidu-general");
        assertThatThrownBy(() -> extractor.extract("CONTRACT", "a.png", server.url("/files/a.png").toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty content");
    }

    @Test
    void emptyGatewayWordsRejected() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "image/png")
                .setBody(new okio.Buffer().write(okio.ByteString.of(new byte[]{1, 2, 3}))));
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"request_id\":\"r1\",\"data\":{}}"));
        KeBaiduContractOcrExtractor extractor = extractor("baidu-general");
        assertThatThrownBy(() -> extractor.extract("CONTRACT", "a.png", server.url("/files/a.png").toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty result");
    }
}
