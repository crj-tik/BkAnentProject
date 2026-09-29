package com.bkanent.common.skill.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 技能基建统一配置（前缀 {@code agent.skills}）。
 *
 * @param externalDir  外部技能目录路径；为空时仅使用 classpath 技能
 * @param watchEnabled 是否监听外部目录变更并热加载（默认 true）
 */
@ConfigurationProperties(prefix = "agent.skills")
public record SkillProperties(String externalDir, @DefaultValue("true") boolean watchEnabled) {

    public SkillProperties {
        if (externalDir == null) {
            externalDir = "";
        }
    }

    public static SkillProperties defaults() {
        return new SkillProperties("", true);
    }
}
