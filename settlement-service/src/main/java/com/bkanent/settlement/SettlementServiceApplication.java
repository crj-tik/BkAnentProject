package com.bkanent.settlement;

import org.mybatis.spring.annotation.MapperScan;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.bkanent.settlement.mapper")
@EnableDubbo
@SpringBootApplication
public class SettlementServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SettlementServiceApplication.class, args);
    }
}
