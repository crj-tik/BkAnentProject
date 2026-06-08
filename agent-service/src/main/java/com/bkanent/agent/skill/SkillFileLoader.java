package com.bkanent.agent.skill;

import com.bkanent.common.skill.SkillDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Scans classpath for skill.md files and parses their YAML frontmatter + Markdown body.
 *
 * <p>Expected file path: {@code classpath:skills/{domain}/*.md}</p>
 *
 * <h3>Skill.md format</h3>
 * <pre>
 * ---
 * name: trade-kpi-report
 * description: 生成交易KPI月度绩效报告
 * domain: trade
 * trigger_keywords: [KPI, 绩效, 月度报告, 业绩]
 * tools: [queryMonthlyKpis, calculateKpiAssessments]
 * priority: 10
 * supervisor_skill: false
 * ---
 * # System Prompt (everything after the second ---)
 * You are generating a KPI report. Follow these steps...
 * </pre>
 */
public class SkillFileLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillFileLoader.class);
    private static final String SKILL_LOCATION_PATTERN = "classpath*:skills/**/*.md";

    private final Yaml yaml = new Yaml();

    public List<SkillDefinition> loadAll() {
        List<SkillDefinition> skills = new ArrayList<>();
        try {
            ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(SKILL_LOCATION_PATTERN);
            for (Resource resource : resources) {
                try {
                    SkillDefinition skill = parse(resource);
                    if (skill != null) {
                        skills.add(skill);
                        log.info("Loaded skill '{}' from {}", skill.name(), resource.getURI());
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse skill file: {}", resource.getDescription(), e);
                }
            }
        } catch (Exception e) {
            log.warn("No skill files found at {}", SKILL_LOCATION_PATTERN, e);
        }
        log.info("Loaded {} skills from classpath", skills.size());
        return skills;
    }

    /**
     * 从外部目录加载技能文件（用于热加载场景）。
     * 与 classpath 加载结果合并，当 name 冲突时外部目录优先。
     *
     * @param externalDir 外部技能目录路径，可为 null
     * @return 合并后的技能列表
     */
    public List<SkillDefinition> loadAll(Path externalDir) {
        List<SkillDefinition> skills = loadAll();
        if (externalDir == null || !Files.isDirectory(externalDir)) {
            return skills;
        }

        Map<String, SkillDefinition> merged = new LinkedHashMap<>();
        for (SkillDefinition skill : skills) {
            merged.put(skill.name(), skill);
        }

        try (Stream<Path> files = Files.walk(externalDir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md"))
                    .forEach(file -> {
                        try {
                            SkillDefinition skill = parseFile(file);
                            if (skill != null) {
                                // 外部目录优先覆盖
                                if (merged.containsKey(skill.name())) {
                                    log.info("External skill '{}' overrides classpath version", skill.name());
                                }
                                merged.put(skill.name(), skill);
                            }
                        } catch (Exception e) {
                            log.warn("Failed to parse external skill file: {}", file, e);
                        }
                    });
        } catch (Exception e) {
            log.warn("Failed to walk external skill directory: {}", externalDir, e);
        }

        log.info("Loaded {} skills total (classpath + external)", merged.size());
        return List.copyOf(merged.values());
    }

    SkillDefinition parseFile(Path file) throws Exception {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        return parseRaw(raw, "file:" + file.toAbsolutePath());
    }

    SkillDefinition parse(Resource resource) throws Exception {
        String raw;
        try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            StringBuilder builder = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                builder.append(buf, 0, n);
            }
            raw = builder.toString();
        }
        return parseRaw(raw, resource.getDescription());
    }

    private SkillDefinition parseRaw(String raw, String sourceDescription) {
        if (!raw.startsWith("---")) {
            log.warn("Skill file doesn't start with YAML frontmatter, skipping: {}", sourceDescription);
            return null;
        }

        int secondDelimiter = raw.indexOf("---", 3);
        if (secondDelimiter < 0) {
            log.warn("Skill file has unclosed YAML frontmatter, skipping: {}", sourceDescription);
            return null;
        }

        String yamlBlock = raw.substring(3, secondDelimiter).trim();
        String markdownBody = raw.substring(secondDelimiter + 3).trim();

        @SuppressWarnings("unchecked")
        Map<String, Object> frontmatter = yaml.load(yamlBlock);
        if (frontmatter == null) {
            return null;
        }

        return SkillDefinition.builder()
                .name(string(frontmatter, "name"))
                .description(string(frontmatter, "description"))
                .domain(string(frontmatter, "domain"))
                .triggerKeywords(stringList(frontmatter, "trigger_keywords"))
                .tools(stringList(frontmatter, "tools"))
                .systemPrompt(markdownBody)
                .priority(intValue(frontmatter, "priority", 5))
                .supervisorSkill(boolValue(frontmatter, "supervisor_skill", false))
                .build();
    }

    private String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : value.toString();
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        if (value instanceof String s && !s.isBlank()) {
            return List.of(s.split("\\s*,\\s*"));
        }
        return List.of();
    }

    private int intValue(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private boolean boolValue(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return "true".equalsIgnoreCase(s);
        }
        return defaultValue;
    }
}
