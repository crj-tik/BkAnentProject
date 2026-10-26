package com.bkanent.promotion.client;

import com.bkanent.promotion.config.PromotionIntegrationProperties;
import com.bkanent.promotion.model.PromotionPublishRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PromotionPlatformClientDispatcherTest {

    @Test
    void neverReportsMockPlatformResultAsARealPublish() {
        PromotionPlatformClient client = mock(PromotionPlatformClient.class);
        PromotionPublishRequest request = new PromotionPublishRequest(null, 1L, "XIAOHONGSHU", null, "operator", null);
        PromotionPlatformClientDispatcher dispatcher = new PromotionPlatformClientDispatcher(
                List.of(client), localProperties());

        assertThrows(UnsupportedOperationException.class, () -> dispatcher.publish(null, request));
        verifyNoInteractions(client);
    }

    @Test
    void rejectsPlatformCallOutsideLocalMode() {
        PromotionPlatformClient client = mock(PromotionPlatformClient.class);
        PromotionIntegrationProperties properties = localProperties();
        properties.setMode("real");
        PromotionPlatformClientDispatcher dispatcher = new PromotionPlatformClientDispatcher(List.of(client), properties);

        assertThrows(UnsupportedOperationException.class, () -> dispatcher.publish(null,
                new PromotionPublishRequest(null, 1L, "XIAOHONGSHU", null, "operator", null)));
    }

    private PromotionIntegrationProperties localProperties() {
        PromotionIntegrationProperties properties = new PromotionIntegrationProperties();
        properties.setMode("local");
        return properties;
    }
}
