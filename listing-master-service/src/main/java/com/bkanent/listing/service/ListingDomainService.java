package com.bkanent.listing.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.listing.entity.ListingEntity;
import com.bkanent.listing.model.ListingBuyerSearchQuery;
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

    /**
     * 业务方法：searchOnSalePage（买方可见在售房源分页查询）。
     * 条件 status=ON_SALE、deleted=0 及区域/小区/户型/面积/挂牌价过滤
     * （asking 为空的存量行回退 total_price×10000 比较）；排序 updated_at DESC, id DESC；
     * 游标分页（base64(lastUpdatedAt,lastId)），limit 默认 20 上限 100。
     */
    ListingOnSalePage searchOnSalePage(ListingBuyerSearchQuery query);

    /**
     * 在售分页结果：当前页行与下一页游标（null 表示末页）。
     */
    record ListingOnSalePage(List<ListingEntity> rows, String nextCursor) {
    }
}
