package com.bkanent.auth.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.auth.entity.UserAccountEntity;

public interface UserAccountService extends IService<UserAccountEntity> {

    UserAccountEntity findByUsername(String username);
}
