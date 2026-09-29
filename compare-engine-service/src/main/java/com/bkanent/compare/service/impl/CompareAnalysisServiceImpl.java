package com.bkanent.compare.service.impl;

import com.bkanent.common.model.CompareReportDTO;
import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.rpc.ListingMasterRpcService;
import com.bkanent.compare.config.CompareAgentProperties;
import com.bkanent.compare.model.CompareColumnResponse;
import com.bkanent.compare.model.CompareMetricResponse;
import com.bkanent.compare.model.CompareReportResponse;
import com.bkanent.compare.model.CompareRowResponse;
import com.bkanent.compare.service.CompareAnalysisService;
import com.bkanent.compare.service.CompareReportCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.DefaultChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 房源对比分析服务实现。
 */
@Service
public class CompareAnalysisServiceImpl implements CompareAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(CompareAnalysisServiceImpl.class);

    private static final String AI_CONCLUSION_SYSTEM_PROMPT = """
            你是房产对比分析专家。仅基于给定的对比指标生成一段简明的中文对比结论，
            指出最具性价比与面积优势的选项并给出筛选建议。严禁编造给定数据之外的事实；
            数据不足时直接说明无法得出结论。只输出结论正文。
            """;

    private final CompareReportCacheService compareReportCacheService;
    private final ListingMasterRpcService listingMasterRpcService;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final CompareAgentProperties properties;

    public CompareAnalysisServiceImpl(CompareReportCacheService compareReportCacheService,
                                      ObjectProvider<ListingMasterRpcService> listingMasterRpcServiceProvider,
                                      ObjectProvider<ChatModel> chatModelProvider,
                                      CompareAgentProperties properties) {
        this.compareReportCacheService = compareReportCacheService;
        this.listingMasterRpcService = listingMasterRpcServiceProvider.getIfAvailable();
        this.chatModelProvider = chatModelProvider;
        this.properties = properties;
    }

    @Override
    public CompareReportResponse generateCompareReport(List<Long> listingIds, boolean includeAiConclusion) {
        if (listingIds == null || listingIds.isEmpty()) {
            return new CompareReportResponse(
                    List.of(),
                    buildColumns(),
                    List.of(),
                    List.of(),
                    "| 房源 | 面积 | 总价 |\n| --- | --- | --- |",
                    "未传入需要对比的房源。",
                    null,
                    null,
                    null
            );
        }

        String cacheKey = buildCacheKey(listingIds, includeAiConclusion);
        return compareReportCacheService.findByCacheKey(cacheKey)
                .orElseGet(() -> compareReportCacheService.save(cacheKey, buildFreshReport(listingIds, includeAiConclusion)));
    }

    @Override
    public CompareReportDTO generateRpcCompareReport(List<Long> listingIds) {
        CompareReportResponse response = generateCompareReport(listingIds, true);
        return new CompareReportDTO(response.listings(), response.comparisonTableMarkdown(), response.aiConclusion());
    }

    @Override
    public CompareReportResponse getSharedReport(String shareCode) {
        return compareReportCacheService.getByShareCode(shareCode);
    }

    @Override
    public Resource loadPdfResource(String shareCode) {
        return compareReportCacheService.loadPdfResource(shareCode);
    }

    private CompareReportResponse buildFreshReport(List<Long> listingIds, boolean includeAiConclusion) {
        List<ListingDTO> listings = loadListings(listingIds);
        List<CompareRowResponse> rows = listings.stream().map(this::toCompareRow).toList();
        String markdown = buildMarkdownTable(rows);
        String aiConclusion = includeAiConclusion ? generateAiConclusion(listings) : "已关闭 AI 分析。";
        return new CompareReportResponse(
                listings,
                buildColumns(),
                rows,
                buildMetrics(listings),
                markdown,
                aiConclusion,
                null,
                null,
                null
        );
    }

    private List<ListingDTO> loadListings(List<Long> listingIds) {
        if (listingMasterRpcService == null) {
            return List.of();
        }
        return listingIds.stream()
                .distinct()
                .map(listingMasterRpcService::getListingById)
                .filter(item -> item != null)
                .toList();
    }

    private List<CompareColumnResponse> buildColumns() {
        return List.of(
                new CompareColumnResponse("area", "面积"),
                new CompareColumnResponse("unitPrice", "单价"),
                new CompareColumnResponse("floorLevel", "楼层"),
                new CompareColumnResponse("decoration", "装修"),
                new CompareColumnResponse("schoolZone", "学区"),
                new CompareColumnResponse("traffic", "交通"),
                new CompareColumnResponse("layout", "户型"),
                new CompareColumnResponse("verificationStatus", "核验状态"),
                new CompareColumnResponse("status", "状态")
        );
    }

    private CompareRowResponse toCompareRow(ListingDTO listing) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("area", decimalText(listing.area()));
        values.put("unitPrice", calculateUnitPrice(listing));
        values.put("floorLevel", safeText(listing.floorLevel()));
        values.put("decoration", safeText(listing.decoration()));
        values.put("schoolZone", safeText(listing.schoolZone()));
        values.put("traffic", safeText(listing.traffic()));
        values.put("layout", safeText(listing.layout()));
        values.put("verificationStatus", safeText(listing.verificationStatus()));
        values.put("status", safeText(listing.status()));
        return new CompareRowResponse(listing.id(), safeText(listing.title()), values);
    }

    private String buildMarkdownTable(List<CompareRowResponse> rows) {
        StringBuilder builder = new StringBuilder();
        builder.append("| 房源 | 面积 | 单价 | 楼层 | 装修 | 学区 | 交通 | 户型 | 核验状态 | 状态 |\n");
        builder.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (CompareRowResponse row : rows) {
            builder.append("| ")
                    .append(row.listingTitle()).append(" | ")
                    .append(row.values().get("area")).append(" | ")
                    .append(row.values().get("unitPrice")).append(" | ")
                    .append(row.values().get("floorLevel")).append(" | ")
                    .append(row.values().get("decoration")).append(" | ")
                    .append(row.values().get("schoolZone")).append(" | ")
                    .append(row.values().get("traffic")).append(" | ")
                    .append(row.values().get("layout")).append(" | ")
                    .append(row.values().get("verificationStatus")).append(" | ")
                    .append(row.values().get("status")).append(" |\n");
        }
        return builder.toString();
    }

    private List<CompareMetricResponse> buildMetrics(List<ListingDTO> listings) {
        BigDecimal minTotalPrice = listings.stream()
                .map(ListingDTO::totalPrice)
                .filter(value -> value != null)
                .reduce(this::minDecimal)
                .orElse(BigDecimal.ZERO);
        BigDecimal maxTotalPrice = listings.stream()
                .map(ListingDTO::totalPrice)
                .filter(value -> value != null)
                .reduce(this::maxDecimal)
                .orElse(BigDecimal.ZERO);
        BigDecimal minUnitPrice = listings.stream()
                .map(this::unitPriceDecimal)
                .reduce(this::minDecimal)
                .orElse(BigDecimal.ZERO);
        BigDecimal maxUnitPrice = listings.stream()
                .map(this::unitPriceDecimal)
                .reduce(this::maxDecimal)
                .orElse(BigDecimal.ZERO);
        return List.of(
                new CompareMetricResponse("最低总价", minTotalPrice.toPlainString()),
                new CompareMetricResponse("最高总价", maxTotalPrice.toPlainString()),
                new CompareMetricResponse("最低单价", minUnitPrice.toPlainString()),
                new CompareMetricResponse("最高单价", maxUnitPrice.toPlainString())
        );
    }

    private String generateAiConclusion(List<ListingDTO> listings) {
        if (listings == null || listings.isEmpty()) {
            return "未获取到可用房源数据，无法生成对比结论。";
        }
        if (!hasComparableMetrics(listings)) {
            return "对比指标不足（缺少总价与面积数据），无法生成对比结论。";
        }
        if (properties.isAiConclusionEnabled()) {
            ChatModel chatModel = chatModelProvider.getIfAvailable();
            if (chatModel != null) {
                try {
                    long start = System.currentTimeMillis();
                    String conclusion = chatModel.call(new Prompt(
                            List.of(new SystemMessage(AI_CONCLUSION_SYSTEM_PROMPT),
                                    new UserMessage(buildMetricsSummary(listings))),
                            conclusionChatOptions()))
                            .getResult().getOutput().getText();
                    if (conclusion != null && !conclusion.isBlank()) {
                        log.info("AI compare conclusion generated via LLM in {} ms",
                                System.currentTimeMillis() - start);
                        return conclusion.strip();
                    }
                    log.warn("LLM returned empty compare conclusion, falling back to template");
                } catch (Exception e) {
                    log.warn("LLM compare conclusion failed, falling back to template: {}", e.getMessage());
                }
            }
        }
        return templateConclusion(listings);
    }

    private boolean hasComparableMetrics(List<ListingDTO> listings) {
        return listings.stream().anyMatch(item -> item != null && item.totalPrice() != null)
                || listings.stream().anyMatch(item -> item != null && item.area() != null);
    }

    private String buildMetricsSummary(List<ListingDTO> listings) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("共对比 ").append(listings.size()).append(" 套房源，指标如下：\n");
        for (ListingDTO listing : listings) {
            if (listing == null) {
                continue;
            }
            sb.append("- ").append(safeText(listing.title()))
                    .append("：面积 ").append(decimalText(listing.area()))
                    .append("㎡，总价 ").append(listing.totalPrice() == null ? "缺失" : listing.totalPrice().toPlainString() + "万")
                    .append("，单价 ").append(calculateUnitPrice(listing))
                    .append("元/㎡，装修 ").append(safeText(listing.decoration()))
                    .append("，学区 ").append(safeText(listing.schoolZone()))
                    .append("，交通 ").append(safeText(listing.traffic()))
                    .append('\n');
        }
        return sb.toString();
    }

    private ChatOptions conclusionChatOptions() {
        DefaultChatOptions options = new DefaultChatOptions();
        options.setModel(properties.getModel());
        options.setTemperature(properties.getTemperature());
        options.setMaxTokens(properties.getMaxTokens());
        return options;
    }

    private String templateConclusion(List<ListingDTO> listings) {
        ListingDTO cheapestListing = listings.stream()
                .filter(item -> item != null && item.totalPrice() != null)
                .reduce((left, right) -> left.totalPrice().compareTo(right.totalPrice()) <= 0 ? left : right)
                .orElse(listings.get(0));
        ListingDTO largestListing = listings.stream()
                .filter(item -> item != null && item.area() != null)
                .reduce((left, right) -> left.area().compareTo(right.area()) >= 0 ? left : right)
                .orElse(listings.get(0));
        return "共对比 " + listings.size() + " 套房源。总价更低的房源是「"
                + safeText(cheapestListing.title())
                + "」，面积更大的房源是「"
                + safeText(largestListing.title())
                + "」。建议继续结合总价、面积、学区与交通进行筛选。";
    }

    private String buildCacheKey(List<Long> listingIds, boolean includeAiConclusion) {
        return listingIds.stream()
                .distinct()
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(","))
                + "|ai=" + includeAiConclusion;
    }

    private BigDecimal unitPriceDecimal(ListingDTO listing) {
        if (listing.totalPrice() == null || listing.area() == null || BigDecimal.ZERO.compareTo(listing.area()) == 0) {
            return BigDecimal.ZERO;
        }
        return listing.totalPrice().divide(listing.area(), 2, RoundingMode.HALF_UP);
    }

    private String calculateUnitPrice(ListingDTO listing) {
        return unitPriceDecimal(listing).toPlainString();
    }

    private String decimalText(BigDecimal value) {
        return value == null ? "0" : value.toPlainString();
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? "待补充" : value;
    }

    private BigDecimal minDecimal(BigDecimal left, BigDecimal right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private BigDecimal maxDecimal(BigDecimal left, BigDecimal right) {
        return left.compareTo(right) >= 0 ? left : right;
    }
}
