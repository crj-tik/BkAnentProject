package com.bkanent.marketing.rpc;

import com.bkanent.common.a2a.A2aCredentialContext;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rpc.AuthPermissionRpcService;
import com.bkanent.common.rpc.ListingMasterRpcService;
import com.bkanent.common.rpc.MediaWorkerRpcService;
import com.bkanent.common.rpc.PromotionRpcService;
import com.bkanent.marketing.MarketingContentServiceApplication;
import com.bkanent.marketing.config.MarketingAgentProperties;
import com.bkanent.marketing.mcp.MarketingMcpTools;
import com.bkanent.marketing.service.MarketingAssetService;
import com.bkanent.marketing.service.MarketingListingReadClient;
import com.bkanent.marketing.tool.MarketingTools;
import org.apache.dubbo.config.annotation.DubboReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class MarketingRpcReferenceConfigurationTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void distributedPackageScanWiresBothConfigurationsAndAuthenticatedListingConsumer() {
        var listing = mock(ListingMasterRpcService.class);
        var media = mock(MediaWorkerRpcService.class);
        var promotion = mock(PromotionRpcService.class);
        var auth = mock(AuthPermissionRpcService.class);
        Map<Class<?>, Object> references = Map.of(
                ListingMasterRpcService.class, listing,
                MediaWorkerRpcService.class, media,
                PromotionRpcService.class, promotion,
                AuthPermissionRpcService.class, auth);

        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("distributed");
            // Explicitly override any inherited environment value; this fixture has no signing key.
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "no-credentials", Map.of("marketing.listing-read.signing-secret", "")));
            context.registerBean(MarketingAssetService.class, () -> mock(MarketingAssetService.class));
            context.registerBean(MarketingAgentProperties.class, MarketingAgentProperties::new);
            // Replace only reference injection, not production configuration or its @Bean methods.
            // Do not enable Dubbo, Boot auto-configuration, Nacos, or any real transport.
            context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                @Override
                public Object postProcessBeforeInitialization(Object bean, String beanName) {
                    ReflectionUtils.doWithFields(bean.getClass(), field -> {
                        var annotation = field.getAnnotation(DubboReference.class);
                        if (annotation != null) {
                            assertThat(annotation.check()).isFalse();
                            assertThat(references).containsKey(field.getType());
                            ReflectionUtils.makeAccessible(field);
                            ReflectionUtils.setField(field, bean, references.get(field.getType()));
                        }
                    });
                    return bean;
                }
            });

            scanProductionComponents(context, true);
            context.refresh();

            assertThat(context.getBeansOfType(com.bkanent.marketing.MarketingRpcReferenceConfiguration.class))
                    .containsOnlyKeys("marketingRpcReferenceConfiguration");
            assertThat(context.getBeansOfType(MarketingRpcReferenceConfiguration.class))
                    .containsOnlyKeys("marketingListingRpcReferenceConfiguration");
            assertThat(context.getBeansOfType(ListingMasterRpcService.class))
                    .containsOnlyKeys("listingMasterRpcServiceBridge");
            assertThat(context.getBean(ListingMasterRpcService.class)).isSameAs(listing);
            assertThat(context.getBean(MediaWorkerRpcService.class)).isSameAs(media);
            assertThat(context.getBean(PromotionRpcService.class)).isSameAs(promotion);
            for (String bridge : Set.of("listingMasterRpcServiceBridge", "mediaWorkerRpcServiceBridge",
                    "promotionRpcServiceBridge")) {
                assertThat(context.getBeanFactory().getBeanDefinition(bridge).isPrimary()).isTrue();
            }

            var client = context.getBean(MarketingListingReadClient.class);
            assertThat(ReflectionTestUtils.getField(client, "listings")).isSameAs(listing);
            assertThat(ReflectionTestUtils.getField(client, "auth")).isSameAs(auth);
            assertThat(ReflectionTestUtils.getField(client, "secret")).isEqualTo("");
            assertThat(ReflectionTestUtils.getField(context.getBean(MarketingTools.class), "listingReadClient"))
                    .isSameAs(client);
            var mcp = context.getBean(MarketingMcpTools.class);
            assertThat(ReflectionTestUtils.getField(mcp, "mediaWorkerRpcService")).isSameAs(media);
            assertThat(ReflectionTestUtils.getField(mcp, "promotionRpcService")).isSameAs(promotion);

            RequestContextHolder.resetRequestAttributes();
            assertThat(A2aCredentialContext.snapshot()).isNull();
            assertThatThrownBy(() -> client.get(7L)).isInstanceOfSatisfying(RagException.class,
                    failure -> assertThat(failure.code()).isEqualTo(RagErrorCode.AUTHENTICATION_FAILED));
            verifyNoInteractions(listing, media, promotion, auth);
        }
    }

    @Test
    void localPackageScanExcludesBothRpcConfigurations() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("local");
            scanProductionComponents(context, false);
            context.refresh();

            assertThat(context.getBeansOfType(com.bkanent.marketing.MarketingRpcReferenceConfiguration.class)).isEmpty();
            assertThat(context.getBeansOfType(MarketingRpcReferenceConfiguration.class)).isEmpty();
            assertThat(context.getBeansOfType(ListingMasterRpcService.class)).isEmpty();
            assertThat(context.getBeansOfType(MediaWorkerRpcService.class)).isEmpty();
            assertThat(context.getBeansOfType(PromotionRpcService.class)).isEmpty();
        }
    }

    private void scanProductionComponents(AnnotationConfigApplicationContext context, boolean includeConsumers) {
        var configurations = Set.of(com.bkanent.marketing.MarketingRpcReferenceConfiguration.class.getName(),
                MarketingRpcReferenceConfiguration.class.getName());
        var consumers = Set.of(MarketingListingReadClient.class.getName(), MarketingTools.class.getName(),
                MarketingMcpTools.class.getName());
        var scanner = new ClassPathBeanDefinitionScanner(context, false);
        scanner.addIncludeFilter((reader, factory) -> {
            String className = reader.getClassMetadata().getClassName();
            return configurations.contains(className) || includeConsumers && consumers.contains(className);
        });
        // Use the real application's package and Spring's default bean-name generator.
        // With the old annotations, this scan itself throws ConflictingBeanDefinitionException.
        scanner.scan(MarketingContentServiceApplication.class.getPackageName());
    }
}
