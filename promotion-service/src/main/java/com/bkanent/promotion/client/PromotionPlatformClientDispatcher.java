package com.bkanent.promotion.client;

import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.promotion.model.PromotionPlatformPublishResult;
import com.bkanent.promotion.model.PromotionPublishRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 平台适配器分发器。
 */
@Component
public class PromotionPlatformClientDispatcher {

    private final Map<String, PromotionPlatformClient> clientMap;

    public PromotionPlatformClientDispatcher(List<PromotionPlatformClient> clients) {
        this.clientMap = clients.stream()
                .collect(Collectors.toMap(
                        client -> client.platform().toUpperCase(Locale.ROOT),
                        Function.identity()
                ));
    }

    public PromotionPlatformPublishResult publish(MarketingContentDTO content, PromotionPublishRequest request) {
        PromotionPlatformClient client = clientMap.get(request.platform().toUpperCase(Locale.ROOT));
        if (client == null) {
            throw new IllegalArgumentException("未找到平台适配器: " + request.platform());
        }
        return client.publish(content, request);
    }
}
