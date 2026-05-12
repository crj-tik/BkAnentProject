package com.bkanent.customer.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

/**
 * 客户收藏房源实体。
 */
@TableName("customer_favorite_listing")
public class CustomerFavoriteListingEntity extends BaseEntity {

    /**
     * 业务属性：customerId。
     */
    private Long customerId;
    /**
     * 业务属性：listingId。
     */
    private Long listingId;
    /**
     * 业务属性：favoriteSource。
     */
    private String favoriteSource;
    /**
     * 业务属性：remark。
     */
    private String remark;

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public Long getListingId() {
        return listingId;
    }

    public void setListingId(Long listingId) {
        this.listingId = listingId;
    }

    public String getFavoriteSource() {
        return favoriteSource;
    }

    public void setFavoriteSource(String favoriteSource) {
        this.favoriteSource = favoriteSource;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
