package com.bkanent.promotion.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.promotion.entity.BrandAssetEntity;

import java.util.List;

/**
 * 品牌素材领域服务接口。
 */
public interface BrandAssetDomainService extends IService<BrandAssetEntity> {

    /**
     * 业务方法：searchAssets。
     */
    List<BrandAssetEntity> searchAssets(String assetType, String keyword);
}
