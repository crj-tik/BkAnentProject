package com.bkanent.common.marketing.campaign;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** A requested platform target; account authority is resolved by the server. */
public record CampaignTargetRequest(String platform, Long accountId, Map<String, String> arguments) {
    public CampaignTargetRequest {
        arguments = arguments == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(arguments));
    }
}
