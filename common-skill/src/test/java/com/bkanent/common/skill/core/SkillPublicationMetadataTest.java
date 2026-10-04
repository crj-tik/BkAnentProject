package com.bkanent.common.skill.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class SkillPublicationMetadataTest {
    @TempDir Path directory;

    @Test
    void parsesIdentityPolicyAndExplicitOnlyWithoutSchedulingBody() throws Exception {
        Path file = directory.resolve("published.md");
        Files.writeString(file, """
                ---
                name: listing-compare
                description: Find and compare
                domain: supervisor
                owner: supervisor
                version: "2"
                explicit_only: true
                capabilities:
                  policy: allowlist
                  refs: [a2a:listing-agent, mcp:compare:compareListings]
                ---
                1. Understand the request.
                2. Search then compare, if candidates exist.
                """);
        var skill = new SkillFileLoader().parseFile(file);
        assertThat(skill.owner()).isEqualTo("supervisor");
        assertThat(skill.version()).isEqualTo("2");
        assertThat(skill.explicitOnly()).isTrue();
        assertThat(skill.capabilities().refs()).containsExactly("a2a:listing-agent", "mcp:compare:compareListings");
        assertThat(skill.systemPrompt()).contains("Search then compare");
        assertThat(skill.contentHash()).hasSize(64);
    }

    @Test
    void rejectsMalformedCapabilityPolicyRatherThanFallingBack() throws Exception {
        Path file = directory.resolve("invalid.md");
        Files.writeString(file, """
                ---
                name: invalid
                description: Invalid
                domain: supervisor
                capabilities:
                  policy: guess-all
                  refs: []
                ---
                Body
                """);
        assertThatThrownBy(() -> new SkillFileLoader().parseFile(file))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown skill capability");
    }
}
