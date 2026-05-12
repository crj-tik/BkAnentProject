package com.bkanent.marketing.service;

import com.bkanent.marketing.entity.MarketingContentEntity;
import com.bkanent.marketing.model.MarketingContentSearchRequest;

import java.util.List;

/**
 * 营销内容检索服务接口。
 */
public interface MarketingContentSearchService {

    /**
     * 业务方法：indexContent。
     */
    void indexContent(MarketingContentEntity entity);

    /**
     * 业务方法：search。
     */
    List<MarketingContentEntity> search(MarketingContentSearchRequest request);
}
