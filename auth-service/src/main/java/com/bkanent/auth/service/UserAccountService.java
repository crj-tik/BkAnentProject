package com.bkanent.auth.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.auth.entity.UserAccountEntity;

/**
 * UserAccountService 服务接口。
 */

public interface UserAccountService extends IService<UserAccountEntity> {

    /**
     * 业务方法：findByUsername。
     */
    UserAccountEntity findByUsername(String username);
}

