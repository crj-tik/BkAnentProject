package com.bkanent.media.client;

import com.bkanent.media.config.MediaIntegrationProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * KE Seedream 文生图客户端：请求形状、URL 下载、b64 回退、local 拦截与命名规则的回归。
 */
class KeSeedreamImageGenerationClientTest {

    private static final byte[] PNG_BYTES = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

    private MockWebServer server;
    private MediaIntegrationProperties properties;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        properties = new MediaIntegrationProperties();
        properties.setMode("real");
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private KeSeedreamImageGenerationClient client() {
        return new KeSeedreamImageGenerationClient(properties, server.url("/v1").toString(),
                "doubao-seedream-4.5-gen", "test-key");
    }

    @Test
    void generatesViaGatewayAndDownloadsUrlResult() throws Exception {
        String imageUrl = server.url("/files/img-1.png").toString();
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"created\":1,\"data\":[{\"url\":\"" + imageUrl + "\"}],\"usage\":{\"num\":1}}"));
        server.enqueue(new MockResponse().setHeader("Content-Type", "image/png")
                .setBody(new okio.Buffer().write(okio.ByteString.of(PNG_BYTES))));

        List<GeneratedMediaFile> files = client()
                .generateListingImages(101L, "现代简约客厅", List.of("客厅视角"));

        var gen = server.takeRequest();
        assertThat(gen.getPath()).isEqualTo("/v1/images/generations");
        assertThat(gen.getHeader("Authorization")).isEqualTo("Bearer test-key");
        String body = gen.getBody().readUtf8();
        assertThat(body).contains("\"model\":\"doubao-seedream-4.5-gen\"");
        assertThat(body).contains("\"n\":1");
        assertThat(body).doesNotContain("size");
        assertThat(body).contains("现代简约客厅，客厅视角");
        var download = server.takeRequest();
        assertThat(download.getPath()).startsWith("/files/");

        assertThat(files).hasSize(1);
        assertThat(files.get(0).fileName()).isEqualTo("1-客厅视角.png");
        assertThat(files.get(0).content()).isEqualTo(PNG_BYTES);
    }

    @Test
    void b64FallbackWhenUrlMissing() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"data\":[{\"b64_json\":\"" + java.util.Base64.getEncoder().encodeToString(PNG_BYTES) + "\"}]}"));

        List<GeneratedMediaFile> files = client()
                .generateListingImages(101L, "现代简约客厅", List.of("客厅视角"));

        assertThat(files).hasSize(1);
        assertThat(files.get(0).content()).isEqualTo(PNG_BYTES);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void localModeIsRejectedExplicitly() {
        properties.setMode("local");
        assertThatThrownBy(() -> client().generateListingImages(101L, "p", List.of("客厅视角")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("real");
    }

    @Test
    void emptyGatewayDataRejected() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"data\":[]}"));
        assertThatThrownBy(() -> client().generateListingImages(101L, "p", List.of("客厅视角")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no data");
    }

    @Test
    void composesPromptWithAngleWhenPromptBlank() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"data\":[{\"b64_json\":\"" + java.util.Base64.getEncoder().encodeToString(PNG_BYTES) + "\"}]}"));
        client().generateListingImages(101L, "", List.of("阳台视角"));
        var gen = server.takeRequest();
        String body = gen.getBody().readUtf8();
        assertThat(body).contains("房产营销图，阳台视角");
    }
}
