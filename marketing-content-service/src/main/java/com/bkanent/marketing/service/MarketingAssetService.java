package com.bkanent.marketing.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.marketing.entity.MarketingContentEntity;

import java.util.List;

public interface MarketingAssetService extends IService<MarketingContentEntity> {

    void saveContents(List<MarketingContentDTO> contents);

    List<MarketingContentDTO> listByListingId(Long listingId);
}
