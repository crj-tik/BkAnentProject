package com.bkanent.common.rpc;

import com.bkanent.common.model.MarketingContentDTO;

public interface PromotionRpcService {

    String publish(MarketingContentDTO content);
}
