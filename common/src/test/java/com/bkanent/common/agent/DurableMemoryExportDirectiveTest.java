package com.bkanent.common.agent;

import com.bkanent.common.memory.MemoryScope;
import com.bkanent.common.memory.MemoryShareGrant;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DurableMemoryExportDirectiveTest {
    private final Instant from = Instant.parse("2026-10-21T00:00:00Z");
    private final Instant until = from.plusSeconds(3600);
    private final List<MemoryShareGrant.Participant> recipients = List.of(
            new MemoryShareGrant.Participant("listing", "listing-agent", "conversation-1"));

    @Test
    void acceptsOnlyExplicitSharedProjectionAndApprovedResultFields() {
        DurableMemoryExportDirective directive = new DurableMemoryExportDirective(
                "grant-1", new MemoryScope(MemoryScope.Kind.SESSION_SHARED, null, null, "session-1"), null,
                "listing-review", recipients,
                List.of(MemoryShareGrant.Projection.COLLABORATION, MemoryShareGrant.Projection.DURABLE_EXPORT),
                List.of("result", "facts"), true, from, until);

        assertThat(directive.exportFields()).containsExactly("result", "facts");
        assertThat(directive.projections()).contains(MemoryShareGrant.Projection.DURABLE_EXPORT);
    }

    @Test
    void rejectsPrivateScopeMissingDurableProjectionAndUnapprovedFields() {
        assertThatThrownBy(() -> directive(MemoryScope.privateScope("listing", "listing-agent"),
                List.of(MemoryShareGrant.Projection.DURABLE_EXPORT), List.of("result")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shared scope");
        assertThatThrownBy(() -> directive(new MemoryScope(MemoryScope.Kind.SESSION_SHARED, null, null, "session-1"),
                List.of(MemoryShareGrant.Projection.COLLABORATION), List.of("result")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projection");
        assertThatThrownBy(() -> directive(new MemoryScope(MemoryScope.Kind.SESSION_SHARED, null, null, "session-1"),
                List.of(MemoryShareGrant.Projection.DURABLE_EXPORT), List.of("memoryRefs")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowlist");
    }

    @Test
    void taskScopeRequiresMatterAndGrantValidityIsBounded() {
        assertThatThrownBy(() -> new DurableMemoryExportDirective("grant-1",
                new MemoryScope(MemoryScope.Kind.TASK_SHARED, null, null, "task-scope"), null,
                "purpose", recipients, List.of(MemoryShareGrant.Projection.DURABLE_EXPORT), List.of("result"),
                false, from, until))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("matterId");
        assertThatThrownBy(() -> new DurableMemoryExportDirective("grant-1",
                new MemoryScope(MemoryScope.Kind.SESSION_SHARED, null, null, "session-1"), null,
                "purpose", recipients, List.of(MemoryShareGrant.Projection.DURABLE_EXPORT), List.of("result"),
                false, from, from.plusSeconds(31L * 24 * 3600)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("30 days");
    }

    private DurableMemoryExportDirective directive(MemoryScope scope,
            List<MemoryShareGrant.Projection> projections, List<String> fields) {
        return new DurableMemoryExportDirective("grant-1", scope, null, "purpose", recipients,
                projections, fields, false, from, until);
    }
}
