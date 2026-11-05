package com.bkanent.listing.mapper;

import com.bkanent.listing.entity.ListingStatusRecordEntity;
import org.apache.ibatis.annotations.Insert;

/**
 * ListingStatusRecordMapper 状态历史数据访问接口。
 * 追加型审计历史：仅提供插入，不提供更新与删除；created_at 由数据库默认值填充。
 */
public interface ListingStatusRecordMapper {

    /**
     * 追加一条状态历史记录。
     */
    @Insert("INSERT INTO listing_status_record (listing_id, from_status, to_status, action, reason, "
            + "operator_user_id, request_id) VALUES (#{listingId}, #{fromStatus}, #{toStatus}, #{action}, "
            + "#{reason}, #{operatorUserId}, #{requestId})")
    int insert(ListingStatusRecordEntity entity);
}
