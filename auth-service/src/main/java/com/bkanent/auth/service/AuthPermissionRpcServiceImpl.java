package com.bkanent.auth.service;

import com.bkanent.common.rpc.AuthPermissionRpcService;
import org.apache.dubbo.config.annotation.DubboService;

@DubboService
public class AuthPermissionRpcServiceImpl implements AuthPermissionRpcService {

    @Override
    public boolean validateToken(String token) {
        return token != null && !token.isBlank();
    }

    @Override
    public boolean hasPermission(Long userId, String permissionCode) {
        return userId != null && permissionCode != null;
    }
}
