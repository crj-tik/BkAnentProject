package com.bkanent.interview.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewMcpToolContractTest {
    @Test
    void registersTheFourPublicReadsAndTwoExactProtectedOperations() {
        Set<String> methods = Arrays.stream(InterviewMcpTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .map(InterviewMcpToolContractTest::toolName)
                .collect(Collectors.toSet());

        assertThat(methods).containsExactlyInAnyOrder(
                "search_transcripts", "get_case_report", "grade_transcript", "search_reports",
                "submit_transcript", "generate_case_report");

        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        assertThat(properties).isNotNull();

        Map<String, String> protectedOperations = properties.stringPropertyNames().stream()
                .filter(key -> key.startsWith("mcp.authorization.server.tool-operations."))
                .collect(Collectors.toMap(key -> key.substring(key.lastIndexOf('.') + 1), properties::getProperty));
        assertThat(protectedOperations).containsExactlyInAnyOrderEntriesOf(Map.of(
                "submit_transcript", "interview.asset.submit",
                "generate_case_report", "interview.report.generate"));

        Set<String> publicTools = properties.stringPropertyNames().stream()
                .filter(key -> key.startsWith("mcp.authorization.server.public-tools["))
                .map(properties::getProperty)
                .collect(Collectors.toSet());
        assertThat(publicTools).containsExactlyInAnyOrder(
                "search_transcripts", "get_case_report", "grade_transcript", "search_reports");
        assertThat(publicTools).doesNotContain("submit_transcript", "generate_case_report");
    }

    private static String toolName(Method method) {
        String name = method.getAnnotation(Tool.class).name();
        return name == null || name.isBlank() ? method.getName() : name;
    }
}
