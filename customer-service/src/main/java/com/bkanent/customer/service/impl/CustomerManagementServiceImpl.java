package com.bkanent.customer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.common.model.CustomerProfileDTO;
import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.rpc.ListingMasterRpcService;
import com.bkanent.customer.converter.CustomerConverter;
import com.bkanent.customer.entity.CustomerEntity;
import com.bkanent.customer.entity.CustomerFavoriteListingEntity;
import com.bkanent.customer.entity.CustomerFollowRecordEntity;
import com.bkanent.customer.entity.OwnerEntrustRecordEntity;
import com.bkanent.customer.enums.CustomerProfileTypeEnum;
import com.bkanent.customer.enums.EntrustStatusEnum;
import com.bkanent.customer.enums.FollowTypeEnum;
import com.bkanent.customer.model.CustomerDetailResponse;
import com.bkanent.customer.model.CustomerFavoriteRequest;
import com.bkanent.customer.model.CustomerFavoriteResponse;
import com.bkanent.customer.model.CustomerFollowRecordRequest;
import com.bkanent.customer.model.CustomerFollowRecordResponse;
import com.bkanent.customer.model.CustomerMatchResponse;
import com.bkanent.customer.model.CustomerQueryRequest;
import com.bkanent.customer.model.CustomerUpsertRequest;
import com.bkanent.customer.model.OwnerEntrustResponse;
import com.bkanent.customer.model.OwnerEntrustUpsertRequest;
import com.bkanent.customer.service.CustomerDomainService;
import com.bkanent.customer.service.CustomerFavoriteListingDomainService;
import com.bkanent.customer.service.CustomerFollowRecordDomainService;
import com.bkanent.customer.service.CustomerManagementService;
import com.bkanent.customer.service.OwnerEntrustRecordDomainService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 客源客户管理服务实现。
 */
@Service
public class CustomerManagementServiceImpl implements CustomerManagementService {

    private static final Logger log = LoggerFactory.getLogger(CustomerManagementServiceImpl.class);

    private final CustomerDomainService customerDomainService;
    private final CustomerFollowRecordDomainService followRecordDomainService;
    private final OwnerEntrustRecordDomainService ownerEntrustRecordDomainService;
    private final CustomerFavoriteListingDomainService favoriteListingDomainService;
    private final CustomerConverter customerConverter;

    private final com.bkanent.customer.service.ListingReadClient listingReadClient;

    public CustomerManagementServiceImpl(CustomerDomainService customerDomainService,
                                         CustomerFollowRecordDomainService followRecordDomainService,
                                         OwnerEntrustRecordDomainService ownerEntrustRecordDomainService,
                                         CustomerFavoriteListingDomainService favoriteListingDomainService,
                                         CustomerConverter customerConverter,
                                         com.bkanent.customer.service.ListingReadClient listingReadClient) {
        this.listingReadClient = listingReadClient;
        this.customerDomainService = customerDomainService;
        this.followRecordDomainService = followRecordDomainService;
        this.ownerEntrustRecordDomainService = ownerEntrustRecordDomainService;
        this.favoriteListingDomainService = favoriteListingDomainService;
        this.customerConverter = customerConverter;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CustomerProfileDTO createProfile(CustomerUpsertRequest request) {
        requireOperation("customer.create", null);
        validateProfileRequest(request);
        long brokerId = com.bkanent.common.authz.AuthzRequestScope.requireBrokerIdentity();
        requireSubmittedBroker(request.brokerId(), brokerId);
        CustomerEntity entity = new CustomerEntity();
        entity.setBrokerId(brokerId);
        applyProfile(entity, request);
        customerDomainService.save(entity);
        log.info("新增客户档案成功，customerId={}，姓名={}", entity.getId(), entity.getName());
        return customerConverter.toProfile(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CustomerProfileDTO updateProfile(Long customerId, CustomerUpsertRequest request) {
        validateProfileRequest(request);
        CustomerEntity entity = requireCustomer(customerId, "customer.update.own", "customer.update.all");
        if (request.brokerId() != null && !request.brokerId().equals(entity.getBrokerId())) deny();
        applyProfile(entity, request);
        customerDomainService.updateById(entity);
        log.info("更新客户档案成功，customerId={}", customerId);
        return customerConverter.toProfile(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteProfile(Long customerId) {
        requireCustomer(customerId, "customer.delete.own", "customer.delete.all");
        customerDomainService.removeById(customerId);
        log.info("删除客户档案成功，customerId={}", customerId);
    }

    @Override
    public CustomerDetailResponse getCustomerDetail(Long customerId) {
        CustomerEntity entity = requireCustomer(customerId, "customer.read", "customer.read.all");
        return new CustomerDetailResponse(
                customerConverter.toProfile(entity),
                listFollowRecords(customerId),
                listEntrusts(customerId),
                listFavorites(customerId)
        );
    }

    @Override
    public List<CustomerProfileDTO> searchProfiles(CustomerQueryRequest request) {
        request = authorizedQuery(request);
        return customerDomainService.searchProfiles(request).stream()
                .map(customerConverter::toProfile)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CustomerFollowRecordResponse addFollowRecord(Long customerId, CustomerFollowRecordRequest request) {
        CustomerEntity customer = requireCustomer(customerId, "customer.follow.write.own", "customer.follow.write.all");
        if (!FollowTypeEnum.contains(request.followType())) {
            throw new IllegalArgumentException("跟进方式不合法: " + request.followType());
        }
        CustomerFollowRecordEntity entity = new CustomerFollowRecordEntity();
        entity.setCustomerId(customerId);
        long brokerId = com.bkanent.common.authz.AuthzRequestScope.requireBrokerIdentity();
        if (request.brokerId() != null && request.brokerId() != brokerId) deny();
        if (!com.bkanent.common.authz.AuthzRequestScope.businessAdmin()
                && !brokerIdEquals(customer.getBrokerId(), brokerId)) deny();
        entity.setBrokerId(brokerId);
        entity.setFollowType(request.followType().toUpperCase());
        entity.setContent(request.content());
        entity.setResultTag(request.resultTag());
        entity.setNextFollowTime(request.nextFollowTime());
        followRecordDomainService.save(entity);
        log.info("新增客户跟进记录成功，customerId={}，followId={}", customerId, entity.getId());
        return customerConverter.toFollowResponse(entity);
    }

    @Override
    public List<CustomerFollowRecordResponse> listFollowRecords(Long customerId) {
        requireCustomer(customerId, "customer.follow.read.own", "customer.follow.read.all");
        return followRecordDomainService.listByCustomerId(customerId).stream()
                .map(customerConverter::toFollowResponse)
                .toList();
    }

    @Override
    public List<CustomerMatchResponse> matchListings(Long customerId) {
        // Resource ownership and the exact match operation are checked before external listing lookup.
        CustomerEntity customer = requireCustomer(customerId, "customer.match.own", "customer.match.all");
        var user = listingReadClient.authenticate();
        if (!StringUtils.hasText(customer.getPreferredArea()) && !StringUtils.hasText(customer.getPreferredLayout())) {
            return List.of();
        }

        String keyword = StringUtils.hasText(customer.getPreferredArea())
                ? customer.getPreferredArea()
                : customer.getPreferredLayout();

        List<ListingDTO> listings = listingReadClient.search(keyword, user);

        List<CustomerMatchResponse> matches = new ArrayList<>();
        for (ListingDTO listing : listings) {
            int score = calculateMatchScore(customer, listing);
            if (score >= 50) {
                matches.add(new CustomerMatchResponse(
                        customerId,
                        listing,
                        buildMatchReason(customer, listing),
                        score
                ));
            }
        }

        return matches.stream()
                .sorted(Comparator.comparing(CustomerMatchResponse::matchScore).reversed())
                .limit(10)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OwnerEntrustResponse saveEntrust(Long customerId, OwnerEntrustUpsertRequest request) {
        CustomerEntity customer = requireCustomer(customerId, "customer.entrust.write.own", "customer.entrust.write.all");
        if (!CustomerProfileTypeEnum.OWNER.name().equalsIgnoreCase(customer.getProfileType())) {
            throw new IllegalArgumentException("只有业主档案才允许维护委托书");
        }
        if (!EntrustStatusEnum.contains(request.status())) {
            throw new IllegalArgumentException("委托状态不合法: " + request.status());
        }
        if (request.listingId() != null) deny();

        OwnerEntrustRecordEntity entity = ownerEntrustRecordDomainService.getOne(
                new LambdaQueryWrapper<OwnerEntrustRecordEntity>()
                        .eq(OwnerEntrustRecordEntity::getCustomerId, customerId)
                        .eq(request.listingId() != null, OwnerEntrustRecordEntity::getListingId, request.listingId())
                        .last("limit 1")
        );
        if (entity == null) {
            entity = new OwnerEntrustRecordEntity();
            entity.setCustomerId(customerId);
        }
        entity.setListingId(request.listingId());
        entity.setContractNo(request.contractNo());
        entity.setEntrustStartDate(request.entrustStartDate());
        entity.setEntrustEndDate(request.entrustEndDate());
        entity.setReminderDays(request.reminderDays());
        entity.setStatus(request.status().toUpperCase());
        entity.setRemark(request.remark());

        ownerEntrustRecordDomainService.saveOrUpdate(entity);
        log.info("保存业主委托记录成功，customerId={}，entrustId={}", customerId, entity.getId());
        return customerConverter.toEntrustResponse(entity);
    }

    @Override
    public List<OwnerEntrustResponse> listEntrusts(Long customerId) {
        requireCustomer(customerId, "customer.entrust.read.own", "customer.entrust.read.all");
        return ownerEntrustRecordDomainService.listByCustomerId(customerId).stream()
                .map(customerConverter::toEntrustResponse)
                .toList();
    }

    @Override
    public List<OwnerEntrustResponse> listExpiringEntrusts(int days) {
        requireAdminOperation("customer.entrust.expiring.read");
        return ownerEntrustRecordDomainService.listExpiringWithinDays(days).stream()
                .map(customerConverter::toEntrustResponse)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CustomerFavoriteResponse addFavorite(Long customerId, CustomerFavoriteRequest request) {
        requireCustomer(customerId, "customer.favorite.write.own", "customer.favorite.write.all");
        CustomerFavoriteListingEntity existing = favoriteListingDomainService.getOne(
                new LambdaQueryWrapper<CustomerFavoriteListingEntity>()
                        .eq(CustomerFavoriteListingEntity::getCustomerId, customerId)
                        .eq(CustomerFavoriteListingEntity::getListingId, request.listingId())
                        .last("limit 1")
        );
        if (existing != null) {
            existing.setFavoriteSource(request.favoriteSource());
            existing.setRemark(request.remark());
            favoriteListingDomainService.updateById(existing);
            log.info("更新客户收藏房源成功，customerId={}，listingId={}", customerId, request.listingId());
            return customerConverter.toFavoriteResponse(existing);
        }

        CustomerFavoriteListingEntity entity = new CustomerFavoriteListingEntity();
        entity.setCustomerId(customerId);
        entity.setListingId(request.listingId());
        entity.setFavoriteSource(request.favoriteSource());
        entity.setRemark(request.remark());
        favoriteListingDomainService.save(entity);
        log.info("新增客户收藏房源成功，customerId={}，listingId={}", customerId, request.listingId());
        return customerConverter.toFavoriteResponse(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeFavorite(Long customerId, Long listingId) {
        requireCustomer(customerId, "customer.favorite.write.own", "customer.favorite.write.all");
        favoriteListingDomainService.remove(new LambdaQueryWrapper<CustomerFavoriteListingEntity>()
                .eq(CustomerFavoriteListingEntity::getCustomerId, customerId)
                .eq(CustomerFavoriteListingEntity::getListingId, listingId));
        log.info("取消客户收藏房源成功，customerId={}，listingId={}", customerId, listingId);
    }

    @Override
    public List<CustomerFavoriteResponse> listFavorites(Long customerId) {
        requireCustomer(customerId, "customer.favorite.read.own", "customer.favorite.read.all");
        return favoriteListingDomainService.listByCustomerId(customerId).stream()
                .map(customerConverter::toFavoriteResponse)
                .toList();
    }

    private void validateProfileRequest(CustomerUpsertRequest request) {
        if (!CustomerProfileTypeEnum.contains(request.profileType())) {
            throw new IllegalArgumentException("档案类型不合法: " + request.profileType());
        }
        if (!StringUtils.hasText(request.name())) {
            throw new IllegalArgumentException("姓名不能为空");
        }
        if (!StringUtils.hasText(request.mobile())) {
            throw new IllegalArgumentException("手机号不能为空");
        }
    }

    private void applyProfile(CustomerEntity entity, CustomerUpsertRequest request) {
        entity.setProfileType(request.profileType().toUpperCase());
        entity.setName(request.name());
        entity.setMobile(request.mobile());
        entity.setWechatNo(request.wechatNo());
        entity.setGender(request.gender());
        entity.setIntention(request.intention());
        entity.setPreferredArea(request.preferredArea());
        entity.setPreferredLayout(request.preferredLayout());
        entity.setBudgetMin(request.budgetMin());
        entity.setBudgetMax(request.budgetMax());
        entity.setPreferredAreaMin(request.preferredAreaMin());
        entity.setPreferredAreaMax(request.preferredAreaMax());
        entity.setSourceChannel(request.sourceChannel());
        entity.setRemark(request.remark());
    }

    private CustomerEntity requireCustomer(Long customerId, String ownOperation, String businessWideOperation) {
        com.bkanent.common.authz.AuthzRequestScope.requireCurrent();
        boolean businessAdmin = com.bkanent.common.authz.AuthzRequestScope.businessAdmin();
        if (businessAdmin) {
            com.bkanent.common.authz.AuthzRequestScope.requireOperation(businessWideOperation);
        } else {
            com.bkanent.common.authz.AuthzRequestScope.requireOperation(ownOperation);
        }
        CustomerEntity customer = customerDomainService.getById(customerId);
        if (customer == null) {
            throw new IllegalArgumentException("客户档案不存在: " + customerId);
        }
        if (!businessAdmin) {
            Long brokerId = com.bkanent.common.authz.AuthzRequestScope.businessIdentity("BROKER");
            if (brokerId == null || !brokerId.equals(customer.getBrokerId())) deny();
        }
        return customer;
    }

    private CustomerQueryRequest authorizedQuery(CustomerQueryRequest request) {
        if (request == null) request = new CustomerQueryRequest(null, null, null, null, null, null);
        if (com.bkanent.common.authz.AuthzRequestScope.businessAdmin()) {
            com.bkanent.common.authz.AuthzRequestScope.requireOperation("customer.read.all");
            return request;
        }
        com.bkanent.common.authz.AuthzRequestScope.requireOperation("customer.read");
        long brokerId = com.bkanent.common.authz.AuthzRequestScope.requireBrokerIdentity();
        if (request.brokerId() != null && request.brokerId() != brokerId) deny();
        return new CustomerQueryRequest(request.profileType(), request.keyword(), brokerId,
                request.intention(), request.budgetMin(), request.budgetMax());
    }

    private void requireOperation(String ownOperation, String businessWideOperation) {
        if (com.bkanent.common.authz.AuthzRequestScope.businessAdmin()) {
            com.bkanent.common.authz.AuthzRequestScope.requireOperation(
                    businessWideOperation == null ? ownOperation : businessWideOperation);
        } else {
            com.bkanent.common.authz.AuthzRequestScope.requireOperation(ownOperation);
        }
    }

    private void requireAdminOperation(String operation) {
        if (!com.bkanent.common.authz.AuthzRequestScope.businessAdmin()) deny();
        com.bkanent.common.authz.AuthzRequestScope.requireOperation(operation);
    }

    private void requireSubmittedBroker(Long submittedBrokerId, long trustedBrokerId) {
        if (submittedBrokerId != null && submittedBrokerId != trustedBrokerId) deny();
    }

    private static boolean brokerIdEquals(Long storedBrokerId, long trustedBrokerId) {
        return storedBrokerId != null && storedBrokerId == trustedBrokerId;
    }

    private static void deny() {
        throw new com.bkanent.common.authz.AuthzException(com.bkanent.common.authz.AuthzErrorCode.PERMISSION_DENIED);
    }

    private int calculateMatchScore(CustomerEntity customer, ListingDTO listing) {
        int score = 0;
        if (StringUtils.hasText(customer.getPreferredArea())
                && listing.address() != null
                && listing.address().contains(customer.getPreferredArea())) {
            score += 40;
        }
        if (StringUtils.hasText(customer.getPreferredLayout())
                && listing.layout() != null
                && listing.layout().equalsIgnoreCase(customer.getPreferredLayout())) {
            score += 30;
        }
        if (customer.getBudgetMin() != null && customer.getBudgetMax() != null && listing.totalPrice() != null
                && listing.totalPrice().compareTo(customer.getBudgetMin()) >= 0
                && listing.totalPrice().compareTo(customer.getBudgetMax()) <= 0) {
            score += 30;
        }
        return score;
    }

    private String buildMatchReason(CustomerEntity customer, ListingDTO listing) {
        List<String> reasons = new ArrayList<>();
        if (StringUtils.hasText(customer.getPreferredArea())
                && listing.address() != null
                && listing.address().contains(customer.getPreferredArea())) {
            reasons.add("区域匹配");
        }
        if (StringUtils.hasText(customer.getPreferredLayout())
                && listing.layout() != null
                && listing.layout().equalsIgnoreCase(customer.getPreferredLayout())) {
            reasons.add("户型匹配");
        }
        if (customer.getBudgetMin() != null && customer.getBudgetMax() != null && listing.totalPrice() != null
                && listing.totalPrice().compareTo(customer.getBudgetMin()) >= 0
                && listing.totalPrice().compareTo(customer.getBudgetMax()) <= 0) {
            reasons.add("预算匹配");
        }
        return reasons.isEmpty() ? "基础条件相近" : String.join("、", reasons);
    }
}
