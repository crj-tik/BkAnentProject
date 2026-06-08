package com.bkanent.agent.service;

import com.bkanent.agent.memory.MemoryStoreClient;
import com.bkanent.agent.milvus.memory.AgentMemoryMilvusService;
import com.bkanent.common.agent.SystemConstraintRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

@Component
public class SystemConstraintBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SystemConstraintBootstrapRunner.class);

    private final MemoryStoreClient memoryStoreClient;
    private final AgentMemoryMilvusService agentMemoryMilvusService;

    public SystemConstraintBootstrapRunner(MemoryStoreClient memoryStoreClient,
                                           AgentMemoryMilvusService agentMemoryMilvusService) {
        this.memoryStoreClient = memoryStoreClient;
        this.agentMemoryMilvusService = agentMemoryMilvusService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void run(ApplicationArguments args) {
        try {
            InputStream input = getClass().getClassLoader()
                    .getResourceAsStream("system-constraints.yml");
            if (input == null) {
                log.warn("system-constraints.yml not found on classpath, skipping constraint bootstrap");
                return;
            }
            Yaml yaml = new Yaml();
            Map<String, Object> raw = yaml.load(input);
            if (raw == null || !raw.containsKey("constraints")) {
                log.info("No constraints defined in system-constraints.yml");
                return;
            }
            List<Map<String, String>> rawConstraints = (List<Map<String, String>>) raw.get("constraints");
            log.info("Bootstrapping {} system constraints", rawConstraints.size());

            for (Map<String, String> def : rawConstraints) {
                String key = def.get("key");
                String text = def.get("text");
                String category = def.get("category");
                String tags = def.get("tags");
                if (key == null || text == null) {
                    continue;
                }

                SystemConstraintRecord record = new SystemConstraintRecord(
                        key, text, category, "bootstrap", tags, true);
                memoryStoreClient.upsertSystemConstraint(record);

                try {
                    String embedText = "[" + (category != null ? category : "general") + "] " + text;
                    agentMemoryMilvusService.upsertMemory(
                            "system_constraint_knowledge",
                            key,
                            embedText,
                            Map.of(
                                    "constraint_key", key,
                                    "category", category != null ? category : "",
                                    "tags", tags != null ? tags : ""
                            )
                    );
                } catch (Exception e) {
                    log.warn("Failed to upsert constraint '{}' to Milvus: {}", key, e.getMessage());
                }
            }

            log.info("System constraint bootstrap complete: {} constraints synced to memory-service and Milvus",
                    rawConstraints.size());
        } catch (Exception e) {
            log.error("System constraint bootstrap failed", e);
        }
    }
}
