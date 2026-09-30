package com.bkanent.interview;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI 深访服务启动类：治理面（A2A）+ 运行面（REST/SSE）+ 能力面（MCP）。
 */
@MapperScan("com.bkanent.interview.mapper")
@ConfigurationPropertiesScan("com.bkanent.interview.config")
@EnableScheduling
@SpringBootApplication
public class InterviewServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InterviewServiceApplication.class, args);
    }
}
