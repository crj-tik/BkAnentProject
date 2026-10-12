package com.bkanent.listing.rag;

import com.bkanent.common.rag.*;
import com.bkanent.listing.config.ListingRagProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Service
public class ListingRagGrantService {
    private final JdbcTemplate jdbc;
    private final ListingRagIdentity identity;
    private final ListingRagOutbox outbox;
    private final ListingRagProperties properties;
    public ListingRagGrantService(JdbcTemplate jdbc, ListingRagIdentity identity, ListingRagOutbox outbox, ListingRagProperties properties) {
        this.jdbc = jdbc; this.identity = identity; this.outbox = outbox; this.properties = properties;
    }
    public List<String> allowed(Long userId) {
        // Fetch one sentinel beyond the hard maximum: never silently truncate authority.
        List<String> ids = jdbc.query("SELECT resource_id FROM listing_rag_grant WHERE actor_user_id=? AND operation='SEARCH' AND active=TRUE ORDER BY resource_id LIMIT 5001",
                (rs, n) -> String.valueOf(rs.getLong(1)), userId);
        if (ids.size() > 5000) { throw new RagException(RagErrorCode.RESOURCE_SCOPE_TOO_LARGE); }
        if (ids.isEmpty()) { throw new RagException(RagErrorCode.FORBIDDEN); }
        return ids;
    }
    public RagAuthorizationScope scope(AuthRagPrincipal principal, RagSearchRequest request) {
        List<String> ids = allowed(principal.userId());
        long now = Instant.now().getEpochSecond();
        long expiry = Math.min(now + 60, principal.expiresAtEpochSecond());
        return RagScopeSigner.sign(new RagAuthorizationScope(1, properties.getKeyId(), ListingRagIdentity.SERVICE,
                ListingRagIdentity.SERVICE, ListingRagIdentity.SPACE, String.valueOf(principal.userId()), RagOperation.SEARCH,
                RagCanonicalHash.requestHash(request), properties.getPolicyVersion(), now, expiry, null, ids, true, null),
                ListingRagIdentity.bytes(properties.getSecret()));
    }
    @Transactional(rollbackFor = Exception.class)
    public void manage(Long userId, Long listingId, boolean active, RagCredentialContext credentials) {
        AuthRagPrincipal admin = identity.requireAdmin(credentials);
        if (userId == null || userId <= 0) { throw new RagException(RagErrorCode.INVALID_ARGUMENT, "actorUserId"); }
        outbox.lock(listingId);
        Long exists = jdbc.queryForObject("SELECT COUNT(*) FROM listing_info WHERE id=? AND deleted=0", Long.class, listingId);
        if (exists == null || exists == 0) { throw new RagException(RagErrorCode.NOT_FOUND); }
        // No grant inferred from broker, no role requested by caller, no automatic administrator grant.
        jdbc.update("INSERT INTO listing_rag_grant (actor_user_id,resource_id,operation,active,version,managed_by,updated_at) VALUES (?,?,'SEARCH',?,1,?,?) ON DUPLICATE KEY UPDATE active=?,version=version+1,managed_by=?,updated_at=?",
                userId, listingId, active, admin.userId(), Timestamp.from(Instant.now()), active, admin.userId(), Timestamp.from(Instant.now()));
        outbox.publishCurrent(listingId);
    }
}
