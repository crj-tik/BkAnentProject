package com.bkanent.common.skill.config;

import com.bkanent.common.skill.core.SkillFileLoader;
import com.bkanent.common.skill.core.SkillFileWatcher;
import com.bkanent.common.skill.core.SkillMatcher;
import com.bkanent.common.skill.core.SkillRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 技能基建自动装配：引入本模块依赖的服务默认获得 core 组件
 * （Loader/Registry/Matcher/Watcher），无需各自编写配置类。
 *
 * <p>消费方自行定义的同类型 Bean 优先（{@code @ConditionalOnMissingBean} 回退），
 * 因此 agent-service 既有的显式配置不受影响。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(SkillProperties.class)
public class SkillAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SkillFileLoader.class)
    public SkillFileLoader skillFileLoader() {
        return new SkillFileLoader();
    }

    @Bean
    @ConditionalOnMissingBean(SkillRegistry.class)
    public SkillRegistry skillRegistry(SkillFileLoader loader, SkillProperties properties) {
        return new SkillRegistry(loader, properties.externalDir());
    }

    @Bean
    @ConditionalOnMissingBean(SkillMatcher.class)
    public SkillMatcher skillMatcher(SkillRegistry registry) {
        return new SkillMatcher(registry);
    }

    @Bean
    @ConditionalOnMissingBean(SkillFileWatcher.class)
    public SkillFileWatcher skillFileWatcher(SkillRegistry registry, SkillProperties properties) {
        return new SkillFileWatcher(registry, properties.externalDir(), properties.watchEnabled());
    }
}
