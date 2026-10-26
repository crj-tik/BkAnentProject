package com.bkanent.marketing.campaign;

import com.bkanent.common.marketing.campaign.*;
import com.bkanent.common.marketing.campaign.CampaignErrorCode;
import com.bkanent.common.marketing.campaign.CampaignDataMode;
import com.bkanent.marketing.entity.MarketingContentEntity;
import com.bkanent.marketing.service.MarketingAssetService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.math.RoundingMode;

@Service
public class CampaignLifecycleService {

    private final CampaignJdbcRepository repository;
    private final CampaignActorResolver actorResolver;
    private final CampaignAuthorizationService authorization;
    private final CampaignRuntimeProperties properties;
    private final CampaignListingSnapshotClient listingSnapshots;
    private final MarketingAssetService marketingAssets;
    private final ObjectMapper objectMapper;
    private final List<CampaignPlatformProvider> providers;

    public CampaignLifecycleService(CampaignJdbcRepository repository, CampaignActorResolver actorResolver,
                                    CampaignAuthorizationService authorization, CampaignRuntimeProperties properties,
                                    CampaignListingSnapshotClient listingSnapshots, MarketingAssetService marketingAssets,
                                    ObjectMapper objectMapper, List<CampaignPlatformProvider> providers) {
        this.repository = repository;
        this.actorResolver = actorResolver;
        this.authorization = authorization;
        this.properties = properties;
        this.listingSnapshots = listingSnapshots;
        this.marketingAssets = marketingAssets;
        this.objectMapper = objectMapper;
        this.providers = providers;
    }

    @Transactional
    public CampaignPlanResponse prepare(CampaignPlanPrepareRequest request) {
        validatePrepare(request);
        long actorId = actorResolver.currentActorId();
        authorization.require(actorId, CampaignAuthorizationService.PLAN_PREPARE);
        MarketingContentEntity content = marketingAssets.getById(request.contentId());
        if (content == null) throw CampaignDomainException.missing(CampaignErrorCode.CONTENT_NOT_FOUND, "marketing content was not found");
        if (!"APPROVED".equalsIgnoreCase(content.getAuditStatus())) {
            throw CampaignDomainException.conflict(CampaignErrorCode.CONTENT_NOT_APPROVED, "content must be approved before preparing a campaign");
        }
        CampaignDataMode requestedMode = configuredMode();
        JsonNode listing = listingSnapshots.read(content.getListingId(), requestedMode);
        Map<String, Object> contentSnapshot = contentSnapshot(content, listing);
        String contentJson = CampaignHash.canonicalJson(contentSnapshot, objectMapper);
        String contentHash = CampaignHash.sha256(contentJson);
        int revision = repository.contentRevision(content.getId(), contentHash, contentJson, requestedMode);

        List<Map<String, Object>> assets = repository.findAssets(request.brandAssetIds());
        if (assets.size() != request.brandAssetIds().size()) {
            throw CampaignDomainException.invalid("one or more selected brand assets are unavailable");
        }
        String assetsJson = CampaignHash.canonicalJson(assets, objectMapper);
        String assetHash = CampaignHash.sha256(assetsJson);
        List<CampaignTargetData> targets = new ArrayList<>();
        List<CampaignTargetSnapshot> targetResponses = new ArrayList<>();
        Set<String> uniqueTargets = new HashSet<>();
        int index = 0;
        for (CampaignTargetRequest targetRequest : request.targets()) {
            String platform = normalizePlatform(targetRequest.platform());
            if (targetRequest.accountId() == null || targetRequest.accountId() <= 0) throw CampaignDomainException.invalid("authorized accountId is required");
            String targetKey = platform + ":" + targetRequest.accountId();
            if (!uniqueTargets.add(targetKey)) throw CampaignDomainException.invalid("duplicate platform/account target");
            CampaignAccountData account = repository.findAccount(targetRequest.accountId());
            if (account == null || !account.active() || !platform.equalsIgnoreCase(account.platform())) {
                throw CampaignDomainException.forbidden("target account is not authorized for the requested platform");
            }
            authorization.require(actorId, account.permissionCode());
            if (requestedMode == CampaignDataMode.SIMULATED && account.dataMode() != CampaignDataMode.SIMULATED) {
                throw CampaignDomainException.conflict(CampaignErrorCode.ACCOUNT_NOT_AUTHORIZED, "simulated plans require an explicitly simulated account grant");
            }
            if (requestedMode == CampaignDataMode.REAL && account.dataMode() != CampaignDataMode.REAL) {
                throw CampaignDomainException.conflict(CampaignErrorCode.ACCOUNT_NOT_AUTHORIZED, "real plans require a real account grant");
            }
            Map<String, String> args = targetRequest.arguments();
            String argsHash = CampaignHash.sha256(CampaignHash.canonicalJson(args, objectMapper));
            CampaignTargetData target = new CampaignTargetData(index, platform, account.id(), account.revision(),
                    account.externalAccountRef(), account.permissionCode(), argsHash, args);
            targets.add(target);
            targetResponses.add(new CampaignTargetSnapshot(index, platform, account.id(), Integer.toString(account.revision()),
                    account.externalAccountRef(), argsHash, args));
            index++;
        }
        String targetsJson = CampaignHash.canonicalJson(targetResponses, objectMapper);
        String targetsHash = CampaignHash.sha256(targetsJson);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("contentRevision", revision);
        snapshot.put("contentHash", contentHash);
        snapshot.put("assetHash", assetHash);
        snapshot.put("targetsHash", targetsHash);
        snapshot.put("ruleVersion", properties.getRuleVersion());
        snapshot.put("dataMode", requestedMode);
        String snapshotHash = CampaignHash.sha256(CampaignHash.canonicalJson(snapshot, objectMapper));
        String planId = UUID.randomUUID().toString();
        CampaignPlanData plan = new CampaignPlanData(planId, actorId, content.getId(), revision, contentHash, assetHash,
                targetsHash, properties.getRuleVersion(), snapshotHash, 1, "PREPARED", requestedMode, null, contentJson, assetsJson);
        repository.insertPlan(plan, targets);
        return response(requirePlan(planId), targetResponses);
    }

    public CampaignPlanResponse getPlan(String planId) {
        long actorId = actorResolver.currentActorId();
        authorization.require(actorId, CampaignAuthorizationService.PLAN_READ);
        CampaignPlanData plan = requirePlan(planId);
        if (plan.ownerUserId() != actorId) throw CampaignDomainException.forbidden("campaign plan belongs to another user");
        return response(plan, repository.findTargets(planId).stream().map(target -> new CampaignTargetSnapshot(
                target.targetIndex(), target.platform(), target.accountId(), Integer.toString(target.accountRevision()),
                target.externalAccountRef(), target.argumentsHash(), target.arguments())).toList());
    }

    @Transactional
    public CampaignReceiptResponse confirm(String planId, CampaignConfirmRequest request) {
        if (request == null || !StringUtils.hasText(request.requestId()) || request.requestId().length() > 128
                || !StringUtils.hasText(request.expectedSnapshotHash())) throw CampaignDomainException.invalid("requestId and expectedSnapshotHash are required");
        long actorId = actorResolver.currentActorId();
        authorization.require(actorId, CampaignAuthorizationService.PLAN_CONFIRM);
        CampaignPlanData plan = requirePlan(planId);
        if (plan.ownerUserId() != actorId) throw CampaignDomainException.forbidden("campaign plan belongs to another user");
        if (plan.planVersion() != request.expectedPlanVersion()) {
            throw CampaignDomainException.conflict(CampaignErrorCode.PLAN_VERSION_CONFLICT, "prepared plan version has changed");
        }
        if (!plan.snapshotHash().equals(request.expectedSnapshotHash())) {
            throw CampaignDomainException.conflict(CampaignErrorCode.CONFIRMATION_HASH_MISMATCH, "confirmation does not match the prepared snapshot");
        }
        String bodyHash = CampaignHash.sha256(CampaignHash.canonicalJson(Map.of("planId", planId,
                "expectedPlanVersion", request.expectedPlanVersion(), "expectedSnapshotHash", request.expectedSnapshotHash()), objectMapper));
        CampaignReceiptResponse prior = repository.findReceiptByOwnerRequest(actorId, request.requestId());
        if (prior != null) {
            if (prior.planId().equals(planId) && bodyHash.equals(requestBodyHash(actorId, request.requestId()))) return prior;
            throw CampaignDomainException.conflict(CampaignErrorCode.REQUEST_ID_CONFLICT, "requestId was already used with a different campaign confirmation");
        }
        if (!"PREPARED".equals(plan.status())) {
            throw CampaignDomainException.conflict(CampaignErrorCode.PLAN_ALREADY_CONFIRMED, "campaign plan has already been confirmed");
        }
        if (repository.isPlanExpired(planId, Math.max(0, properties.getPlanTtlSeconds()))) {
            throw CampaignDomainException.conflict(CampaignErrorCode.CONFIRMATION_EXPIRED, "prepared campaign confirmation has expired; prepare a new plan");
        }
        validateCurrentSnapshot(plan);
        List<CampaignTargetData> targets = repository.findTargets(planId);
        for (CampaignTargetData target : targets) {
            CampaignAccountData account = repository.findAccount(target.accountId());
            if (account == null || !account.active() || account.revision() != target.accountRevision()
                    || !account.externalAccountRef().equals(target.externalAccountRef())
                    || !account.permissionCode().equals(target.permissionCode())) {
                throw CampaignDomainException.conflict(CampaignErrorCode.PLAN_VERSION_CONFLICT, "target account changed after preparation");
            }
            authorization.require(actorId, target.permissionCode());
            if (provider(plan.dataMode(), target.platform()) == null) {
                throw CampaignDomainException.unavailable(CampaignErrorCode.PROVIDER_NOT_CONFIGURED,
                        "no campaign provider is configured for " + target.platform() + " in " + plan.dataMode() + " mode");
            }
        }
        String receiptId = UUID.randomUUID().toString();
        repository.accept(planId, actorId, request.requestId(), bodyHash, receiptId, plan.dataMode(), targets);
        return repository.findReceipt(planId, request.requestId());
    }

    public CampaignReceiptResponse getReceipt(String receiptId) {
        long actorId = actorResolver.currentActorId();
        authorization.require(actorId, CampaignAuthorizationService.PLAN_READ);
        CampaignReceiptResponse receipt = repository.findReceiptById(receiptId);
        if (receipt == null) throw CampaignDomainException.missing(CampaignErrorCode.ATTEMPT_NOT_FOUND, "campaign receipt was not found");
        CampaignPlanData plan = requirePlan(receipt.planId());
        if (plan.ownerUserId() != actorId) throw CampaignDomainException.forbidden("campaign receipt belongs to another user");
        return receipt;
    }

    public boolean dispatchOne() {
        CampaignAttemptData attempt = repository.claimPending(UUID.randomUUID().toString(), properties.getOutboxLeaseSeconds());
        if (attempt == null) return false;
        CampaignTargetData target = repository.findTargets(attempt.planId()).stream()
                .filter(item -> item.targetIndex() == attempt.targetIndex()).findFirst().orElse(null);
        CampaignPlatformProvider provider = provider(attempt.dataMode(), attempt.platform());
        if (provider == null || target == null) {
            repository.finishAttempt(attempt, outboxLeaseToken(attempt.attemptId()), "FAILED", null,
                    "PROVIDER_NOT_CONFIGURED: provider or prepared target is unavailable", attempt.dataMode());
            return true;
        }
        CampaignProviderResult result;
        try {
            result = provider.publish(attempt, target);
        } catch (RuntimeException uncertain) {
            result = new CampaignProviderResult("OUTCOME_UNKNOWN", null,
                    "provider call outcome is unknown; original attempt retained", attempt.dataMode());
        }
        result = validateProviderResult(attempt, result);
        repository.finishAttempt(attempt, outboxLeaseToken(attempt.attemptId()), result.status(), result.externalPublishId(), result.message(), result.dataMode());
        return true;
    }

    public boolean reconcileOne() {
        CampaignAttemptData attempt = repository.claimReconciliation(UUID.randomUUID().toString(), properties.getOutboxLeaseSeconds());
        if (attempt == null) return false;
        CampaignPlatformProvider provider = provider(attempt.dataMode(), attempt.platform());
        if (provider == null) {
            repository.finishAttempt(attempt, outboxLeaseToken(attempt.attemptId()), "OUTCOME_UNKNOWN", null,
                    "PROVIDER_NOT_CONFIGURED: original attempt retained for manual reconciliation", attempt.dataMode());
            return true;
        }
        CampaignProviderResult result;
        try {
            result = provider.reconcile(attempt);
        } catch (RuntimeException uncertain) {
            result = new CampaignProviderResult("OUTCOME_UNKNOWN", null,
                    "reconciliation is still unresolved; original attempt retained", attempt.dataMode());
        }
        result = validateProviderResult(attempt, result);
        repository.finishAttempt(attempt, outboxLeaseToken(attempt.attemptId()), result.status(),
                result.externalPublishId(), result.message(), result.dataMode());
        return true;
    }

    public CampaignReceiptResponse findReceipt(String planId, String requestId) {
        long actorId = actorResolver.currentActorId();
        authorization.require(actorId, CampaignAuthorizationService.PLAN_READ);
        CampaignPlanData plan = requirePlan(planId);
        if (plan.ownerUserId() != actorId) throw CampaignDomainException.forbidden("campaign plan belongs to another user");
        CampaignReceiptResponse receipt = repository.findReceipt(planId, requestId);
        if (receipt == null) throw CampaignDomainException.missing(CampaignErrorCode.ATTEMPT_NOT_FOUND, "campaign receipt was not found");
        return receipt;
    }

    public CampaignEffectResponse readEffect(String attemptId, boolean refresh) {
        long actorId = actorResolver.currentActorId();
        authorization.require(actorId, CampaignAuthorizationService.EFFECT_READ);
        CampaignAttemptData attempt = repository.findAttempt(attemptId);
        if (attempt == null) throw CampaignDomainException.missing(CampaignErrorCode.ATTEMPT_NOT_FOUND, "campaign attempt was not found");
        CampaignPlanData plan = requirePlan(attempt.planId());
        if (plan.ownerUserId() != actorId) throw CampaignDomainException.forbidden("campaign attempt belongs to another user");
        CampaignEffectResponse existing = repository.findEffect(attemptId);
        if (existing != null && !refresh) return existing;
        CampaignPlatformProvider provider = provider(attempt.dataMode(), attempt.platform());
        if (provider == null) return unknownEffect(attempt, "effect provider is not configured");
        CampaignEffectObservation observation;
        try { observation = provider.readEffect(attempt); }
        catch (RuntimeException unavailable) { return unknownEffect(attempt, "effect source is unavailable"); }
        if (!validEffectIdentity(observation)) return unknownEffect(attempt, "effect source returned an incomplete observation");
        MarketingContentEntity content = marketingAssets.getById(plan.contentId());
        return repository.saveEffect(attempt, plan.contentId(), content == null ? null : content.getListingId(), observation);
    }

    public CampaignReceiptResponse reconcile(String attemptId) {
        long actorId = actorResolver.currentActorId();
        authorization.require(actorId, CampaignAuthorizationService.PLAN_CONFIRM);
        CampaignAttemptData original = repository.findAttempt(attemptId);
        if (original == null) throw CampaignDomainException.missing(CampaignErrorCode.ATTEMPT_NOT_FOUND, "campaign attempt was not found");
        CampaignPlanData plan = requirePlan(original.planId());
        if (plan.ownerUserId() != actorId) throw CampaignDomainException.forbidden("campaign attempt belongs to another user");
        if (!"OUTCOME_UNKNOWN".equals(original.status())) {
            throw CampaignDomainException.conflict(CampaignErrorCode.PLAN_ALREADY_CONFIRMED, "only an outcome-unknown attempt can be reconciled");
        }
        String leaseToken = UUID.randomUUID().toString();
        CampaignAttemptData claimed = repository.claimReconciliation(attemptId, leaseToken, properties.getOutboxLeaseSeconds());
        if (claimed == null) throw CampaignDomainException.conflict(CampaignErrorCode.PLAN_VERSION_CONFLICT, "attempt reconciliation is already in progress");
        CampaignPlatformProvider provider = provider(claimed.dataMode(), claimed.platform());
        if (provider == null) {
            repository.finishAttempt(claimed, leaseToken, "OUTCOME_UNKNOWN", null,
                    "provider is unavailable; original attempt remains unresolved", claimed.dataMode());
        } else {
            CampaignProviderResult result;
            try { result = provider.reconcile(claimed); }
            catch (RuntimeException unavailable) {
                result = new CampaignProviderResult("OUTCOME_UNKNOWN", null,
                        "reconciliation is still unresolved; original attempt retained", claimed.dataMode());
            }
            result = validateProviderResult(claimed, result);
            repository.finishAttempt(claimed, leaseToken, result.status(), result.externalPublishId(), result.message(), result.dataMode());
        }
        return repository.findReceipt(original.planId(), repository.findReceiptById(original.receiptId()).requestId());
    }

    public List<CampaignRoiReportResponse> roiReport(String platformFilter) {
        long actorId = actorResolver.currentActorId();
        authorization.require(actorId, CampaignAuthorizationService.EFFECT_READ);
        List<CampaignEffectResponse> effects = repository.findAttemptsForOwner(actorId, platformFilter).stream()
                .map(attempt -> {
                    CampaignEffectResponse observed = repository.findEffect(attempt.attemptId());
                    return observed == null ? unknownEffect(attempt, "no attributable effect observation is available") : observed;
                }).toList();
        Map<String, List<CampaignEffectResponse>> grouped = new LinkedHashMap<>();
        effects.stream().sorted(Comparator.comparing(CampaignEffectResponse::platform).thenComparing(effect -> effect.dataMode().name()))
                .forEach(effect -> grouped.computeIfAbsent(effect.platform() + "|" + effect.dataMode(), key -> new ArrayList<>()).add(effect));
        return grouped.values().stream().map(this::roiGroup).toList();
    }

    public List<CampaignEffectResponse> effectsForListing(long listingId) {
        long actorId = actorResolver.currentActorId();
        authorization.require(actorId, CampaignAuthorizationService.EFFECT_READ);
        if (listingId <= 0) throw CampaignDomainException.invalid("listingId must be positive");
        return repository.findEffectsByListingId(listingId).stream()
                .filter(effect -> requirePlanForAttempt(effect.attemptId()).ownerUserId() == actorId).toList();
    }

    public CampaignEffectResponse syncLegacyEffect(long legacyPublishId) {
        if (legacyPublishId <= 0) throw CampaignDomainException.invalid("publishRecordId must be positive");
        String attemptId = repository.findAttemptIdByLegacyPublishId(legacyPublishId);
        if (attemptId == null) throw CampaignDomainException.missing(CampaignErrorCode.ATTEMPT_NOT_FOUND,
                "legacy publish record has not been mapped to a campaign attempt");
        return readEffect(attemptId, true);
    }

    private CampaignRoiReportResponse roiGroup(List<CampaignEffectResponse> rows) {
        CampaignEffectResponse first = rows.get(0);
        long unknown = rows.stream().filter(row -> row.unavailableReason() != null || row.dataMode() == CampaignDataMode.UNKNOWN).count();
        Long exposure = rows.stream().allMatch(row -> row.exposureCount() != null)
                ? rows.stream().mapToLong(CampaignEffectResponse::exposureCount).sum() : null;
        Long leads = rows.stream().allMatch(row -> row.leadCount() != null)
                ? rows.stream().mapToLong(CampaignEffectResponse::leadCount).sum() : null;
        boolean sameCurrency = first.currency() != null && rows.stream().allMatch(row -> first.currency().equals(row.currency()));
        boolean completeRevenue = first.dataMode() == CampaignDataMode.REAL && sameCurrency
                && rows.stream().allMatch(row -> row.attributableRevenue() != null);
        boolean completeCost = first.dataMode() == CampaignDataMode.REAL && sameCurrency
                && rows.stream().allMatch(row -> row.attributableCost() != null);
        BigDecimal revenue = completeRevenue ? rows.stream().map(CampaignEffectResponse::attributableRevenue).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
        BigDecimal cost = completeCost ? rows.stream().map(CampaignEffectResponse::attributableCost).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
        String definition = rows.stream().map(CampaignEffectResponse::roiDefinitionVersion).distinct().count() == 1
                ? first.roiDefinitionVersion() : null;
        BigDecimal leadCost = cost != null && leads != null && leads > 0 ? cost.divide(BigDecimal.valueOf(leads), 4, RoundingMode.HALF_UP) : null;
        BigDecimal roi = revenue != null && cost != null && cost.signum() > 0 && definition != null
                ? revenue.subtract(cost).divide(cost, 6, RoundingMode.HALF_UP) : null;
        return new CampaignRoiReportResponse(first.platform(), first.dataMode(), rows.size(), exposure, leads, revenue, cost,
                leadCost, roi, unknown, sameCurrency ? first.currency() : null, definition);
    }

    private CampaignPlanData requirePlanForAttempt(String attemptId) {
        CampaignAttemptData attempt = repository.findAttempt(attemptId);
        if (attempt == null) throw CampaignDomainException.missing(CampaignErrorCode.ATTEMPT_NOT_FOUND, "campaign attempt was not found");
        return requirePlan(attempt.planId());
    }

    private CampaignEffectResponse unknownEffect(CampaignAttemptData attempt, String reason) {
        CampaignPlanData plan = requirePlan(attempt.planId());
        MarketingContentEntity content = marketingAssets.getById(plan.contentId());
        return new CampaignEffectResponse(attempt.attemptId(), null, plan.contentId(), content == null ? null : content.getListingId(),
                attempt.platform(),
                null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null,
                null, null, null,
                Instant.now(), CampaignDataMode.UNKNOWN, reason);
    }

    private boolean validEffectIdentity(CampaignEffectObservation observation) {
        return observation != null && StringUtils.hasText(observation.sourceSystem())
                && observation.sourceSystem().matches("[A-Za-z0-9_.:-]{1,64}")
                && StringUtils.hasText(observation.sourceEventId())
                && observation.sourceEventId().matches("[A-Za-z0-9_.:-]{1,128}")
                && observation.sourceVersion() >= 1 && observation.windowStart() != null
                && observation.windowEnd() != null && observation.windowEnd().isAfter(observation.windowStart())
                && observation.observedAt() != null;
    }

    CampaignPlatformProvider provider(CampaignDataMode mode, String platform) {
        return providers.stream().filter(candidate -> candidate.simulated() == (mode == CampaignDataMode.SIMULATED))
                .filter(candidate -> candidate.supports(platform)).findFirst().orElse(null);
    }

    private CampaignProviderResult validateProviderResult(CampaignAttemptData attempt, CampaignProviderResult result) {
        if (result == null || result.dataMode() != attempt.dataMode()
                || !Set.of("SUCCESS", "FAILED", "OUTCOME_UNKNOWN").contains(result.status())) {
            return new CampaignProviderResult("OUTCOME_UNKNOWN", null,
                    "provider response provenance or status was invalid; original attempt retained", attempt.dataMode());
        }
        if (attempt.dataMode() == CampaignDataMode.REAL && result.externalPublishId() != null
                && result.externalPublishId().startsWith("sim-")) {
            return new CampaignProviderResult("OUTCOME_UNKNOWN", null,
                    "provider returned a simulated external ID in real mode; original attempt retained", CampaignDataMode.REAL);
        }
        return result;
    }

    private String outboxLeaseToken(String attemptId) { return repository.currentLeaseToken(attemptId); }

    private void validateCurrentSnapshot(CampaignPlanData plan) {
        MarketingContentEntity current = marketingAssets.getById(plan.contentId());
        if (current == null || !"APPROVED".equalsIgnoreCase(current.getAuditStatus())) {
            throw CampaignDomainException.conflict(CampaignErrorCode.PLAN_VERSION_CONFLICT, "content approval or identity changed after preparation");
        }
        JsonNode prior = readJson(plan.contentSnapshotJson());
        JsonNode previousListing = prior.path("listingSnapshot");
        JsonNode currentListing = listingSnapshots.read(plan.contentId() == current.getId() ? current.getListingId() : plan.contentId(), plan.dataMode());
        Map<String, Object> currentSnapshot = contentSnapshot(current, currentListing);
        String currentHash = CampaignHash.sha256(CampaignHash.canonicalJson(currentSnapshot, objectMapper));
        if (!currentHash.equals(plan.contentHash()) || !previousListing.path("revision").asText().equals(currentListing.path("revision").asText())
                || !previousListing.path("contentHash").asText().equals(currentListing.path("contentHash").asText())) {
            throw CampaignDomainException.conflict(CampaignErrorCode.PLAN_VERSION_CONFLICT, "content or listing snapshot changed after preparation");
        }
        List<Map<String, Object>> currentAssets = repository.findAssets(readAssetIds(plan.assetsSnapshotJson()));
        String assetHash = CampaignHash.sha256(CampaignHash.canonicalJson(currentAssets, objectMapper));
        if (!assetHash.equals(plan.assetHash())) throw CampaignDomainException.conflict(CampaignErrorCode.PLAN_VERSION_CONFLICT, "selected brand assets changed after preparation");
    }

    private List<Long> readAssetIds(String assetsJson) {
        JsonNode assets = readJson(assetsJson);
        List<Long> ids = new ArrayList<>();
        assets.forEach(asset -> ids.add(asset.path("id").asLong()));
        return ids;
    }

    private Map<String, Object> contentSnapshot(MarketingContentEntity content, JsonNode listing) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("contentId", content.getId()); map.put("listingId", content.getListingId());
        map.put("platform", content.getPlatform()); map.put("title", content.getTitle());
        map.put("contentType", content.getContentType()); map.put("copywriting", content.getCopywriting());
        map.put("assetUrls", content.getAssetUrls()); map.put("coverImageUrl", content.getCoverImageUrl());
        map.put("videoUrl", content.getVideoUrl()); map.put("versionNo", content.getVersionNo());
        map.put("parentContentId", content.getParentContentId()); map.put("platformVariant", content.getPlatformVariant());
        map.put("tags", content.getTags()); map.put("auditStatus", content.getAuditStatus());
        Map<String, Object> listingBinding = new LinkedHashMap<>();
        listingBinding.put("listingRef", listing.path("listingRef"));
        listingBinding.put("revision", listing.path("revision").asText());
        listingBinding.put("contentHash", listing.path("contentHash").asText());
        listingBinding.put("dataMode", listing.path("dataMode").asText());
        listingBinding.put("datasetId", listing.path("datasetId").asText());
        listingBinding.put("facts", listing.path("facts"));
        map.put("listingSnapshot", listingBinding);
        return map;
    }

    private JsonNode readJson(String value) {
        try { return objectMapper.readTree(value); }
        catch (Exception failure) { throw new IllegalStateException("stored campaign snapshot is invalid", failure); }
    }

    private CampaignPlanResponse response(CampaignPlanData plan, List<CampaignTargetSnapshot> targets) {
        return new CampaignPlanResponse(plan.planId(), plan.contentId(), plan.contentRevision(), plan.contentHash(), plan.assetHash(),
                plan.targetsHash(), plan.ruleVersion(), plan.snapshotHash(), plan.planVersion(), plan.status(), plan.dataMode(), targets,
                plan.createdAt(), plan.createdAt() == null ? null : plan.createdAt().plusSeconds(Math.max(0, properties.getPlanTtlSeconds())));
    }

    private CampaignPlanData requirePlan(String planId) {
        CampaignPlanData plan = repository.findPlan(planId);
        if (plan == null) throw CampaignDomainException.missing(CampaignErrorCode.PLAN_NOT_FOUND, "campaign plan was not found");
        return plan;
    }

    private String requestBodyHash(long owner, String requestId) { return repository.findRequestBodyHash(owner, requestId); }

    private CampaignDataMode configuredMode() {
        return properties.getMode() == CampaignRuntimeProperties.Mode.SIMULATED ? CampaignDataMode.SIMULATED : CampaignDataMode.REAL;
    }

    private void validatePrepare(CampaignPlanPrepareRequest request) {
        if (request == null || request.contentId() == null || request.contentId() <= 0) throw CampaignDomainException.invalid("contentId is required");
        if (request.targets().isEmpty() || request.targets().size() > 20) throw CampaignDomainException.invalid("campaign requires 1 to 20 targets");
        if (request.brandAssetIds().stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(request.brandAssetIds()).size() != request.brandAssetIds().size()) {
            throw CampaignDomainException.invalid("brand asset IDs must be positive and unique");
        }
    }

    private String normalizePlatform(String value) {
        if (!StringUtils.hasText(value) || !value.matches("[A-Za-z0-9_-]{2,32}")) throw CampaignDomainException.invalid("invalid campaign platform");
        return value.toUpperCase();
    }
}
