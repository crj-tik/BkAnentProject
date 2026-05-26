package com.bkanent.agent.milvus.listing;

import com.bkanent.agent.model.rag.ListingRagQueryRequest;
import com.bkanent.common.model.ListingDTO;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

/**
 * ListingStructuredFilter 组件。
 */
public final class ListingStructuredFilter {

    /**
     * 处理ListingStructuredFilter。
     */
    private ListingStructuredFilter() {
    }

    /**
     * 判断是否匹配es。
     */
    public static boolean matches(ListingDTO listing, ListingRagQueryRequest request) {
        if (listing == null) {
            return false;
        }
        if (StringUtils.hasText(request.region())
                && !containsIgnoreCase(listing.address(), request.region())) {
            return false;
        }
        if (StringUtils.hasText(request.layout())
                && !request.layout().equalsIgnoreCase(defaultString(listing.layout()))) {
            return false;
        }
        if (!inRange(listing.area(), request.minArea(), request.maxArea())) {
            return false;
        }
        return inRange(listing.totalPrice(), request.minTotalPrice(), request.maxTotalPrice());
    }

    /**
     * 处理inRange。
     */
    private static boolean inRange(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value == null) {
            return min == null && max == null;
        }
        if (min != null && value.compareTo(min) < 0) {
            return false;
        }
        return max == null || value.compareTo(max) <= 0;
    }

    /**
     * 处理containsIgnoreCase。
     */
    private static boolean containsIgnoreCase(String source, String target) {
        return defaultString(source).toLowerCase().contains(defaultString(target).toLowerCase());
    }

    /**
     * 获取默认string。
     */
    private static String defaultString(String value) {
        return value == null ? "" : value.trim();
    }
}
