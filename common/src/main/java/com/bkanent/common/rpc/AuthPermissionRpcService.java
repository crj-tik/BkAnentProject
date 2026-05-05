package com.bkanent.common.rpc;

public interface AuthPermissionRpcService {

    boolean validateToken(String token);

    boolean hasPermission(Long userId, String permissionCode);
}
