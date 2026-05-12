package com.bkanent.auth.rpc;

import com.bkanent.common.rpc.AuthPermissionRpcService;
import org.apache.dubbo.config.annotation.DubboService;

/**
 * AuthPermissionRpcServiceImpl RPC 服务实现类。
 */
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


