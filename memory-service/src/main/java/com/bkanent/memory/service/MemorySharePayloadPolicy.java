package com.bkanent.memory.service;

import com.bkanent.common.memory.MemoryJsonValue;
import com.bkanent.common.memory.MemorySharedExportSubmitRequest;
import com.bkanent.common.memory.MemorySharedExportPolicy;
import org.springframework.stereotype.Component;

/** Defines the bounded durable collaboration projection; it excludes credentials and internal reasoning fields. */
@Component
public class MemorySharePayloadPolicy {
    private final int maxUtf8Bytes;

    public MemorySharePayloadPolicy(com.bkanent.memory.config.MemorySharingProperties properties) {
        this.maxUtf8Bytes = properties.getMaxExportUtf8Bytes();
    }

    public String project(MemorySharedExportSubmitRequest request) {
        return project(request.brief(), request.payload());
    }

    public String project(String brief, MemoryJsonValue payload) {
        return MemorySharedExportPolicy.project(brief, payload, maxUtf8Bytes);
    }
}
