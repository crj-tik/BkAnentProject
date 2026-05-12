package com.bkanent.marketing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.marketing.config.MarketingSearchProperties;
import com.bkanent.marketing.converter.MarketingContentConverter;
import com.bkanent.marketing.entity.MarketingContentEntity;
import com.bkanent.marketing.mapper.MarketingContentMapper;
import com.bkanent.marketing.model.MarketingContentSearchRequest;
import com.bkanent.marketing.search.MarketingContentDocument;
import com.bkanent.marketing.service.MarketingContentSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.StringQuery;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 营销内容搜索服务实现。
 */
@Service
public class MarketingContentSearchServiceImpl implements MarketingContentSearchService {

    private static final Logger log = LoggerFactory.getLogger(MarketingContentSearchServiceImpl.class);

    private final MarketingSearchProperties marketingSearchProperties;
    private final ObjectProvider<ElasticsearchOperations> elasticsearchOperationsProvider;
    private final MarketingContentConverter marketingContentConverter;
    private final MarketingContentMapper marketingContentMapper;

    public MarketingContentSearchServiceImpl(MarketingSearchProperties marketingSearchProperties,
                                             ObjectProvider<ElasticsearchOperations> elasticsearchOperationsProvider,
                                             MarketingContentConverter marketingContentConverter,
                                             MarketingContentMapper marketingContentMapper) {
        this.marketingSearchProperties = marketingSearchProperties;
        this.elasticsearchOperationsProvider = elasticsearchOperationsProvider;
        this.marketingContentConverter = marketingContentConverter;
        this.marketingContentMapper = marketingContentMapper;
    }

    @Override
    public void indexContent(MarketingContentEntity entity) {
        ElasticsearchOperations operations = elasticsearchOperationsProvider.getIfAvailable();
        if (!marketingSearchProperties.isUseElasticsearch()
                || !marketingSearchProperties.isWriteEnabled()
                || operations == null) {
            return;
        }
        try {
            operations.save(marketingContentConverter.toDocument(entity), IndexCoordinates.of(marketingSearchProperties.getIndexName()));
        } catch (Exception exception) {
            log.warn("营销内容写入 Elasticsearch 失败，contentId={}，原因={}", entity.getId(), exception.getMessage());
        }
    }

    @Override
    public List<MarketingContentEntity> search(MarketingContentSearchRequest request) {
        ElasticsearchOperations operations = elasticsearchOperationsProvider.getIfAvailable();
        if (marketingSearchProperties.isUseElasticsearch() && operations != null) {
            try {
                return searchByElasticsearch(operations, request);
            } catch (Exception exception) {
                log.warn("营销内容 Elasticsearch 检索失败，降级使用 MySQL，原因={}", exception.getMessage());
            }
        }
        return searchByMysql(request);
    }

    private List<MarketingContentEntity> searchByElasticsearch(ElasticsearchOperations operations,
                                                               MarketingContentSearchRequest request) {
        String queryJson = buildEsQuery(request);
        List<Long> ids = operations.search(new StringQuery(queryJson), MarketingContentDocument.class,
                        IndexCoordinates.of(marketingSearchProperties.getIndexName()))
                .stream()
                .map(SearchHit::getContent)
                .map(MarketingContentDocument::id)
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return marketingContentMapper.selectBatchIds(ids).stream()
                .sorted((left, right) -> ids.indexOf(left.getId()) - ids.indexOf(right.getId()))
                .toList();
    }

    private List<MarketingContentEntity> searchByMysql(MarketingContentSearchRequest request) {
        LambdaQueryWrapper<MarketingContentEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(request.listingId() != null, MarketingContentEntity::getListingId, request.listingId());
        queryWrapper.eq(StringUtils.hasText(request.platform()), MarketingContentEntity::getPlatform, request.platform());
        queryWrapper.eq(StringUtils.hasText(request.contentType()), MarketingContentEntity::getContentType, request.contentType());
        queryWrapper.eq(StringUtils.hasText(request.auditStatus()), MarketingContentEntity::getAuditStatus, request.auditStatus());
        queryWrapper.eq(StringUtils.hasText(request.publishStatus()), MarketingContentEntity::getStatus, request.publishStatus());
        if (StringUtils.hasText(request.keyword())) {
            queryWrapper.and(wrapper -> wrapper.like(MarketingContentEntity::getTitle, request.keyword())
                    .or()
                    .like(MarketingContentEntity::getCopywriting, request.keyword()));
        }
        queryWrapper.like(StringUtils.hasText(request.tag()), MarketingContentEntity::getTags, request.tag());
        queryWrapper.orderByDesc(MarketingContentEntity::getUpdatedAt);
        return marketingContentMapper.selectList(queryWrapper);
    }

    private String buildEsQuery(MarketingContentSearchRequest request) {
        Set<String> mustClauses = new LinkedHashSet<>();
        if (request.listingId() != null) {
            mustClauses.add("{\"term\":{\"listingId\":" + request.listingId() + "}}");
        }
        if (StringUtils.hasText(request.platform())) {
            mustClauses.add("{\"term\":{\"platform.keyword\":\"" + escapeJson(request.platform()) + "\"}}");
        }
        if (StringUtils.hasText(request.contentType())) {
            mustClauses.add("{\"term\":{\"contentType.keyword\":\"" + escapeJson(request.contentType()) + "\"}}");
        }
        if (StringUtils.hasText(request.auditStatus())) {
            mustClauses.add("{\"term\":{\"auditStatus.keyword\":\"" + escapeJson(request.auditStatus()) + "\"}}");
        }
        if (StringUtils.hasText(request.publishStatus())) {
            mustClauses.add("{\"term\":{\"publishStatus.keyword\":\"" + escapeJson(request.publishStatus()) + "\"}}");
        }
        if (StringUtils.hasText(request.tag())) {
            mustClauses.add("{\"term\":{\"tags.keyword\":\"" + escapeJson(request.tag()) + "\"}}");
        }
        if (StringUtils.hasText(request.keyword())) {
            mustClauses.add("{\"multi_match\":{\"query\":\"" + escapeJson(request.keyword())
                    + "\",\"fields\":[\"title\",\"copywriting\",\"tags\",\"platformVariant\"]}}");
        }

        String must = mustClauses.isEmpty()
                ? ""
                : mustClauses.stream().collect(Collectors.joining(","));
        return """
                {
                  "query": {
                    "bool": {
                      "must": [%s]
                    }
                  },
                  "sort": [
                    {"_score": {"order": "desc"}},
                    {"id": {"order": "desc"}}
                  ],
                  "size": 50
                }
                """.formatted(must);
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
