package com.bkanent.listing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.listing.entity.ListingEntity;
import com.bkanent.listing.mapper.ListingMapper;
import com.bkanent.listing.service.ListingDomainService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ListingDomainServiceImpl extends ServiceImpl<ListingMapper, ListingEntity> implements ListingDomainService {

    @Override
    public List<ListingEntity> searchByKeyword(String keyword) {
        return list(new LambdaQueryWrapper<ListingEntity>()
                .and(keyword != null && !keyword.isBlank(), wrapper -> wrapper
                        .like(ListingEntity::getTitle, keyword)
                        .or()
                        .like(ListingEntity::getAddress, keyword))
                .last("limit 20"));
    }
}
