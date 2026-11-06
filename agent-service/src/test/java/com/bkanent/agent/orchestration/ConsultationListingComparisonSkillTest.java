package com.bkanent.agent.orchestration;

import com.bkanent.common.agent.SkillSelection;
import com.bkanent.common.skill.core.SkillFileLoader;
import com.bkanent.common.skill.core.SkillRegistry;
import com.bkanent.common.skill.runtime.SkillExecutionResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务 4.2：买方咨询对比技能装配——显式技能、绑定 listing-agent 现有能力、
 * 卡片透传与失败关闭硬约束可被目录发现；listing-agent 侧无新增技能（复用既有
 * listing-search / listing-comparison，由 listing-master-service 既有装配测试覆盖）。
 */
class ConsultationListingComparisonSkillTest {

    @Test
    void consultationComparisonSkillIsExplicitAndBoundToListingAgent() {
        var registry = new SkillRegistry(new SkillFileLoader(), "");
        var skill = registry.getByName("consultation-listing-comparison");

        assertThat(skill).isNotNull();
        assertThat(registry.findOperationalSkills("supervisor")).extracting(item -> item.name())
                .contains("consultation-listing-comparison");
        assertThat(skill.explicitOnly()).isTrue();
        assertThat(skill.supervisorSkill()).isFalse();
        assertThat(skill.capabilities().policy()).isEqualTo("allowlist");
        assertThat(skill.capabilities().refs()).containsExactly("a2a:listing-agent");

        var capabilities = skill.capabilities().refs().stream()
                .collect(java.util.stream.Collectors.toMap(id -> id, id -> id));
        var resolver = new SkillExecutionResolver(registry);
        assertThat(resolver.resolve(new SkillSelection(skill.name(), "1"), "supervisor", capabilities, true)
                .capabilityIds()).containsExactlyInAnyOrderElementsOf(capabilities.keySet());
        assertThatThrownBy(() -> resolver.resolve(new SkillSelection(skill.name(), "1"), "supervisor",
                capabilities, false)).hasMessageContaining("consultation-listing-comparison");
    }

    @Test
    void consultationComparisonSkillEnforcesCardTransparencyAndFailClosedConstraints() {
        var registry = new SkillRegistry(new SkillFileLoader(), "");
        var skill = registry.getByName("consultation-listing-comparison");

        // 复用 listing-agent 既有能力，不新增域内技能
        assertThat(skill.systemPrompt()).contains("listing-search", "listing-comparison");
        // 卡片透传与 agent 观点隔离
        assertThat(skill.systemPrompt()).contains("卡片数据原样透传", "不得增删改任何事实字段",
                "不写入卡片结构", "不补值");
        // 版本冲突与失败关闭
        assertThat(skill.systemPrompt()).contains("重新搜索一次", "不使用过期数据完成比较",
                "不降级、不使用模拟数据替代", "不虚构偏好");
        // 留痕经客户域关联接口，agent 不直接写客户域
        assertThat(skill.systemPrompt()).contains("comparison-reports", "agent 不直接写客户域");
    }
}
