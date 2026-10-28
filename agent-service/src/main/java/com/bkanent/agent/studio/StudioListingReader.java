package com.bkanent.agent.studio;

import com.bkanent.agent.authorization.AgentAuthorizationScope;
import com.bkanent.agent.security.AgentRagRequestAuthentication;
import com.bkanent.common.authz.AuthzCanonicalHash;
import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.authz.AuthzResponse;
import com.bkanent.common.listing.ListingBusinessResponse;
import com.bkanent.common.listing.ListingBusinessSearchRequest;
import com.bkanent.common.listing.ListingRef;
import com.bkanent.common.listing.ListingSnapshotBatchReadItem;
import com.bkanent.common.listing.ListingSnapshotBatchReadRequest;
import com.bkanent.common.listing.ListingSnapshotCheckItem;
import com.bkanent.common.listing.ListingSnapshotCheckRequest;
import com.bkanent.common.listing.ListingSourceSnapshot;
import com.bkanent.common.listing.ListingSourceStatus;
import com.bkanent.common.listing.ListingSourceVersionRef;
import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagAuthorizedListingBusinessBatchReadRequest;
import com.bkanent.common.rag.RagAuthorizedListingBusinessCheckRequest;
import com.bkanent.common.rag.RagAuthorizedListingBusinessSearchRequest;
import com.bkanent.common.rag.RagCanonicalHash;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rag.RagOperation;
import com.bkanent.common.rpc.ListingBusinessSourceRpcService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** Studio's stable, user-authorized Listing read adapter. */
@Component
public class StudioListingReader {
    private static final String AUDIENCE = "listing-master-service";
    private static final String CONTRACT = ListingBusinessResponse.VERSION;
    private final AgentRagRequestAuthentication authentication;
    private final StudioJson json;
    private final ObjectProvider<ListingBusinessSourceRpcService> provider;

    public StudioListingReader(AgentRagRequestAuthentication authentication, StudioJson json,
                               @Qualifier("listingBusinessSourceRpcServiceBridge")
                               ObjectProvider<ListingBusinessSourceRpcService> provider) {
        this.authentication = authentication;
        this.json = json;
        this.provider = provider;
    }

    public ListingBusinessResponse<ListingSourceSnapshot> search(String owner, String keyword,
                                                                  String cursor, Integer limit) {
        ListingBusinessSearchRequest request = new ListingBusinessSearchRequest(CONTRACT, keyword, cursor, limit);
        String requestId = json.id();
        RagAuthorizedListingBusinessSearchRequest signed = new RagAuthorizedListingBusinessSearchRequest(1,
                requestId, request, evidence(owner));
        return call(owner, signed, requestId, ListingBusinessSourceRpcService::search);
    }

    public List<ListingSourceSnapshot> readCurrent(String owner, List<ListingRef> refs) {
        if (refs == null || refs.isEmpty()) throw StudioException.invalid("请先选择房源来源");
        ListingSnapshotBatchReadRequest request = new ListingSnapshotBatchReadRequest(CONTRACT,
                refs.stream().map(ref -> new ListingSourceVersionRef(ref, null)).toList());
        List<ListingSnapshotBatchReadItem> read = batchRead(owner, request).items();
        if (read.size() != refs.size()) throw StudioException.unavailable("房源来源响应不完整");
        List<ListingSourceSnapshot> snapshots = new ArrayList<>(read.size());
        for (int index = 0; index < refs.size(); index++) {
            ListingSnapshotBatchReadItem item = read.get(index);
            if (item == null || !refs.get(index).equals(item.listingRef())) throw StudioException.unavailable("房源来源响应不匹配");
            if (item.status() == ListingSourceStatus.NOT_ACCESSIBLE) throw inaccessible();
            if (item.status() == ListingSourceStatus.UNAVAILABLE) throw StudioException.unavailable("房源来源暂不可用");
            if (item.status() != ListingSourceStatus.CURRENT || item.snapshot() == null) throw stale();
            snapshots.add(item.snapshot());
        }
        verifyCurrent(owner, snapshots.stream().map(snapshot ->
                new ListingSourceVersionRef(snapshot.listingRef(), snapshot.revision())).toList());
        return List.copyOf(snapshots);
    }

    /** Reads a candidate only if every expected source revision and content hash remain current. */
    public List<ListingSourceSnapshot> readExpected(String owner, List<ListingSourceVersionRef> expected) {
        verifyCurrent(owner, expected);
        List<ListingRef> refs = expected.stream().map(ListingSourceVersionRef::listingRef).toList();
        List<ListingSourceSnapshot> snapshots = readCurrent(owner, refs);
        Map<ListingRef, String> revisions = expected.stream().collect(java.util.stream.Collectors.toMap(
                ListingSourceVersionRef::listingRef, ListingSourceVersionRef::expectedRevision));
        for (ListingSourceSnapshot snapshot : snapshots) {
            if (!snapshot.revision().equals(revisions.get(snapshot.listingRef()))) throw stale();
        }
        verifyCurrent(owner, expected);
        return snapshots;
    }

    public List<ListingSourceSnapshot> readExpected(String owner, List<ListingSourceVersionRef> expected,
                                                    Map<ListingRef, String> expectedContentHashes) {
        List<ListingSourceSnapshot> snapshots = readExpected(owner, expected);
        for (ListingSourceSnapshot snapshot : snapshots) {
            if (expectedContentHashes == null
                    || !snapshot.contentHash().equals(expectedContentHashes.get(snapshot.listingRef()))) throw stale();
        }
        return snapshots;
    }

    public static ListingRef reference(Map<String, Object> ref) {
        if (ref == null) throw StudioException.invalid("房源引用格式无效");
        Object rawId = ref.get("sourceId") instanceof String ? ref.get("sourceId") : ref.get("id");
        String id = rawId instanceof String text ? text : null;
        String system = ref.get("sourceSystem") instanceof String text ? text : "legacy-local";
        if (id == null) throw StudioException.invalid("房源来源编号缺失");
        try { return new ListingRef(system, id); }
        catch (IllegalArgumentException invalid) { throw StudioException.invalid("房源来源引用无效"); }
    }

    public void verifyCurrent(String owner, List<ListingSourceVersionRef> refs) {
        if (refs == null || refs.isEmpty()) throw StudioException.invalid("缺少房源来源版本");
        ListingSnapshotCheckRequest request = new ListingSnapshotCheckRequest(CONTRACT, refs);
        String requestId = json.id();
        RagAuthorizedListingBusinessCheckRequest signed = new RagAuthorizedListingBusinessCheckRequest(1,
                requestId, request, evidence(owner));
        ListingBusinessResponse<ListingSnapshotCheckItem> response = call(owner, signed, requestId,
                ListingBusinessSourceRpcService::check);
        if (response.items().size() != refs.size()) throw StudioException.unavailable("房源版本核验响应不完整");
        for (int index = 0; index < refs.size(); index++) {
            ListingSourceVersionRef expected = refs.get(index);
            ListingSnapshotCheckItem item = response.items().get(index);
            if (item == null || !expected.listingRef().equals(item.listingRef())) throw StudioException.unavailable("房源版本核验响应不匹配");
            switch (item.status()) {
                case CURRENT -> { }
                case STALE -> throw stale();
                case NOT_ACCESSIBLE -> throw inaccessible();
                case UNAVAILABLE -> throw StudioException.unavailable("房源来源暂不可用");
            }
        }
    }

    public Map<String, Object> publicListing(ListingSourceSnapshot snapshot) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", snapshot.listingRef().sourceId());
        row.put("title", snapshot.facts().title() == null ? "房源 " + snapshot.listingRef().sourceId() : snapshot.facts().title());
        row.put("totalPrice", snapshot.facts().totalPrice() == null ? null : Map.of(
                "amount", snapshot.facts().totalPrice().amount(), "currency", snapshot.facts().totalPrice().currency()));
        row.put("areaSqm", snapshot.facts().areaSquareMetres());
        row.put("layout", snapshot.facts().attributes().get("layout"));
        row.put("status", snapshot.facts().attributes().get("status"));
        row.put("sourceRef", sourceRef(snapshot));
        row.put("sourceRevision", snapshot.revision());
        row.put("contentHash", snapshot.contentHash());
        row.put("dataMode", snapshot.dataMode().name());
        return row;
    }

    public Map<String, Object> source(ListingSourceSnapshot snapshot) {
        return Map.of("type", "listing", "id", snapshot.listingRef().sourceId(),
                "sourceSystem", snapshot.listingRef().sourceSystem(), "label",
                snapshot.facts().title() == null ? "房源" : snapshot.facts().title(),
                "sourceRevision", snapshot.revision(), "contentHash", snapshot.contentHash(),
                "dataMode", snapshot.dataMode().name(),
                "fields", List.of("totalPriceCny", "areaSquareMetres", "layout", "status"));
    }

    public Map<String, Object> sourceRef(ListingSourceSnapshot snapshot) {
        return Map.of("sourceSystem", snapshot.listingRef().sourceSystem(),
                "sourceId", snapshot.listingRef().sourceId(), "sourceRevision", snapshot.revision(),
                "contentHash", snapshot.contentHash(), "dataMode", snapshot.dataMode().name());
    }

    private ListingBusinessResponse<ListingSnapshotBatchReadItem> batchRead(
            String owner, ListingSnapshotBatchReadRequest request) {
        String requestId = json.id();
        RagAuthorizedListingBusinessBatchReadRequest signed = new RagAuthorizedListingBusinessBatchReadRequest(1,
                requestId, request, evidence(owner));
        return call(owner, signed, requestId, ListingBusinessSourceRpcService::batchRead);
    }

    private <T, R> R call(String owner, T request, String requestId, RpcCall<T, R> action) {
        AgentAuthorizationScope.Entry entry = AgentAuthorizationScope.current();
        if (entry == null) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        entry.requireActor(owner);
        entry.session().requirePermission("listing.read");
        AuthzResponse authorization = new AuthzResponse(entry.session().snapshot(), entry.session().context(),
                entry.session().freshness());
        RagAuthEnvelope proof = authentication.user(request, requestId, AUDIENCE, RagOperation.READ, owner, null);
        ListingBusinessSourceRpcService service = provider.getIfAvailable();
        if (service == null) throw StudioException.unavailable("当前环境未连接房源来源服务");
        try {
            return action.call(service, request, proof, authorization);
        } catch (AuthzException failure) {
            HttpStatus status = HttpStatus.valueOf(failure.code().httpStatus());
            if (status.is4xxClientError()) throw new StudioException(status, failure.code().name(), "当前身份无权读取房源");
            throw StudioException.unavailable("房源授权服务暂不可用");
        } catch (RagException failure) {
            switch (failure.code()) {
                case FORBIDDEN -> throw inaccessible();
                case INVALID_ARGUMENT -> throw StudioException.invalid("房源来源请求无效");
                case AUTHENTICATION_EXPIRED -> throw new StudioException(HttpStatus.UNAUTHORIZED,
                        "AUTHENTICATION_EXPIRED", "当前授权已过期，请重新认证");
                case DEPENDENCY_UNAVAILABLE, TIMEOUT -> throw StudioException.unavailable("房源来源暂不可用");
                default -> throw new StudioException(HttpStatus.FORBIDDEN, "SOURCE_NOT_ACCESSIBLE", "当前身份无权读取房源");
            }
        }
    }

    private String evidence(String owner) {
        AgentAuthorizationScope.Entry entry = AgentAuthorizationScope.current();
        if (entry == null) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        entry.requireActor(owner);
        entry.session().requirePermission("listing.read");
        return AuthzCanonicalHash.signedResponseHash(new AuthzResponse(entry.session().snapshot(),
                entry.session().context(), entry.session().freshness()));
    }

    private static StudioException inaccessible() {
        return new StudioException(org.springframework.http.HttpStatus.FORBIDDEN,
                "SOURCE_NOT_ACCESSIBLE", "引用的房源已不可访问，请重新核对范围");
    }

    private static StudioException stale() {
        return StudioException.conflict("SOURCE_VERSION_CONFLICT", "房源资料已变化，请刷新候选成果后重新比较");
    }

    @FunctionalInterface
    private interface RpcCall<T, R> {
        R call(ListingBusinessSourceRpcService service, T request, RagAuthEnvelope authentication,
               AuthzResponse authorization);
    }
}
