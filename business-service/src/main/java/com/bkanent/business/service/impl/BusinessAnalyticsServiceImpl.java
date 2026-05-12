package com.bkanent.business.service.impl;

import com.bkanent.business.config.BusinessRankingProperties;
import com.bkanent.business.config.BusinessRedisProperties;
import com.bkanent.business.converter.BusinessConverter;
import com.bkanent.business.entity.EmployeeKpiStatEntity;
import com.bkanent.business.entity.StoreDashboardSnapshotEntity;
import com.bkanent.business.enums.RankingScopeEnum;
import com.bkanent.business.model.BigDataDashboardResponse;
import com.bkanent.business.model.DashboardRegionAggregateResponse;
import com.bkanent.business.model.DashboardStoreAggregateResponse;
import com.bkanent.business.model.EmployeeDailyWorkloadResponse;
import com.bkanent.business.model.KpiAssessmentResponse;
import com.bkanent.business.model.ListingTurnoverReportResponse;
import com.bkanent.business.model.RankingItemResponse;
import com.bkanent.business.model.StoreDashboardResponse;
import com.bkanent.business.service.BusinessAnalyticsService;
import com.bkanent.business.service.EmployeeDailyWorkloadService;
import com.bkanent.business.service.KpiStatService;
import com.bkanent.business.service.ListingTurnoverStatService;
import com.bkanent.business.service.StoreDashboardSnapshotService;
import com.bkanent.common.model.KpiSummaryDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 职能业务分析服务实现。
 */
@Service
public class BusinessAnalyticsServiceImpl implements BusinessAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(BusinessAnalyticsServiceImpl.class);

    private final KpiStatService kpiStatService;
    private final EmployeeDailyWorkloadService employeeDailyWorkloadService;
    private final ListingTurnoverStatService listingTurnoverStatService;
    private final StoreDashboardSnapshotService storeDashboardSnapshotService;
    private final BusinessConverter businessConverter;
    private final BusinessRankingProperties businessRankingProperties;
    private final BusinessRedisProperties businessRedisProperties;
    private final Optional<StringRedisTemplate> stringRedisTemplate;

    public BusinessAnalyticsServiceImpl(KpiStatService kpiStatService,
                                        EmployeeDailyWorkloadService employeeDailyWorkloadService,
                                        ListingTurnoverStatService listingTurnoverStatService,
                                        StoreDashboardSnapshotService storeDashboardSnapshotService,
                                        BusinessConverter businessConverter,
                                        BusinessRankingProperties businessRankingProperties,
                                        BusinessRedisProperties businessRedisProperties,
                                        Optional<StringRedisTemplate> stringRedisTemplate) {
        this.kpiStatService = kpiStatService;
        this.employeeDailyWorkloadService = employeeDailyWorkloadService;
        this.listingTurnoverStatService = listingTurnoverStatService;
        this.storeDashboardSnapshotService = storeDashboardSnapshotService;
        this.businessConverter = businessConverter;
        this.businessRankingProperties = businessRankingProperties;
        this.businessRedisProperties = businessRedisProperties;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public List<KpiSummaryDTO> listMonthlyKpis(String month) {
        return kpiStatService.listByMonth(month).stream()
                .map(businessConverter::toKpiSummary)
                .toList();
    }

    @Override
    public List<EmployeeDailyWorkloadResponse> listDailyWorkloads(String statDate) {
        return employeeDailyWorkloadService.listByDate(statDate).stream()
                .map(businessConverter::toDailyWorkload)
                .toList();
    }

    @Override
    public List<KpiAssessmentResponse> calculateKpiAssessments(String month) {
        return kpiStatService.listByMonth(month).stream()
                .map(this::normalizeAssessment)
                .map(businessConverter::toAssessment)
                .toList();
    }

    @Override
    public List<RankingItemResponse> listRankings(String month, String scope, int topN) {
        if (!RankingScopeEnum.contains(scope)) {
            throw new IllegalArgumentException("排行榜范围不合法: " + scope);
        }
        List<RankingItemResponse> redisRanking = listRankingsFromRedis(month, scope, topN);
        if (!redisRanking.isEmpty()) {
            return redisRanking;
        }
        return listRankingsFromMysql(month, scope, topN);
    }

    @Override
    public List<ListingTurnoverReportResponse> listTurnoverReports(String month) {
        return listingTurnoverStatService.listByMonth(month).stream()
                .map(businessConverter::toTurnoverReport)
                .toList();
    }

    @Override
    public StoreDashboardResponse getStoreDashboard(String storeName) {
        return businessConverter.toDashboard(storeDashboardSnapshotService.getLatestByStoreName(storeName));
    }

    @Override
    public BigDataDashboardResponse getBigDataDashboard(String month,
                                                        String startDate,
                                                        String endDate,
                                                        String regionName,
                                                        int topN) {
        List<StoreDashboardSnapshotEntity> snapshots = storeDashboardSnapshotService.listByDateRange(startDate, endDate, regionName);
        List<DashboardStoreAggregateResponse> storeAggregates = buildStoreAggregates(snapshots, topN);
        List<DashboardRegionAggregateResponse> regionAggregates = buildRegionAggregates(snapshots);
        List<RankingItemResponse> topBrokers = listRankings(month, RankingScopeEnum.PERSONAL.name(), topN);
        List<ListingTurnoverReportResponse> turnoverHighlights = listTurnoverReports(month).stream()
                .filter(report -> !StringUtils.hasText(regionName) || regionName.equals(report.regionName()))
                .limit(topN)
                .toList();
        return new BigDataDashboardResponse(
                month,
                startDate,
                endDate,
                regionName,
                storeAggregates.size(),
                storeAggregates.stream().map(DashboardStoreAggregateResponse::activeListingCount).reduce(0, Integer::sum),
                storeAggregates.stream().map(DashboardStoreAggregateResponse::viewingCount).reduce(0, Integer::sum),
                storeAggregates.stream().map(DashboardStoreAggregateResponse::newCustomerCount).reduce(0, Integer::sum),
                storeAggregates.stream().map(DashboardStoreAggregateResponse::dealCount).reduce(0, Integer::sum),
                storeAggregates.stream().map(DashboardStoreAggregateResponse::performanceAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                calculateAverageSatisfaction(snapshots),
                calculateAverageTurnoverDays(turnoverHighlights),
                storeAggregates,
                regionAggregates,
                topBrokers,
                turnoverHighlights
        );
    }

    @Override
    public void rebuildRanking(String month) {
        if (!businessRankingProperties.isUseRedis() || !businessRedisProperties.isEnabled() || stringRedisTemplate.isEmpty()) {
            return;
        }
        StringRedisTemplate redisTemplate = stringRedisTemplate.get();
        List<EmployeeKpiStatEntity> entities = kpiStatService.listByMonth(month).stream()
                .map(this::normalizeAssessment)
                .toList();
        writePersonalRanking(redisTemplate, month, entities);
        writeGroupRanking(redisTemplate, month, entities, RankingScopeEnum.STORE.name(), EmployeeKpiStatEntity::getStoreName);
        writeGroupRanking(redisTemplate, month, entities, RankingScopeEnum.REGION.name(), EmployeeKpiStatEntity::getRegionName);
        log.info("重建业务排行榜完成，month={}", month);
    }

    private EmployeeKpiStatEntity normalizeAssessment(EmployeeKpiStatEntity entity) {
        if (entity.getClosedDeals() != null && entity.getViewingCount() != null && entity.getViewingCount() > 0) {
            entity.setConversionRate(BigDecimal.valueOf(entity.getClosedDeals())
                    .divide(BigDecimal.valueOf(entity.getViewingCount()), 4, RoundingMode.HALF_UP));
        }
        if (entity.getCompletionRate() == null) {
            entity.setCompletionRate(BigDecimal.ZERO);
        }
        if (entity.getSatisfactionScore() == null) {
            entity.setSatisfactionScore(BigDecimal.ZERO);
        }
        if (entity.getPerformanceAmount() == null) {
            entity.setPerformanceAmount(BigDecimal.ZERO);
        }
        return entity;
    }

    private List<RankingItemResponse> listRankingsFromRedis(String month, String scope, int topN) {
        if (!businessRankingProperties.isUseRedis() || !businessRedisProperties.isEnabled() || stringRedisTemplate.isEmpty()) {
            return List.of();
        }
        try {
            String key = buildRankingKey(month, scope);
            Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> typedTuples =
                    stringRedisTemplate.get().opsForZSet().reverseRangeWithScores(key, 0, topN - 1);
            if (typedTuples == null || typedTuples.isEmpty()) {
                rebuildRanking(month);
                typedTuples = stringRedisTemplate.get().opsForZSet().reverseRangeWithScores(key, 0, topN - 1);
            }
            if (typedTuples == null) {
                return List.of();
            }
            int rank = 1;
            List<RankingItemResponse> responses = new ArrayList<>();
            for (org.springframework.data.redis.core.ZSetOperations.TypedTuple<String> tuple : typedTuples) {
                responses.add(new RankingItemResponse(rank++, scope.toUpperCase(), tuple.getValue(),
                        BigDecimal.valueOf(tuple.getScore() == null ? 0 : tuple.getScore())));
            }
            return responses;
        } catch (Exception exception) {
            log.warn("读取 Redis 排行榜失败，降级使用 MySQL，原因={}", exception.getMessage());
            return List.of();
        }
    }

    private List<RankingItemResponse> listRankingsFromMysql(String month, String scope, int topN) {
        List<EmployeeKpiStatEntity> entities = kpiStatService.listByMonth(month).stream()
                .map(this::normalizeAssessment)
                .toList();
        if (RankingScopeEnum.PERSONAL.name().equalsIgnoreCase(scope)) {
            return buildPersonalRanking(entities, topN);
        }
        if (RankingScopeEnum.STORE.name().equalsIgnoreCase(scope)) {
            return buildGroupRanking(entities, scope, topN, EmployeeKpiStatEntity::getStoreName);
        }
        return buildGroupRanking(entities, scope, topN, EmployeeKpiStatEntity::getRegionName);
    }

    private List<RankingItemResponse> buildPersonalRanking(List<EmployeeKpiStatEntity> entities, int topN) {
        List<EmployeeKpiStatEntity> sortedEntities = entities.stream()
                .sorted(Comparator.comparing(EmployeeKpiStatEntity::getPerformanceAmount).reversed()
                        .thenComparing(EmployeeKpiStatEntity::getCompletionRate, Comparator.reverseOrder()))
                .toList();
        List<RankingItemResponse> responses = new ArrayList<>();
        for (int index = 0; index < sortedEntities.size() && index < topN; index++) {
            EmployeeKpiStatEntity entity = sortedEntities.get(index);
            responses.add(new RankingItemResponse(index + 1, RankingScopeEnum.PERSONAL.name(),
                    entity.getEmployeeName(), entity.getPerformanceAmount()));
        }
        return responses;
    }

    private List<RankingItemResponse> buildGroupRanking(List<EmployeeKpiStatEntity> entities,
                                                        String scope,
                                                        int topN,
                                                        java.util.function.Function<EmployeeKpiStatEntity, String> groupFunction) {
        Map<String, BigDecimal> scoreMap = entities.stream()
                .collect(Collectors.groupingBy(groupFunction,
                        Collectors.mapping(EmployeeKpiStatEntity::getPerformanceAmount,
                                Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))));
        List<Map.Entry<String, BigDecimal>> entries = scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(topN)
                .toList();
        List<RankingItemResponse> responses = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            Map.Entry<String, BigDecimal> entry = entries.get(index);
            responses.add(new RankingItemResponse(index + 1, scope.toUpperCase(), entry.getKey(), entry.getValue()));
        }
        return responses;
    }

    private void writePersonalRanking(StringRedisTemplate redisTemplate, String month, List<EmployeeKpiStatEntity> entities) {
        String key = buildRankingKey(month, RankingScopeEnum.PERSONAL.name());
        redisTemplate.delete(key);
        entities.forEach(entity -> redisTemplate.opsForZSet().add(key, entity.getEmployeeName(), entity.getPerformanceAmount().doubleValue()));
        redisTemplate.expire(key, businessRedisProperties.getRankingTtlHours(), TimeUnit.HOURS);
    }

    private void writeGroupRanking(StringRedisTemplate redisTemplate,
                                   String month,
                                   List<EmployeeKpiStatEntity> entities,
                                   String scope,
                                   java.util.function.Function<EmployeeKpiStatEntity, String> groupFunction) {
        String key = buildRankingKey(month, scope);
        redisTemplate.delete(key);
        Map<String, BigDecimal> groupScoreMap = entities.stream()
                .filter(entity -> StringUtils.hasText(groupFunction.apply(entity)))
                .collect(Collectors.groupingBy(groupFunction,
                        Collectors.mapping(EmployeeKpiStatEntity::getPerformanceAmount,
                                Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))));
        groupScoreMap.forEach((name, score) -> redisTemplate.opsForZSet().add(key, name, score.doubleValue()));
        redisTemplate.expire(key, businessRedisProperties.getRankingTtlHours(), TimeUnit.HOURS);
    }

    private String buildRankingKey(String month, String scope) {
        return businessRedisProperties.getRankingKeyPrefix() + month + ":" + scope.toUpperCase();
    }

    private List<DashboardStoreAggregateResponse> buildStoreAggregates(List<StoreDashboardSnapshotEntity> snapshots, int topN) {
        Map<String, List<StoreDashboardSnapshotEntity>> groupedStoreMap = snapshots.stream()
                .collect(Collectors.groupingBy(entity -> entity.getStoreName() + "@" + entity.getRegionName(),
                        LinkedHashMap::new, Collectors.toList()));
        return groupedStoreMap.values().stream()
                .map(this::toStoreAggregate)
                .sorted(Comparator.comparing(DashboardStoreAggregateResponse::performanceAmount).reversed()
                        .thenComparing(DashboardStoreAggregateResponse::dealCount, Comparator.reverseOrder()))
                .limit(topN)
                .toList();
    }

    private DashboardStoreAggregateResponse toStoreAggregate(List<StoreDashboardSnapshotEntity> snapshots) {
        StoreDashboardSnapshotEntity latestSnapshot = snapshots.stream()
                .max(Comparator.comparing(StoreDashboardSnapshotEntity::getStatDate)
                        .thenComparing(StoreDashboardSnapshotEntity::getId))
                .orElse(null);
        String storeName = latestSnapshot == null ? null : latestSnapshot.getStoreName();
        String regionName = latestSnapshot == null ? null : latestSnapshot.getRegionName();
        int activeListingCount = latestSnapshot == null || latestSnapshot.getActiveListingCount() == null
                ? 0 : latestSnapshot.getActiveListingCount();
        int viewingCount = snapshots.stream()
                .map(StoreDashboardSnapshotEntity::getTodayViewingCount)
                .filter(value -> value != null)
                .reduce(0, Integer::sum);
        int newCustomerCount = snapshots.stream()
                .map(StoreDashboardSnapshotEntity::getTodayNewCustomerCount)
                .filter(value -> value != null)
                .reduce(0, Integer::sum);
        int dealCount = snapshots.stream()
                .map(StoreDashboardSnapshotEntity::getTodayDealCount)
                .filter(value -> value != null)
                .reduce(0, Integer::sum);
        BigDecimal performanceAmount = snapshots.stream()
                .map(StoreDashboardSnapshotEntity::getTodayPerformanceAmount)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSatisfaction = snapshots.stream()
                .map(StoreDashboardSnapshotEntity::getSatisfactionScore)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long satisfactionCount = snapshots.stream()
                .map(StoreDashboardSnapshotEntity::getSatisfactionScore)
                .filter(value -> value != null)
                .count();
        BigDecimal satisfactionScore = satisfactionCount == 0 ? BigDecimal.ZERO
                : totalSatisfaction.divide(BigDecimal.valueOf(satisfactionCount), 2, RoundingMode.HALF_UP);
        return new DashboardStoreAggregateResponse(
                storeName,
                regionName,
                activeListingCount,
                viewingCount,
                newCustomerCount,
                dealCount,
                performanceAmount,
                satisfactionScore
        );
    }

    private List<DashboardRegionAggregateResponse> buildRegionAggregates(List<StoreDashboardSnapshotEntity> snapshots) {
        return snapshots.stream()
                .filter(snapshot -> StringUtils.hasText(snapshot.getRegionName()))
                .collect(Collectors.groupingBy(StoreDashboardSnapshotEntity::getRegionName, LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .map(entry -> toRegionAggregate(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(DashboardRegionAggregateResponse::performanceAmount).reversed()
                        .thenComparing(DashboardRegionAggregateResponse::dealCount, Comparator.reverseOrder()))
                .toList();
    }

    private DashboardRegionAggregateResponse toRegionAggregate(String regionName, List<StoreDashboardSnapshotEntity> snapshots) {
        int storeCount = (int) snapshots.stream()
                .map(StoreDashboardSnapshotEntity::getStoreName)
                .filter(StringUtils::hasText)
                .distinct()
                .count();
        int activeListingCount = snapshots.stream()
                .collect(Collectors.toMap(StoreDashboardSnapshotEntity::getStoreName, entity -> entity,
                        (left, right) -> left.getStatDate().compareTo(right.getStatDate()) >= 0 ? left : right))
                .values().stream()
                .map(StoreDashboardSnapshotEntity::getActiveListingCount)
                .filter(value -> value != null)
                .reduce(0, Integer::sum);
        int viewingCount = snapshots.stream()
                .map(StoreDashboardSnapshotEntity::getTodayViewingCount)
                .filter(value -> value != null)
                .reduce(0, Integer::sum);
        int newCustomerCount = snapshots.stream()
                .map(StoreDashboardSnapshotEntity::getTodayNewCustomerCount)
                .filter(value -> value != null)
                .reduce(0, Integer::sum);
        int dealCount = snapshots.stream()
                .map(StoreDashboardSnapshotEntity::getTodayDealCount)
                .filter(value -> value != null)
                .reduce(0, Integer::sum);
        BigDecimal performanceAmount = snapshots.stream()
                .map(StoreDashboardSnapshotEntity::getTodayPerformanceAmount)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new DashboardRegionAggregateResponse(
                regionName,
                storeCount,
                activeListingCount,
                viewingCount,
                newCustomerCount,
                dealCount,
                performanceAmount,
                calculateAverageSatisfaction(snapshots)
        );
    }

    private BigDecimal calculateAverageSatisfaction(List<StoreDashboardSnapshotEntity> snapshots) {
        BigDecimal totalScore = snapshots.stream()
                .map(StoreDashboardSnapshotEntity::getSatisfactionScore)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long count = snapshots.stream()
                .map(StoreDashboardSnapshotEntity::getSatisfactionScore)
                .filter(value -> value != null)
                .count();
        return count == 0 ? BigDecimal.ZERO
                : totalScore.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateAverageTurnoverDays(List<ListingTurnoverReportResponse> turnoverReports) {
        List<Integer> turnoverDays = turnoverReports.stream()
                .map(ListingTurnoverReportResponse::totalTurnoverDays)
                .filter(value -> value != null)
                .toList();
        if (turnoverDays.isEmpty()) {
            return BigDecimal.ZERO;
        }
        int totalDays = turnoverDays.stream().reduce(0, Integer::sum);
        return BigDecimal.valueOf(totalDays)
                .divide(BigDecimal.valueOf(turnoverDays.size()), 2, RoundingMode.HALF_UP);
    }
}
