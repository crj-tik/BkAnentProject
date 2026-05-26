package com.bkanent.agent;

import com.bkanent.common.rpc.AuthPermissionRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!local")
public class AgentPermissionRpcReferenceConfiguration {

    @DubboReference(check = false)
    private AuthPermissionRpcService authPermissionRpcService;

    @Bean
    public AuthPermissionRpcService authPermissionRpcServiceBridge() {
        return authPermissionRpcService;
    }
}
