package com.bkanent.listing.mapper;

import com.bkanent.listing.entity.ListingPriceRecordEntity;
import org.apache.ibatis.annotations.Insert;

/**
 * ListingPriceRecordMapper 价格历史数据访问接口。
 * 追加型审计历史：仅提供插入，不提供更新与删除；created_at 由数据库默认值填充。
 */
public interface ListingPriceRecordMapper {

    /**
     * 追加一条价格历史记录。
     */
    @Insert("INSERT INTO listing_price_record (listing_id, old_price_yuan, new_price_yuan, reason, "
            + "operator_user_id, request_id) VALUES (#{listingId}, #{oldPriceYuan}, #{newPriceYuan}, "
            + "#{reason}, #{operatorUserId}, #{requestId})")
    int insert(ListingPriceRecordEntity entity);
}
