package com.bkanent.business.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.business.entity.EmployeeDailyWorkloadEntity;

import java.util.List;

/**
 * 员工日工作量领域服务接口。
 */
public interface EmployeeDailyWorkloadService extends IService<EmployeeDailyWorkloadEntity> {

    /**
     * 业务方法：listByDate。
     */
    List<EmployeeDailyWorkloadEntity> listByDate(String statDate);
}
