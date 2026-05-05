package com.bkanent.business.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.business.entity.EmployeeKpiStatEntity;

import java.util.List;

public interface KpiStatService extends IService<EmployeeKpiStatEntity> {

    List<EmployeeKpiStatEntity> listByMonth(String month);
}
