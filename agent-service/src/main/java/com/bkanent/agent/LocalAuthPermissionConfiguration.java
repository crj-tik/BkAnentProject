package com.bkanent.agent;

import com.bkanent.common.rpc.AuthPermissionRpcService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("local")
public class LocalAuthPermissionConfiguration {

    @Bean
    public AuthPermissionRpcService localAuthPermissionRpcService() {
        return new AuthPermissionRpcService() {
            @Override
            public boolean validateToken(String token) {
                return token != null && !token.isBlank();
            }

            @Override
            public boolean hasPermission(Long userId, String permissionCode) {
                return userId != null && permissionCode != null && !permissionCode.isBlank();
            }
        };
    }
}
