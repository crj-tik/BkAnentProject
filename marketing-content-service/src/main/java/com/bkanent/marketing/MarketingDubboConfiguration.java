package com.bkanent.marketing;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!local")
@EnableDubbo
public class MarketingDubboConfiguration {
}
