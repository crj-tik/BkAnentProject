package com.bkanent.common.skill.core;

import com.bkanent.common.skill.SkillDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillFileLoaderTest {

    private final SkillFileLoader loader = new SkillFileLoader();

    @TempDir
    Path tempDir;

    @Test
    void parsesValidSkillFileWithFrontmatterAndBody() throws Exception {
        Path file = tempDir.resolve("risk-review.md");
        Files.writeString(file, """
                ---
                name: contract-risk-review
                description: 审查合同风险
                domain: contract
                trigger_keywords: [风险, 审查]
                tools: [getContractDetail, reviewContractRisks]
                priority: 8
                supervisor_skill: false
                ---
                # 执行指引
                按步骤审查合同。
                """);

        SkillDefinition skill = loader.parseFile(file);

        assertThat(skill).isNotNull();
        assertThat(skill.name()).isEqualTo("contract-risk-review");
        assertThat(skill.domain()).isEqualTo("contract");
        assertThat(skill.triggerKeywords()).containsExactly("风险", "审查");
        assertThat(skill.tools()).containsExactly("getContractDetail", "reviewContractRisks");
        assertThat(skill.priority()).isEqualTo(8);
        assertThat(skill.supervisorSkill()).isFalse();
        assertThat(skill.systemPrompt()).contains("# 执行指引").contains("按步骤审查合同。");
    }

    @Test
    void parsesCommaSeparatedStringLists() throws Exception {
        Path file = tempDir.resolve("single-line.md");
        Files.writeString(file, """
                ---
                name: s
                description: d
                domain: trade
                tools: toolA,toolB
                ---
                body
                """);

        SkillDefinition skill = loader.parseFile(file);

        assertThat(skill).isNotNull();
        assertThat(skill.tools()).containsExactly("toolA", "toolB");
    }

    @Test
    void skipsFileWithoutFrontmatter() throws Exception {
        Path file = tempDir.resolve("plain.md");
        Files.writeString(file, "# 只是普通 markdown\n没有 frontmatter");

        assertThat(loader.parseFile(file)).isNull();
    }

    @Test
    void skipsFileWithUnclosedFrontmatter() throws Exception {
        Path file = tempDir.resolve("unclosed.md");
        Files.writeString(file, "---\nname: x\n");

        assertThat(loader.parseFile(file)).isNull();
    }

    @Test
    void externalDirectoryOverridesClasspathOnNameConflict() throws Exception {
        Path external = tempDir.resolve("skills");
        Files.createDirectories(external);
        Files.writeString(external.resolve("a.md"), """
                ---
                name: shared-skill
                description: 外部版本
                domain: trade
                ---
                外部正文
                """);

        List<SkillDefinition> merged = loader.loadAll(external);

        assertThat(merged).extracting(SkillDefinition::name).contains("shared-skill");
        SkillDefinition shared = merged.stream()
                .filter(s -> s.name().equals("shared-skill"))
                .findFirst().orElseThrow();
        assertThat(shared.description()).isEqualTo("外部版本");
        assertThat(shared.systemPrompt()).contains("外部正文");
    }
}
