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

    @DubboReference(check = false)
    private ListingMasterRpcService listingMasterRpcService;

    public CustomerManagementServiceImpl(CustomerDomainService customerDomainService,
                                         CustomerFollowRecordDomainService followRecordDomainService,
                                         OwnerEntrustRecordDomainService ownerEntrustRecordDomainService,
                                         CustomerFavoriteListingDomainService favoriteListingDomainService,
                                         CustomerConverter customerConverter) {
        this.customerDomainService = customerDomainService;
        this.followRecordDomainService = followRecordDomainService;
        this.ownerEntrustRecordDomainService = ownerEntrustRecordDomainService;
        this.favoriteListingDomainService = favoriteListingDomainService;
        this.customerConverter = customerConverter;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CustomerProfileDTO createProfile(CustomerUpsertRequest request) {
        validateProfileRequest(request);
        CustomerEntity entity = new CustomerEntity();
        applyProfile(entity, request);
        customerDomainService.save(entity);
        log.info("新增客户档案成功，customerId={}，姓名={}", entity.getId(), entity.getName());
        return customerConverter.toProfile(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CustomerProfileDTO updateProfile(Long customerId, CustomerUpsertRequest request) {
        validateProfileRequest(request);
        CustomerEntity entity = requireCustomer(customerId);
        applyProfile(entity, request);
        customerDomainService.updateById(entity);
        log.info("更新客户档案成功，customerId={}", customerId);
        return customerConverter.toProfile(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteProfile(Long customerId) {
        requireCustomer(customerId);
        customerDomainService.removeById(customerId);
        log.info("删除客户档案成功，customerId={}", customerId);
    }

    @Override
    public CustomerDetailResponse getCustomerDetail(Long customerId) {
        CustomerEntity entity = requireCustomer(customerId);
        return new CustomerDetailResponse(
                customerConverter.toProfile(entity),
                listFollowRecords(customerId),
                listEntrusts(customerId),
                listFavorites(customerId)
        );
    }

    @Override
    public List<CustomerProfileDTO> searchProfiles(CustomerQueryRequest request) {
        return customerDomainService.searchProfiles(request).stream()
                .map(customerConverter::toProfile)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CustomerFollowRecordResponse addFollowRecord(Long customerId, CustomerFollowRecordRequest request) {
        requireCustomer(customerId);
        if (!FollowTypeEnum.contains(request.followType())) {
            throw new IllegalArgumentException("跟进方式不合法: " + request.followType());
        }
        CustomerFollowRecordEntity entity = new CustomerFollowRecordEntity();
        entity.setCustomerId(customerId);
        entity.setBrokerId(request.brokerId());
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
        requireCustomer(customerId);
        return followRecordDomainService.listByCustomerId(customerId).stream()
                .map(customerConverter::toFollowResponse)
                .toList();
    }

    @Override
    public List<CustomerMatchResponse> matchListings(Long customerId) {
        CustomerEntity customer = requireCustomer(customerId);
        if (!StringUtils.hasText(customer.getPreferredArea()) && !StringUtils.hasText(customer.getPreferredLayout())) {
            return List.of();
        }

        String keyword = StringUtils.hasText(customer.getPreferredArea())
                ? customer.getPreferredArea()
                : customer.getPreferredLayout();

        List<ListingDTO> listings = listingMasterRpcService == null
                ? List.of()
                : listingMasterRpcService.searchListings(keyword);

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
        CustomerEntity customer = requireCustomer(customerId);
        if (!CustomerProfileTypeEnum.OWNER.name().equalsIgnoreCase(customer.getProfileType())) {
            throw new IllegalArgumentException("只有业主档案才允许维护委托书");
        }
        if (!EntrustStatusEnum.contains(request.status())) {
            throw new IllegalArgumentException("委托状态不合法: " + request.status());
        }

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
        requireCustomer(customerId);
        return ownerEntrustRecordDomainService.listByCustomerId(customerId).stream()
                .map(customerConverter::toEntrustResponse)
                .toList();
    }

    @Override
    public List<OwnerEntrustResponse> listExpiringEntrusts(int days) {
        return ownerEntrustRecordDomainService.listExpiringWithinDays(days).stream()
                .map(customerConverter::toEntrustResponse)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CustomerFavoriteResponse addFavorite(Long customerId, CustomerFavoriteRequest request) {
        requireCustomer(customerId);
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
        requireCustomer(customerId);
        favoriteListingDomainService.remove(new LambdaQueryWrapper<CustomerFavoriteListingEntity>()
                .eq(CustomerFavoriteListingEntity::getCustomerId, customerId)
                .eq(CustomerFavoriteListingEntity::getListingId, listingId));
        log.info("取消客户收藏房源成功，customerId={}，listingId={}", customerId, listingId);
    }

    @Override
    public List<CustomerFavoriteResponse> listFavorites(Long customerId) {
        requireCustomer(customerId);
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
        entity.setBrokerId(request.brokerId());
        entity.setSourceChannel(request.sourceChannel());
        entity.setRemark(request.remark());
    }

    private CustomerEntity requireCustomer(Long customerId) {
        CustomerEntity customer = customerDomainService.getById(customerId);
        if (customer == null) {
            throw new IllegalArgumentException("客户档案不存在: " + customerId);
        }
        return customer;
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
