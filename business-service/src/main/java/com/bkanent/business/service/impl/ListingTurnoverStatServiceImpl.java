package com.bkanent.business.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.business.entity.ListingTurnoverStatEntity;
import com.bkanent.business.mapper.ListingTurnoverStatMapper;
import com.bkanent.business.service.ListingTurnoverStatService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 房源流通效率领域服务实现。
 */
@Service
public class ListingTurnoverStatServiceImpl
        extends ServiceImpl<ListingTurnoverStatMapper, ListingTurnoverStatEntity>
        implements ListingTurnoverStatService {

    @Override
    public List<ListingTurnoverStatEntity> listByMonth(String month) {
        return list(new LambdaQueryWrapper<ListingTurnoverStatEntity>()
                .eq(ListingTurnoverStatEntity::getStatMonth, month)
                .orderByAsc(ListingTurnoverStatEntity::getTotalTurnoverDays));
    }
}
