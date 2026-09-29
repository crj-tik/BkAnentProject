package com.bkanent.common.skill.core;

import com.bkanent.common.skill.SkillDefinition;
import com.bkanent.common.skill.SkillMatchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillMatcherTest {

    @Test
    void matchesByFullKeywordHit() {
        SkillRegistry registry = registryWith(
                skill("kpi-report", "trade", List.of("KPI", "绩效", "月度报告"), 10),
                skill("other", "trade", List.of("无关词"), 10));
        SkillMatcher matcher = new SkillMatcher(registry);

        SkillMatchResult result = matcher.match("帮我生成本月的KPI绩效月度报告", "trade");

        assertThat(result.isMatched()).isTrue();
        assertThat(result.skill().name()).isEqualTo("kpi-report");
        assertThat(result.score()).isGreaterThanOrEqualTo(0.95);
    }

    @Test
    void prefersPriorityOnTie() {
        SkillRegistry registry = registryWith(
                skill("low-priority", "trade", List.of("共同词"), 1),
                skill("high-priority", "trade", List.of("共同词"), 9));
        SkillMatcher matcher = new SkillMatcher(registry);

        SkillMatchResult result = matcher.match("涉及共同词的请求", "trade");

        assertThat(result.isMatched()).isTrue();
        assertThat(result.skill().name()).isEqualTo("high-priority");
    }

    @Test
    void returnsNoMatchWhenNoKeywordHits() {
        SkillRegistry registry = registryWith(
                skill("kpi-report", "trade", List.of("KPI", "绩效"), 10));
        SkillMatcher matcher = new SkillMatcher(registry);

        // 命中 1/2 关键词（0.5）低于阈值 0.6 —— 语义无关词面不应触发技能
        SkillMatchResult result = matcher.match("获取时事新闻", "trade");

        assertThat(result.isMatched()).isFalse();
    }

    @Test
    void rankAllReturnsSortedCandidates() {
        SkillRegistry registry = registryWith(
                skill("kpi-report", "trade", List.of("KPI", "绩效"), 10));
        SkillMatcher matcher = new SkillMatcher(registry);

        List<SkillMatchResult> ranked = matcher.rankAll("KPI相关", "trade");

        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).skill().name()).isEqualTo("kpi-report");
    }

    private SkillRegistry registryWith(SkillDefinition... definitions) {
        return new SkillRegistry(new SkillFileLoader() {
            @Override
            public List<SkillDefinition> loadAll() {
                return List.of(definitions);
            }
        }, "");
    }

    private SkillDefinition skill(String name, String domain, List<String> keywords, int priority) {
        return SkillDefinition.builder()
                .name(name)
                .description(name)
                .domain(domain)
                .triggerKeywords(keywords)
                .priority(priority)
                .build();
    }
}
