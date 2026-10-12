package com.bkanent.listing.rag;

import com.bkanent.common.rag.*;
import com.bkanent.listing.config.ListingRagProperties;
import com.bkanent.listing.converter.ListingConverter;
import com.bkanent.listing.entity.ListingEntity;
import com.bkanent.listing.mapper.ListingMapper;
import com.bkanent.listing.search.ListingKnowledgeDocumentAssembler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** All truth readers/writers serialize on a persistent identity, including deleted IDs. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ListingRagOutbox {
    private final JdbcTemplate jdbc;
    private final ListingMapper listings;
    private final ObjectMapper json;
    private final ListingConverter converter;
    private final ListingKnowledgeDocumentAssembler assembler;
    private final ListingRagProperties properties;
    public ListingRagOutbox(JdbcTemplate jdbc, ListingMapper listings, ObjectMapper json, ListingConverter converter,
                            ListingKnowledgeDocumentAssembler assembler, ListingRagProperties properties) {
        this.jdbc = jdbc; this.listings = listings; this.json = json; this.converter = converter;
        this.assembler = assembler; this.properties = properties;
    }
    public void lock(Long id) {
        if (id == null || id <= 0) { throw new RagException(RagErrorCode.INVALID_ARGUMENT, "listingId"); }
        jdbc.update("INSERT INTO listing_rag_revision (listing_id,revision,tombstone) VALUES (?,0,FALSE) ON DUPLICATE KEY UPDATE listing_id=listing_id", id);
        jdbc.queryForObject("SELECT revision FROM listing_rag_revision WHERE listing_id=? FOR UPDATE", Long.class, id);
    }
    public RagIndexEvent publishCurrent(Long id) {
        lock(id);
        ListingEntity current = listings.selectById(id);
        return publishLocked(id, current);
    }
    public RagIndexEvent publishLocked(Long id, ListingEntity current) {
        long revision = jdbc.queryForObject("SELECT revision FROM listing_rag_revision WHERE listing_id=? FOR UPDATE", Long.class, id) + 1;
        RagIndexOperation operation = current == null ? RagIndexOperation.DELETE : RagIndexOperation.UPSERT;
        RagDocument document = current == null ? null : document(current);
        String hash = RagCanonicalHash.documentHash(operation, document);
        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        RagIndexEvent event = new RagIndexEvent(1, eventId, eventId, ListingRagIdentity.SPACE,
                RagDocumentIds.listing(id), revision, properties.getSchemaVersion(), operation, hash, document, now);
        String payload;
        try { payload = json.writeValueAsString(event); }
        catch (JsonProcessingException e) { throw new RagException(RagErrorCode.INVALID_ARGUMENT, "projection"); }
        jdbc.update("INSERT INTO listing_rag_outbox (event_id,listing_id,revision,content_hash,payload,state,attempts,next_attempt_at,created_at) VALUES (?,?,?,?,?,'PENDING',0,?,?)",
                eventId, id, revision, hash, payload, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("UPDATE listing_rag_revision SET revision=?,tombstone=?,content_hash=?,latest_event_id=?,updated_at=? WHERE listing_id=?",
                revision, current == null, hash, eventId, Timestamp.from(now), id);
        return event;
    }
    private RagDocument document(ListingEntity entity) {
        var dto = converter.toDto(entity);
        Map<String, RagValue> attributes = new LinkedHashMap<>();
        attributes.put("listingId", RagValue.string(String.valueOf(entity.getId())));
        put(attributes, "title", dto.title()); put(attributes, "address", dto.address());
        put(attributes, "region", dto.address()); put(attributes, "layout", dto.layout());
        if (dto.area() != null) { attributes.put("area", RagValue.number(dto.area())); }
        if (dto.totalPrice() != null) { attributes.put("totalPrice", RagValue.number(dto.totalPrice())); }
        put(attributes, "status", dto.status());
        attributes.put("active", RagValue.bool("ON_SALE".equals(dto.status())));
        attributes.put("verified", RagValue.bool("VERIFIED".equals(dto.verificationStatus())));
        attributes.put("ownerDomain", RagValue.string(ListingRagIdentity.SERVICE));
        attributes.put("policyVersion", RagValue.string(properties.getPolicyVersion()));
        List<String> grants = jdbc.query("SELECT actor_user_id FROM listing_rag_grant WHERE resource_id=? AND operation='SEARCH' AND active=TRUE ORDER BY actor_user_id",
                (rs, n) -> String.valueOf(rs.getLong(1)), entity.getId());
        if (!grants.isEmpty()) { attributes.put("allowedActorIds", RagValue.strings(grants)); }
        // Asset and verification changes change the immutable projection, without indexing private certificates/owner names.
        put(attributes, "imageUrls", entity.getImageUrls()); put(attributes, "floorPlanUrls", entity.getFloorPlanUrls());
        put(attributes, "videoUrls", entity.getVideoUrls()); put(attributes, "ocrStatus", entity.getOcrStatus());
        put(attributes, "verificationStatus", entity.getVerificationStatus());
        return new RagDocument("LISTING", String.valueOf(entity.getId()), assembler.assemble(dto).content(), attributes);
    }
    private static void put(Map<String, RagValue> values, String key, String value) {
        if (value != null && !value.isBlank()) { values.put(key, RagValue.string(value)); }
    }
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Map<Long, Revision> revisions(java.util.Collection<Long> ids) {
        if (ids.isEmpty()) { return Map.of(); }
        if (ids.size() > 500) { throw new RagException(RagErrorCode.INVALID_ARGUMENT, "candidateLimit"); }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Map<Long, Revision> revisions = new LinkedHashMap<>();
        jdbc.query("SELECT listing_id,revision,tombstone,content_hash,latest_event_id FROM listing_rag_revision WHERE listing_id IN (" + placeholders + ")",
                rs -> { revisions.put(rs.getLong(1), new Revision(rs.getLong(2), rs.getBoolean(3), rs.getString(4), rs.getString(5))); }, ids.toArray());
        return Map.copyOf(revisions);
    }
    public Revision revision(Long id) {
        List<Revision> rows = jdbc.query("SELECT revision,tombstone,content_hash,latest_event_id FROM listing_rag_revision WHERE listing_id=?",
                (rs, n) -> new Revision(rs.getLong(1), rs.getBoolean(2), rs.getString(3), rs.getString(4)), id);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public record Revision(long revision, boolean tombstone, String hash, String eventId) { }
}
