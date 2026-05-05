package com.bkanent.marketing.service;

import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.common.rpc.MarketingContentRpcService;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

@DubboService
public class MarketingContentRpcServiceImpl implements MarketingContentRpcService {

    private final MarketingAssetService marketingAssetService;

    public MarketingContentRpcServiceImpl(MarketingAssetService marketingAssetService) {
        this.marketingAssetService = marketingAssetService;
    }

    @Override
    public void saveGeneratedContents(List<MarketingContentDTO> contents) {
        marketingAssetService.saveContents(contents);
    }
}
