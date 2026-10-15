package com.bkanent.agent.studio;

import com.bkanent.agent.security.AgentRagCredentialLeaseStore;
import com.bkanent.agent.security.AgentRagRequestAuthentication;
import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.ListingMasterRpcService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.*;

@Component
public class StudioListingReader {
    private final ObjectProvider<ListingMasterRpcService> provider;
    private final AgentRagCredentialLeaseStore credentials;
    private final AgentRagRequestAuthentication authentication;
    private final StudioJson json;
    public StudioListingReader(AgentRagCredentialLeaseStore credentials, AgentRagRequestAuthentication authentication, StudioJson json,
                               @Qualifier("listingMasterRpcServiceBridge") ObjectProvider<ListingMasterRpcService> provider) {
        this.credentials = credentials; this.authentication = authentication; this.json = json; this.provider = provider;
    }
    public ListingDTO read(String owner, String id) {
        long listingId;
        try { listingId = Long.parseLong(id); if (listingId <= 0) throw new IllegalArgumentException(); }
        catch (Exception ex) { throw StudioException.invalid("房源编号无效"); }
        var request = new RagListingReadRequest(1, json.id(), listingId, null);
        var credential = credentials.credentials(owner);
        var envelope = authentication.user(request, request.requestId(), "listing-master-service", RagOperation.READ, owner, credential);
        var listings = provider.getIfAvailable();
        if (listings == null) throw StudioException.unavailable("当前环境未连接房源服务");
        List<ListingDTO> result = listings.read(request, envelope, credential);
        if (result == null || result.size() != 1 || !Objects.equals(result.get(0).id(), listingId))
            throw new StudioException(org.springframework.http.HttpStatus.FORBIDDEN, "SOURCE_NOT_ACCESSIBLE", "引用的房源已不可访问，请重新核对范围");
        return result.get(0);
    }
    public Map<String, Object> publicListing(ListingDTO value) {
        Map<String, Object> row = new LinkedHashMap<>(); row.put("id", value.id().toString()); row.put("title", value.title() == null ? "房源 " + value.id() : value.title());
        row.put("totalPrice", value.totalPrice() == null ? null : Map.of("amount", value.totalPrice().multiply(new BigDecimal("10000")).toPlainString(), "currency", "CNY"));
        row.put("areaSqm", value.area()); row.put("layout", value.layout()); row.put("floor", value.floorLevel()); row.put("status", value.status()); row.put("sourceRevision", json.hash(value));
        return row;
    }
    public Map<String, Object> source(ListingDTO value) { return Map.of("type", "listing", "id", value.id().toString(), "label", value.title() == null ? "房源" : value.title(), "sourceRevision", json.hash(value), "fields", List.of("totalPrice", "area", "layout", "status")); }
}
