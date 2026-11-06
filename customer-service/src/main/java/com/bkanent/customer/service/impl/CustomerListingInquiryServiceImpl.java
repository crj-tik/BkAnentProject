package com.bkanent.customer.service.impl;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.authz.AuthzRequestScope;
import com.bkanent.common.rpc.ListingComparisonReportVerifyResponse;
import com.bkanent.common.rpc.ListingInquiryVerifyResponse;
import com.bkanent.customer.entity.CustomerEntity;
import com.bkanent.customer.entity.CustomerInquiryComparisonEntity;
import com.bkanent.customer.entity.CustomerListingInquiryEntity;
import com.bkanent.customer.enums.InquiryStatusEnum;
import com.bkanent.customer.enums.InquiryValidationStateEnum;
import com.bkanent.customer.model.CustomerInquiryComparisonView;
import com.bkanent.customer.model.CustomerInquiryCreateRequest;
import com.bkanent.customer.model.CustomerInquiryResponse;
import com.bkanent.customer.service.CustomerDomainService;
import com.bkanent.customer.service.CustomerInquiryCommandReceiptStore;
import com.bkanent.customer.service.CustomerInquiryComparisonDomainService;
import com.bkanent.customer.service.CustomerListingInquiryDomainService;
import com.bkanent.customer.service.CustomerListingInquiryService;
import com.bkanent.customer.service.ListingComparisonReportVerifyClient;
import com.bkanent.customer.service.ListingInquiryVerifyClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 买方房源咨询管理服务实现。
 *
 * <p>设计要点（implement-internal-listing-business 决策 4）：
 * 创建路径同步验证房源并落 CONFIRMED；跨服务校验与本地提交之间的状态变化窗口
 * 由提交后复查（recheckAfterCreate）以 NEEDS_RECHECK 补偿状态保留而非谎报已确认；
 * 依赖不可用一律失败关闭。幂等回执与咨询同事务，唯一键串行化并发重试。
 *
 * <p>咨询-对比报告关联（add-consultation-comparison-cards 决策 2/3）：
 * 关联单向轻量，客户域只存 reportId 与元数据；attach 时经签名上下文核验一次
 * 报告归属（owner=当前经纪人），核验失败或依赖异常拒绝且不落库；读取咨询只带出
 * 关联清单，不重复跨域核验，也不缓存报告内容。
 */
@Service
public class CustomerListingInquiryServiceImpl implements CustomerListingInquiryService {

    private static final Logger log = LoggerFactory.getLogger(CustomerListingInquiryServiceImpl.class);

    /**
     * 对比报告 ID 格式：32 位十六进制（与 listing 域报告存储一致）。
     */
    private static final Pattern REPORT_ID_PATTERN = Pattern.compile("[0-9a-f]{32}");

    private final CustomerDomainService customerDomainService;
    private final CustomerListingInquiryDomainService inquiryDomainService;
    private final CustomerInquiryCommandReceiptStore receiptStore;
    private final ListingInquiryVerifyClient listingInquiryVerifyClient;
    private final CustomerInquiryComparisonDomainService comparisonDomainService;
    private final ListingComparisonReportVerifyClient comparisonReportVerifyClient;

    public CustomerListingInquiryServiceImpl(CustomerDomainService customerDomainService,
                                             CustomerListingInquiryDomainService inquiryDomainService,
                                             CustomerInquiryCommandReceiptStore receiptStore,
                                             ListingInquiryVerifyClient listingInquiryVerifyClient,
                                             CustomerInquiryComparisonDomainService comparisonDomainService,
                                             ListingComparisonReportVerifyClient comparisonReportVerifyClient) {
        this.customerDomainService = customerDomainService;
        this.inquiryDomainService = inquiryDomainService;
        this.receiptStore = receiptStore;
        this.listingInquiryVerifyClient = listingInquiryVerifyClient;
        this.comparisonDomainService = comparisonDomainService;
        this.comparisonReportVerifyClient = comparisonReportVerifyClient;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CustomerInquiryResponse createInquiry(Long customerId, CustomerInquiryCreateRequest request) {
        requireCreateRequest(request);
        requireCustomer(customerId, "customer.inquiry.write.own", "customer.inquiry.write.all");
        long brokerId = AuthzRequestScope.requireBrokerIdentity();
        long actorId = AuthzRequestScope.actorId();
        byte[] requestHash = receiptStore.requestHash(customerId, request);
        if (StringUtils.hasText(request.requestId())) {
            var existing = receiptStore.find(actorId, CustomerInquiryCommandReceiptStore.CREATE_OPERATION,
                    request.requestId());
            if (existing.isPresent()) {
                if (!MessageDigest.isEqual(existing.get().requestHash(), requestHash)) {
                    throw new IllegalArgumentException("幂等请求标识已绑定不同内容");
                }
                CustomerListingInquiryEntity original = inquiryDomainService.getById(existing.get().inquiryId());
                if (original == null) {
                    throw new IllegalArgumentException("原始咨询记录不存在: " + existing.get().inquiryId());
                }
                return toResponse(original);
            }
        }
        ListingInquiryVerifyResponse verified = listingInquiryVerifyClient.verify(request.listingId());
        if (verified == null || !ListingInquiryVerifyResponse.ACCESSIBLE.equals(verified.result())) {
            throw new IllegalArgumentException("房源当前不可咨询: " + request.listingId());
        }
        CustomerListingInquiryEntity entity = new CustomerListingInquiryEntity();
        entity.setCustomerId(customerId);
        entity.setListingId(request.listingId());
        entity.setBrokerId(brokerId);
        entity.setInquiryChannel(request.inquiryChannel());
        entity.setInquiryContent(request.inquiryContent());
        entity.setStatus(InquiryStatusEnum.OPEN.name());
        entity.setValidationState(InquiryValidationStateEnum.CONFIRMED.name());
        entity.setCheckedListingVersion(verified.listingVersion());
        entity.setCheckedAt(LocalDateTime.now());
        inquiryDomainService.save(entity);
        receiptStore.insert(actorId, CustomerInquiryCommandReceiptStore.CREATE_OPERATION,
                request.requestId(), requestHash, entity.getId(), "COMPLETED");
        log.info("登记买方房源咨询成功，customerId={}，inquiryId={}，listingId={}",
                customerId, entity.getId(), request.listingId());
        CustomerInquiryResponse response = toResponse(entity);
        registerRecheckAfterCreate(entity.getId());
        return response;
    }

    @Override
    public List<CustomerInquiryResponse> listInquiries(Long customerId) {
        requireCustomer(customerId, "customer.inquiry.read.own", "customer.inquiry.read.all");
        List<CustomerListingInquiryEntity> inquiries = inquiryDomainService.listByCustomer(customerId);
        Map<Long, List<CustomerInquiryComparisonEntity>> comparisonsByInquiry = comparisonDomainService
                .listByInquiryIds(inquiries.stream().map(CustomerListingInquiryEntity::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(CustomerInquiryComparisonEntity::getInquiryId));
        return inquiries.stream()
                .map(inquiry -> toResponse(inquiry,
                        comparisonsByInquiry.getOrDefault(inquiry.getId(), List.of())))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CustomerInquiryResponse updateInquiryStatus(Long inquiryId, String status) {
        if (!InquiryStatusEnum.contains(status)) {
            throw new IllegalArgumentException("咨询状态不合法: " + status);
        }
        CustomerListingInquiryEntity inquiry = inquiryDomainService.getById(inquiryId);
        if (inquiry == null) {
            throw new IllegalArgumentException("咨询不存在: " + inquiryId);
        }
        requireCustomer(inquiry.getCustomerId(), "customer.inquiry.write.own", "customer.inquiry.write.all");
        String target = status.toUpperCase();
        requireTransition(inquiry.getStatus(), target);
        inquiry.setStatus(target);
        inquiryDomainService.updateById(inquiry);
        log.info("更新咨询业务状态成功，inquiryId={}，status={}", inquiryId, target);
        return toResponse(inquiry);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CustomerInquiryComparisonView attachComparisonReport(Long inquiryId, String reportId) {
        if (inquiryId == null || inquiryId <= 0) {
            throw new IllegalArgumentException("咨询 ID 不合法: " + inquiryId);
        }
        if (reportId == null || !REPORT_ID_PATTERN.matcher(reportId).matches()) {
            throw new IllegalArgumentException("对比报告 ID 不合法: " + reportId);
        }
        CustomerListingInquiryEntity inquiry = inquiryDomainService.getById(inquiryId);
        if (inquiry == null) {
            throw new IllegalArgumentException("咨询不存在: " + inquiryId);
        }
        requireCustomer(inquiry.getCustomerId(), "customer.inquiry.write.own", "customer.inquiry.write.all");
        ListingComparisonReportVerifyResponse verified = comparisonReportVerifyClient.verify(reportId);
        if (verified == null || !ListingComparisonReportVerifyResponse.ACCESSIBLE.equals(verified.result())) {
            throw new IllegalArgumentException("对比报告不可关联: " + reportId);
        }
        Optional<CustomerInquiryComparisonEntity> existing = comparisonDomainService.find(inquiryId, reportId);
        if (existing.isPresent()) {
            return toView(existing.get());
        }
        long actorId = AuthzRequestScope.actorId();
        try {
            CustomerInquiryComparisonEntity created = comparisonDomainService.insert(inquiryId, reportId, actorId);
            log.info("咨询关联对比报告成功，inquiryId={}，reportId={}，createdBy={}",
                    inquiryId, reportId, actorId);
            return toView(created);
        } catch (DuplicateKeyException concurrentDuplicate) {
            // 并发重复关联由唯一键 uk_customer_inquiry_comparison 兜底，按幂等语义返回既有关联。
            return toView(comparisonDomainService.find(inquiryId, reportId)
                    .orElseThrow(() -> concurrentDuplicate));
        }
    }

    @Override
    public void recheckAfterCreate(Long inquiryId) {
        if (inquiryId == null || inquiryId <= 0) {
            return;
        }
        CustomerListingInquiryEntity inquiry = inquiryDomainService.getById(inquiryId);
        if (inquiry == null
                || !InquiryValidationStateEnum.CONFIRMED.name().equals(inquiry.getValidationState())) {
            return;
        }
        ListingInquiryVerifyResponse current;
        try {
            current = listingInquiryVerifyClient.verify(inquiry.getListingId());
        } catch (RuntimeException failure) {
            log.warn("咨询提交后复查依赖失败，保留原验证状态 inquiryId={}", inquiryId);
            return;
        }
        if (current == null || !ListingInquiryVerifyResponse.ACCESSIBLE.equals(current.result())) {
            markNeedsRecheck(inquiry, "房源在提交后复查时不可访问");
            return;
        }
        if (current.listingVersion() != null
                && !current.listingVersion().equals(inquiry.getCheckedListingVersion())) {
            markNeedsRecheck(inquiry, "房源版本在提交后发生变化");
        }
    }

    @Override
    public int recheckInquiries() {
        List<CustomerListingInquiryEntity> pending = inquiryDomainService.listByValidationState(
                InquiryValidationStateEnum.NEEDS_RECHECK.name());
        int updated = 0;
        for (CustomerListingInquiryEntity inquiry : pending) {
            ListingInquiryVerifyResponse current;
            try {
                current = listingInquiryVerifyClient.verify(inquiry.getListingId());
            } catch (RuntimeException failure) {
                log.warn("咨询补偿复查依赖失败，跳过 inquiryId={}", inquiry.getId());
                continue;
            }
            if (current == null) {
                continue;
            }
            if (ListingInquiryVerifyResponse.ACCESSIBLE.equals(current.result())) {
                if (current.listingVersion() != null
                        && current.listingVersion().equals(inquiry.getCheckedListingVersion())) {
                    inquiry.setValidationState(InquiryValidationStateEnum.CONFIRMED.name());
                } else {
                    inquiry.setCheckedListingVersion(current.listingVersion());
                }
                inquiry.setCheckedAt(LocalDateTime.now());
                inquiryDomainService.updateById(inquiry);
                updated++;
            } else {
                inquiry.setValidationState(InquiryValidationStateEnum.REJECTED.name());
                inquiry.setCheckedAt(LocalDateTime.now());
                inquiryDomainService.updateById(inquiry);
                updated++;
            }
        }
        return updated;
    }

    private void markNeedsRecheck(CustomerListingInquiryEntity inquiry, String reason) {
        inquiry.setValidationState(InquiryValidationStateEnum.NEEDS_RECHECK.name());
        inquiryDomainService.updateById(inquiry);
        log.warn("咨询标记待复查，inquiryId={}，原因={}", inquiry.getId(), reason);
    }

    /**
     * 创建事务提交后触发复查；无事务同步时（如单测）直接执行，失败不影响已创建记录。
     */
    private void registerRecheckAfterCreate(Long inquiryId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            recheckAfterCreate(inquiryId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    recheckAfterCreate(inquiryId);
                } catch (RuntimeException failure) {
                    log.warn("咨询提交后复查失败，保留当前验证状态 inquiryId={}", inquiryId, failure);
                }
            }
        });
    }

    private void requireCreateRequest(CustomerInquiryCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        if (request.listingId() == null || request.listingId() <= 0) {
            throw new IllegalArgumentException("房源 ID 不合法");
        }
        if (!StringUtils.hasText(request.requestId()) || request.requestId().length() > 128) {
            throw new IllegalArgumentException("requestId 不能为空且不超过 128 字符");
        }
        if (StringUtils.hasText(request.inquiryChannel()) && request.inquiryChannel().length() > 32) {
            throw new IllegalArgumentException("咨询渠道不能超过 32 字符");
        }
    }

    /**
     * 业务状态机：OPEN→FOLLOWING/CLOSED、FOLLOWING→CLOSED；CLOSED 终态，其余拒绝。
     */
    private void requireTransition(String from, String to) {
        boolean allowed = (InquiryStatusEnum.OPEN.name().equals(from)
                && (InquiryStatusEnum.FOLLOWING.name().equals(to) || InquiryStatusEnum.CLOSED.name().equals(to)))
                || (InquiryStatusEnum.FOLLOWING.name().equals(from)
                && InquiryStatusEnum.CLOSED.name().equals(to));
        if (!allowed) {
            throw new IllegalArgumentException("咨询状态不允许从 " + from + " 变更为 " + to);
        }
    }

    private CustomerEntity requireCustomer(Long customerId, String ownOperation, String businessWideOperation) {
        AuthzRequestScope.requireCurrent();
        boolean businessAdmin = AuthzRequestScope.businessAdmin();
        if (businessAdmin) {
            AuthzRequestScope.requireOperation(businessWideOperation);
        } else {
            AuthzRequestScope.requireOperation(ownOperation);
        }
        CustomerEntity customer = customerDomainService.getById(customerId);
        if (customer == null) {
            throw new IllegalArgumentException("客户档案不存在: " + customerId);
        }
        if (!businessAdmin) {
            Long brokerId = AuthzRequestScope.businessIdentity("BROKER");
            if (brokerId == null || !brokerId.equals(customer.getBrokerId())) {
                deny();
            }
        }
        return customer;
    }

    private static void deny() {
        throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
    }

    private CustomerInquiryResponse toResponse(CustomerListingInquiryEntity entity) {
        return toResponse(entity, comparisonDomainService.listByInquiry(entity.getId()));
    }

    private CustomerInquiryResponse toResponse(CustomerListingInquiryEntity entity,
                                               List<CustomerInquiryComparisonEntity> comparisons) {
        return new CustomerInquiryResponse(entity.getId(), entity.getCustomerId(), entity.getListingId(),
                entity.getBrokerId(), entity.getInquiryChannel(), entity.getInquiryContent(),
                entity.getStatus(), entity.getValidationState(), entity.getCheckedListingVersion(),
                entity.getCheckedAt(), comparisons.stream().map(this::toView).toList());
    }

    private CustomerInquiryComparisonView toView(CustomerInquiryComparisonEntity entity) {
        return new CustomerInquiryComparisonView(entity.getReportId(), entity.getCreatedAt());
    }
}
