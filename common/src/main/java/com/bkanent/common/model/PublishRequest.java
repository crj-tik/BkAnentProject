package com.bkanent.common.model;

import java.util.List;

public record PublishRequest(
        Long listingId,
        List<String> platforms,
        String prompt
) {
}
