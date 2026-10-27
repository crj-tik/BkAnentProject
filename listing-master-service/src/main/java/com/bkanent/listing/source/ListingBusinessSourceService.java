package com.bkanent.listing.source;

import com.bkanent.common.a2a.VerifiedDomainAuthorization;
import com.bkanent.common.listing.ListingBusinessResponse;
import com.bkanent.common.listing.ListingBusinessSearchRequest;
import com.bkanent.common.listing.ListingRef;
import com.bkanent.common.listing.ListingSnapshotBatchReadItem;
import com.bkanent.common.listing.ListingSnapshotBatchReadRequest;
import com.bkanent.common.listing.ListingSnapshotCheckItem;
import com.bkanent.common.listing.ListingSnapshotCheckRequest;
import com.bkanent.common.listing.ListingSourceStatus;
import com.bkanent.common.listing.ListingSourceVersionRef;
import com.bkanent.common.listing.ListingSourceSnapshot;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ListingBusinessSourceService {
    private static final String CONTRACT = ListingBusinessResponse.VERSION;
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_REFS = 20;
    private final ListingSourceAdapter source;

    public ListingBusinessSourceService(ListingSourceAdapter source) {
        this.source = source;
    }

    public ListingBusinessResponse<ListingSourceSnapshot> search(ListingBusinessSearchRequest request) {
        if (request == null) throw ListingBusinessApiException.invalidArgument();
        requireContract(request.contractVersion());
        String keyword = request.keyword();
        if (keyword != null && (keyword.length() > 256 || keyword.chars().anyMatch(Character::isISOControl))) {
            throw ListingBusinessApiException.invalidArgument();
        }
        if (request.cursor() != null && request.cursor().length() > 512) throw ListingBusinessApiException.invalidArgument();
        int limit = request.limit() == null ? DEFAULT_LIMIT : request.limit();
        if (limit < 1 || limit > MAX_LIMIT) throw ListingBusinessApiException.invalidArgument();
        ListingAccessScope scope = requireReadScope();
        ListingSourcePage page = call(() -> source.search(
                new ListingBusinessSearchRequest(CONTRACT, keyword, request.cursor(), limit), scope));
        recheckReadScope();
        return new ListingBusinessResponse<>(CONTRACT, page.items(), page.nextCursor());
    }

    public ListingBusinessResponse<ListingSnapshotBatchReadItem> batchRead(ListingSnapshotBatchReadRequest request) {
        validateRefs(request == null ? null : request.contractVersion(), request == null ? null : request.refs(), false);
        ListingAccessScope scope = requireReadScope();
        List<ListingSnapshotBatchReadItem> items = call(() -> source.batchRead(request, scope));
        validateBatchReadResult(request.refs(), items);
        recheckReadScope();
        return new ListingBusinessResponse<>(CONTRACT, items, null);
    }

    public ListingBusinessResponse<ListingSnapshotCheckItem> check(ListingSnapshotCheckRequest request) {
        validateRefs(request == null ? null : request.contractVersion(), request == null ? null : request.refs(), true);
        ListingAccessScope scope = requireReadScope();
        List<ListingSnapshotCheckItem> items;
        try {
            items = source.check(request, scope);
        } catch (ListingSourceUnavailableException unavailable) {
            items = request.refs().stream().map(ref -> new ListingSnapshotCheckItem(
                    ref.listingRef(), ref.expectedRevision(), ListingSourceStatus.UNAVAILABLE)).toList();
        }
        validateCheckResult(request.refs(), items);
        recheckReadScope();
        return new ListingBusinessResponse<>(CONTRACT, items, null);
    }

    private ListingAccessScope requireReadScope() {
        Long brokerId = VerifiedDomainAuthorization.requireBrokerScope("listing.read", "listing.read.all");
        return new ListingAccessScope(VerifiedDomainAuthorization.requireActorId(), brokerId, brokerId == null);
    }

    private void recheckReadScope() {
        requireReadScope();
    }

    private static void requireContract(String version) {
        if (!CONTRACT.equals(version)) throw ListingBusinessApiException.unsupportedContractVersion();
    }

    private static void validateRefs(String contractVersion, List<ListingSourceVersionRef> refs, boolean requireExpected) {
        requireContract(contractVersion);
        if (refs == null || refs.isEmpty() || refs.size() > MAX_REFS) throw ListingBusinessApiException.invalidArgument();
        Set<ListingRef> unique = new HashSet<>();
        for (ListingSourceVersionRef ref : refs) {
            if (ref == null || ref.listingRef() == null || !unique.add(ref.listingRef())
                    || requireExpected && (ref.expectedRevision() == null || ref.expectedRevision().isBlank())) {
                throw ListingBusinessApiException.invalidArgument();
            }
        }
    }

    private static void validateBatchReadResult(List<ListingSourceVersionRef> requested,
                                                List<ListingSnapshotBatchReadItem> items) {
        if (items == null || items.size() != requested.size()) throw ListingBusinessApiException.dependencyUnavailable();
        for (int i = 0; i < requested.size(); i++) {
            ListingSnapshotBatchReadItem item = items.get(i);
            if (item == null || !requested.get(i).listingRef().equals(item.listingRef())) {
                throw ListingBusinessApiException.dependencyUnavailable();
            }
            if (item.status() == ListingSourceStatus.UNAVAILABLE) throw ListingBusinessApiException.dependencyUnavailable();
            if (item.status() == ListingSourceStatus.STALE) {
                if (requested.get(i).expectedRevision() == null) throw ListingBusinessApiException.dependencyUnavailable();
                throw ListingBusinessApiException.sourceVersionConflict();
            }
            if (item.status() == ListingSourceStatus.CURRENT) {
                if (!item.listingRef().equals(item.snapshot().listingRef())) {
                    throw ListingBusinessApiException.dependencyUnavailable();
                }
                if (requested.get(i).expectedRevision() != null
                        && !requested.get(i).expectedRevision().equals(item.snapshot().revision())) {
                    throw ListingBusinessApiException.sourceVersionConflict();
                }
            }
        }
    }

    private static void validateCheckResult(List<ListingSourceVersionRef> requested,
                                            List<ListingSnapshotCheckItem> items) {
        if (items == null || items.size() != requested.size()) throw ListingBusinessApiException.dependencyUnavailable();
        for (int i = 0; i < requested.size(); i++) {
            ListingSnapshotCheckItem item = items.get(i);
            ListingSourceVersionRef ref = requested.get(i);
            if (item == null || !ref.listingRef().equals(item.listingRef())
                    || !ref.expectedRevision().equals(item.expectedRevision())) {
                throw ListingBusinessApiException.dependencyUnavailable();
            }
        }
    }

    private static <T> T call(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (ListingSourceUnavailableException unavailable) {
            throw ListingBusinessApiException.dependencyUnavailable();
        }
    }
}
