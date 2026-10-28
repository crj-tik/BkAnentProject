package com.bkanent.knowledge;

import com.bkanent.knowledge.config.KnowledgeAuthorizationProperties;
import com.bkanent.knowledge.config.KnowledgeDataSourceProperties;
import com.bkanent.knowledge.config.KnowledgeSourceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({KnowledgeAuthorizationProperties.class, KnowledgeDataSourceProperties.class,
        KnowledgeSourceProperties.class})
public class KnowledgeServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(KnowledgeServiceApplication.class, args);
    }
}
