package com.bkanent.marketing;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MarketingContentServiceApplication 启动类。
 */
@MapperScan("com.bkanent.marketing.mapper")
@SpringBootApplication
public class MarketingContentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketingContentServiceApplication.class, args);
    }
}


