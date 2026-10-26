package com.bkanent.customer;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import com.bkanent.common.authz.AuthzRestExceptionHandler;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 客户服务启动类。
 */
@MapperScan("com.bkanent.customer.mapper")
@EnableDubbo
@EnableScheduling
@SpringBootApplication
@Import(AuthzRestExceptionHandler.class)
public class CustomerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerServiceApplication.class, args);
    }
}
