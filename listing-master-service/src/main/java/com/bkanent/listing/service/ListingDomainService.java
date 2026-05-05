package com.bkanent.listing.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.listing.entity.ListingEntity;

import java.util.List;

public interface ListingDomainService extends IService<ListingEntity> {

    List<ListingEntity> searchByKeyword(String keyword);
}
