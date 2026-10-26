package com.bkanent.business;

import com.bkanent.business.config.BusinessAuthorizationProperties;
import com.bkanent.common.authz.AuthzRestExceptionHandler;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * BusinessServiceApplication 启动类。
 */
@MapperScan("com.bkanent.business.mapper")
@EnableConfigurationProperties(BusinessAuthorizationProperties.class)
@Import(AuthzRestExceptionHandler.class)
@SpringBootApplication
public class BusinessServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BusinessServiceApplication.class, args);
    }
}


