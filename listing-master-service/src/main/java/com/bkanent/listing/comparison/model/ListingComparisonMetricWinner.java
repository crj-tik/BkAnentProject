package com.bkanent.listing.comparison.model;

import com.bkanent.common.listing.ListingRef;

/**
 * 跨卡确定性指标归属：哪个房源在哪项指标胜出（值与既有 metrics 同源同格式）。
 * 并列时每个胜出房源各一条记录。
 */
public record ListingComparisonMetricWinner(String metric, ListingRef listingRef, String value) {
}
