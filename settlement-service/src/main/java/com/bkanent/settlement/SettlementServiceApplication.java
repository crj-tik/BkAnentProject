package com.bkanent.settlement;

import com.bkanent.common.authz.AuthzRestExceptionHandler;
import com.bkanent.settlement.config.SettlementAuthorizationProperties;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 交易结算服务启动类。
 */
@MapperScan("com.bkanent.settlement.mapper")
@EnableConfigurationProperties(SettlementAuthorizationProperties.class)
@Import(AuthzRestExceptionHandler.class)
@EnableDubbo
@EnableScheduling
@SpringBootApplication
public class SettlementServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SettlementServiceApplication.class, args);
    }
}
