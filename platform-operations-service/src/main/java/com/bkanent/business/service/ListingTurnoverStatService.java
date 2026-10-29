package com.bkanent.business.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.business.entity.ListingTurnoverStatEntity;

import java.util.List;

/**
 * 房源流通效率领域服务接口。
 */
public interface ListingTurnoverStatService extends IService<ListingTurnoverStatEntity> {

    /**
     * 业务方法：listByMonth。
     */
    List<ListingTurnoverStatEntity> listByMonth(String month);
}
