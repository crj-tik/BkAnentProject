package com.bkanent.auth.rpc;

import com.bkanent.auth.entity.UserAccountEntity;
import com.bkanent.auth.service.AuthTokenService;
import com.bkanent.auth.service.UserAccountService;
import com.bkanent.common.rpc.AuthPermissionRpcService;
import com.bkanent.common.rpc.AuthenticatedPrincipal;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.util.StringUtils;

/**
 * AuthPermissionRpcServiceImpl RPC 服务实现类。
 */
@DubboService
public class AuthPermissionRpcServiceImpl implements AuthPermissionRpcService {

    private final AuthTokenService authTokenService;
    private final UserAccountService userAccountService;

    public AuthPermissionRpcServiceImpl(AuthTokenService authTokenService,
                                        UserAccountService userAccountService) {
        this.authTokenService = authTokenService;
        this.userAccountService = userAccountService;
    }

    @Override
    public boolean validateToken(String token) {
        return resolvePrincipal(token) != null;
    }

    @Override
    public AuthenticatedPrincipal resolvePrincipal(String token) {
        AuthTokenService.TokenPrincipal tokenPrincipal = authTokenService.parse(token);
        if (tokenPrincipal == null || !"access".equals(tokenPrincipal.tokenType())) {
            return null;
        }
        UserAccountEntity account = userAccountService.getById(tokenPrincipal.userId());
        if (!isActive(account)) {
            return null;
        }
        return new AuthenticatedPrincipal(tokenPrincipal.userId());
    }

    @Override
    public boolean hasPermission(Long userId, String permissionCode) {
        if (userId == null || !StringUtils.hasText(permissionCode)) {
            return false;
        }
        UserAccountEntity account = userAccountService.getById(userId);
        return isActive(account);
    }

    private boolean isActive(UserAccountEntity account) {
        return account != null && Integer.valueOf(0).equals(account.getDeleted())
                && "ACTIVE".equalsIgnoreCase(account.getAccountStatus());
    }
}


