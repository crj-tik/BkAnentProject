package com.bkanent.listing.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.bkanent.common.model.KnowledgeDocument;
import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.model.ListingKeywordSearchRequest;
import com.bkanent.common.model.ListingKeywordSearchResultDTO;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.listing.customer.CustomerProfileVerifyClient;
import com.bkanent.listing.rag.ListingRagOutbox;
import com.bkanent.listing.converter.ListingConverter;
import com.bkanent.listing.entity.ListingAssetEntity;
import com.bkanent.listing.entity.ListingEntity;
import com.bkanent.listing.entity.ListingPriceRecordEntity;
import com.bkanent.listing.entity.ListingStatusRecordEntity;
import com.bkanent.listing.enums.ListingStatusEnum;
import com.bkanent.listing.enums.ListingVerificationStatusEnum;
import com.bkanent.listing.mapper.ListingPriceRecordMapper;
import com.bkanent.listing.mapper.ListingStatusRecordMapper;
import com.bkanent.listing.model.ListingAssetBindRequest;
import com.bkanent.listing.model.ListingAssetUpsertItem;
import com.bkanent.listing.model.ListingAssetView;
import com.bkanent.listing.model.ListingBuyerSearchQuery;
import com.bkanent.listing.model.ListingBuyerSearchResponse;
import com.bkanent.listing.model.ListingBuyerViewResponse;
import com.bkanent.listing.model.ListingDetailResponse;
import com.bkanent.listing.model.ListingOcrRecognizeRequest;
import com.bkanent.listing.model.ListingQueryRequest;
import com.bkanent.listing.model.ListingStatusUpdateRequest;
import com.bkanent.listing.model.ListingUpsertRequest;
import com.bkanent.listing.model.ListingVerifyRequest;
import com.bkanent.listing.search.ListingKnowledgeDocumentAssembler;
import com.bkanent.listing.service.ListingAssetService;
import com.bkanent.listing.service.ListingCommandReceiptService;
import com.bkanent.listing.service.ListingDomainService;
import com.bkanent.listing.service.ListingManagementService;
import com.bkanent.listing.service.ListingPrivateInfoService;
import com.bkanent.listing.service.ListingSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ListingManagementServiceImpl 服务实现类。
 * 内部房源登记与维护：业主档案验证失败关闭、登记幂等回执、价格/状态历史与 RAG outbox 同事务、
 * 版本条件写（手动条件更新，不依赖 MyBatis Plus @Version 拦截器）。
 */
@Service
public class ListingManagementServiceImpl implements ListingManagementService {

    /**
     * 常量：YUAN_PER_WAN（万元到元的换算系数）。
     */
    private static final BigDecimal YUAN_PER_WAN = BigDecimal.valueOf(10000L);
    /**
     * 常量：CREATE_OPERATION（登记幂等操作码）。
     */
    private static final String CREATE_OPERATION = "listing.create";
    /**
     * 常量：ALLOWED_TRANSITIONS（状态机：PENDING_REVIEW→ON_SALE/OFFLINE；ON_SALE→OFFLINE/SOLD；
     * OFFLINE→ON_SALE；SOLD 终态；同状态 no-op 由调用方处理）。
     */
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            "PENDING_REVIEW", Set.of("ON_SALE", "OFFLINE"),
            "ON_SALE", Set.of("OFFLINE", "SOLD"),
            "OFFLINE", Set.of("ON_SALE"));
    /**
     * 常量：BUYER_ASSET_TYPES（兼容端点与 upsert 双写的素材类型：图片/户型图/视频，可见性 BUYER）。
     */
    private static final Set<String> BUYER_ASSET_TYPES = Set.of("IMAGE", "FLOOR_PLAN", "VIDEO");

    /**
     * 字段：log。
     */
    private static final Logger log = LoggerFactory.getLogger(ListingManagementServiceImpl.class);
    /**
     * 字段：listingDomainService。
     */
    private final ListingDomainService listingDomainService;
    /**
     * 字段：listingConverter。
     */
    private final ListingConverter listingConverter;
    /**
     * 字段：listingSearchService。
     */
    private final ListingSearchService listingSearchService;
    /**
     * 字段：listingKnowledgeDocumentAssembler。
     */
    private final ListingKnowledgeDocumentAssembler listingKnowledgeDocumentAssembler;
    /**
     * 字段：outbox。
     */
    private final ListingRagOutbox outbox;
    /**
     * 字段：identity。
     */
    private final com.bkanent.listing.rag.ListingRagIdentity identity;
    /**
     * 字段：priceRecordMapper（追加型价格历史）。
     */
    private final ListingPriceRecordMapper priceRecordMapper;
    /**
     * 字段：statusRecordMapper（追加型状态历史）。
     */
    private final ListingStatusRecordMapper statusRecordMapper;
    /**
     * 字段：receipts（登记幂等回执）。
     */
    private final ListingCommandReceiptService receipts;
    /**
     * 字段：ownerVerify（业主档案验证调用方，失败关闭）。
     */
    private final CustomerProfileVerifyClient ownerVerify;
    /**
     * 字段：assetService（素材逐项管理，3.6）。
     */
    private final ListingAssetService assetService;
    /**
     * 字段：privateInfo（产权证号受管加密，3.7）。
     */
    private final ListingPrivateInfoService privateInfo;

    /**
     * 构造 ListingManagementServiceImpl 实例。
     */
    public ListingManagementServiceImpl(ListingDomainService listingDomainService,
                                        ListingConverter listingConverter,
                                        ListingSearchService listingSearchService,
                                        ListingKnowledgeDocumentAssembler listingKnowledgeDocumentAssembler,
                                        ListingRagOutbox outbox,
                                        com.bkanent.listing.rag.ListingRagIdentity identity,
                                        ListingPriceRecordMapper priceRecordMapper,
                                        ListingStatusRecordMapper statusRecordMapper,
                                        ListingCommandReceiptService receipts,
                                        CustomerProfileVerifyClient ownerVerify,
                                        ListingAssetService assetService,
                                        ListingPrivateInfoService privateInfo) {
        this.identity = identity;
        this.listingDomainService = listingDomainService;
        this.listingConverter = listingConverter;
        this.listingSearchService = listingSearchService;
        this.listingKnowledgeDocumentAssembler = listingKnowledgeDocumentAssembler;
        this.outbox = outbox;
        this.priceRecordMapper = priceRecordMapper;
        this.statusRecordMapper = statusRecordMapper;
        this.receipts = receipts;
        this.ownerVerify = ownerVerify;
        this.assetService = assetService;
        this.privateInfo = privateInfo;
    }

    /**
     * 创建房源：可信 broker 归属 → 业主档案当前授权验证（失败关闭不创建）→ 幂等检查 →
     * 同事务保存房源、初始价格/状态历史、登记回执与 RAG outbox。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ListingDetailResponse createListing(ListingUpsertRequest request) {
        requireOperation(CREATE_OPERATION);
        long brokerId = com.bkanent.common.authz.AuthzRequestScope.requireBrokerIdentity();
        requireSubmittedBroker(request.brokerId(), brokerId);
        if (request.ownerCustomerId() == null || request.ownerCustomerId() <= 0) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "ownerCustomerId");
        }
        long actorUserId = currentActorUserId();
        // 业主档案验证：仅 VERIFIED 继续；不可用或拒绝时不创建可用房源。
        ownerVerify.verifyOwnerProfile(request.ownerCustomerId());
        // 幂等检查：同键同内容重试返回原房源，不同内容拒绝冲突。
        String requestId = StringUtils.hasText(request.requestId()) ? request.requestId().trim() : null;
        byte[] requestHash = null;
        if (requestId != null) {
            requestHash = receipts.requestHash(request);
            var existing = receipts.find(actorUserId, CREATE_OPERATION, requestId);
            if (existing.isPresent()) {
                if (!Arrays.equals(existing.get().requestHash(), requestHash)) {
                    throw new RagException(RagErrorCode.INVALID_ARGUMENT, "requestId");
                }
                return toDetail(loadCommitted(existing.get().listingId()));
            }
        }
        ListingEntity entity = new ListingEntity();
        entity.setBrokerId(brokerId);
        entity.setOwnerUserId(actorUserId);
        entity.setOwnerCustomerId(request.ownerCustomerId());
        applyUpsert(entity, request);
        entity.setTotalPrice(request.totalPrice());
        entity.setAskingPriceYuan(yuanFromWan(request.totalPrice()));
        entity.setStatus(ListingStatusEnum.PENDING_REVIEW.name());
        entity.setOcrStatus("PENDING");
        entity.setVerificationStatus(ListingVerificationStatusEnum.PENDING.name());
        entity.setVersion(0L);
        listingDomainService.save(entity);
        String recordRequestId = requestId != null ? requestId : UUID.randomUUID().toString();
        if (entity.getAskingPriceYuan() != null) {
            ListingPriceRecordEntity priceRecord = new ListingPriceRecordEntity();
            priceRecord.setListingId(entity.getId());
            priceRecord.setOldPriceYuan(null);
            priceRecord.setNewPriceYuan(entity.getAskingPriceYuan());
            priceRecord.setOperatorUserId(actorUserId);
            priceRecord.setRequestId(recordRequestId);
            priceRecordMapper.insert(priceRecord);
        }
        ListingStatusRecordEntity statusRecord = new ListingStatusRecordEntity();
        statusRecord.setListingId(entity.getId());
        statusRecord.setFromStatus(null);
        statusRecord.setToStatus(ListingStatusEnum.PENDING_REVIEW.name());
        statusRecord.setAction("REGISTER");
        statusRecord.setOperatorUserId(actorUserId);
        statusRecord.setRequestId(recordRequestId);
        statusRecordMapper.insert(statusRecord);
        if (requestId != null) {
            // 主键冲突（并发重试）时整体回滚，重试按既有回执返回原房源。
            receipts.insertSameTransaction(actorUserId, CREATE_OPERATION, requestId,
                    requestHash, entity.getId(), "COMPLETED");
        }
        // 素材逐项行（IMAGE/FLOOR_PLAN/VIDEO → BUYER）：与旧字段同事务双写（过渡期）。
        assetService.replaceAssets(entity.getId(),
                buyerAssetItems(request.imageUrls(), request.floorPlanUrls(), request.videoUrls()),
                BUYER_ASSET_TYPES, actorUserId);
        outbox.publishCurrent(entity.getId());
        log.info("新增房源成功，listingId={}，title={}，ownerCustomerId={}",
                entity.getId(), entity.getTitle(), entity.getOwnerCustomerId());
        return toDetail(entity);
    }

    /**
     * 更新房源：版本条件写（冲突回滚）；价格变化写价格历史并同步万元投影（除不尽时兼容期拒绝）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ListingDetailResponse updateListing(Long listingId, ListingUpsertRequest request) {
        requireOperation("listing.update.own");
        outbox.lock(listingId);
        ListingEntity entity = requireListing(listingId);
        requireOwner(entity, "listing.update.own", "listing.update.all");
        if (request.brokerId() != null && !request.brokerId().equals(entity.getBrokerId())) {
            throw new com.bkanent.common.authz.AuthzException(com.bkanent.common.authz.AuthzErrorCode.PERMISSION_DENIED);
        }
        BigDecimal oldAsking = entity.getAskingPriceYuan();
        BigDecimal newAsking = yuanFromWan(request.totalPrice());
        boolean priceChanged = newAsking != null
                && (oldAsking == null || newAsking.compareTo(oldAsking) != 0);
        if (priceChanged) {
            BigDecimal[] division = newAsking.divideAndRemainder(YUAN_PER_WAN);
            if (division[1].signum() != 0) {
                throw new IllegalArgumentException("挂牌价无法无损换算为万元兼容投影，兼容期拒绝该价格: " + newAsking);
            }
        }
        applyUpsert(entity, request);
        if (request.totalPrice() != null) {
            entity.setTotalPrice(request.totalPrice());
            if (priceChanged) {
                entity.setAskingPriceYuan(newAsking);
            }
        }
        updateWithVersion(entity);
        if (priceChanged) {
            ListingPriceRecordEntity priceRecord = new ListingPriceRecordEntity();
            priceRecord.setListingId(listingId);
            priceRecord.setOldPriceYuan(oldAsking);
            priceRecord.setNewPriceYuan(newAsking);
            priceRecord.setOperatorUserId(currentActorUserId());
            priceRecord.setRequestId(UUID.randomUUID().toString());
            priceRecordMapper.insert(priceRecord);
        }
        // 素材逐项行与旧字段同事务双写（过渡期）：请求携带的三类列表全量替换。
        assetService.replaceAssets(listingId,
                buyerAssetItems(request.imageUrls(), request.floorPlanUrls(), request.videoUrls()),
                BUYER_ASSET_TYPES, currentActorUserId());
        outbox.publishCurrent(listingId);
        log.info("更新房源成功，listingId={}，version={}", listingId, entity.getVersion());
        return toDetail(entity);
    }

    /**
     * 删除房源。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteListing(Long listingId) {
        requireOperation("listing.delete.own");
        outbox.lock(listingId);
        ListingEntity entity = requireListing(listingId);
        requireOwner(entity, "listing.delete.own", "listing.delete.all");
        listingDomainService.removeById(listingId);
        outbox.publishCurrent(listingId);
        log.info("删除房源成功，listingId={}", listingId);
    }

    /**
     * 获取房源详情。
     */
    @Override
    public ListingDetailResponse getListingDetail(Long listingId) {
        if (com.bkanent.common.authz.AuthzRequestScope.present()) {
            com.bkanent.common.authz.AuthzRequestScope.requireCurrent();
        } else if (com.bkanent.common.mcp.authz.McpAuthorizationScope.current() == null) {
            // 私有详情要求版本化 REST 上下文或 MCP 授权上下文；旧 RAG 凭据单独不放行。
            throw new com.bkanent.common.authz.AuthzException(
                    com.bkanent.common.authz.AuthzErrorCode.AUTHENTICATION_REQUIRED);
        }
        requireReadPermission();
        ListingEntity entity = requireListing(listingId);
        requireOwner(entity, "listing.read", "listing.read.all");
        requireReadable(entity, currentReader());
        return toDetail(entity);
    }

    /**
     * 查询房源详情列表。
     */
    @Override
    public List<ListingDetailResponse> searchListings(ListingQueryRequest request) {
        request = authorizedQuery(request, "listing.read", "listing.read.all");
        var principal = currentReader();
        if (com.bkanent.listing.rag.ListingRagIdentity.activeOnly(principal)) {
            if (request != null && StringUtils.hasText(request.status()) && !"ON_SALE".equals(request.status())) {
                return List.of();
            }
            request = request == null ? new ListingQueryRequest(null, "ON_SALE", null, null, null, null, null, null)
                    : new ListingQueryRequest(request.keyword(), "ON_SALE", request.brokerId(), request.minArea(),
                    request.maxArea(), request.minTotalPrice(), request.maxTotalPrice(), request.verifiedOnly(),
                    request.ownerUserId());
        }
        var rows = listingDomainService.search(request);
        principal = currentReader();
        var current = principal;
        List<ListingDetailResponse> result = new java.util.ArrayList<>();
        for (ListingEntity row : rows) {
            requireOwner(row, "listing.read", "listing.read.all");
            if (com.bkanent.listing.rag.ListingRagIdentity.canRead(current, row)) result.add(toDetail(row));
        }
        return List.copyOf(result);
    }

    /**
     * 查询房源摘要列表。
     */
    @Override
    public List<ListingDTO> searchListingSummaries(String keyword) {
        ListingQueryRequest query = authorizedSummaryQuery(new ListingQueryRequest(keyword, null, null,
                null, null, null, null, null));
        var principal = currentReader();
        var rows = listingDomainService.search(new ListingQueryRequest(keyword,
                com.bkanent.listing.rag.ListingRagIdentity.activeOnly(principal) ? "ON_SALE" : null,
                query.brokerId(), null, null, null, null, null));
        var current = currentReader();
        List<ListingDTO> result = new java.util.ArrayList<>();
        for (ListingEntity row : rows) {
            if (com.bkanent.listing.rag.ListingRagIdentity.canRead(current, row)) result.add(listingConverter.toDto(row));
        }
        return List.copyOf(result);
    }

    /**
     * 使用授权 RAG 默认混合重排查询房源摘要候选。
     */
    @Override
    public List<ListingKeywordSearchResultDTO> searchListingSummariesByKeyword(ListingKeywordSearchRequest request) {
        requireReadPermission();
        return listingSearchService.searchByKeyword(request);
    }

    /**
     * 获取房源摘要。
     */
    @Override
    public ListingDTO getListingSummary(Long listingId) {
        requireReadPermission();
        var principal = currentReader();
        ListingEntity entity = listingDomainService.getById(listingId);
        var current = currentReader();
        if (!principal.userId().equals(current.userId())) {
            throw new com.bkanent.common.rag.RagException(com.bkanent.common.rag.RagErrorCode.AUTHENTICATION_FAILED);
        }
        if (entity == null) { return null; }
        requireReadable(entity, current);
        return listingConverter.toDto(entity);
    }

    /**
     * 获取房源知识文档。
     */
    @Override
    public KnowledgeDocument getListingKnowledgeDocument(Long listingId) {
        ListingDTO listing = getListingSummary(listingId);
        return listing == null ? null : listingKnowledgeDocumentAssembler.assemble(listing);
    }

    /**
     * 更新房源状态：状态机校验（非法跳转拒绝且数据不变；同状态 no-op 不写历史）、
     * 上架资料校验、版本条件写与追加状态历史同事务。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ListingDetailResponse updateStatus(Long listingId, ListingStatusUpdateRequest request) {
        requireOperation("listing.status.update.own");
        if (!ListingStatusEnum.contains(request.status())) {
            throw new IllegalArgumentException("房源状态不合法: " + request.status());
        }
        String target = request.status().toUpperCase();
        outbox.lock(listingId);
        ListingEntity entity = requireListing(listingId);
        requireOwner(entity, "listing.status.update.own", "listing.status.update.all");
        String current = entity.getStatus();
        if (target.equals(current)) {
            // 同状态 no-op：直接返回当前，不写历史、不递增版本。
            return toDetail(entity);
        }
        if (current == null || !ALLOWED_TRANSITIONS.getOrDefault(current, Set.of()).contains(target)) {
            throw new IllegalArgumentException("非法房源状态跳转: " + current + " -> " + target);
        }
        if (ListingStatusEnum.ON_SALE.name().equals(target)) {
            requireListable(entity);
        }
        entity.setStatus(target);
        updateWithVersion(entity);
        ListingStatusRecordEntity statusRecord = new ListingStatusRecordEntity();
        statusRecord.setListingId(listingId);
        statusRecord.setFromStatus(current);
        statusRecord.setToStatus(target);
        statusRecord.setAction(actionFor(target));
        statusRecord.setOperatorUserId(currentActorUserId());
        statusRecord.setRequestId(UUID.randomUUID().toString());
        statusRecordMapper.insert(statusRecord);
        outbox.publishCurrent(listingId);
        log.info("更新房源状态成功，listingId={}，{} -> {}，version={}", listingId, current, target, entity.getVersion());
        return toDetail(entity);
    }

    /**
     * 确认存量房源的业主档案关联：先验证业主档案当前授权与类型（VERIFIED 才继续），
     * 再更新 owner_customer_id 并递增版本；该关联不证明产权或登录身份。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ListingDetailResponse confirmOwner(Long listingId, Long ownerCustomerId) {
        requireOperation("listing.update.own");
        if (ownerCustomerId == null || ownerCustomerId <= 0) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "ownerCustomerId");
        }
        outbox.lock(listingId);
        ListingEntity entity = requireListing(listingId);
        requireOwner(entity, "listing.update.own", "listing.update.all");
        ownerVerify.verifyOwnerProfile(ownerCustomerId);
        entity.setOwnerCustomerId(ownerCustomerId);
        updateWithVersion(entity);
        outbox.publishCurrent(listingId);
        log.info("确认房源业主关联成功，listingId={}，ownerCustomerId={}", listingId, ownerCustomerId);
        return toDetail(entity);
    }

    /**
     * 解除存量房源的业主档案关联：owner_customer_id 置 NULL 并递增版本；
     * 不删除任何价格/状态历史或回执记录。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ListingDetailResponse unbindOwner(Long listingId) {
        requireOperation("listing.update.own");
        outbox.lock(listingId);
        ListingEntity entity = requireListing(listingId);
        requireOwner(entity, "listing.update.own", "listing.update.all");
        if (entity.getOwnerCustomerId() == null) {
            return toDetail(entity);
        }
        long oldVersion = versionOf(entity);
        boolean updated = listingDomainService.update(null, new LambdaUpdateWrapper<ListingEntity>()
                .eq(ListingEntity::getId, listingId)
                .eq(ListingEntity::getVersion, oldVersion)
                .set(ListingEntity::getOwnerCustomerId, null)
                .set(ListingEntity::getVersion, oldVersion + 1L));
        if (!updated) {
            throw new IllegalStateException("房源版本冲突，请刷新后重试: " + listingId);
        }
        entity.setOwnerCustomerId(null);
        entity.setVersion(oldVersion + 1L);
        outbox.publishCurrent(listingId);
        log.info("解除房源业主关联成功，listingId={}，version={}", listingId, entity.getVersion());
        return toDetail(entity);
    }

    /**
     * 历史素材迁移登记（受权操作 listing.assets.bind.own/.all + requireOwner）：
     * 旧逗号拼接 URL 与证件/合同 URL 逐条登记为 PENDING_REVIEW 行（图片/户型图/视频=BUYER，
     * 产权证/合同=SENSITIVE），幂等（同 storageKey 跳过）；不抓取任何 URL，不修改旧字段。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer registerLegacyAssets(Long listingId) {
        requireOperation("listing.assets.bind.own");
        ListingEntity entity = requireListing(listingId);
        requireOwner(entity, "listing.assets.bind.own", "listing.assets.bind.all");
        int registered = assetService.registerLegacyAssets(listingId, currentActorUserId());
        log.info("历史素材迁移登记成功，listingId={}，新登记={}", listingId, registered);
        return registered;
    }

    /**
     * 退役单条素材（受权操作 listing.assets.bind.own/.all + requireOwner，逻辑删除）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void retireAsset(Long assetId) {
        requireOperation("listing.assets.bind.own");
        ListingAssetEntity asset = assetService.getAsset(assetId);
        if (asset == null) {
            throw new IllegalArgumentException("素材不存在: " + assetId);
        }
        ListingEntity entity = requireListing(asset.getListingId());
        requireOwner(entity, "listing.assets.bind.own", "listing.assets.bind.all");
        assetService.retireAsset(assetId);
        log.info("退役房源素材成功，assetId={}，listingId={}", assetId, asset.getListingId());
    }

    /**
     * 买方可见在售房源分页搜索：requireReadPermission + ON_SALE 公开范围（canRead）；
     * 输出前逐行复核当前状态（非在售/已删不返回）；查询只读，不创建任何咨询或档案。
     */
    @Override
    public ListingBuyerSearchResponse searchBuyerListings(ListingBuyerSearchQuery query) {
        com.bkanent.common.authz.AuthzRequestScope.requireCurrent();
        requireReadPermission();
        var page = listingDomainService.searchOnSalePage(query);
        var principal = currentReader();
        List<ListingBuyerViewResponse> items = new ArrayList<>();
        for (ListingEntity row : page.rows()) {
            if (!ListingStatusEnum.ON_SALE.name().equals(row.getStatus())
                    || !Integer.valueOf(0).equals(row.getDeleted())
                    || !com.bkanent.listing.rag.ListingRagIdentity.canRead(principal, row)) {
                continue;
            }
            items.add(toBuyerView(row));
        }
        return new ListingBuyerSearchResponse(List.copyOf(items), page.nextCursor());
    }

    /**
     * 买方可见房源详情：requireReadPermission + ON_SALE 公开范围（canRead）；
     * 输出前复核当前状态，非在售/已删/不可读返回 null，不泄露受限详情。
     */
    @Override
    public ListingBuyerViewResponse getBuyerDetail(Long listingId) {
        com.bkanent.common.authz.AuthzRequestScope.requireCurrent();
        requireReadPermission();
        ListingEntity entity = listingDomainService.getById(listingId);
        if (entity == null || !Integer.valueOf(0).equals(entity.getDeleted())
                || !ListingStatusEnum.ON_SALE.name().equals(entity.getStatus())) {
            return null;
        }
        var principal = currentReader();
        if (!com.bkanent.listing.rag.ListingRagIdentity.canRead(principal, entity)) {
            return null;
        }
        return toBuyerView(entity);
    }

    /**
     * 绑定房源资源：委托 ListingAssetService 逐项写入/替换素材行（IMAGE/FLOOR_PLAN/VIDEO → BUYER），
     * 旧 listing_info 的 image_urls 等字段保留兼容写入（同事务双写，过渡期）；
     * 详情响应的素材数组从 listing_asset 读取。storageKey 保留原值，服务端不抓取任何 URL。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ListingDetailResponse bindAssets(Long listingId, ListingAssetBindRequest request) {
        requireOperation("listing.assets.bind.own");
        outbox.lock(listingId);
        ListingEntity entity = requireListing(listingId);
        requireOwner(entity, "listing.assets.bind.own", "listing.assets.bind.all");
        entity.setImageUrls(listingConverter.joinValues(request.imageUrls()));
        entity.setFloorPlanUrls(listingConverter.joinValues(request.floorPlanUrls()));
        entity.setVideoUrls(listingConverter.joinValues(request.videoUrls()));
        listingDomainService.updateById(entity);
        assetService.replaceAssets(listingId,
                buyerAssetItems(request.imageUrls(), request.floorPlanUrls(), request.videoUrls()),
                BUYER_ASSET_TYPES, currentActorUserId());
        outbox.publishCurrent(listingId);
        log.info("绑定房源媒资成功，listingId={}", listingId);
        return toDetail(entity);
    }

    /**
     * 执行房源 OCR（模拟 OCR 占位，非真实识别）：不再默认填 OCR-{id} 占位证号或占位业主名，
     * 证号为空时保持为空并提示走人工登记，避免占位值通过核验。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ListingDetailResponse recognizeOcr(Long listingId, ListingOcrRecognizeRequest request) {
        requireOperation("listing.ocr.own");
        outbox.lock(listingId);
        ListingEntity entity = requireListing(listingId);
        requireOwner(entity, "listing.ocr.own", "listing.ocr.all");
        entity.setPropertyCertificateUrl(request.propertyCertificateUrl());
        entity.setContractUrl(request.contractUrl());
        entity.setOcrStatus("DONE");
        if (!ListingVerificationStatusEnum.VERIFIED.name().equals(entity.getVerificationStatus())) {
            String remark = "模拟 OCR 占位，非真实识别";
            if (!StringUtils.hasText(entity.getCertificateNo())) {
                remark = remark + "；证号为空，需人工登记核验";
            }
            entity.setVerificationRemark(remark);
        }
        listingDomainService.updateById(entity);
        outbox.publishCurrent(listingId);
        log.info("完成房源 OCR 识别（模拟 OCR 占位，非真实识别），listingId={}", listingId);
        return toDetail(entity);
    }

    /**
     * 核验房源真实性（内部人工登记核验，非第三方权属核验）：
     * 核验通过时同时调用 saveCertificate 加密保存证号（同事务置空明文）；
     * 证号来源优先请求值，其次实体明文，最后已加密密文的解密值（支持加密后重复核验）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ListingDetailResponse verifyAuthenticity(Long listingId, ListingVerifyRequest request) {
        requireOperation("listing.verify.own");
        outbox.lock(listingId);
        ListingEntity entity = requireListing(listingId);
        requireOwner(entity, "listing.verify.own", "listing.verify.all");
        String certificateNo = StringUtils.hasText(request.certificateNo()) ? request.certificateNo()
                : StringUtils.hasText(entity.getCertificateNo()) ? entity.getCertificateNo()
                : privateInfo.readCertificate(listingId);
        if (StringUtils.hasText(certificateNo)) {
            entity.setCertificateNo(certificateNo);
        }
        if (StringUtils.hasText(request.ownerName())) {
            entity.setOwnerName(request.ownerName());
        }
        entity.setVerificationSource(StringUtils.hasText(request.externalSource()) ? request.externalSource() : "manual-internal");

        boolean verified = StringUtils.hasText(certificateNo) && StringUtils.hasText(entity.getOwnerName());
        entity.setVerificationStatus(verified
                ? ListingVerificationStatusEnum.VERIFIED.name()
                : ListingVerificationStatusEnum.FAILED.name());
        entity.setVerificationRemark(verified ? "内部人工登记核验通过（非第三方权属核验）" : "证号或业主信息缺失，校验失败");
        listingDomainService.updateById(entity);
        if (verified) {
            privateInfo.saveCertificate(listingId, certificateNo);
        }
        outbox.publishCurrent(listingId);
        log.info("完成房源真实性核验，listingId={}，status={}", listingId, entity.getVerificationStatus());
        return toDetail(entity);
    }

    /**
     * 组装内部详情响应：素材数组从 listing_asset 读取（全部可见性，内部视图）；
     * 证号优先实体明文（未迁移行），无明文时读取已加密密文的解密值（已迁移行）。
     * 调用方均已通过房源 owner/admin 级读取权限校验。
     */
    private ListingDetailResponse toDetail(ListingEntity entity) {
        String certificateNo = entity.getCertificateNo();
        if (!StringUtils.hasText(certificateNo)) {
            certificateNo = privateInfo.readCertificate(entity.getId());
        }
        List<ListingAssetEntity> assets = assetService.listAssets(entity.getId(), null);
        return listingConverter.toDetailResponse(entity, assets, certificateNo);
    }

    /**
     * 组装买方可见投影：不含 ownerName/certificateNo/证件与合同 URL/内部备注；
     * 素材仅 BUYER 可见且 ACTIVE 状态；askingPriceYuan 为 CNY 元（asking 为空的存量行回退 total_price×10000）。
     */
    private ListingBuyerViewResponse toBuyerView(ListingEntity entity) {
        BigDecimal askingPriceYuan = entity.getAskingPriceYuan() != null ? entity.getAskingPriceYuan()
                : entity.getTotalPrice() != null ? entity.getTotalPrice().multiply(YUAN_PER_WAN) : null;
        List<ListingAssetView> assets = new ArrayList<>();
        for (ListingAssetEntity asset : assetService.listAssets(entity.getId(), "BUYER")) {
            if (!"ACTIVE".equals(asset.getStatus())) {
                continue;
            }
            assets.add(new ListingAssetView(asset.getAssetType(), asset.getStorageKey(), asset.getSortOrder()));
        }
        return new ListingBuyerViewResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getAddress(),
                entity.getCommunityName(),
                entity.getDistrictCode(),
                entity.getLayout(),
                entity.getArea(),
                askingPriceYuan,
                entity.getStatus(),
                entity.getFloorLevel(),
                entity.getDecoration(),
                entity.getSchoolZone(),
                entity.getTraffic(),
                List.copyOf(assets)
        );
    }

    /**
     * 兼容端点与 upsert 双写的素材条目映射：IMAGE/FLOOR_PLAN/VIDEO、visibility=BUYER、
     * status=ACTIVE、sortOrder 按列表顺序；空列表即清空对应类型。
     */
    private static List<ListingAssetUpsertItem> buyerAssetItems(List<String> imageUrls,
                                                                List<String> floorPlanUrls,
                                                                List<String> videoUrls) {
        List<ListingAssetUpsertItem> items = new ArrayList<>();
        appendBuyerAssetItems(items, "IMAGE", imageUrls);
        appendBuyerAssetItems(items, "FLOOR_PLAN", floorPlanUrls);
        appendBuyerAssetItems(items, "VIDEO", videoUrls);
        return items;
    }

    /**
     * 追加单类型 BUYER 素材条目。
     */
    private static void appendBuyerAssetItems(List<ListingAssetUpsertItem> items, String assetType,
                                              List<String> storageKeys) {
        if (storageKeys == null) {
            return;
        }
        int sortOrder = 0;
        for (String storageKey : storageKeys) {
            if (!StringUtils.hasText(storageKey)) {
                continue;
            }
            items.add(new ListingAssetUpsertItem(assetType, storageKey.trim(), "BUYER", sortOrder++, "ACTIVE"));
        }
    }

    /**
     * 应用upsert字段（价格与业主关联由统一入口单独维护，此处不触碰）。
     */
    private void applyUpsert(ListingEntity entity, ListingUpsertRequest request) {
        entity.setTitle(request.title());
        entity.setAddress(request.address());
        entity.setCommunityName(request.communityName());
        entity.setDistrictCode(request.districtCode());
        entity.setLayout(request.layout());
        entity.setArea(request.area());
        entity.setFloorLevel(request.floorLevel());
        entity.setDecoration(request.decoration());
        entity.setSchoolZone(request.schoolZone());
        entity.setTraffic(request.traffic());
        entity.setOwnerName(request.ownerName());
        entity.setCertificateNo(request.certificateNo());
        entity.setPropertyCertificateUrl(request.propertyCertificateUrl());
        entity.setContractUrl(request.contractUrl());
        entity.setImageUrls(listingConverter.joinValues(request.imageUrls()));
        entity.setFloorPlanUrls(listingConverter.joinValues(request.floorPlanUrls()));
        entity.setVideoUrls(listingConverter.joinValues(request.videoUrls()));
    }

    /**
     * 万元转元（null 保持 null）。
     */
    private static BigDecimal yuanFromWan(BigDecimal totalPriceWan) {
        return totalPriceWan == null ? null : totalPriceWan.multiply(YUAN_PER_WAN);
    }

    /**
     * 上架资料校验：标题/地址/户型非空、面积大于零、挂牌价大于零
     * （asking 为空但 total_price 大于零时用 total_price×10000 补齐）；
     * 不校验合同、委托、审批或核验状态。
     */
    private void requireListable(ListingEntity entity) {
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(entity.getTitle())) {
            missing.add("标题");
        }
        if (!StringUtils.hasText(entity.getAddress())) {
            missing.add("地址");
        }
        if (!StringUtils.hasText(entity.getLayout())) {
            missing.add("户型");
        }
        if (entity.getArea() == null || entity.getArea().signum() <= 0) {
            missing.add("面积");
        }
        if (entity.getAskingPriceYuan() == null && entity.getTotalPrice() != null
                && entity.getTotalPrice().signum() > 0) {
            entity.setAskingPriceYuan(entity.getTotalPrice().multiply(YUAN_PER_WAN));
        }
        if (entity.getAskingPriceYuan() == null || entity.getAskingPriceYuan().signum() <= 0) {
            missing.add("挂牌价");
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("房源缺少有效上架资料: " + String.join("、", missing));
        }
    }

    /**
     * 目标状态对应的动作编码。
     */
    private static String actionFor(String target) {
        return switch (target) {
            case "ON_SALE" -> "LIST";
            case "OFFLINE" -> "DELIST";
            case "SOLD" -> "SOLD_CONFIRM";
            default -> "STATUS_CHANGE";
        };
    }

    /**
     * 版本条件写：SET version=version+1 WHERE id=? AND version=?；
     * 影响行数为 0 视为版本冲突，整体回滚。
     */
    private void updateWithVersion(ListingEntity entity) {
        long oldVersion = versionOf(entity);
        entity.setVersion(oldVersion + 1L);
        boolean updated = listingDomainService.update(entity, new LambdaUpdateWrapper<ListingEntity>()
                .eq(ListingEntity::getId, entity.getId())
                .eq(ListingEntity::getVersion, oldVersion));
        if (!updated) {
            throw new IllegalStateException("房源版本冲突，请刷新后重试: " + entity.getId());
        }
    }

    /**
     * 读取既有版本（存量空值按 0 处理）。
     */
    private static long versionOf(ListingEntity entity) {
        return entity.getVersion() == null ? 0L : entity.getVersion();
    }

    /**
     * 按回执加载原房源（幂等重放路径，不重复创建）。
     */
    private ListingEntity loadCommitted(Long listingId) {
        if (listingId == null || listingId <= 0) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "listingId");
        }
        ListingEntity entity = listingDomainService.getById(listingId);
        if (entity == null || !Integer.valueOf(0).equals(entity.getDeleted())) {
            throw new IllegalArgumentException("房源不存在: " + listingId);
        }
        return entity;
    }

    /**
     * 获取必需房源。
     */
    private ListingEntity requireListing(Long listingId) {
        currentReader();
        ListingEntity entity = listingDomainService.getById(listingId);
        if (entity == null || !Integer.valueOf(0).equals(entity.getDeleted())) {
            throw new IllegalArgumentException("房源不存在: " + listingId);
        }
        return entity;
    }

    /**
     * 当前操作人用户 ID：AuthzRequestScope 存在时用 actorId()；
     * 否则按 currentReader() 经 identity 解析。
     */
    private long currentActorUserId() {
        if (com.bkanent.common.authz.AuthzRequestScope.present()) {
            return com.bkanent.common.authz.AuthzRequestScope.actorId();
        }
        var principal = currentReader();
        if (principal.userId() == null || principal.userId() <= 0) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
        return principal.userId();
    }

    private com.bkanent.common.rag.AuthRagPrincipal currentReader() {
        if (com.bkanent.common.authz.AuthzRequestScope.present()) {
            return identity.requireReader(null);
        }
        if (com.bkanent.common.mcp.authz.McpAuthorizationScope.current() != null) {
            return mcpReader();
        }
        return identity.requireReader(com.bkanent.listing.rag.ListingRagCredentialContext.current());
    }

    /**
     * MCP 上下文桥接：从 McpAuthorizationScope 的已验证上下文构造读取主体。
     * 与 scopedPrincipal() 同构（SIGNED/版本化/操作清单来自 Auth 签名上下文），
     * 使摘要/详情的公开范围与归属校验在 MCP 通道下与 REST 行为一致。
     */
    private com.bkanent.common.rag.AuthRagPrincipal mcpReader() {
        var verified = com.bkanent.common.mcp.authz.McpAuthorizationScope.requireCurrent();
        var context = verified.context();
        long expires = Math.min(context.expiresAtEpochSecond(),
                Math.min(context.runHardDeadlineEpochSecond(), verified.freshness().notAfterEpochSecond()));
        // A broad Listing read grant is not a platform-administrator role. Preserve the
        // compatibility marker only when Auth explicitly signed the admin-management grant.
        boolean businessAdmin = context.allowedOperations().contains("auth.users.manage");
        return new com.bkanent.common.rag.AuthRagPrincipal(
                Long.parseLong(verified.actorId()), "SIGNED", true, true, expires,
                com.bkanent.common.rag.AuthRagPrincipal.VERSIONED_AUTHORIZATION_VERSION,
                context.permissionRevision(), context.credentialEpoch(), businessAdmin,
                java.util.List.of(),
                context.allowedOperations(), null);
    }

    private void requireOperation(String operation) {
        if (!com.bkanent.common.authz.AuthzRequestScope.present()) {
            throw new com.bkanent.common.authz.AuthzException(com.bkanent.common.authz.AuthzErrorCode.AUTHENTICATION_REQUIRED);
        }
        com.bkanent.common.authz.AuthzRequestScope.requireOperation(operation);
    }

    private void requireReadPermission() {
        if (com.bkanent.common.authz.AuthzRequestScope.present()) {
            if (com.bkanent.common.authz.AuthzRequestScope.hasOperation("listing.read.all")) {
                com.bkanent.common.authz.AuthzRequestScope.requireOperation("listing.read.all");
            } else {
                com.bkanent.common.authz.AuthzRequestScope.requireOperation("listing.read");
            }
        }
    }

    private ListingQueryRequest authorizedQuery(ListingQueryRequest request, String ownOperation, String allOperation) {
        com.bkanent.common.authz.AuthzRequestScope.requireCurrent();
        if (com.bkanent.common.authz.AuthzRequestScope.hasOperation(allOperation)) {
            com.bkanent.common.authz.AuthzRequestScope.requireOperation(allOperation);
            return request;
        }
        com.bkanent.common.authz.AuthzRequestScope.requireOperation(ownOperation);
        Long brokerId = com.bkanent.common.authz.AuthzRequestScope.businessIdentity("BROKER");
        if (request != null && request.brokerId() != null
                && (brokerId == null || !request.brokerId().equals(brokerId))) {
            throw new com.bkanent.common.authz.AuthzException(com.bkanent.common.authz.AuthzErrorCode.PERMISSION_DENIED);
        }
        long ownerUserId = com.bkanent.common.authz.AuthzRequestScope.actorId();
        return request == null ? new ListingQueryRequest(null, null, brokerId, null, null, null, null, null,
                ownerUserId)
                : new ListingQueryRequest(request.keyword(), request.status(), brokerId, request.minArea(),
                request.maxArea(), request.minTotalPrice(), request.maxTotalPrice(), request.verifiedOnly(), ownerUserId);
    }

    /** Public listing summaries keep the established ON_SALE browse range; private detail routes use owner checks. */
    private ListingQueryRequest authorizedSummaryQuery(ListingQueryRequest request) {
        if (!com.bkanent.common.authz.AuthzRequestScope.present()) return request;
        if (com.bkanent.common.authz.AuthzRequestScope.hasOperation("listing.read.all")) {
            com.bkanent.common.authz.AuthzRequestScope.requireOperation("listing.read.all");
        } else {
            com.bkanent.common.authz.AuthzRequestScope.requireOperation("listing.read");
        }
        return request;
    }

    private void requireOwner(ListingEntity entity, String ownOperation, String allOperation) {
        if (com.bkanent.common.mcp.authz.McpAuthorizationScope.current() != null) {
            requireMcpOwner(entity, ownOperation, allOperation);
            return;
        }
        com.bkanent.common.authz.AuthzRequestScope.requireUserOrLegacyBrokerOwnership(
                entity.getOwnerUserId(), entity.getBrokerId(), ownOperation, allOperation);
    }

    /**
     * MCP 上下文下的资源归属校验：业务管理员凭 all 操作放行；
     * 普通用户凭 own 操作 + Auth 签名上下文中的 BROKER 映射与房源经纪人比对。
     * 与 AuthzRequestScope.requireBrokerOwnership 语义一致，仅上下文来源不同。
     */
    private void requireMcpOwner(ListingEntity entity, String ownOperation, String allOperation) {
        var verified = com.bkanent.common.mcp.authz.McpAuthorizationScope.requireCurrent();
        var context = verified.context();
        if (context.allowedOperations().contains(allOperation)) {
            return;
        }
        if (!context.allowedOperations().contains(ownOperation)) {
            throw new com.bkanent.common.authz.AuthzException(com.bkanent.common.authz.AuthzErrorCode.PERMISSION_DENIED);
        }
        Long actorId = context.actorId() == null ? null : Long.valueOf(context.actorId());
        Long brokerId = context.businessIdentities().get("BROKER");
        boolean ownerMatches = entity.getOwnerUserId() != null
                ? entity.getOwnerUserId().equals(actorId)
                : brokerId != null && brokerId.equals(entity.getBrokerId());
        if (!ownerMatches) {
            throw new com.bkanent.common.authz.AuthzException(com.bkanent.common.authz.AuthzErrorCode.PERMISSION_DENIED);
        }
    }

    private void requireSubmittedBroker(Long submittedBrokerId, long trustedBrokerId) {
        if (submittedBrokerId != null && submittedBrokerId.longValue() != trustedBrokerId) {
            throw new com.bkanent.common.authz.AuthzException(com.bkanent.common.authz.AuthzErrorCode.PERMISSION_DENIED);
        }
    }

    private void requireReadable(ListingEntity entity, com.bkanent.common.rag.AuthRagPrincipal principal) {
        if (!com.bkanent.listing.rag.ListingRagIdentity.canRead(principal, entity)) {
            throw new com.bkanent.common.rag.RagException(com.bkanent.common.rag.RagErrorCode.FORBIDDEN);
        }
    }
}
