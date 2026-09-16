package com.bkanent.promotion.client;

import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.promotion.config.PromotionIntegrationProperties;
import com.bkanent.promotion.model.PromotionPlatformPublishResult;
import com.bkanent.promotion.model.PromotionPublishRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromotionPlatformClientDispatcherTest {

    @Test
    void marksLocalPlatformResultAsSimulated() {
        PromotionPlatformClient client = mock(PromotionPlatformClient.class);
        PromotionPublishRequest request = new PromotionPublishRequest(null, 1L, "XIAOHONGSHU", null, "operator", null);
        when(client.platform()).thenReturn("XIAOHONGSHU");
        when(client.publish(null, request)).thenReturn(new PromotionPlatformPublishResult(
                true, "SUCCESS", "PUB-1", "发布成功", LocalDateTime.now()));

        PromotionPlatformClientDispatcher dispatcher = new PromotionPlatformClientDispatcher(
                List.of(client), localProperties());
        PromotionPlatformPublishResult result = dispatcher.publish(null, request);

        assertTrue(result.success());
        assertEquals("SIMULATED-PUB-1", result.externalPublishId());
        assertTrue(result.publishMessage().startsWith("[SIMULATED]"));
    }

    @Test
    void rejectsPlatformCallOutsideLocalMode() {
        PromotionPlatformClient client = mock(PromotionPlatformClient.class);
        when(client.platform()).thenReturn("XIAOHONGSHU");
        PromotionIntegrationProperties properties = localProperties();
        properties.setMode("real");
        PromotionPlatformClientDispatcher dispatcher = new PromotionPlatformClientDispatcher(List.of(client), properties);

        assertThrows(IllegalStateException.class, () -> dispatcher.publish(null,
                new PromotionPublishRequest(null, 1L, "XIAOHONGSHU", null, "operator", null)));
    }

    private PromotionIntegrationProperties localProperties() {
        PromotionIntegrationProperties properties = new PromotionIntegrationProperties();
        properties.setMode("local");
        return properties;
    }
}
