package com.bkanent.contract;

import org.mybatis.spring.annotation.MapperScan;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.bkanent.contract.mapper")
@EnableDubbo
@SpringBootApplication
public class ContractServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContractServiceApplication.class, args);
    }
}
