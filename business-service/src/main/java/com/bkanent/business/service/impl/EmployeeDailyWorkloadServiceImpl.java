package com.bkanent.business.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.business.entity.EmployeeDailyWorkloadEntity;
import com.bkanent.business.mapper.EmployeeDailyWorkloadMapper;
import com.bkanent.business.service.EmployeeDailyWorkloadService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 员工日工作量领域服务实现。
 */
@Service
public class EmployeeDailyWorkloadServiceImpl
        extends ServiceImpl<EmployeeDailyWorkloadMapper, EmployeeDailyWorkloadEntity>
        implements EmployeeDailyWorkloadService {

    @Override
    public List<EmployeeDailyWorkloadEntity> listByDate(String statDate) {
        return list(new LambdaQueryWrapper<EmployeeDailyWorkloadEntity>()
                .eq(EmployeeDailyWorkloadEntity::getStatDate, statDate)
                .orderByDesc(EmployeeDailyWorkloadEntity::getViewingCount)
                .orderByDesc(EmployeeDailyWorkloadEntity::getNewCustomers));
    }
}
