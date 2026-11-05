package com.bkanent.listing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.listing.entity.ListingEntity;
import com.bkanent.listing.enums.ListingStatusEnum;
import com.bkanent.listing.mapper.ListingMapper;
import com.bkanent.listing.model.ListingBuyerSearchQuery;
import com.bkanent.listing.model.ListingPageCursor;
import com.bkanent.listing.model.ListingQueryRequest;
import com.bkanent.listing.service.ListingDomainService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * ListingDomainServiceImpl 房源领域服务实现类。
 */
@Service
public class ListingDomainServiceImpl extends ServiceImpl<ListingMapper, ListingEntity> implements ListingDomainService {

    /**
     * 常量：DEFAULT_PAGE_LIMIT（默认页大小）。
     */
    private static final int DEFAULT_PAGE_LIMIT = 20;
    /**
     * 常量：MAX_PAGE_LIMIT（页大小上限）。
     */
    private static final int MAX_PAGE_LIMIT = 100;
    /**
     * 常量：PAGE_FETCH_LIMIT_SQL（预取上限 = MAX_PAGE_LIMIT + 1，用于判断是否有下一页；
     * 常量字符串避免拼接 SQL）。
     */
    private static final String PAGE_FETCH_LIMIT_SQL = "LIMIT 101";

    @Override
    public List<ListingEntity> searchByKeyword(String keyword) {
        return list(new LambdaQueryWrapper<ListingEntity>()
                .and(StringUtils.hasText(keyword), wrapper -> wrapper
                        .like(ListingEntity::getTitle, keyword)
                        .or()
                        .like(ListingEntity::getAddress, keyword))
                .last("limit 20"));
    }

    @Override
    public List<ListingEntity> search(ListingQueryRequest request) {
        LambdaQueryWrapper<ListingEntity> wrapper = new LambdaQueryWrapper<>();
        if (request != null) {
            wrapper.and(StringUtils.hasText(request.keyword()), query -> query
                    .like(ListingEntity::getTitle, request.keyword())
                    .or()
                    .like(ListingEntity::getAddress, request.keyword())
                    .or()
                    .like(ListingEntity::getOwnerName, request.keyword()));
            wrapper.eq(StringUtils.hasText(request.status()), ListingEntity::getStatus, request.status());
            wrapper.eq(request.brokerId() != null, ListingEntity::getBrokerId, request.brokerId());
            wrapper.ge(request.minArea() != null, ListingEntity::getArea, request.minArea());
            wrapper.le(request.maxArea() != null, ListingEntity::getArea, request.maxArea());
            wrapper.ge(request.minTotalPrice() != null, ListingEntity::getTotalPrice, request.minTotalPrice());
            wrapper.le(request.maxTotalPrice() != null, ListingEntity::getTotalPrice, request.maxTotalPrice());
            wrapper.eq(Boolean.TRUE.equals(request.verifiedOnly()), ListingEntity::getVerificationStatus, "VERIFIED");
        }
        wrapper.orderByDesc(ListingEntity::getUpdatedAt).last("limit 50");
        return list(wrapper);
    }

    /**
     * 买方可见在售分页查询：键集游标 (updated_at, id) 保证同条件翻页不重不漏。
     */
    @Override
    public ListingOnSalePage searchOnSalePage(ListingBuyerSearchQuery query) {
        ListingBuyerSearchQuery effective = query == null
                ? new ListingBuyerSearchQuery(null, null, null, null, null, null, null, null, null)
                : query;
        int limit = normalizeLimit(effective.limit());
        LambdaQueryWrapper<ListingEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ListingEntity::getStatus, ListingStatusEnum.ON_SALE.name());
        wrapper.eq(StringUtils.hasText(effective.districtCode()),
                ListingEntity::getDistrictCode, effective.districtCode());
        wrapper.like(StringUtils.hasText(effective.communityName()),
                ListingEntity::getCommunityName, effective.communityName());
        wrapper.eq(StringUtils.hasText(effective.layout()), ListingEntity::getLayout, effective.layout());
        wrapper.ge(effective.minArea() != null, ListingEntity::getArea, effective.minArea());
        wrapper.le(effective.maxArea() != null, ListingEntity::getArea, effective.maxArea());
        appendPriceFilter(wrapper, effective.minAskingPriceYuan(), effective.maxAskingPriceYuan());
        ListingPageCursor cursor = ListingPageCursor.decode(effective.cursor());
        if (cursor != null) {
            wrapper.and(condition -> condition
                    .lt(ListingEntity::getUpdatedAt, cursor.lastUpdatedAt())
                    .or(tie -> tie.eq(ListingEntity::getUpdatedAt, cursor.lastUpdatedAt())
                            .lt(ListingEntity::getId, cursor.lastId())));
        }
        wrapper.orderByDesc(ListingEntity::getUpdatedAt).orderByDesc(ListingEntity::getId);
        wrapper.last(PAGE_FETCH_LIMIT_SQL);
        List<ListingEntity> rows = list(wrapper);
        String nextCursor = null;
        if (rows.size() > limit) {
            rows = new ArrayList<>(rows.subList(0, limit));
            ListingEntity last = rows.get(rows.size() - 1);
            nextCursor = new ListingPageCursor(last.getUpdatedAt(), last.getId()).encode();
        }
        return new ListingOnSalePage(List.copyOf(rows), nextCursor);
    }

    /**
     * 挂牌价过滤：asking_price_yuan 为主值；asking 为空的存量行回退 total_price×10000 比较。
     */
    private static void appendPriceFilter(LambdaQueryWrapper<ListingEntity> wrapper,
                                          BigDecimal minPriceYuan, BigDecimal maxPriceYuan) {
        if (minPriceYuan == null && maxPriceYuan == null) {
            return;
        }
        wrapper.and(price -> price
                .and(asking -> asking.isNotNull(ListingEntity::getAskingPriceYuan)
                        .ge(minPriceYuan != null, ListingEntity::getAskingPriceYuan, minPriceYuan)
                        .le(maxPriceYuan != null, ListingEntity::getAskingPriceYuan, maxPriceYuan))
                .or(fallback -> fallback.isNull(ListingEntity::getAskingPriceYuan)
                        .apply(minPriceYuan != null, "total_price * 10000 >= {0}", minPriceYuan)
                        .apply(maxPriceYuan != null, "total_price * 10000 <= {0}", maxPriceYuan)));
    }

    /**
     * 页大小归一化：空或非法取默认 20，上限 100。
     */
    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_PAGE_LIMIT;
        }
        return Math.min(limit, MAX_PAGE_LIMIT);
    }
}
