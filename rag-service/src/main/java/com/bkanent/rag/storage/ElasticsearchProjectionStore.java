package com.bkanent.rag.storage;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.bkanent.common.rag.*;
import com.bkanent.rag.index.*;
import com.bkanent.rag.retrieval.RecallHit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.util.*;

/** Migrated ElasticsearchOperations path, only complete domain projections; no SQL/RPC fallback. */
@Component
public class ElasticsearchProjectionStore implements IndexSink {
    private static final Logger log = LoggerFactory.getLogger(ElasticsearchProjectionStore.class);
    public record EsProjection(@Id String documentId, String content, Map<String, Object> metadata) { }
    private final RagSpaceRegistry registry;
    private final ElasticsearchOperations operations;
    private final ObjectMapper mapper;
    private final ElasticsearchDeadlineReader reader;

    public ElasticsearchProjectionStore(RagSpaceRegistry registry, ElasticsearchOperations operations, ObjectMapper mapper,
                                         ElasticsearchDeadlineReader reader) {
        this.registry = registry;
        this.operations = operations;
        this.mapper = mapper;
        this.reader = reader;
    }
    @Override public Backend backend() { return Backend.KEYWORD; }
    @Override public void upsert(IndexDocument document) {
        RagSpaceDefinition space = registry.requireReady(document.spaceId());
        Map<String, Object> metadata = ProjectionCodec.metadata(document, space);
        try {
            // Explicit JSON source avoids implicit _class/dynamic mappings and entity leakage.
            IndexQuery query = IndexQuery.builder().withId(document.documentId()).withSource(mapper.writeValueAsString(
                    new EsProjection(document.documentId(), document.document().content(), metadata))).build();
            operations.index(query, target(space));
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "document");
        }
    }
    @Override public void delete(IndexDocument tombstone) {
        operations.delete(tombstone.documentId(), target(registry.requireReady(tombstone.spaceId())));
    }
    @Override public Optional<StoredRevision> inspect(String spaceId, String documentId) {
        EsProjection stored = operations.get(documentId, EsProjection.class, target(registry.require(spaceId)));
        if (stored == null) return Optional.empty();
        return Optional.of(new StoredRevision(((Number) stored.metadata().get("projectionVersion")).longValue(),
                String.valueOf(stored.metadata().get("contentHash")), false));
    }

    public List<RecallHit> search(RagSpaceDefinition space, String text, List<RagFilter> filters,
                                  int limit, Duration remaining) {
        List<Query> conditions = filters.stream().filter(f -> f.op() != RagFilterOperator.CONTAINS_CI)
                .map(this::condition).toList();
        NativeQuery query = NativeQuery.builder().withQuery(q -> q.bool(b -> b
                        .must(m -> m.match(match -> match.field("content").query(text))).filter(conditions)))
                .withSort(s -> s.score(score -> score.order(SortOrder.Desc)))
                .withSort(s -> s.field(field -> field.field("documentId").order(SortOrder.Asc)))
                .withPageable(PageRequest.of(0, limit)).withTimeout(remaining).withTrackTotalHits(false).build();
        try {
            // Reuse the same typed query and protected ES RestClient; do not construct a second transport.
            String typed = co.elastic.clients.json.JsonpUtils.toJsonString(query.getQuery(), new co.elastic.clients.json.jackson.JacksonJsonpMapper(mapper));
            var body = mapper.createObjectNode();
            body.set("query", mapper.readTree(typed));
            body.put("size", limit); body.put("track_total_hits", false);
            body.put("timeout", Math.max(1, remaining.toMillis()) + "ms");
            body.set("sort", mapper.readTree("[{\"_score\":\"desc\"},{\"documentId\":\"asc\"}]"));
            org.elasticsearch.client.Request request = new org.elasticsearch.client.Request("POST", "/" + space.elasticsearchIndex() + "/_search");
            request.setJsonEntity(mapper.writeValueAsString(body));
            int maxBytes = (int) Math.min(16 * 1024 * 1024L, Math.max(65536L, space.limits().maxRecallUtf8Bytes() * 4));
            org.elasticsearch.client.Response response = reader.read(request, remaining, maxBytes);
            var root = mapper.readTree(org.apache.http.util.EntityUtils.toString(response.getEntity(), java.nio.charset.StandardCharsets.UTF_8));
            if (root.path("timed_out").asBoolean(false)) throw new RagException(RagErrorCode.TIMEOUT);
            if (root.path("_shards").path("failed").asInt(0) != 0) unavailable();
            var hits = root.path("hits").path("hits");
            if (!hits.isArray() || hits.size() > limit) unavailable();
            List<RecallHit> result = new ArrayList<>();
            for (var hit : hits) {
                EsProjection p = mapper.treeToValue(hit.path("_source"), EsProjection.class);
                if (!hit.path("_score").isNumber() || !Double.isFinite(hit.path("_score").asDouble())) unavailable();
                RecallHit decoded = ProjectionCodec.hit(p.documentId(), p.content(), p.metadata(), hit.path("_score").asDouble(), RagRecallMode.KEYWORD, space);
                if (ProjectionFilters.matches(decoded.candidate().metadata(), filters)) result.add(decoded);
            }
            return List.copyOf(result);
        }
        catch (java.io.IOException failure) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }
    }

    private Query condition(RagFilter filter) {
        String field = "metadata." + filter.field();
        Object value = ProjectionFilters.raw(filter.value());
        return switch (filter.op()) {
            case EQ -> Query.of(q -> q.term(t -> t.field(field).value(fieldValue(value))));
            case IN -> Query.of(q -> q.terms(t -> t.field(field).terms(v -> v.value(
                    ((List<?>) value).stream().map(this::fieldValue).toList()))));
            case GTE, LTE -> filter.value().type() == RagValueType.INSTANT
                    ? Query.of(q -> q.range(r -> r.date(d -> {
                        d.field(field); return filter.op() == RagFilterOperator.GTE ? d.gte((String) value) : d.lte((String) value);
                    })))
                    : Query.of(q -> q.range(r -> r.number(n -> {
                        n.field(field); return filter.op() == RagFilterOperator.GTE ? n.gte((Double) value) : n.lte((Double) value);
                    })));
            case CONTAINS_CI -> throw new RagException(RagErrorCode.INVALID_ARGUMENT, filter.field());
        };
    }
    private FieldValue fieldValue(Object value) {
        if (value instanceof Boolean b) return FieldValue.of(b);
        if (value instanceof Number n) return FieldValue.of(n.doubleValue());
        return FieldValue.of((String) value);
    }

    public void provision(RagSpaceDefinition space) {
        var index = operations.indexOps(target(space));
        var mapping = org.springframework.data.elasticsearch.core.document.Document.from(mapping(space));
        if (!index.exists() && !index.create(Map.of(), mapping)) unavailable();
        inspectSchema(space);
    }
    public void inspectSchema(RagSpaceDefinition space) {
        Duration budget = Duration.ofMillis(Math.min(5000, space.limits().recallTimeoutMillis()));
        long deadline = com.bkanent.rag.security.http.RagHttpDeadline.capture(budget);
        SchemaOperation operation = SchemaOperation.SCHEMA_HEAD;
        long started = System.nanoTime();
        int httpStatus = 0;
        RagErrorCode errorCode = null;
        try (var ignored = com.bkanent.rag.security.http.RagHttpDeadline.until(deadline)) {
            String path = "/" + space.elasticsearchIndex();
            var exists = reader.read(new org.elasticsearch.client.Request("HEAD", path), budget, 65536);
            httpStatus = exists.getStatusLine().getStatusCode();
            if (httpStatus == 404) throw new RagException(RagErrorCode.SPACE_NOT_READY);
            if (httpStatus != 200) unavailable();
            logSchemaDiagnostic(operation, httpStatus, null, started);
            operation = SchemaOperation.SCHEMA_MAPPING;
            started = System.nanoTime();
            httpStatus = 0;
            com.bkanent.rag.security.http.RagHttpDeadline.remainingNanos(deadline);
            var response = reader.read(new org.elasticsearch.client.Request("GET", path + "/_mapping"), budget, 1024 * 1024);
            httpStatus = response.getStatusLine().getStatusCode();
            var root = mapper.readTree(org.apache.http.util.EntityUtils.toString(response.getEntity(), java.nio.charset.StandardCharsets.UTF_8));
            Map<?, ?> actual = mapper.convertValue(root.path(space.elasticsearchIndex()).path("mappings"), Map.class);
            if (!containsMapping(actual, mapping(space))) throw new RagException(RagErrorCode.SPACE_NOT_READY, "keywordMapping");
            com.bkanent.rag.security.http.RagHttpDeadline.remainingNanos(deadline);
        } catch (RagException failure) {
            errorCode = failure.code();
            throw failure;
        } catch (java.io.IOException failure) {
            errorCode = RagErrorCode.DEPENDENCY_UNAVAILABLE;
            throw new RagException(errorCode);
        } catch (RuntimeException failure) {
            errorCode = RagErrorCode.DEPENDENCY_UNAVAILABLE;
            throw failure;
        } finally {
            logSchemaDiagnostic(operation, httpStatus, errorCode, started);
        }
    }

    private enum SchemaOperation { SCHEMA_HEAD, SCHEMA_MAPPING }

    private static void logSchemaDiagnostic(SchemaOperation operation, int status, RagErrorCode code, long started) {
        // Only a returned protected-reader response supplies a status; timeout proves neither headers nor cancellation.
        String httpStatus = status > 0 && code != RagErrorCode.TIMEOUT ? Integer.toString(status) : "UNKNOWN";
        long elapsedMillis = Math.max(0, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        log.info("operation={} httpStatus={} errorCode={} elapsedMillis={}",
                operation.name(), httpStatus, code == null ? "NONE" : code.name(), elapsedMillis);
    }

    public Map<String, Object> mapping(RagSpaceDefinition space) {
        Map<String, Object> metadata = new HashMap<>();
        space.fields().forEach((name, field) -> metadata.put(name, field.type() == RagValueType.INSTANT
                ? Map.of("type", "date_nanos", "format", "strict_date_optional_time_nanos")
                : Map.of("type", switch (field.type()) {
                    case STRING, STRING_LIST -> "keyword";
                    case NUMBER, NUMBER_LIST -> "double";
                    case BOOLEAN -> "boolean";
                    case INSTANT -> throw new IllegalStateException();
                })));
        for (String field : List.of("documentId", "schemaVersion", "contentHash",
                space.vectorSchema().sourceTypeField(), space.vectorSchema().sourceIdField())) metadata.put(field, Map.of("type", "keyword"));
        metadata.put("projectionVersion", Map.of("type", "long"));
        metadata.put("tombstone", Map.of("type", "boolean"));
        return Map.of("dynamic", "strict", "properties", Map.of("documentId", Map.of("type", "keyword"),
                "content", Map.of("type", "text"), "metadata", Map.of("type", "object", "dynamic", "strict", "properties", metadata)));
    }
    private boolean containsMapping(Object actual, Object expected) {
        if (expected instanceof Map<?, ?> map) {
            if (!(actual instanceof Map<?, ?> actualMap)) return false;
            return map.entrySet().stream().allMatch(e ->
                    (e.getKey().equals("type") && e.getValue().equals("object")
                            && !actualMap.containsKey("type")
                            && actualMap.get("properties") instanceof Map<?, ?> properties && !properties.isEmpty())
                            || containsMapping(actualMap.get(e.getKey()), e.getValue()));
        }
        return Objects.equals(actual, expected);
    }
    private static IndexCoordinates target(RagSpaceDefinition space) { return IndexCoordinates.of(space.elasticsearchIndex()); }
    private static void unavailable() { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }
}
