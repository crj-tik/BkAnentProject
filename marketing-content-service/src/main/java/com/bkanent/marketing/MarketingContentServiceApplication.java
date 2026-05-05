package com.bkanent.marketing;

import org.mybatis.spring.annotation.MapperScan;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.bkanent.marketing.mapper")
@EnableDubbo
@SpringBootApplication
public class MarketingContentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketingContentServiceApplication.class, args);
    }
}
