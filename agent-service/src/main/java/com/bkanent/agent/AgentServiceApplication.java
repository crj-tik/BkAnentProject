package com.bkanent.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AgentServiceApplication 应用入口类。
 */
@MapperScan("com.bkanent.agent.mapper")
@EnableScheduling
@SpringBootApplication
public class AgentServiceApplication {

    /**
     * 启动应用。
     */
    public static void main(String[] args) {
        SpringApplication.run(AgentServiceApplication.class, args);
    }
}
