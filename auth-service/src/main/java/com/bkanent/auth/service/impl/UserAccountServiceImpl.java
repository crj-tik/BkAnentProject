package com.bkanent.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.auth.entity.UserAccountEntity;
import com.bkanent.auth.mapper.UserAccountMapper;
import com.bkanent.auth.service.UserAccountService;
import org.springframework.stereotype.Service;

/**
 * UserAccountServiceImpl 服务实现类。
 */
@Service
public class UserAccountServiceImpl extends ServiceImpl<UserAccountMapper, UserAccountEntity> implements UserAccountService {

    @Override
    public UserAccountEntity findByUsername(String username) {
        return getOne(new LambdaQueryWrapper<UserAccountEntity>()
                .eq(UserAccountEntity::getUsername, username)
                .last("limit 1"));
    }
}


