package com.bkanent.listing.comparison;

import com.bkanent.common.listing.ListingMoney;
import com.bkanent.common.listing.ListingSourceSnapshot;
import com.bkanent.common.model.ListingComparisonFacts;
import com.bkanent.listing.comparison.model.ListingComparisonCard;
import com.bkanent.listing.comparison.model.ListingComparisonMetricWinner;
import com.bkanent.listing.comparison.model.ListingComparisonReportResponse;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 卡片投影：从已存储报告的来源快照确定性投影出 cards 与 metricWinners。
 * 与 rows/metrics 同源（同一 sources、同一规则版本），不引入独立计算；
 * 在服务读取边界调用、从不写入存储，存量旧报告自动获得卡片视图。
 */
final class ListingComparisonCardProjection {

    private ListingComparisonCardProjection() { }

    /** 指标键：与既有 metrics 名称一致；面积最大来自结论的确定性计算。 */
    static final String LOWEST_TOTAL_PRICE = "lowestTotalPriceCny";
    static final String HIGHEST_TOTAL_PRICE = "highestTotalPriceCny";
    static final String LOWEST_UNIT_PRICE = "lowestUnitPriceCnyPerSquareMetre";
    static final String HIGHEST_UNIT_PRICE = "highestUnitPriceCnyPerSquareMetre";
    static final String LARGEST_AREA = "largestAreaSquareMetres";

    static ListingComparisonReportResponse fill(ListingComparisonReportResponse report) {
        List<ListingSourceSnapshot> sources = report.sources();
        List<List<String>> highlights = new ArrayList<>(sources.size());
        for (int i = 0; i < sources.size(); i++) highlights.add(new ArrayList<>());
        List<ListingComparisonMetricWinner> winners = new ArrayList<>();

        collect(sources, ListingComparisonCardProjection::totalPriceOf, LOWEST_TOTAL_PRICE, true, highlights, winners);
        collect(sources, ListingComparisonCardProjection::totalPriceOf, HIGHEST_TOTAL_PRICE, false, highlights, winners);
        collect(sources, ListingComparisonFacts::unitPriceYuan, LOWEST_UNIT_PRICE, true, highlights, winners);
        collect(sources, ListingComparisonFacts::unitPriceYuan, HIGHEST_UNIT_PRICE, false, highlights, winners);
        collect(sources, ListingComparisonCardProjection::areaOf, LARGEST_AREA, false, highlights, winners);

        List<ListingComparisonCard> cards = new ArrayList<>(sources.size());
        for (int i = 0; i < sources.size(); i++) {
            ListingSourceSnapshot source = sources.get(i);
            BigDecimal unitPrice = ListingComparisonFacts.unitPriceYuan(source);
            cards.add(new ListingComparisonCard(
                    source.listingRef(), source.revision(), source.contentHash(),
                    source.facts().title(),
                    source.facts().totalPrice(),
                    source.facts().areaSquareMetres(),
                    unitPrice == null ? null : unitPrice.toPlainString(),
                    source.facts().attributes(),
                    source.facts().unknownFields(),
                    highlights.get(i)));
        }
        return new ListingComparisonReportResponse(report.reportId(), report.contractVersion(),
                report.rulesVersion(), report.createdAt(), report.dataMode(), report.sources(),
                report.rows(), report.metrics(), report.comparisonTableMarkdown(), report.conclusion(),
                report.pdfDownloadPath(), cards, winners);
    }

    /** 单一指标的确定性归属：缺值房源不参与；并列时每个胜出房源都获得徽标与记录。 */
    private static void collect(List<ListingSourceSnapshot> sources,
                                Function<ListingSourceSnapshot, BigDecimal> value, String metric, boolean lowest,
                                List<List<String>> highlights, List<ListingComparisonMetricWinner> winners) {
        BigDecimal best = null;
        for (ListingSourceSnapshot source : sources) {
            BigDecimal current = value.apply(source);
            if (current == null) continue;
            if (best == null || (lowest ? current.compareTo(best) < 0 : current.compareTo(best) > 0)) {
                best = current;
            }
        }
        if (best == null) return;
        for (int i = 0; i < sources.size(); i++) {
            ListingSourceSnapshot source = sources.get(i);
            BigDecimal current = value.apply(source);
            if (current != null && current.compareTo(best) == 0) {
                highlights.get(i).add(metric);
                winners.add(new ListingComparisonMetricWinner(metric, source.listingRef(),
                        best.stripTrailingZeros().toPlainString()));
            }
        }
    }

    private static BigDecimal totalPriceOf(ListingSourceSnapshot source) {
        ListingMoney money = source.facts().totalPrice();
        if (money == null || money.unit() != ListingMoney.Unit.YUAN || !"CNY".equals(money.currency())) return null;
        return new BigDecimal(money.amount());
    }

    private static BigDecimal areaOf(ListingSourceSnapshot source) {
        return source.facts().areaSquareMetres() == null ? null : new BigDecimal(source.facts().areaSquareMetres());
    }
}
