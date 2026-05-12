package com.bkanent.common.rpc;

/**
 * AuthPermissionRpcService 服务接口。
 */

public interface AuthPermissionRpcService {

    /**
     * 业务方法：validateToken。
     */
    boolean validateToken(String token);

    /**
     * 业务方法：hasPermission。
     */
    boolean hasPermission(Long userId, String permissionCode);
}

