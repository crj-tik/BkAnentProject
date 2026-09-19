package com.bkanent.agent.registry;

import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.common.agent.AgentCard;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Alibaba 官方 A2A 注册校验器。
 */
public final class OfficialA2aRegistrationValidator {

    private OfficialA2aRegistrationValidator() {
    }

    public static void requireValid(DistributedAgentProperties.AgentRegistration registration) {
        List<String> errors = validate(registration);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }

    public static List<String> validate(DistributedAgentProperties.AgentRegistration registration) {
        return validate(registration, null);
    }

    public static List<String> validate(DistributedAgentProperties.AgentRegistration registration,
                                        String defaultCardPath) {
        List<String> errors = new ArrayList<>();
        if (registration == null) {
            return List.of("Agent registration must not be null");
        }
        String agentId = registration.getAgentId();
        if (!StringUtils.hasText(agentId)) {
            errors.add("agentId must not be blank");
        }
        String provider = normalize(registration.getRuntimeProvider(), "official");
        if ("custom".equals(provider) || "custom_http".equals(provider)) {
            errors.add("agent " + agentId + " declares unsupported custom HTTP runtime; Alibaba official A2A is required");
        } else if (!"official".equals(provider) && !"auto".equals(provider)) {
            errors.add("agent " + agentId + " declares unsupported runtime provider: " + provider);
        }
        String cardPath = StringUtils.hasText(registration.getAgentCardPath())
                ? registration.getAgentCardPath()
                : defaultCardPath;
        if (!StringUtils.hasText(cardPath)) {
            errors.add("agent " + agentId + " must configure an official Agent Card path");
        } else if (!cardPath.contains("/.well-known/agent.json")) {
            errors.add("agent " + agentId + " must use the official /.well-known/agent.json Agent Card path");
        }
        if (!StringUtils.hasText(registration.getBaseUrl())
                && !StringUtils.hasText(registration.getServiceId())) {
            errors.add("agent " + agentId + " must configure baseUrl or serviceId for official A2A discovery");
        }
        return List.copyOf(errors);
    }

    public static void requireValidDescriptor(RegisteredAgentDescriptor descriptor) {
        List<String> errors = validateDescriptor(descriptor);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }

    public static List<String> validateDescriptor(RegisteredAgentDescriptor descriptor) {
        List<String> errors = new ArrayList<>();
        if (descriptor == null) {
            return List.of("Agent descriptor must not be null");
        }
        if (!StringUtils.hasText(descriptor.agentId())) {
            errors.add("agentId must not be blank");
        }
        if (descriptor.runtimeType() != AgentRuntimeType.ALIBABA_A2A) {
            errors.add("agent " + descriptor.agentId() + " must use Alibaba official A2A runtime");
        }
        if (!StringUtils.hasText(descriptor.agentCardPath())
                || !descriptor.agentCardPath().contains("/.well-known/agent.json")) {
            errors.add("agent " + descriptor.agentId() + " must resolve an official Agent Card");
        }
        AgentCard card = descriptor.agentCard();
        if (card == null || !StringUtils.hasText(card.a2aEndpoint())) {
            errors.add("agent " + descriptor.agentId() + " must expose an official A2A endpoint in its Agent Card");
        }
        if (!StringUtils.hasText(descriptor.a2aPath())) {
            errors.add("agent " + descriptor.agentId() + " must resolve an official A2A endpoint path");
        }
        return List.copyOf(errors);
    }

    private static String normalize(String value, String fallback) {
        return (StringUtils.hasText(value) ? value : fallback).trim().toLowerCase(Locale.ROOT);
    }
}
