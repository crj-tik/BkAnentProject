package com.bkanent.marketing;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.bkanent.marketing.campaign.CampaignRuntimeProperties;

/**
 * MarketingContentServiceApplication 启动类。
 */
@MapperScan("com.bkanent.marketing.mapper")
@SpringBootApplication
@EnableConfigurationProperties(CampaignRuntimeProperties.class)
@EnableScheduling
public class MarketingContentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketingContentServiceApplication.class, args);
    }
}


