package com.bkanent.memory.config;

import com.bkanent.memory.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.deepseek.*;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Duration;

/** No provider bean/key validation/network request when the processing feature is disabled. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="memory.v2", name={"archive-enabled", "preference.processing.enabled"}, havingValue="true")
public class MemoryPreferenceProcessingConfiguration {
    @Bean PreferenceEvaluator preferenceEvaluator(ObjectMapper mapper,
            @Value("${memory.v2.preference.processing.model}") String model,
            @Value("${DEEPSEEK_API_KEY:${KE_API_KEY:}}") String key,
            @Value("${DEEPSEEK_BASE_URL:https://open-chatgpt.ke.com/v1}") String baseUrl) {
        if(key.isBlank() || key.equals("local-dev-placeholder") || model.isBlank()) throw new IllegalArgumentException("preference model credentials/configuration missing");
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory=new JdkClientHttpRequestFactory(client); factory.setReadTimeout(Duration.ofSeconds(30));
        var api=DeepSeekApi.builder().apiKey(key).baseUrl(baseUrl).completionsPath("/chat/completions")
                .restClientBuilder(RestClient.builder().requestFactory(factory)).build();
        var chat=DeepSeekChatModel.builder().deepSeekApi(api).retryTemplate(RetryTemplate.builder().maxAttempts(1).noBackoff().build())
                .defaultOptions(DeepSeekChatOptions.builder().model(model).temperature(0.0).maxTokens(2000).internalToolExecutionEnabled(false).build()).build();
        return new PreferenceModelEvaluator(chat,model,mapper);
    }
    @Bean ThreadPoolTaskScheduler memoryPreferenceScheduler() {
        var scheduler=new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("preference-processing-");
        return scheduler;
    }
}
