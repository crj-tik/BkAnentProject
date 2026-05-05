package com.bkanent.agent;

import com.bkanent.agent.config.MilvusProperties;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@EnableConfigurationProperties(MilvusProperties.class)
@EnableDubbo
@SpringBootApplication
public class AgentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentServiceApplication.class, args);
    }
}
