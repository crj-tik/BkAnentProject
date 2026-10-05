package com.bkanent.common.a2a;

import com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerProperties;
import com.alibaba.cloud.ai.a2a.registry.nacos.properties.NacosA2aProperties;
import com.alibaba.cloud.ai.a2a.registry.nacos.register.NacosA2aRegistryProperties;
import com.alibaba.cloud.ai.a2a.registry.nacos.service.NacosA2aOperationService;
import com.alibaba.nacos.api.ai.A2aService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/** Decorates only an enabled Starter registration bean; does not enable registration or create another SDK client. */
@AutoConfiguration
@ConditionalOnClass(NacosA2aOperationService.class)
public class SkillNacosCompatibilityAutoConfiguration {
    @Bean
    static BeanPostProcessor skillNacosRegistrationCompatibility(ObjectProvider<A2aService> service,
            ObjectProvider<NacosA2aProperties> nacos, ObjectProvider<A2aServerProperties> server,
            ObjectProvider<NacosA2aRegistryProperties> registry) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean.getClass() == NacosA2aOperationService.class) {
                    return new SkillAwareNacosOperationService(service.getObject(), nacos.getObject(), server.getObject(), registry.getObject());
                }
                return bean;
            }
        };
    }
}
