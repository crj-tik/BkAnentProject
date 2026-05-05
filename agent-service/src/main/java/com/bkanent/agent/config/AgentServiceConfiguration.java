package com.bkanent.agent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({MilvusProperties.class, AgentQwenProperties.class})
public class AgentServiceConfiguration {
}
