package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

/** Current SQL grants are authority; context, namespace and locator possession alone grant nothing. */
@Component
public class MemoryAccessPolicy {
    public void requirePrivate(long currentUserId, MemoryContextKey callerContext, MemoryContextKey storedContext) {
        requireSelf(currentUserId, callerContext);
        requireSelf(currentUserId, storedContext);
        require(callerContext.equals(storedContext));
    }

    public void requireShared(long currentUserId, MemoryGrantRequest request,
                              MemorySharingStore.ExportHeader header, MemoryShareGrant grant, Instant now) {
        requireSelf(currentUserId, request.contextKey());
        requireSelf(currentUserId, request.sharedRef().sourceContext());
        require(header != null && grant != null && now != null);
        requireSelf(currentUserId, header.sourceContext());
        require(grant.sourceContext().equals(header.sourceContext())
                && header.sourceContext().equals(request.sharedRef().sourceContext())
                && header.exportId().equals(request.sharedRef().exportId()));
        if (!header.version().equals(request.sharedRef().version())) {
            throw new MemoryException(MemoryErrorCode.VERSION_CONFLICT);
        }
        require(header.state() == MemorySharedExport.State.COMMITTED && header.lifecycleRevision() > 0);
        require(grant.state() == MemoryShareGrant.State.ACTIVE && header.grant().equals(grant.grant())
                && !now.isBefore(grant.validFrom()) && now.isBefore(grant.validUntil()));
        require(request.scope().equals(grant.scope()) && Objects.equals(request.matterId(), grant.matterId())
                && Objects.equals(header.matterId(), grant.matterId()));
        require(request.purpose().equals(grant.purpose()) && request.purpose().equals(header.purpose()));
        require(grant.recipients().contains(new MemoryShareGrant.Participant(request.contextKey().domainId(),
                request.contextKey().agentId(), request.contextKey().conversationId())));
        // This endpoint returns a durable payload. Display/collaboration permission cannot authorize it.
        require(grant.projections().contains(MemoryShareGrant.Projection.DURABLE_EXPORT));
    }

    public void requirePayload(MemoryGrantRequest request, MemorySharingStore.ExportHeader header,
                               MemoryShareGrant grant, MemorySharedExport export) {
        require(export != null && export.state() == MemorySharedExport.State.COMMITTED
                && request.sharedRef().equals(export.committedRef())
                && header.sourceContext().equals(export.sourceContext()) && header.version().equals(export.version())
                && header.grant().equals(export.grant()) && header.purpose().equals(export.purpose())
                && Objects.equals(header.matterId(), export.association().matterId()));
        // Chained source grants require the independent dependency revalidation from task 3.5.
        require(export.dependencies().stream().allMatch(dependency -> dependency.grant() == null
                && grant.sources().contains(dependency.source())));
    }

    public void requireSelf(long currentUserId, MemoryContextKey context) {
        require(currentUserId > 0 && context != null && Long.toString(currentUserId).equals(context.ownerId()));
    }

    private static void require(boolean condition) {
        if (!condition) throw new MemoryException(MemoryErrorCode.FORBIDDEN);
    }
}
