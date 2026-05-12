package com.bkanent.listing;

import org.mybatis.spring.annotation.MapperScan;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ListingMasterServiceApplication 启动类。
 */
@MapperScan("com.bkanent.listing.mapper")
@EnableDubbo
@SpringBootApplication
public class ListingMasterServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ListingMasterServiceApplication.class, args);
    }
}


