package com.bkanent.listing.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.listing.entity.ListingEntity;
import com.bkanent.listing.model.ListingQueryRequest;

import java.util.List;

/**
 * ListingDomainService 房源领域服务接口。
 */
public interface ListingDomainService extends IService<ListingEntity> {

    /**
     * 业务方法：searchByKeyword。
     */
    List<ListingEntity> searchByKeyword(String keyword);

    /**
     * 业务方法：search。
     */
    List<ListingEntity> search(ListingQueryRequest request);
}
