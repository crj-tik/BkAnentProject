package com.bkanent.listing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.common.model.ListingKeywordSearchRequest;
import com.bkanent.common.model.ListingKeywordSearchResultDTO;
import com.bkanent.listing.config.ListingSearchProperties;
import com.bkanent.listing.converter.ListingConverter;
import com.bkanent.listing.entity.ListingEntity;
import com.bkanent.listing.mapper.ListingMapper;
import com.bkanent.listing.search.ListingSearchDocument;
import com.bkanent.listing.service.ListingSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.StringQuery;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ListingSearchServiceImpl 服务实现类。
 */
@Service
public class ListingSearchServiceImpl implements ListingSearchService {

    /**
     * 字段：log。
     */
    private static final Logger log = LoggerFactory.getLogger(ListingSearchServiceImpl.class);
    /**
     * 字段：listingSearchProperties。
     */
    private final ListingSearchProperties listingSearchProperties;
    /**
     * 字段：elasticsearchOperationsProvider。
     */
    private final ObjectProvider<ElasticsearchOperations> elasticsearchOperationsProvider;
    /**
     * 字段：listingConverter。
     */
    private final ListingConverter listingConverter;
    /**
     * 字段：listingMapper。
     */
    private final ListingMapper listingMapper;

    /**
     * 构造 ListingSearchServiceImpl 实例。
     */
    public ListingSearchServiceImpl(ListingSearchProperties listingSearchProperties,
                                    ObjectProvider<ElasticsearchOperations> elasticsearchOperationsProvider,
                                    ListingConverter listingConverter,
                                    ListingMapper listingMapper) {
        this.listingSearchProperties = listingSearchProperties;
        this.elasticsearchOperationsProvider = elasticsearchOperationsProvider;
        this.listingConverter = listingConverter;
        this.listingMapper = listingMapper;
    }

    /**
     * 索引listing。
     */
    @Override
    public void indexListing(ListingEntity entity) {
        ElasticsearchOperations operations = elasticsearchOperationsProvider.getIfAvailable();
        if (entity == null
                || !listingSearchProperties.isUseElasticsearch()
                || !listingSearchProperties.isWriteEnabled()
                || operations == null) {
            return;
        }
        try {
            operations.save(listingConverter.toSearchDocument(entity), IndexCoordinates.of(listingSearchProperties.getIndexName()));
        }
        catch (Exception exception) {
            log.warn("房源写入 Elasticsearch 失败，listingId={}，原因={}", entity.getId(), exception.getMessage());
        }
    }

    /**
     * 删除listing。
     */
    @Override
    public void deleteListing(Long listingId) {
        ElasticsearchOperations operations = elasticsearchOperationsProvider.getIfAvailable();
        if (listingId == null
                || !listingSearchProperties.isUseElasticsearch()
                || !listingSearchProperties.isWriteEnabled()
                || operations == null) {
            return;
        }
        try {
            operations.delete(String.valueOf(listingId), IndexCoordinates.of(listingSearchProperties.getIndexName()));
        }
        catch (Exception exception) {
            log.warn("房源删除 Elasticsearch 文档失败，listingId={}，原因={}", listingId, exception.getMessage());
        }
    }

    /**
     * 检索byKeyword。
     */
    @Override
    public List<ListingKeywordSearchResultDTO> searchByKeyword(ListingKeywordSearchRequest request) {
        ElasticsearchOperations operations = elasticsearchOperationsProvider.getIfAvailable();
        if (listingSearchProperties.isUseElasticsearch() && operations != null) {
            try {
                return searchByElasticsearch(operations, request);
            }
            catch (Exception exception) {
                log.warn("房源 Elasticsearch 检索失败，降级使用 MySQL，原因={}", exception.getMessage());
            }
        }
        return searchByMysql(request);
    }

    /**
     * 检索byElasticsearch。
     */
    private List<ListingKeywordSearchResultDTO> searchByElasticsearch(ElasticsearchOperations operations,
                                                                      ListingKeywordSearchRequest request) {
        String queryJson = buildEsQuery(request);
        List<SearchHit<ListingSearchDocument>> hits = operations.search(
                new StringQuery(queryJson),
                ListingSearchDocument.class,
                IndexCoordinates.of(listingSearchProperties.getIndexName())
        ).stream().toList();
        if (hits.isEmpty()) {
            return List.of();
        }
        List<Long> ids = hits.stream()
                .map(SearchHit::getContent)
                .map(ListingSearchDocument::id)
                .toList();
        Map<Long, Double> scoreMap = hits.stream()
                .collect(Collectors.toMap(
                        hit -> hit.getContent().id(),
                        hit -> (double) hit.getScore(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Map<Long, ListingEntity> entityMap = listingMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(ListingEntity::getId, entity -> entity));
        return ids.stream()
                .map(entityMap::get)
                .filter(entity -> entity != null)
                .map(entity -> new ListingKeywordSearchResultDTO(
                        listingConverter.toDto(entity),
                        scoreMap.get(entity.getId())
                ))
                .toList();
    }

    /**
     * 检索byMysql。
     */
    private List<ListingKeywordSearchResultDTO> searchByMysql(ListingKeywordSearchRequest request) {
        LambdaQueryWrapper<ListingEntity> wrapper = new LambdaQueryWrapper<>();
        if (request != null) {
            wrapper.and(StringUtils.hasText(request.keyword()), query -> query
                    .like(ListingEntity::getTitle, request.keyword())
                    .or()
                    .like(ListingEntity::getAddress, request.keyword())
                    .or()
                    .like(ListingEntity::getOwnerName, request.keyword()));
            wrapper.like(StringUtils.hasText(request.region()), ListingEntity::getAddress, request.region());
            wrapper.eq(StringUtils.hasText(request.layout()), ListingEntity::getLayout, request.layout());
            wrapper.ge(request.minArea() != null, ListingEntity::getArea, request.minArea());
            wrapper.le(request.maxArea() != null, ListingEntity::getArea, request.maxArea());
            wrapper.ge(request.minTotalPrice() != null, ListingEntity::getTotalPrice, request.minTotalPrice());
            wrapper.le(request.maxTotalPrice() != null, ListingEntity::getTotalPrice, request.maxTotalPrice());
        }
        wrapper.orderByDesc(ListingEntity::getUpdatedAt)
                .last("limit " + resolveSize(request == null ? null : request.topK()));
        return listingMapper.selectList(wrapper).stream()
                .map(entity -> new ListingKeywordSearchResultDTO(listingConverter.toDto(entity), 0D))
                .toList();
    }

    /**
     * 构建esQuery。
     */
    private String buildEsQuery(ListingKeywordSearchRequest request) {
        Set<String> mustClauses = new LinkedHashSet<>();
        Set<String> filterClauses = new LinkedHashSet<>();
        if (StringUtils.hasText(request.keyword())) {
            mustClauses.add("""
                    {"multi_match":{"query":"%s","fields":["title^4","address^3","layout^2","schoolZone^2","traffic^2","ownerName","decoration","floorLevel"]}}
                    """.formatted(escapeJson(request.keyword())));
        }
        if (StringUtils.hasText(request.region())) {
            filterClauses.add("""
                    {"match_phrase":{"region":"%s"}}
                    """.formatted(escapeJson(request.region())));
        }
        if (StringUtils.hasText(request.layout())) {
            filterClauses.add("""
                    {"term":{"layout.keyword":"%s"}}
                    """.formatted(escapeJson(request.layout())));
        }
        if (request.minArea() != null || request.maxArea() != null) {
            filterClauses.add(buildRangeClause("area", request.minArea() == null ? null : request.minArea().toPlainString(),
                    request.maxArea() == null ? null : request.maxArea().toPlainString()));
        }
        if (request.minTotalPrice() != null || request.maxTotalPrice() != null) {
            filterClauses.add(buildRangeClause("totalPrice",
                    request.minTotalPrice() == null ? null : request.minTotalPrice().toPlainString(),
                    request.maxTotalPrice() == null ? null : request.maxTotalPrice().toPlainString()));
        }

        String must = mustClauses.isEmpty()
                ? "{\"match_all\":{}}"
                : mustClauses.stream().collect(Collectors.joining(","));
        String filter = filterClauses.stream().collect(Collectors.joining(","));
        String filterPart = filter.isBlank() ? "" : "\"filter\":[" + filter + "],";
        return """
                {
                  "query": {
                    "bool": {
                      "must": [%s],
                      %s
                      "minimum_should_match": 0
                    }
                  },
                  "sort": [
                    {"_score": {"order": "desc"}},
                    {"id": {"order": "desc"}}
                  ],
                  "size": %s
                }
                """.formatted(must, filterPart, resolveSize(request.topK()));
    }

    /**
     * 构建rangeClause。
     */
    private String buildRangeClause(String fieldName, String gte, String lte) {
        List<String> parts = new java.util.ArrayList<>();
        if (StringUtils.hasText(gte)) {
            parts.add("\"gte\":" + gte);
        }
        if (StringUtils.hasText(lte)) {
            parts.add("\"lte\":" + lte);
        }
        return """
                {"range":{"%s":{%s}}}
                """.formatted(fieldName, String.join(",", parts));
    }

    /**
     * 解析size。
     */
    private int resolveSize(Integer topK) {
        if (topK == null || topK <= 0) {
            return 20;
        }
        return Math.min(topK, 100);
    }

    /**
     * 转义json。
     */
    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
