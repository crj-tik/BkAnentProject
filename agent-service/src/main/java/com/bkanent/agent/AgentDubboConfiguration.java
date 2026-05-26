package com.bkanent.agent;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * AgentDubboConfiguration 配置类。
 */
@Configuration
@Profile("!local")
@EnableDubbo
public class AgentDubboConfiguration {
}
