package com.bkanent.common.skill.core;

import com.bkanent.common.skill.SkillDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SkillRegistryTest {

    @Test
    void concurrentReloadPublishesCompleteImmutableRegistryAtOnce() throws Exception {
        var old = skill("old", "listing", false); var replacement = skill("new", "listing", false);
        var assembling = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var loader = new SkillFileLoader() {
            public java.util.List<SkillDefinition> loadAll() {
                if (calls.getAndIncrement() == 0) return java.util.List.of(old);
                return new java.util.AbstractList<>() {
                    public int size() { return 1; }
                    public SkillDefinition get(int index) {
                        assembling.countDown();
                        try { if (!release.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("reload timeout"); }
                        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                        return replacement;
                    }
                };
            }
        };
        var registry = new SkillRegistry(loader, "");
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var reload = worker.submit(registry::reload);
            assertThat(assembling.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(registry.getByName("old")).isEqualTo(old);
            assertThat(registry.findByDomain("listing")).containsExactly(old);
            release.countDown(); reload.get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(registry.getByName("new")).isEqualTo(replacement);
            assertThat(registry.getByName("old")).isNull();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> registry.findByDomain("listing").clear()).isInstanceOf(UnsupportedOperationException.class);
        } finally { release.countDown(); worker.shutdownNow(); }
    }

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

    @Test
    void reloadListenersSeeCompleteSnapshotAndAreRemovableEvenAfterListenerFailure() throws Exception {
        SkillRegistry registry = new SkillRegistry(loaderWith(skill("a", "trade", false)), "");
        var observed = new java.util.concurrent.atomic.AtomicInteger();
        registry.onReload(() -> { throw new IllegalStateException("listener failure"); });
        AutoCloseable listener = registry.onReload(() -> {
            assertThat(registry.getByName("a")).isNotNull();
            assertThat(registry.findOperationalSkills("trade")).hasSize(1);
            observed.incrementAndGet();
        });
        registry.reload();
        assertThat(observed).hasValue(1);
        listener.close();
        registry.reload();
        assertThat(observed).hasValue(1);
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
