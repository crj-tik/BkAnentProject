package com.bkanent.business.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.business.entity.StoreDashboardSnapshotEntity;
import com.bkanent.business.mapper.StoreDashboardSnapshotMapper;
import com.bkanent.business.service.StoreDashboardSnapshotService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 门店经营仪表盘领域服务实现。
 */
@Service
public class StoreDashboardSnapshotServiceImpl
        extends ServiceImpl<StoreDashboardSnapshotMapper, StoreDashboardSnapshotEntity>
        implements StoreDashboardSnapshotService {

    @Override
    public StoreDashboardSnapshotEntity getLatestByStoreName(String storeName) {
        return getOne(new LambdaQueryWrapper<StoreDashboardSnapshotEntity>()
                .eq(StoreDashboardSnapshotEntity::getStoreName, storeName)
                .orderByDesc(StoreDashboardSnapshotEntity::getStatDate)
                .last("limit 1"));
    }

    @Override
    public List<StoreDashboardSnapshotEntity> listByDateRange(String startDate, String endDate, String regionName) {
        return list(new LambdaQueryWrapper<StoreDashboardSnapshotEntity>()
                .ge(StringUtils.hasText(startDate), StoreDashboardSnapshotEntity::getStatDate, startDate)
                .le(StringUtils.hasText(endDate), StoreDashboardSnapshotEntity::getStatDate, endDate)
                .eq(StringUtils.hasText(regionName), StoreDashboardSnapshotEntity::getRegionName, regionName)
                .orderByDesc(StoreDashboardSnapshotEntity::getStatDate)
                .orderByAsc(StoreDashboardSnapshotEntity::getStoreName));
    }
}
