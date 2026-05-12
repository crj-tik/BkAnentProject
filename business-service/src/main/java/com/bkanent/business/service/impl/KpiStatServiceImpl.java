package com.bkanent.business.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.business.entity.EmployeeKpiStatEntity;
import com.bkanent.business.mapper.EmployeeKpiStatMapper;
import com.bkanent.business.service.KpiStatService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * KPI 统计领域服务实现。
 */
@Service
public class KpiStatServiceImpl extends ServiceImpl<EmployeeKpiStatMapper, EmployeeKpiStatEntity> implements KpiStatService {

    @Override
    public List<EmployeeKpiStatEntity> listByMonth(String month) {
        return list(new LambdaQueryWrapper<EmployeeKpiStatEntity>()
                .eq(EmployeeKpiStatEntity::getStatMonth, month)
                .orderByDesc(EmployeeKpiStatEntity::getCompletionRate)
                .orderByDesc(EmployeeKpiStatEntity::getPerformanceAmount));
    }
}
