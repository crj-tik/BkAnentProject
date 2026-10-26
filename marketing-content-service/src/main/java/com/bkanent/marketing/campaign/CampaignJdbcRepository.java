package com.bkanent.marketing.campaign;

import com.bkanent.common.marketing.campaign.CampaignDataMode;
import com.bkanent.common.marketing.campaign.CampaignAttemptReceipt;
import com.bkanent.common.marketing.campaign.CampaignEffectResponse;
import com.bkanent.common.marketing.campaign.CampaignReceiptResponse;
import com.bkanent.common.marketing.campaign.CampaignErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.sql.Statement;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import java.util.Arrays;

@Repository
public class CampaignJdbcRepository {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CampaignJdbcRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> findAssets(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        return jdbc.queryForList("SELECT id,asset_type,asset_name,asset_url,platform_scope,tag_names,status,revision_no " +
                "FROM marketing_campaign_brand_asset WHERE deleted=0 AND status='ACTIVE' AND id IN (" + placeholders + ") ORDER BY id", ids.toArray());
    }

    public long createAsset(CampaignBrandAssetRequest request) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO marketing_campaign_brand_asset(asset_type,asset_name,asset_url,platform_scope,tag_names,status,revision_no,remark) VALUES(?,?,?,?,?,?,1,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, request.assetType()); statement.setString(2, request.assetName());
            statement.setString(3, request.assetUrl()); statement.setString(4, request.platformScope());
            statement.setString(5, String.join(",", request.tags())); statement.setString(6, request.status());
            statement.setString(7, request.remark());
            return statement;
        }, key);
        Number id = key.getKey();
        if (id == null) throw new IllegalStateException("campaign asset insert did not return an ID");
        return id.longValue();
    }

    public List<CampaignBrandAssetResponse> listAssets(String assetType, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT id,asset_type,asset_name,asset_url,platform_scope,tag_names,status,remark,revision_no FROM marketing_campaign_brand_asset WHERE deleted=0");
        List<Object> args = new ArrayList<>();
        if (assetType != null && !assetType.isBlank()) { sql.append(" AND asset_type=?"); args.add(assetType.toUpperCase()); }
        if (keyword != null && !keyword.isBlank()) { sql.append(" AND (asset_name LIKE ? OR tag_names LIKE ?)"); args.add("%" + keyword + "%"); args.add("%" + keyword + "%"); }
        sql.append(" ORDER BY id DESC");
        return jdbc.query(sql.toString(), (rs, row) -> {
            String rawTags = rs.getString("tag_names");
            List<String> tags = rawTags == null || rawTags.isBlank() ? List.of() : Arrays.stream(rawTags.split(",")).filter(s -> !s.isBlank()).toList();
            return new CampaignBrandAssetResponse(rs.getLong("id"), rs.getString("asset_type"), rs.getString("asset_name"),
                    rs.getString("asset_url"), rs.getString("platform_scope"), tags, rs.getString("status"),
                    rs.getString("remark"), rs.getInt("revision_no"));
        }, args.toArray());
    }

    public CampaignAccountData findAccount(long accountId) {
        List<CampaignAccountData> rows = jdbc.query("SELECT id,platform,external_account_ref,permission_code,revision_no,data_mode,active " +
                        "FROM marketing_campaign_account WHERE id=?", (rs, row) -> account(rs), accountId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int contentRevision(long contentId, String contentHash, String snapshotJson, CampaignDataMode mode) {
        List<Integer> existing = jdbc.query("SELECT revision_no FROM marketing_campaign_content_revision WHERE content_id=? AND content_hash=?",
                (rs, row) -> rs.getInt(1), contentId, contentHash);
        if (!existing.isEmpty()) return existing.get(0);
        Integer max = jdbc.queryForObject("SELECT MAX(revision_no) FROM marketing_campaign_content_revision WHERE content_id=?", Integer.class, contentId);
        int revision = max == null ? 1 : max + 1;
        try {
            jdbc.update("INSERT INTO marketing_campaign_content_revision(content_id,revision_no,content_hash,snapshot_json,data_mode) VALUES(?,?,?,?,?)",
                    contentId, revision, contentHash, snapshotJson, mode.name());
        } catch (DuplicateKeyException race) {
            Integer raced = jdbc.queryForObject("SELECT revision_no FROM marketing_campaign_content_revision WHERE content_id=? AND content_hash=?",
                    Integer.class, contentId, contentHash);
            if (raced == null) throw race;
            return raced;
        }
        return revision;
    }

    public void insertPlan(CampaignPlanData plan, List<CampaignTargetData> targets) {
        jdbc.update("INSERT INTO marketing_campaign_plan(plan_id,owner_user_id,content_id,content_revision,content_hash,asset_hash,targets_hash,rule_version,snapshot_hash,plan_version,status,data_mode,content_snapshot_json,assets_snapshot_json) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,'PREPARED',?,?,?)",
                plan.planId(), plan.ownerUserId(), plan.contentId(), plan.contentRevision(), plan.contentHash(), plan.assetHash(),
                plan.targetsHash(), plan.ruleVersion(), plan.snapshotHash(), plan.planVersion(), plan.dataMode().name(),
                plan.contentSnapshotJson(), plan.assetsSnapshotJson());
        for (CampaignTargetData target : targets) {
            jdbc.update("INSERT INTO marketing_campaign_plan_target(plan_id,target_index,platform,account_id,account_revision,external_account_ref,permission_code,arguments_hash,arguments_json,data_mode) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?)",
                    plan.planId(), target.targetIndex(), target.platform(), target.accountId(), target.accountRevision(),
                    target.externalAccountRef(), target.permissionCode(), target.argumentsHash(), json(target.arguments()), plan.dataMode().name());
        }
    }

    public CampaignPlanData findPlan(String planId) {
        List<CampaignPlanData> rows = jdbc.query("SELECT plan_id,owner_user_id,content_id,content_revision,content_hash,asset_hash,targets_hash,rule_version,snapshot_hash,plan_version,status,data_mode,created_at,content_snapshot_json,assets_snapshot_json " +
                        "FROM marketing_campaign_plan WHERE plan_id=?", (rs, row) -> new CampaignPlanData(
                        rs.getString("plan_id"), rs.getLong("owner_user_id"), rs.getLong("content_id"), rs.getInt("content_revision"),
                        rs.getString("content_hash"), rs.getString("asset_hash"), rs.getString("targets_hash"), rs.getString("rule_version"),
                        rs.getString("snapshot_hash"), rs.getInt("plan_version"), rs.getString("status"), mode(rs.getString("data_mode")),
                        localDateTime(rs.getTimestamp("created_at")),
                        rs.getString("content_snapshot_json"), rs.getString("assets_snapshot_json")), planId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean isPlanExpired(String planId, long ttlSeconds) {
        Boolean expired = jdbc.queryForObject("SELECT created_at<TIMESTAMPADD(SECOND,-?,CURRENT_TIMESTAMP) FROM marketing_campaign_plan WHERE plan_id=?",
                Boolean.class, ttlSeconds, planId);
        return Boolean.TRUE.equals(expired);
    }

    public List<CampaignTargetData> findTargets(String planId) {
        return jdbc.query("SELECT target_index,platform,account_id,account_revision,external_account_ref,permission_code,arguments_hash,arguments_json " +
                        "FROM marketing_campaign_plan_target WHERE plan_id=? ORDER BY target_index", (rs, row) -> new CampaignTargetData(
                        rs.getInt("target_index"), rs.getString("platform"), rs.getLong("account_id"), rs.getInt("account_revision"),
                        rs.getString("external_account_ref"), rs.getString("permission_code"), rs.getString("arguments_hash"),
                        readStringMap(rs.getString("arguments_json"))), planId);
    }

    public CampaignReceiptResponse findReceipt(String planId, String requestId) {
        List<CampaignReceiptResponse> rows = jdbc.query("SELECT receipt_id,plan_id,request_id,status,data_mode,created_at FROM marketing_campaign_publish_receipt WHERE plan_id=? AND request_id=?",
                (rs, row) -> new CampaignReceiptResponse(rs.getString("receipt_id"), rs.getString("plan_id"), rs.getString("request_id"),
                        rs.getString("status"), mode(rs.getString("data_mode")), localDateTime(rs.getTimestamp("created_at")),
                        receiptAttempts(rs.getString("receipt_id"))), planId, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public CampaignReceiptResponse findReceiptByOwnerRequest(long ownerId, String requestId) {
        List<String> ids = jdbc.query("SELECT receipt_id FROM marketing_campaign_publish_request WHERE owner_user_id=? AND request_id=?",
                (rs, row) -> rs.getString(1), ownerId, requestId);
        if (ids.isEmpty()) return null;
        List<CampaignReceiptResponse> receipt = jdbc.query("SELECT receipt_id,plan_id,request_id,status,data_mode,created_at FROM marketing_campaign_publish_receipt WHERE receipt_id=?",
                (rs, row) -> new CampaignReceiptResponse(rs.getString("receipt_id"), rs.getString("plan_id"), rs.getString("request_id"),
                        rs.getString("status"), mode(rs.getString("data_mode")), localDateTime(rs.getTimestamp("created_at")),
                        receiptAttempts(rs.getString("receipt_id"))), ids.get(0));
        return receipt.isEmpty() ? null : receipt.get(0);
    }

    public CampaignReceiptResponse findReceiptById(String receiptId) {
        List<CampaignReceiptResponse> rows = jdbc.query("SELECT receipt_id,plan_id,request_id,status,data_mode,created_at FROM marketing_campaign_publish_receipt WHERE receipt_id=?",
                (rs, row) -> new CampaignReceiptResponse(rs.getString("receipt_id"), rs.getString("plan_id"), rs.getString("request_id"),
                        rs.getString("status"), mode(rs.getString("data_mode")), localDateTime(rs.getTimestamp("created_at")),
                        receiptAttempts(rs.getString("receipt_id"))), receiptId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public String findRequestBodyHash(long ownerId, String requestId) {
        List<String> hashes = jdbc.query("SELECT body_hash FROM marketing_campaign_publish_request WHERE owner_user_id=? AND request_id=?",
                (rs, row) -> rs.getString(1), ownerId, requestId);
        return hashes.isEmpty() ? null : hashes.get(0);
    }

    public String currentLeaseToken(String attemptId) {
        List<String> tokens = jdbc.query("SELECT lease_token FROM marketing_campaign_outbox WHERE attempt_id=?",
                (rs, row) -> rs.getString(1), attemptId);
        return tokens.isEmpty() ? null : tokens.get(0);
    }

    @Transactional
    public CampaignEffectResponse saveEffect(CampaignAttemptData attempt, long contentId, Long listingId,
                                               CampaignEffectObservation observation) {
        if (observation == null || observation.sourceEventId() == null
                || !observation.sourceEventId().matches("[A-Za-z0-9_.:-]{1,128}")
                || observation.sourceSystem() == null
                || !observation.sourceSystem().matches("[A-Za-z0-9_.:-]{1,64}") || observation.sourceVersion() < 1
                || observation.windowStart() == null || observation.windowEnd() == null
                || observation.observedAt() == null || !observation.windowEnd().isAfter(observation.windowStart())) {
            throw CampaignDomainException.invalid("effect source identity, version, and observation window are required");
        }
        if (negative(observation.exposureCount()) || negative(observation.clickCount())
                || negative(observation.privateMessageCount()) || negative(observation.leadCount())
                || negative(observation.attributableRevenue()) || negative(observation.attributableCost())) {
            throw CampaignDomainException.invalid("effect counts and attributable amounts must be non-negative");
        }
        String payloadHash = CampaignHash.sha256(CampaignHash.canonicalJson(observation, objectMapper));
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT id,attempt_id,source_version,payload_hash FROM marketing_campaign_effect_observation WHERE source_system=? AND source_event_id=?",
                observation.sourceSystem(), observation.sourceEventId());
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO marketing_campaign_effect_observation(attempt_id,content_id,listing_id,platform,source_system,source_event_id,source_version,payload_hash,window_start,window_end,observed_at,data_mode,exposure_count,click_count,private_message_count,lead_count,attributable_revenue,attributable_cost,currency,roi_definition_version,unavailable_reason) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    attempt.attemptId(), contentId, listingId, attempt.platform(), observation.sourceSystem(), observation.sourceEventId(),
                    observation.sourceVersion(), payloadHash, Timestamp.from(observation.windowStart()), Timestamp.from(observation.windowEnd()),
                    Timestamp.from(observation.observedAt()), attempt.dataMode().name(), observation.exposureCount(), observation.clickCount(),
                    observation.privateMessageCount(), observation.leadCount(), observation.attributableRevenue(), observation.attributableCost(),
                    observation.currency(), observation.roiDefinitionVersion(), observation.unavailableReason());
            long id = jdbc.queryForObject("SELECT id FROM marketing_campaign_effect_observation WHERE source_system=? AND source_event_id=?", Long.class,
                    observation.sourceSystem(), observation.sourceEventId());
            jdbc.update("INSERT INTO marketing_campaign_effect_revision(observation_id,source_version,payload_hash,snapshot_json) VALUES(?,?,?,?)",
                    id, observation.sourceVersion(), payloadHash, json(observation));
        } else {
            Map<String, Object> row = existing.get(0);
            if (!attempt.attemptId().equals(String.valueOf(row.get("attempt_id")))) {
                throw CampaignDomainException.conflict(CampaignErrorCode.SOURCE_CONFLICT, "effect event identity is already bound to another attempt");
            }
            long currentVersion = ((Number) row.get("source_version")).longValue();
            String currentHash = String.valueOf(row.get("payload_hash"));
            if (observation.sourceVersion() == currentVersion && !payloadHash.equals(currentHash)) {
                throw CampaignDomainException.conflict(CampaignErrorCode.SOURCE_CONFLICT, "effect source reused a version with different content");
            }
            if (observation.sourceVersion() > currentVersion) {
                long id = ((Number) row.get("id")).longValue();
                jdbc.update("UPDATE marketing_campaign_effect_observation SET listing_id=?,source_version=?,payload_hash=?,window_start=?,window_end=?,observed_at=?,data_mode=?,exposure_count=?,click_count=?,private_message_count=?,lead_count=?,attributable_revenue=?,attributable_cost=?,currency=?,roi_definition_version=?,unavailable_reason=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND source_version<?",
                        listingId, observation.sourceVersion(), payloadHash, Timestamp.from(observation.windowStart()), Timestamp.from(observation.windowEnd()),
                        Timestamp.from(observation.observedAt()), attempt.dataMode().name(), observation.exposureCount(), observation.clickCount(),
                        observation.privateMessageCount(), observation.leadCount(), observation.attributableRevenue(), observation.attributableCost(),
                        observation.currency(), observation.roiDefinitionVersion(), observation.unavailableReason(), id, observation.sourceVersion());
                jdbc.update("INSERT INTO marketing_campaign_effect_revision(observation_id,source_version,payload_hash,snapshot_json) VALUES(?,?,?,?)",
                        id, observation.sourceVersion(), payloadHash, json(observation));
            }
        }
        return findEffect(attempt.attemptId());
    }

    public CampaignEffectResponse findEffect(String attemptId) {
        List<CampaignEffectResponse> rows = jdbc.query("SELECT attempt_id,legacy_publish_record_id,content_id,listing_id,platform,exposure_count,click_count,private_message_count,lead_count,attributable_revenue,attributable_cost,currency,roi_definition_version,source_system,source_event_id,source_version,window_start,window_end,observed_at,data_mode,unavailable_reason FROM marketing_campaign_effect_observation WHERE attempt_id=? ORDER BY source_version DESC,observed_at DESC LIMIT 1",
                (rs, row) -> effectResponse(rs), attemptId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<CampaignEffectResponse> findAllEffects() {
        return jdbc.query("SELECT attempt_id,legacy_publish_record_id,content_id,listing_id,platform,exposure_count,click_count,private_message_count,lead_count,attributable_revenue,attributable_cost,currency,roi_definition_version,source_system,source_event_id,source_version,window_start,window_end,observed_at,data_mode,unavailable_reason FROM marketing_campaign_effect_observation ORDER BY platform,observed_at",
                (rs, row) -> effectResponse(rs));
    }

    public List<CampaignEffectResponse> findEffectsByListingId(long listingId) {
        return jdbc.query("SELECT attempt_id,legacy_publish_record_id,content_id,listing_id,platform,exposure_count,click_count,private_message_count,lead_count,attributable_revenue,attributable_cost,currency,roi_definition_version,source_system,source_event_id,source_version,window_start,window_end,observed_at,data_mode,unavailable_reason FROM marketing_campaign_effect_observation WHERE listing_id=? ORDER BY observed_at DESC",
                (rs, row) -> effectResponse(rs), listingId);
    }

    public String findAttemptIdByLegacyPublishId(long legacyPublishId) {
        List<String> ids = jdbc.query("SELECT target_id FROM marketing_campaign_legacy_map WHERE source_system='promotion-service' AND entity_type='PUBLISH_RECORD' AND legacy_id=? ORDER BY migrated_at DESC LIMIT 1",
                (rs, row) -> rs.getString(1), Long.toString(legacyPublishId));
        return ids.isEmpty() ? null : ids.get(0);
    }

    @Transactional
    public void accept(String planId, long ownerId, String requestId, String bodyHash,
                       String receiptId, CampaignDataMode mode, List<CampaignTargetData> targets) {
        jdbc.update("INSERT INTO marketing_campaign_publish_request(plan_id,owner_user_id,request_id,body_hash,receipt_id) VALUES(?,?,?,?,?)",
                planId, ownerId, requestId, bodyHash, receiptId);
        jdbc.update("INSERT INTO marketing_campaign_publish_receipt(receipt_id,plan_id,request_id,status,data_mode) VALUES(?,?,?,'ACCEPTED',?)",
                receiptId, planId, requestId, mode.name());
        for (CampaignTargetData target : targets) {
            String attemptId = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO marketing_campaign_publish_attempt(attempt_id,receipt_id,plan_id,target_index,platform,account_id,status,data_mode,message) " +
                            "VALUES(?,?,?,?,?,?,'ACCEPTED',?,?)",
                    attemptId, receiptId, planId, target.targetIndex(), target.platform(), target.accountId(), mode.name(), "Accepted locally; awaiting provider dispatch");
            jdbc.update("INSERT INTO marketing_campaign_outbox(event_id,attempt_id,state) VALUES(?,?,'PENDING')", UUID.randomUUID().toString(), attemptId);
            jdbc.update("INSERT INTO marketing_campaign_event(event_id,attempt_id,event_type,data_mode,payload_json) VALUES(?,?,'ACCEPTED',?,?)",
                    UUID.randomUUID().toString(), attemptId, mode.name(), "{}");
        }
        jdbc.update("UPDATE marketing_campaign_plan SET status='CONFIRMED',updated_at=CURRENT_TIMESTAMP WHERE plan_id=? AND status='PREPARED'", planId);
    }

    public List<CampaignAttemptData> findAttempts(String receiptId) {
        return jdbc.query("SELECT attempt_id,receipt_id,plan_id,target_index,platform,account_id,status,data_mode,external_publish_id,message,attempt_version,updated_at " +
                        "FROM marketing_campaign_publish_attempt WHERE receipt_id=? ORDER BY target_index", (rs, row) -> attempt(rs), receiptId);
    }

    public List<CampaignAttemptData> findAttemptsForOwner(long ownerUserId, String platformFilter) {
        if (platformFilter == null || platformFilter.isBlank()) {
            return jdbc.query("SELECT a.attempt_id,a.receipt_id,a.plan_id,a.target_index,a.platform,a.account_id,a.status,a.data_mode,a.external_publish_id,a.message,a.attempt_version,a.updated_at " +
                    "FROM marketing_campaign_publish_attempt a JOIN marketing_campaign_plan p ON p.plan_id=a.plan_id WHERE p.owner_user_id=? ORDER BY p.created_at,a.target_index",
                    (rs, row) -> attempt(rs), ownerUserId);
        }
        return jdbc.query("SELECT a.attempt_id,a.receipt_id,a.plan_id,a.target_index,a.platform,a.account_id,a.status,a.data_mode,a.external_publish_id,a.message,a.attempt_version,a.updated_at " +
                        "FROM marketing_campaign_publish_attempt a JOIN marketing_campaign_plan p ON p.plan_id=a.plan_id WHERE p.owner_user_id=? AND UPPER(a.platform)=UPPER(?) ORDER BY p.created_at,a.target_index",
                (rs, row) -> attempt(rs), ownerUserId, platformFilter);
    }

    private List<CampaignAttemptReceipt> receiptAttempts(String receiptId) {
        return findAttempts(receiptId).stream().map(attempt -> new CampaignAttemptReceipt(attempt.attemptId(),
                attempt.targetIndex(), attempt.platform(), attempt.accountId(), attempt.status(), attempt.externalPublishId(),
                attempt.message(), attempt.dataMode(), attempt.updatedAt())).toList();
    }

    @Transactional
    public CampaignAttemptData claimPending(String leaseToken, long leaseSeconds) {
        recoverExpiredDispatches();
        List<String> candidates = jdbc.query("SELECT a.attempt_id FROM marketing_campaign_publish_attempt a JOIN marketing_campaign_outbox o ON o.attempt_id=a.attempt_id " +
                        "WHERE o.state='PENDING' AND o.next_attempt_at<=CURRENT_TIMESTAMP ORDER BY o.created_at,a.target_index LIMIT 1",
                (rs, row) -> rs.getString(1));
        if (candidates.isEmpty()) return null;
        String attemptId = candidates.get(0);
        int claimed = jdbc.update("UPDATE marketing_campaign_outbox SET state='LEASED',lease_token=?,lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP WHERE attempt_id=? AND state='PENDING'",
                leaseToken, leaseSeconds, attemptId);
        if (claimed != 1) return null;
        jdbc.update("UPDATE marketing_campaign_publish_attempt SET status='DISPATCHING',attempt_version=attempt_version+1,updated_at=CURRENT_TIMESTAMP WHERE attempt_id=? AND status='ACCEPTED'", attemptId);
        return findAttempt(attemptId);
    }

    @Transactional
    public CampaignAttemptData claimReconciliation(String leaseToken, long leaseSeconds) {
        List<String> candidates = jdbc.query("SELECT attempt_id FROM marketing_campaign_outbox WHERE state='RECONCILE' AND next_attempt_at<=CURRENT_TIMESTAMP ORDER BY created_at LIMIT 1",
                (rs, row) -> rs.getString(1));
        if (candidates.isEmpty()) return null;
        String attemptId = candidates.get(0);
        int claimed = jdbc.update("UPDATE marketing_campaign_outbox SET state='LEASED',lease_token=?,lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP WHERE attempt_id=? AND state='RECONCILE'",
                leaseToken, leaseSeconds, attemptId);
        return claimed == 1 ? findAttempt(attemptId) : null;
    }

    @Transactional
    public CampaignAttemptData claimReconciliation(String attemptId, String leaseToken, long leaseSeconds) {
        int claimed = jdbc.update("UPDATE marketing_campaign_outbox SET state='LEASED',lease_token=?,lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP " +
                        "WHERE attempt_id=? AND state='RECONCILE'",
                leaseToken, leaseSeconds, attemptId);
        return claimed == 1 ? findAttempt(attemptId) : null;
    }

    public CampaignAttemptData findAttempt(String attemptId) {
        List<CampaignAttemptData> rows = jdbc.query("SELECT attempt_id,receipt_id,plan_id,target_index,platform,account_id,status,data_mode,external_publish_id,message,attempt_version,updated_at " +
                        "FROM marketing_campaign_publish_attempt WHERE attempt_id=?", (rs, row) -> attempt(rs), attemptId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional
    public boolean finishAttempt(CampaignAttemptData attempt, String leaseToken, String status, String externalId,
                                 String message, CampaignDataMode dataMode) {
        int updated = jdbc.update("UPDATE marketing_campaign_publish_attempt SET status=?,external_publish_id=?,message=?,data_mode=?,attempt_version=attempt_version+1,updated_at=CURRENT_TIMESTAMP " +
                        "WHERE attempt_id=? AND attempt_version=? AND status IN ('DISPATCHING','OUTCOME_UNKNOWN')",
                status, externalId, trim(message), dataMode.name(), attempt.attemptId(), attempt.attemptVersion());
        if (updated != 1) return false;
        jdbc.update("INSERT INTO marketing_campaign_event(event_id,attempt_id,event_type,data_mode,payload_json) VALUES(?,?,?,?,?)",
                UUID.randomUUID().toString(), attempt.attemptId(), status, dataMode.name(), json(Map.of("externalPublishId", externalId == null ? "" : externalId,
                        "message", message == null ? "" : message)));
        if ("SUCCESS".equals(status)) {
            jdbc.update("UPDATE marketing_content SET status='SUCCESS',publish_message=?,external_publish_id=?,publish_time=CURRENT_TIMESTAMP WHERE id=(SELECT content_id FROM marketing_campaign_plan WHERE plan_id=?)",
                    trim(message), externalId, attempt.planId());
        }
        if ("LEASED".equals(outboxState(attempt.attemptId()))) {
            jdbc.update("UPDATE marketing_campaign_outbox SET state=?,lease_token=NULL,lease_until=NULL,completed_at=?,updated_at=CURRENT_TIMESTAMP,next_attempt_at=CURRENT_TIMESTAMP " +
                            "WHERE attempt_id=? AND lease_token=?",
                    "OUTCOME_UNKNOWN".equals(status) ? "RECONCILE" : "COMPLETED",
                    "OUTCOME_UNKNOWN".equals(status) ? null : Timestamp.valueOf(LocalDateTime.now()), attempt.attemptId(), leaseToken);
        }
        updateReceiptStatus(attempt.receiptId());
        return true;
    }

    @Transactional
    public void recoverExpiredDispatches() {
        List<String> expired = jdbc.query("SELECT attempt_id FROM marketing_campaign_outbox WHERE state='LEASED' AND lease_until<CURRENT_TIMESTAMP",
                (rs, row) -> rs.getString(1));
        for (String attemptId : expired) {
            jdbc.update("UPDATE marketing_campaign_publish_attempt SET status='OUTCOME_UNKNOWN',message='Dispatcher lease expired; outcome requires reconciliation',attempt_version=attempt_version+1,updated_at=CURRENT_TIMESTAMP WHERE attempt_id=? AND status='DISPATCHING'", attemptId);
            jdbc.update("UPDATE marketing_campaign_outbox SET state='RECONCILE',lease_token=NULL,lease_until=NULL,next_attempt_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE attempt_id=? AND state='LEASED' AND lease_until<CURRENT_TIMESTAMP", attemptId);
        }
    }

    private void updateReceiptStatus(String receiptId) {
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM marketing_campaign_publish_attempt WHERE receipt_id=?", Integer.class, receiptId);
        Integer success = jdbc.queryForObject("SELECT COUNT(*) FROM marketing_campaign_publish_attempt WHERE receipt_id=? AND status='SUCCESS'", Integer.class, receiptId);
        Integer unknown = jdbc.queryForObject("SELECT COUNT(*) FROM marketing_campaign_publish_attempt WHERE receipt_id=? AND status='OUTCOME_UNKNOWN'", Integer.class, receiptId);
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM marketing_campaign_publish_attempt WHERE receipt_id=? AND status IN ('ACCEPTED','DISPATCHING')", Integer.class, receiptId);
        Integer failed = jdbc.queryForObject("SELECT COUNT(*) FROM marketing_campaign_publish_attempt WHERE receipt_id=? AND status='FAILED'", Integer.class, receiptId);
        String status = active > 0 ? "ACCEPTED" : unknown > 0 ? "OUTCOME_UNKNOWN" : success > 0 && failed > 0 ? "PARTIAL_SUCCESS" : success > 0 ? "SUCCESS" : "FAILED";
        jdbc.update("UPDATE marketing_campaign_publish_receipt SET status=?,updated_at=CURRENT_TIMESTAMP WHERE receipt_id=?", status, receiptId);
    }

    public String outboxState(String attemptId) {
        List<String> states = jdbc.query("SELECT state FROM marketing_campaign_outbox WHERE attempt_id=?", (rs, row) -> rs.getString(1), attemptId);
        return states.isEmpty() ? "" : states.get(0);
    }

    private CampaignAttemptData attempt(ResultSet rs) throws SQLException {
        return new CampaignAttemptData(rs.getString("attempt_id"), rs.getString("receipt_id"), rs.getString("plan_id"),
                rs.getInt("target_index"), rs.getString("platform"), rs.getLong("account_id"), rs.getString("status"),
                mode(rs.getString("data_mode")), rs.getString("external_publish_id"), rs.getString("message"),
                rs.getLong("attempt_version"), localDateTime(rs.getTimestamp("updated_at")));
    }

    private CampaignAccountData account(ResultSet rs) throws SQLException {
        return new CampaignAccountData(rs.getLong("id"), rs.getString("platform"), rs.getString("external_account_ref"),
                rs.getString("permission_code"), rs.getInt("revision_no"), mode(rs.getString("data_mode")), rs.getBoolean("active"));
    }

    private CampaignEffectResponse effectResponse(ResultSet rs) throws SQLException {
        Long exposure = nullableLong(rs, "exposure_count");
        Long clicks = nullableLong(rs, "click_count");
        Long leads = nullableLong(rs, "lead_count");
        BigDecimal revenue = rs.getBigDecimal("attributable_revenue");
        BigDecimal cost = rs.getBigDecimal("attributable_cost");
        CampaignDataMode mode = mode(rs.getString("data_mode"));
        BigDecimal ctr = rate(clicks, exposure);
        BigDecimal conversion = rate(leads, clicks);
        BigDecimal leadCost = rs.getString("currency") != null && leads != null && leads > 0 && cost != null
                ? cost.divide(BigDecimal.valueOf(leads), 4, RoundingMode.HALF_UP) : null;
        BigDecimal roi = mode == CampaignDataMode.REAL && revenue != null && cost != null && cost.signum() > 0
                && rs.getString("roi_definition_version") != null
                ? revenue.subtract(cost).divide(cost, 6, RoundingMode.HALF_UP) : null;
        return new CampaignEffectResponse(rs.getString("attempt_id"), nullableLong(rs, "legacy_publish_record_id"),
                rs.getLong("content_id"), nullableLong(rs, "listing_id"), rs.getString("platform"), exposure, clicks,
                nullableLong(rs, "private_message_count"), leads, ctr, conversion, leadCost, revenue, cost,
                roi, rs.getString("currency"), rs.getString("roi_definition_version"), rs.getString("source_system"),
                rs.getString("source_event_id"), nullableLong(rs, "source_version"), instant(rs.getTimestamp("window_start")),
                instant(rs.getTimestamp("window_end")), instant(rs.getTimestamp("observed_at")), mode, rs.getString("unavailable_reason"));
    }

    private BigDecimal rate(Long numerator, Long denominator) {
        return numerator != null && denominator != null && denominator > 0
                ? BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP) : null;
    }

    private boolean negative(Long value) { return value != null && value < 0; }
    private boolean negative(BigDecimal value) { return value != null && value.signum() < 0; }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }

    private CampaignDataMode mode(String mode) {
        try { return CampaignDataMode.valueOf(mode); } catch (RuntimeException ignored) { return CampaignDataMode.UNKNOWN; }
    }

    private LocalDateTime localDateTime(Timestamp timestamp) { return timestamp == null ? null : timestamp.toLocalDateTime(); }
    private String trim(String value) { return value == null || value.length() <= 1024 ? value : value.substring(0, 1024); }
    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalArgumentException("campaign payload cannot be serialized", failure); }
    }
    private Map<String, String> readStringMap(String value) {
        try { return objectMapper.readValue(value, STRING_MAP); }
        catch (Exception failure) { throw new IllegalStateException("stored campaign target payload is invalid", failure); }
    }
}
