package com.bkanent.memory.service;

import com.bkanent.common.agent.SystemConstraintRecord;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

@Component
public class SystemConstraintBootstrapRunner implements ApplicationRunner {
    private final SystemConstraintMemoryService constraints;
    private final boolean enabled;
    public SystemConstraintBootstrapRunner(SystemConstraintMemoryService constraints,
            @Value("${memory.system-constraint-bootstrap.enabled:true}") boolean enabled) {
        this.constraints = constraints; this.enabled = enabled;
    }
    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!enabled) return;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("system-constraints.yml")) {
            if (input == null) throw new IllegalStateException("Missing system constraints resource");
            Object raw = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
            if (!(raw instanceof Map<?, ?> root) || !(root.get("constraints") instanceof List<?> definitions)) throw new IllegalStateException("Invalid system constraints resource");
            for (Object definition : definitions) {
                if (!(definition instanceof Map<?, ?> map) || !(map.get("key") instanceof String key) || !(map.get("text") instanceof String text)) throw new IllegalStateException("Invalid system constraint definition");
                constraints.bootstrapMissing(new SystemConstraintRecord(key, text, (String) map.get("category"), "bootstrap", (String) map.get("tags"), true));
            }
        }
    }
}
