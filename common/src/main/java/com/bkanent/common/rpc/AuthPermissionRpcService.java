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
     * Resolves a valid access token to an active account principal.
     *
     * @param token raw access token
     * @return authenticated principal, or {@code null} when the token/account is invalid
     */
    AuthenticatedPrincipal resolvePrincipal(String token);

    /**
     * 业务方法：hasPermission。
     */
    boolean hasPermission(Long userId, String permissionCode);
}

