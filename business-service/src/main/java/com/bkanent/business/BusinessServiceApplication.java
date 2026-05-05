package com.bkanent.business;

import org.mybatis.spring.annotation.MapperScan;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.bkanent.business.mapper")
@EnableDubbo
@SpringBootApplication
public class BusinessServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BusinessServiceApplication.class, args);
    }
}
