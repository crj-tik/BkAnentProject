package com.bkanent.listing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.listing.entity.ListingEntity;
import com.bkanent.listing.mapper.ListingMapper;
import com.bkanent.listing.model.ListingQueryRequest;
import com.bkanent.listing.service.ListingDomainService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * ListingDomainServiceImpl 房源领域服务实现类。
 */
@Service
public class ListingDomainServiceImpl extends ServiceImpl<ListingMapper, ListingEntity> implements ListingDomainService {

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
}
