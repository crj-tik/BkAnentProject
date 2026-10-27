package com.bkanent.listing.source;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.common.listing.ListingBusinessSearchRequest;
import com.bkanent.common.listing.ListingDataMode;
import com.bkanent.common.listing.ListingRef;
import com.bkanent.common.listing.ListingSnapshotBatchReadItem;
import com.bkanent.common.listing.ListingSnapshotCheckItem;
import com.bkanent.common.listing.ListingSnapshotCheckRequest;
import com.bkanent.common.listing.ListingSnapshotBatchReadRequest;
import com.bkanent.common.listing.ListingSourceSnapshot;
import com.bkanent.common.listing.ListingSourceStatus;
import com.bkanent.common.listing.ListingSnapshotFacts;
import com.bkanent.listing.rag.ListingRagIdentity;
import com.bkanent.listing.entity.ListingEntity;
import com.bkanent.listing.mapper.ListingMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only adapter over existing listing_info records. */
@Component
@ConditionalOnProperty(prefix = "listing.source", name = "provider", havingValue = "legacy-local", matchIfMissing = true)
public class LegacyLocalListingSourceAdapter implements ListingSourceAdapter {
    private static final String SOURCE = "legacy-local";
    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Shanghai");

    private final ListingMapper mapper;
    private final ListingSourceProperties properties;

    public LegacyLocalListingSourceAdapter(ListingMapper mapper, ListingSourceProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public ListingSourcePage search(ListingBusinessSearchRequest request, ListingAccessScope scope) {
        String keyword = request.keyword() == null ? null : request.keyword().strip();
        Long afterId = decodeCursor(request.cursor());
        LambdaQueryWrapper<ListingEntity> query = new LambdaQueryWrapper<>();
        query.and(StringUtils.hasText(keyword), nested -> nested
                .like(ListingEntity::getTitle, keyword)
                .or().like(ListingEntity::getAddress, keyword));
        query.gt(afterId != null, ListingEntity::getId, afterId);
        query.eq(!scope.businessWideRead(), ListingEntity::getBrokerId, scope.brokerId());
        query.eq(!scope.businessWideRead(), ListingEntity::getStatus, "ON_SALE");
        query.orderByAsc(ListingEntity::getId).last("limit " + (request.limit() + 1));

        List<ListingEntity> rows = select(query);
        boolean hasMore = rows.size() > request.limit();
        if (hasMore) rows = new ArrayList<>(rows.subList(0, request.limit()));
        List<ListingSourceSnapshot> snapshots = rows.stream()
                .filter(entity -> readable(entity, scope))
                .map(this::snapshot)
                .toList();
        String next = hasMore && !rows.isEmpty() ? encodeCursor(rows.get(rows.size() - 1).getId()) : null;
        return new ListingSourcePage(snapshots, next);
    }

    @Override
    public List<ListingSnapshotBatchReadItem> batchRead(ListingSnapshotBatchReadRequest request,
                                                       ListingAccessScope scope) {
        List<Long> ids = request.refs().stream().map(ref -> localId(ref.listingRef()))
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, ListingEntity> byId = new HashMap<>();
        if (!ids.isEmpty()) {
            selectBatch(ids).stream().filter(entity -> readable(entity, scope))
                    .forEach(entity -> byId.put(entity.getId(), entity));
        }
        List<ListingSnapshotBatchReadItem> result = new ArrayList<>(request.refs().size());
        for (var versionRef : request.refs()) {
            ListingRef ref = versionRef.listingRef();
            Long id = localId(ref);
            ListingEntity entity = id == null ? null : byId.get(id);
            if (entity == null) {
                result.add(new ListingSnapshotBatchReadItem(ref, ListingSourceStatus.NOT_ACCESSIBLE, null));
                continue;
            }
            ListingSourceSnapshot snapshot = snapshot(entity);
            if (versionRef.expectedRevision() != null
                    && !versionRef.expectedRevision().equals(snapshot.revision())) {
                throw ListingBusinessApiException.sourceVersionConflict();
            }
            result.add(new ListingSnapshotBatchReadItem(ref, ListingSourceStatus.CURRENT, snapshot));
        }
        return List.copyOf(result);
    }

    @Override
    public List<ListingSnapshotCheckItem> check(ListingSnapshotCheckRequest request, ListingAccessScope scope) {
        ListingSnapshotBatchReadRequest read = new ListingSnapshotBatchReadRequest(request.contractVersion(),
                request.refs().stream().map(ref -> new com.bkanent.common.listing.ListingSourceVersionRef(
                        ref.listingRef(), null)).toList());
        List<ListingSnapshotBatchReadItem> snapshots = batchRead(read, scope);
        List<ListingSnapshotCheckItem> result = new ArrayList<>(request.refs().size());
        for (int i = 0; i < request.refs().size(); i++) {
            var ref = request.refs().get(i);
            var item = snapshots.get(i);
            ListingSourceStatus status = item.snapshot() == null ? ListingSourceStatus.NOT_ACCESSIBLE
                    : ref.expectedRevision().equals(item.snapshot().revision())
                    ? ListingSourceStatus.CURRENT : ListingSourceStatus.STALE;
            result.add(new ListingSnapshotCheckItem(ref.listingRef(), ref.expectedRevision(), status));
        }
        return List.copyOf(result);
    }

    private List<ListingEntity> select(LambdaQueryWrapper<ListingEntity> query) {
        try {
            return mapper.selectList(query);
        } catch (DataAccessException failure) {
            throw new ListingSourceUnavailableException();
        }
    }

    private List<ListingEntity> selectBatch(List<Long> ids) {
        try {
            return mapper.selectBatchIds(ids);
        } catch (DataAccessException failure) {
            throw new ListingSourceUnavailableException();
        }
    }

    private boolean readable(ListingEntity entity, ListingAccessScope scope) {
        return entity != null && Integer.valueOf(0).equals(entity.getDeleted())
                && (scope.businessWideRead() || scope.brokerId().equals(entity.getBrokerId()))
                && (scope.businessWideRead() || "ON_SALE".equals(entity.getStatus()));
    }

    private ListingSourceSnapshot snapshot(ListingEntity entity) {
        ListingRef ref = new ListingRef(SOURCE, String.valueOf(entity.getId()));
        Map<String, String> attributes = new HashMap<>();
        put(attributes, "layout", entity.getLayout());
        put(attributes, "floorLevel", entity.getFloorLevel());
        put(attributes, "decoration", entity.getDecoration());
        put(attributes, "schoolZone", entity.getSchoolZone());
        put(attributes, "traffic", entity.getTraffic());
        put(attributes, "verificationStatus", entity.getVerificationStatus());
        put(attributes, "status", entity.getStatus());
        List<String> unknown = new ArrayList<>();
        if (entity.getArea() == null) unknown.add("areaSquareMetres");
        if (entity.getTotalPrice() == null) unknown.add("totalPrice");
        for (String field : List.of("layout", "floorLevel", "decoration", "schoolZone", "traffic",
                "verificationStatus", "status")) {
            if (!attributes.containsKey(field)) unknown.add(field);
        }
        ListingSnapshotFacts facts = new ListingSnapshotFacts(entity.getTitle(),
                ListingSourceSnapshotFactory.decimal(entity.getArea()),
                ListingSourceSnapshotFactory.yuan(entity.getTotalPrice() == null ? null
                        : entity.getTotalPrice().multiply(new java.math.BigDecimal("10000"))),
                attributes, unknown);
        Instant observedAt = entity.getUpdatedAt() != null ? entity.getUpdatedAt().atZone(DATABASE_ZONE).toInstant()
                : entity.getCreatedAt() != null ? entity.getCreatedAt().atZone(DATABASE_ZONE).toInstant()
                : Instant.now();
        return ListingSourceSnapshotFactory.create(ref, null, observedAt, ListingDataMode.UNKNOWN,
                properties.getDatasetId(), facts);
    }

    private static void put(Map<String, String> target, String key, String value) {
        if (StringUtils.hasText(value)) target.put(key, value.strip());
    }

    private static Long localId(ListingRef ref) {
        if (ref == null || !SOURCE.equals(ref.sourceSystem()) || !ref.sourceId().matches("[1-9][0-9]{0,18}")) return null;
        try { return Long.valueOf(ref.sourceId()); }
        catch (NumberFormatException invalid) { return null; }
    }

    private static String encodeCursor(Long id) {
        return SOURCE + ":" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.valueOf(id).getBytes(StandardCharsets.UTF_8));
    }

    private static Long decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            if (!cursor.startsWith(SOURCE + ":")) throw new IllegalArgumentException();
            String raw = new String(Base64.getUrlDecoder().decode(cursor.substring(SOURCE.length() + 1)), StandardCharsets.UTF_8);
            if (!raw.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
            return Long.valueOf(raw);
        } catch (IllegalArgumentException invalid) {
            throw ListingBusinessApiException.invalidArgument();
        }
    }
}
