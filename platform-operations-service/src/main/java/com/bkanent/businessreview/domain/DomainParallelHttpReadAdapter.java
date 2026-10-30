package com.bkanent.businessreview.domain;

import com.bkanent.businessreview.domain.ReviewDomain.*;
import com.bkanent.common.knowledge.KnowledgeRef;
import com.bkanent.common.knowledge.PublishedCheckRequest;
import com.bkanent.common.knowledge.PublishedSearchRequest;
import com.bkanent.knowledge.service.PublishedKnowledgeService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consumer-local wire adapter for domain-parallel-v1. It never turns a remote error into a fixture.
 */
@Component
@ConditionalOnProperty(prefix = "platform-operations.review.sources", name = "mode", havingValue = "http")
public class DomainParallelHttpReadAdapter implements ReviewDatasetPort {
    public static final String CONTRACT_VERSION = "domain-parallel-v1";
    private final RestClient listing;
    private final DomainParallelProperties properties;
    private final DomainParallelRequestAuthenticator authenticator;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final PublishedKnowledgeService localKnowledge;

    @org.springframework.beans.factory.annotation.Autowired
    public DomainParallelHttpReadAdapter(RestClient.Builder builder, DomainParallelProperties properties,
                                         DomainParallelRequestAuthenticator authenticator,
                                         com.fasterxml.jackson.databind.ObjectMapper mapper,
                                         PublishedKnowledgeService localKnowledge) {
        this(builder, properties, authenticator, mapper, true, localKnowledge);
    }

    DomainParallelHttpReadAdapter(RestClient.Builder builder, DomainParallelProperties properties,
                                  DomainParallelRequestAuthenticator authenticator,
                                  com.fasterxml.jackson.databind.ObjectMapper mapper, boolean configureTimeouts,
                                  PublishedKnowledgeService localKnowledge) {
        if (properties.getListingBaseUrl() == null || properties.getListingBaseUrl().isBlank()) {
            throw new IllegalStateException("The Listing source URL is required in HTTP mode");
        }
        if (localKnowledge == null) throw new IllegalStateException("Platform Operations Knowledge source is required");
        if (configureTimeouts && (properties.getConnectTimeoutMs() < 1 || properties.getReadTimeoutMs() < 1
                || properties.getConnectTimeoutMs() > 60_000 || properties.getReadTimeoutMs() > 60_000)) {
            throw new IllegalStateException("Domain source HTTP timeouts must be between 1 and 60000 ms");
        }
        this.listing = createClient(builder, properties.getListingBaseUrl(), properties, configureTimeouts);
        this.properties = properties;
        this.authenticator = authenticator;
        this.mapper = mapper;
        this.localKnowledge = localKnowledge;
    }

    private RestClient createClient(RestClient.Builder builder, String baseUrl,
                                    DomainParallelProperties properties, boolean configureTimeouts) {
        RestClient.Builder client = builder.clone().baseUrl(baseUrl);
        if (configureTimeouts) {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
            requestFactory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
            client.requestFactory(requestFactory);
        }
        return client.build();
    }

    @Override
    public ReviewDataset read(String datasetId, long storeId, String authorizationOperation) {
        if (!CONTRACT_VERSION.equals(datasetId)) {
            throw new ReviewDependencyException("UNSUPPORTED_CONTRACT_VERSION",
                    "HTTP source mode accepts only domain-parallel-v1");
        }
        Instant observedAt = Instant.now();
        JsonNode listingResponse = post(listing, "/listings/business/v1/search", properties.getListingAudience(),
                authorizationOperation, Map.of("contractVersion", CONTRACT_VERSION, "keyword", "房源维护", "limit", 20));
        JsonNode knowledgeResponse = localSearch(authorizationOperation);
        List<ReviewFact> facts = new ArrayList<>();
        facts.addAll(mapListing(listingResponse, observedAt));
        facts.addAll(mapKnowledge(knowledgeResponse, observedAt));
        return new ReviewDataset(CONTRACT_VERSION, deriveMode(facts), observedAt,
                observedAt.minusSeconds(30L * 24 * 60 * 60), observedAt, ZoneId.of("Asia/Shanghai"), facts);
    }

    @Override
    public boolean sourcesCurrent(ReviewDataset dataset, String authorizationOperation) {
        List<ReviewFact> listingRefs = dataset.facts().stream()
                .filter(f -> f.kind() == FactKind.LISTING_MAINTENANCE).toList();
        List<ReviewFact> knowledgeRefs = dataset.facts().stream()
                .filter(f -> f.kind() == FactKind.KNOWLEDGE).toList();
        return check(listing, "/listings/business/v1/snapshots/check", properties.getListingAudience(),
                authorizationOperation, listingRefs, false)
                && check(null, null, null, authorizationOperation, knowledgeRefs, true);
    }

    private boolean check(RestClient client, String path, String audience, String operation,
                          List<ReviewFact> facts, boolean knowledgeSource) {
        if (facts.isEmpty()) return true;
        List<Map<String, Object>> refs = facts.stream().map(f -> {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put(knowledgeSource ? "knowledgeId" : "sourceSystem", knowledgeSource
                    ? f.source().sourceId() : f.source().sourceSystem());
            if (!knowledgeSource) ref.put("sourceId", f.source().sourceId());
            ref.put("expectedRevision", f.source().revision());
            if (knowledgeSource && f.source().contentHash() != null)
                ref.put("contentHash", f.source().contentHash());
            return ref;
        }).toList();
        JsonNode response = knowledgeSource
                ? localCheck(facts, operation)
                : post(client, path, audience, operation, Map.of("contractVersion", CONTRACT_VERSION, "refs", refs));
        JsonNode items = response.path("data").path("items");
        if (!items.isArray() || items.size() != facts.size())
            throw new ReviewDependencyException("DEPENDENCY_UNAVAILABLE", "Source check response does not match request");
        java.util.Set<String> checked = new java.util.HashSet<>();
        for (JsonNode item : items) {
            JsonNode ref = item.path(knowledgeSource ? "knowledgeRef" : "listingRef");
            String id = knowledgeSource ? requiredText(ref, "knowledgeId")
                    : requiredText(ref, "sourceId");
            String system = knowledgeSource ? "published-knowledge" : requiredText(ref, "sourceSystem");
            String revision = item.path("expectedRevision").asText(
                    item.path("revision").asText(ref.path("expectedRevision").asText(ref.path("revision").asText(""))));
            String key = system + ":" + id + "@" + revision;
            if (!checked.add(key))
                throw new ReviewDependencyException("DEPENDENCY_UNAVAILABLE", "Source check response contains duplicate references");
            boolean matchesRequest = facts.stream().anyMatch(f -> f.source().sourceSystem().equals(system)
                    && f.source().sourceId().equals(id) && f.source().revision().equals(revision));
            if (!matchesRequest)
                throw new ReviewDependencyException("DEPENDENCY_UNAVAILABLE", "Source check response does not match request");
            String status = item.path("status").asText();
            if ("UNAVAILABLE".equals(status))
                throw new ReviewDependencyException("DEPENDENCY_UNAVAILABLE", "Source check is unavailable");
            if ("STALE".equals(status) || "NOT_ACCESSIBLE".equals(status)) return false;
            if (!"CURRENT".equals(status))
                throw new ReviewDependencyException("DEPENDENCY_UNAVAILABLE", "Source check returned an unknown status");
        }
        return true;
    }

    private JsonNode localSearch(String operation) {
        try {
            com.bkanent.common.a2a.VerifiedDomainAuthorization.requireOperation(operation);
            var data = localKnowledge.search(new PublishedSearchRequest(CONTRACT_VERSION, "带看停滞或房源维护缺口",
                    "store-business-review", List.of("store", "listing"), null, 20));
            return mapper.valueToTree(com.bkanent.common.model.ApiResponse.ok(data));
        } catch (com.bkanent.common.authz.AuthzException failure) {
            throw new ReviewDependencyException("SOURCE_ACCESS_DENIED", "Knowledge source access was denied");
        } catch (RuntimeException failure) {
            throw new ReviewDependencyException("DEPENDENCY_UNAVAILABLE", "Knowledge source is unavailable");
        }
    }

    private JsonNode localCheck(List<ReviewFact> facts, String operation) {
        try {
            com.bkanent.common.a2a.VerifiedDomainAuthorization.requireOperation(operation);
            var refs = facts.stream().map(f -> new KnowledgeRef(f.source().sourceId(),
                    f.source().revision(), f.source().contentHash())).toList();
            var data = localKnowledge.check(new PublishedCheckRequest(CONTRACT_VERSION, refs));
            return mapper.valueToTree(com.bkanent.common.model.ApiResponse.ok(data));
        } catch (com.bkanent.common.authz.AuthzException failure) {
            throw new ReviewDependencyException("SOURCE_ACCESS_DENIED", "Knowledge source access was denied");
        } catch (RuntimeException failure) {
            throw new ReviewDependencyException("DEPENDENCY_UNAVAILABLE", "Knowledge source is unavailable");
        }
    }

    private JsonNode post(RestClient client, String path, String audience, String operation, Map<String, ?> request) {
        try {
            byte[] body = mapper.writeValueAsBytes(request);
            String authorization = authenticator.authorizationHeader(body, audience, operation, "POST", path);
            JsonNode response = client.post().uri(path)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .header(DomainParallelAuthorizationSigner.HEADER, authorization)
                    .body(new String(body, StandardCharsets.UTF_8)).retrieve().body(JsonNode.class);
            if (response == null) throw new ReviewDependencyException("DEPENDENCY_UNAVAILABLE", "Empty source response");
            if (!response.path("success").asBoolean(false)) {
                String code = response.path("code").asText("DEPENDENCY_UNAVAILABLE");
                throw new ReviewDependencyException(code, "Domain source rejected the request");
            }
            JsonNode data = response.path("data");
            if (!data.isObject() || !CONTRACT_VERSION.equals(data.path("contractVersion").asText()))
                throw new ReviewDependencyException("UNSUPPORTED_CONTRACT_VERSION",
                        "Domain source returned an unknown contract version");
            if (!data.path("items").isArray())
                throw new ReviewDependencyException("DEPENDENCY_UNAVAILABLE", "Domain source omitted items");
            return response;
        } catch (ReviewDependencyException failure) {
            throw failure;
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 401 || failure.getStatusCode().value() == 403)
                throw new ReviewDependencyException("SOURCE_ACCESS_DENIED", "Domain source access was denied");
            if (failure.getStatusCode().value() == 409)
                throw new ReviewDependencyException("SOURCE_VERSION_CONFLICT", "Domain source version changed");
            throw new ReviewDependencyException("DEPENDENCY_UNAVAILABLE", "Domain source request failed");
        } catch (RuntimeException failure) {
            throw new ReviewDependencyException("DEPENDENCY_UNAVAILABLE", "Domain source request failed");
        } catch (Exception failure) {
            throw new ReviewDependencyException("DEPENDENCY_UNAVAILABLE", "Domain source request failed");
        }
    }

    private List<ReviewFact> mapListing(JsonNode response, Instant observedAt) {
        List<ReviewFact> facts = new ArrayList<>();
        for (JsonNode item : response.path("data").path("items")) {
            JsonNode ref = item.path("listingRef");
            String sourceSystem = requiredText(ref, "sourceSystem");
            String sourceId = requiredText(ref, "sourceId");
            facts.add(mapFact(FactKind.LISTING_MAINTENANCE, item, sourceSystem, sourceId,
                    "房源公开快照已读取；具体维护状态仅按来源事实表达。", observedAt));
        }
        return List.copyOf(facts);
    }

    private List<ReviewFact> mapKnowledge(JsonNode response, Instant observedAt) {
        List<ReviewFact> facts = new ArrayList<>();
        for (JsonNode item : response.path("data").path("items")) {
            if (!"PUBLISHED".equals(item.path("publicationState").asText()))
                throw new ReviewDependencyException("DEPENDENCY_UNAVAILABLE", "Published knowledge source returned a non-published item");
            JsonNode ref = item.path("knowledgeRef");
            String sourceId = requiredText(ref, "knowledgeId");
            facts.add(mapFact(FactKind.KNOWLEDGE, item, "published-knowledge", sourceId,
                    "已发布知识摘要已读取并保留知识 revision；它不是门店事实。", observedAt));
        }
        return List.copyOf(facts);
    }

    private ReviewFact mapFact(FactKind kind, JsonNode item, String sourceSystem,
                               String sourceId, String summary, Instant fallbackObservedAt) {
        JsonNode sourceRef = kind == FactKind.KNOWLEDGE ? item.path("knowledgeRef") : item;
        String revision = item.path("revision").isTextual() ? requiredText(item, "revision") : requiredText(sourceRef, "revision");
        Instant observedAt = parseInstant(requiredText(item, "observedAt"));
        String sourceModeText = requiredText(item, "dataMode");
        DataMode mode;
        try { mode = DataMode.valueOf(sourceModeText); }
        catch (IllegalArgumentException failure) { mode = DataMode.UNKNOWN; }
        String datasetId = requiredText(item, "datasetId");
        String sourceStatus = kind == FactKind.KNOWLEDGE ? item.path("publicationState").asText("PUBLISHED") : "CURRENT";
        String hash = item.path("contentHash").isTextual() ? item.path("contentHash").asText()
                : sourceRef.path("contentHash").isTextual() ? sourceRef.path("contentHash").asText() : null;
        Instant eventTime = item.path("eventTime").isTextual()
                ? parseInstant(item.path("eventTime").asText()) : null;
        Map<String, String> attributes = new LinkedHashMap<>();
        if (kind == FactKind.LISTING_MAINTENANCE) {
            JsonNode listingFacts = item.path("facts");
            for (String field : List.of("title", "areaSquareMetres", "totalPrice", "status", "maintenanceGap")) {
                if (listingFacts.has(field) && !listingFacts.get(field).isNull())
                    attributes.put(field, listingFacts.get(field).isValueNode()
                            ? listingFacts.get(field).asText() : listingFacts.get(field).toString());
            }
        } else if (item.path("body").isObject()) {
            JsonNode body = item.path("body");
            for (String field : List.of("problem", "scene", "preconditions", "actions", "observedResult",
                    "applicableScope", "unverifiedItems")) {
                if (body.has(field) && !body.get(field).isNull()) attributes.put(field, body.get(field).toString());
            }
        }
        return new ReviewFact(kind, new SourceReference(sourceSystem, sourceId, revision, observedAt,
                eventTime, mode, datasetId, sourceStatus, hash), summary, null, false, attributes);
    }

    private static DataMode deriveMode(List<ReviewFact> facts) {
        if (facts.isEmpty()) return DataMode.UNKNOWN;
        if (facts.stream().anyMatch(f -> f.source().dataMode() == DataMode.UNKNOWN)) return DataMode.UNKNOWN;
        boolean real = facts.stream().anyMatch(f -> f.source().dataMode() == DataMode.REAL);
        boolean simulated = facts.stream().anyMatch(f -> f.source().dataMode() == DataMode.SIMULATED);
        boolean mixed = facts.stream().anyMatch(f -> f.source().dataMode() == DataMode.MIXED);
        return mixed || real && simulated ? DataMode.MIXED
                : simulated ? DataMode.SIMULATED : real ? DataMode.REAL : DataMode.UNKNOWN;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank())
            throw new ReviewDependencyException("DEPENDENCY_UNAVAILABLE", "Domain source omitted a required field");
        return value;
    }

    private static Instant parseInstant(String value) {
        try { return Instant.parse(value); }
        catch (RuntimeException failure) {
            throw new ReviewDependencyException("DEPENDENCY_UNAVAILABLE", "Domain source returned an invalid timestamp");
        }
    }
}
