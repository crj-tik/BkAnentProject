package com.bkanent.common.rpc;

import com.bkanent.common.model.MarketingContentDTO;

import java.util.List;

public interface MarketingContentRpcService {

    void saveGeneratedContents(List<MarketingContentDTO> contents);
}
