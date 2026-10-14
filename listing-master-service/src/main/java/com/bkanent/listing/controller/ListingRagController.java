package com.bkanent.listing.controller;

import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.rag.*;
import com.bkanent.listing.rag.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** New domain read/admin surface: Authorization is a dedicated credential, never a model field. */
@RestController
@RequestMapping("/listings/rag")
public class ListingRagController {
    private final ListingRagFacade facade;
    private final ListingRagGrantService grants;
    private final ListingRagBackfillService backfill;
    private final ListingRagDispatcher dispatcher;
    public ListingRagController(ListingRagFacade facade, ListingRagGrantService grants, ListingRagBackfillService backfill, ListingRagDispatcher dispatcher) {
        this.facade = facade; this.grants = grants; this.backfill = backfill; this.dispatcher = dispatcher;
    }
    @PostMapping("/search")
    public ApiResponse<RagListingSearchResponse> search(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                       @RequestBody RagListingSearchRequest request) {
        return ApiResponse.ok(facade.search(request, ListingRagCredentialContext.bearer(authorization), null));
    }
    @PostMapping("/backfill")
    public ApiResponse<List<QueuedEvent>> backfill(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                   @RequestParam(defaultValue = "0") long afterId, @RequestParam(defaultValue = "100") int limit) {
        // Explicitly QUEUED local outbox receipt, not falsely RAG ACCEPTED/APPLIED.
        return ApiResponse.ok(backfill.backfill(afterId, limit, ListingRagCredentialContext.bearer(authorization)).stream()
                .map(event -> new QueuedEvent(event.eventId(), event.documentId(), event.revision(), "QUEUED")).toList());
    }
    @PostMapping("/index/{listingId}")
    public ApiResponse<RagIndexStatus> index(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable Long listingId) {
        RagIndexEvent event = backfill.index(listingId, ListingRagCredentialContext.bearer(authorization));
        return ApiResponse.ok(dispatcher.deliver(event.eventId()));
    }
    public record QueuedEvent(String eventId, String documentId, long revision, String state) { }
}
