package com.bkanent.listing.model;

import java.math.BigDecimal;

/**
 * ListingBuyerSearchQuery 买方可见在售房源分页查询条件。
 * 价格条件为 CNY 元（askingPriceYuan 主值；asking 为空的存量行回退 total_price×10000 比较）；
 * cursor 为 base64(lastUpdatedAt,lastId) 游标；limit 默认 20、上限 100。
 * 查询只读，不创建任何咨询或客户档案。
 */
public record ListingBuyerSearchQuery(
        /** 业务属性：districtCode（行政区域编码，精确匹配）。 */
        String districtCode,
        /** 业务属性：communityName（小区名称，模糊匹配）。 */
        String communityName,
        /** 业务属性：layout（户型，精确匹配）。 */
        String layout,
        /** 业务属性：minArea（面积下限，平方米）。 */
        BigDecimal minArea,
        /** 业务属性：maxArea（面积上限，平方米）。 */
        BigDecimal maxArea,
        /** 业务属性：minAskingPriceYuan（挂牌价下限，CNY 元）。 */
        BigDecimal minAskingPriceYuan,
        /** 业务属性：maxAskingPriceYuan（挂牌价上限，CNY 元）。 */
        BigDecimal maxAskingPriceYuan,
        /** 业务属性：cursor（游标，首页为空）。 */
        String cursor,
        /** 业务属性：limit（页大小，默认 20 上限 100）。 */
        Integer limit
) {
}
