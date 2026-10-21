package com.bkanent.common.agent;

import com.bkanent.common.memory.MemoryScope;
import com.bkanent.common.memory.MemorySharedExportFields;
import com.bkanent.common.memory.MemoryShareGrant;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

/** Explicit owner-authorized request to publish selected fields from one A2A result. */
public record DurableMemoryExportDirective(String grantId, MemoryScope scope, String matterId,
        String purpose, List<MemoryShareGrant.Participant> recipients,
        List<MemoryShareGrant.Projection> projections, List<String> exportFields,
        boolean includeBrief, Instant validFrom, Instant validUntil) implements Serializable {
    private static final long serialVersionUID = 1L;
    public DurableMemoryExportDirective {
        grantId = requireId(grantId, "grantId");
        if (scope == null || scope.kind() == MemoryScope.Kind.DOMAIN_PRIVATE) {
            throw new IllegalArgumentException("shared scope is required for durable export");
        }
        if (scope.kind() == MemoryScope.Kind.TASK_SHARED && !hasText(matterId)) {
            throw new IllegalArgumentException("matterId is required for task-scoped export");
        }
        matterId = hasText(matterId) ? requireId(matterId, "matterId") : null;
        purpose = requireId(purpose, "purpose");
        recipients = List.copyOf(recipients == null ? List.of() : recipients);
        projections = List.copyOf(projections == null ? List.of() : projections);
        exportFields = List.copyOf(exportFields == null ? List.of() : exportFields);
        if (recipients.isEmpty() || recipients.size() > 64 || new HashSet<>(recipients).size() != recipients.size()) {
            throw new IllegalArgumentException("durable export needs 1 to 64 unique recipients");
        }
        if (projections.isEmpty() || new HashSet<>(projections).size() != projections.size()
                || !projections.contains(MemoryShareGrant.Projection.DURABLE_EXPORT)) {
            throw new IllegalArgumentException("durable export projection must be explicit");
        }
        if (exportFields.isEmpty() || exportFields.size() > 32 || new HashSet<>(exportFields).size() != exportFields.size()) {
            throw new IllegalArgumentException("durable export needs 1 to 32 unique result fields");
        }
        for (String field : exportFields) {
            if (field == null || !MemorySharedExportFields.allowsRootField(field)) {
                throw new IllegalArgumentException("result field is not in the shared export allowlist");
            }
        }
        if (validFrom == null || validUntil == null || !validUntil.isAfter(validFrom)
                || Duration.between(validFrom, validUntil).compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("durable export grant validity must be positive and at most 30 days");
        }
    }

    private static String requireId(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:%-]{0,254}")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
}
