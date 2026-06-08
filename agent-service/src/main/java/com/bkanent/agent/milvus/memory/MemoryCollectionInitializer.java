package com.bkanent.agent.milvus.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class MemoryCollectionInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MemoryCollectionInitializer.class);

    private final AgentMemoryMilvusService agentMemoryMilvusService;

    public MemoryCollectionInitializer(AgentMemoryMilvusService agentMemoryMilvusService) {
        this.agentMemoryMilvusService = agentMemoryMilvusService;
    }

    @Override
    public void run(ApplicationArguments args) {
        initCollection("user_preference_knowledge");
        initCollection("system_constraint_knowledge");
    }

    private void initCollection(String name) {
        try {
            agentMemoryMilvusService.initializeMemoryCollection(name);
            log.info("Milvus collection '{}' initialized", name);
        } catch (Exception e) {
            log.warn("Failed to initialize Milvus collection '{}': {}", name, e.getMessage());
        }
    }
}
