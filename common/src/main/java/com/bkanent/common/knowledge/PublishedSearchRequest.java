package com.bkanent.common.knowledge;

import java.util.List;

public record PublishedSearchRequest(String contractVersion, String problem, String scene,
                                     List<String> tags, String cursor, Integer limit) {
    public PublishedSearchRequest {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
