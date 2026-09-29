package com.bkanent.common.skill.core;

import com.bkanent.common.skill.SkillDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SkillRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void filtersOperationalSkillsByDomain() {
        SkillFileLoader loader = loaderWith(
                skill("contract-risk", "contract", false),
                skill("trade-kpi", "trade", false),
                skill("contract-knowledge", "contract", true));

        SkillRegistry registry = new SkillRegistry(loader, "");

        assertThat(registry.findOperationalSkills("contract"))
                .extracting(SkillDefinition::name)
                .containsExactly("contract-risk");
        assertThat(registry.findSupervisorSkills())
                .extracting(SkillDefinition::name)
                .containsExactly("contract-knowledge");
    }

    @Test
    void returnsEmptyForUnknownDomain() {
        SkillRegistry registry = new SkillRegistry(loaderWith(skill("a", "trade", false)), "");

        assertThat(registry.findOperationalSkills("contract")).isEmpty();
        assertThat(registry.getByName("a")).isNotNull();
        assertThat(registry.findByDomain("trade")).hasSize(1);
    }

    @Test
    void reloadPicksUpNewExternalSkill() throws Exception {
        Path external = tempDir.resolve("skills");
        Files.createDirectories(external);

        SkillRegistry registry = new SkillRegistry(new SkillFileLoader(), external.toString());
        assertThat(registry.findOperationalSkills("contract")).isEmpty();

        Files.writeString(external.resolve("new.md"), """
                ---
                name: hot-skill
                description: 热加载技能
                domain: contract
                ---
                正文
                """);

        registry.reload();

        assertThat(registry.findOperationalSkills("contract"))
                .extracting(SkillDefinition::name)
                .containsExactly("hot-skill");
    }

    @Test
    void blankExternalDirKeepsClasspathOnly() {
        SkillRegistry registry = new SkillRegistry(loaderWith(skill("a", "trade", false)), "");

        assertThat(registry.size()).isEqualTo(1);
    }

    private SkillFileLoader loaderWith(SkillDefinition... definitions) {
        return new SkillFileLoader() {
            @Override
            public java.util.List<SkillDefinition> loadAll() {
                return java.util.List.of(definitions);
            }
        };
    }

    private SkillDefinition skill(String name, String domain, boolean supervisorSkill) {
        return SkillDefinition.builder()
                .name(name)
                .description(name + " description")
                .domain(domain)
                .supervisorSkill(supervisorSkill)
                .build();
    }
}
