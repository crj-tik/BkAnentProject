package com.bkanent.business.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.business.entity.StoreDashboardSnapshotEntity;

import java.util.List;

/**
 * 门店经营仪表盘领域服务接口。
 */
public interface StoreDashboardSnapshotService extends IService<StoreDashboardSnapshotEntity> {

    /**
     * 业务方法：getLatestByStoreName。
     */
    StoreDashboardSnapshotEntity getLatestByStoreName(String storeName);

    /**
     * 业务方法：listByDateRange。
     */
    List<StoreDashboardSnapshotEntity> listByDateRange(String startDate, String endDate, String regionName);
}
