package com.bkanent.common.rpc;

import com.bkanent.common.model.MarketingContentDTO;

/**
 * PromotionRpcService 服务接口。
 */

public interface PromotionRpcService {

    /**
     * 业务方法：publish。
     */
    String publish(MarketingContentDTO content);
}

