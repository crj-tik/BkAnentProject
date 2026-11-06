package com.bkanent.listing.comparison.model;

import com.bkanent.common.listing.ListingMoney;
import com.bkanent.common.listing.ListingRef;

import java.util.List;
import java.util.Map;

/**
 * 对比卡片：每套被对比房源一张，事实字段显式单位、未知不补值；
 * highlights 仅来自确定性指标（与 metricWinners 同源）。
 *
 * @param listingRef 稳定房源引用
 * @param revision 本卡基于的房源版本
 * @param contentHash 版本内容哈希
 * @param title 房源标题（快照事实）
 * @param totalPrice 总价（显式币种与单位；null 表示未知）
 * @param areaSquareMetres 面积平方米（null 表示未知）
 * @param unitPriceCnyPerSquareMetre 单价元/平方米（计算值；缺面积或总价时为 null）
 * @param attributes 快照属性（原样，有什么是什么）
 * @param unknownFields 显式未知字段（前端呈现"未知"，不得补值）
 * @param highlights 命中的确定性徽标（指标名，与 metricWinners 同源）
 */
public record ListingComparisonCard(
        ListingRef listingRef,
        String revision,
        String contentHash,
        String title,
        ListingMoney totalPrice,
        String areaSquareMetres,
        String unitPriceCnyPerSquareMetre,
        Map<String, String> attributes,
        List<String> unknownFields,
        List<String> highlights) {
    public ListingComparisonCard {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        unknownFields = unknownFields == null ? List.of() : List.copyOf(unknownFields);
        highlights = highlights == null ? List.of() : List.copyOf(highlights);
    }
}
