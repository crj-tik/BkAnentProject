package com.bkanent.business.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.business.entity.EmployeeKpiStatEntity;

import java.util.List;

/**
 * KPI 统计领域服务接口。
 */
public interface KpiStatService extends IService<EmployeeKpiStatEntity> {

    /**
     * 业务方法：listByMonth。
     */
    List<EmployeeKpiStatEntity> listByMonth(String month);
}
