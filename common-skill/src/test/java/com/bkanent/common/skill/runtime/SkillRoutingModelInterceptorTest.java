package com.bkanent.common.skill.runtime;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.bkanent.common.skill.SkillDefinition;
import com.bkanent.common.skill.core.SkillFileLoader;
import com.bkanent.common.skill.core.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


import static org.assertj.core.api.Assertions.assertThat;

class SkillRoutingModelInterceptorTest {

    private final List<ModelRequest> captured = new ArrayList<>();

    private final ModelCallHandler handler = request -> {
        captured.add(request);
        return ModelResponse.of(new AssistantMessage("ok"));
    };

    private final SkillRegistry registry = new SkillRegistry(new SkillFileLoader() {
        @Override
        public List<SkillDefinition> loadAll() {
            return List.of(SkillDefinition.builder()
                    .name("contract-risk-review")
                    .description("当需要审查合同风险、识别条款问题时使用")
                    .domain("contract")
                    .tools(List.of("reviewContractRisks"))
                    .priority(9)
                    .systemPrompt("按步骤审查合同风险。")
                    .build());
        }
    }, "");

    @Test
    void appendsCatalogWhenNoSkillActivated() {
        SkillRoutingModelInterceptor interceptor = new SkillRoutingModelInterceptor(registry, "contract");
        ModelRequest request = requestWith(List.of(userMessage("审查合同101"), List.of()));

        interceptor.interceptModel(request, handler);

        assertThat(captured).hasSize(1);
        ModelRequest forwarded = captured.get(0);
        assertThat(forwarded.getSystemMessage().getText())
                .contains("可用技能")
                .contains("contract-risk-review")
                .contains("审查合同风险");
        assertThat(forwarded.getTools()).containsExactly("getContractDetail", "reviewContractRisks", "skill");
    }

    @Test
    void validHintPreActivatesSkill() {
        SkillRoutingModelInterceptor interceptor = new SkillRoutingModelInterceptor(registry, "contract");
        ModelRequest request = ModelRequest.builder()
                .systemMessage(new SystemMessage("基础提示词"))
                .messages(List.of(userMessage("随便看看")))
                .tools(List.of("getContractDetail", "reviewContractRisks", "skill"))
                .context(Map.of("supervisor", Map.of("skillHint", "contract-risk-review")))
                .build();

        interceptor.interceptModel(request, handler);

        // 预激活：首轮即加载技能正文并收窄工具面，无需先经历目录浏览
        ModelRequest forwarded = captured.get(0);
        assertThat(forwarded.getSystemMessage().getText())
                .contains("[当前技能: contract-risk-review 生效中]")
                .contains("按步骤审查合同风险。");
        assertThat(forwarded.getTools()).containsExactly("reviewContractRisks", "skill");
    }

    @Test
    void explicitSkillCallOverridesHint() {
        SkillRoutingModelInterceptor interceptor = new SkillRoutingModelInterceptor(registry, "contract");
        // 消息历史已有 skill 伪工具调用 → 以消息历史为准，hint 不产生持久隐藏状态
        ModelRequest request = ModelRequest.builder()
                .systemMessage(new SystemMessage("基础提示词"))
                .messages(List.of(assistantWithSkillCall("contract-risk-review")))
                .tools(List.of("getContractDetail", "reviewContractRisks", "skill"))
                .context(Map.of("supervisor", Map.of("skillHint", "contract-risk-review")))
                .build();

        interceptor.interceptModel(request, handler);

        assertThat(captured.get(0).getSystemMessage().getText())
                .contains("[当前技能: contract-risk-review 生效中]");
    }

    @Test
    void crossDomainHintIgnored() {
        SkillRoutingModelInterceptor interceptor = new SkillRoutingModelInterceptor(registry, "contract");
        ModelRequest request = ModelRequest.builder()
                .systemMessage(new SystemMessage("基础提示词"))
                .messages(List.of(userMessage("随便看看")))
                .tools(List.of("getContractDetail", "reviewContractRisks", "skill"))
                .context(Map.of("supervisor", Map.of("skillHint", "trade-kpi-report")))
                .build();

        interceptor.interceptModel(request, handler);

        // 跨域 hint 静默忽略，回退目录注入
        assertThat(captured.get(0).getSystemMessage().getText())
                .contains("可用技能")
                .contains("contract-risk-review");
        assertThat(captured.get(0).getTools())
                .containsExactly("getContractDetail", "reviewContractRisks", "skill");
    }

    @Test
    void unknownHintIsSilentlyIgnored() {
        SkillRoutingModelInterceptor interceptor = new SkillRoutingModelInterceptor(registry, "contract");
        ModelRequest request = ModelRequest.builder()
                .systemMessage(new SystemMessage("基础提示词"))
                .messages(List.of(userMessage("随便看看")))
                .tools(List.of("getContractDetail", "reviewContractRisks", "skill"))
                .context(Map.of("supervisor", Map.of("skillHint", "not-a-skill")))
                .build();

        interceptor.interceptModel(request, handler);

        assertThat(captured.get(0).getSystemMessage().getText())
                .contains("contract-risk-review")
                .doesNotContain("Supervisor 建议");
    }

    @Test
    void noSkillsMeansRequestUntouched() {
        SkillRoutingModelInterceptor interceptor = new SkillRoutingModelInterceptor(
                new SkillRegistry(new SkillFileLoader(), ""), "contract");
        ModelRequest request = requestWith(List.of(userMessage("查询合同"), List.of()));

        interceptor.interceptModel(request, handler);

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0)).isSameAs(request);
    }

    @Test
    void activatedSkillSwapsPromptAndNarrowsTools() {
        SkillRoutingModelInterceptor interceptor = new SkillRoutingModelInterceptor(registry, "contract");
        ModelRequest request = requestWith(List.of(
                userMessage("审查合同101"),
                assistantWithSkillCall("contract-risk-review"),
                List.of()));

        interceptor.interceptModel(request, handler);

        ModelRequest forwarded = captured.get(0);
        assertThat(forwarded.getSystemMessage().getText())
                .contains("基础提示词")
                .contains("[当前技能: contract-risk-review 生效中]")
                .contains("按步骤审查合同风险。");
        assertThat(forwarded.getTools()).containsExactly("reviewContractRisks", "skill");
    }

    @Test
    void latestSkillCallWinsOnSwitch() {
        SkillRoutingModelInterceptor interceptor = new SkillRoutingModelInterceptor(registry, "contract");
        ModelRequest request = requestWith(List.of(
                assistantWithSkillCall("old-skill-name"),
                assistantWithSkillCall("contract-risk-review"),
                List.of()));

        interceptor.interceptModel(request, handler);

        assertThat(captured.get(0).getSystemMessage().getText())
                .contains("contract-risk-review 生效中");
    }

    @Test
    void skillWithoutToolDeclarationsKeepsAllTools() {
        SkillRegistry emptyToolsRegistry = new SkillRegistry(new SkillFileLoader() {
            @Override
            public List<SkillDefinition> loadAll() {
                return List.of(SkillDefinition.builder()
                        .name("free-skill")
                        .description("不限定工具")
                        .domain("contract")
                        .systemPrompt("自由执行。")
                        .build());
            }
        }, "");
        SkillRoutingModelInterceptor interceptor = new SkillRoutingModelInterceptor(emptyToolsRegistry, "contract");
        ModelRequest request = requestWith(List.of(assistantWithSkillCall("free-skill"), List.of()));

        interceptor.interceptModel(request, handler);

        assertThat(captured.get(0).getTools())
                .containsExactly("getContractDetail", "reviewContractRisks", "skill");
    }

    @Test
    void skillToolsWithoutIntersectionFallsBackToAllTools() {
        SkillRegistry mismatchedRegistry = new SkillRegistry(new SkillFileLoader() {
            @Override
            public List<SkillDefinition> loadAll() {
                return List.of(SkillDefinition.builder()
                        .name("mismatch")
                        .description("工具名不匹配")
                        .domain("contract")
                        .tools(List.of("nonexistentTool"))
                        .systemPrompt("正文。")
                        .build());
            }
        }, "");
        SkillRoutingModelInterceptor interceptor = new SkillRoutingModelInterceptor(mismatchedRegistry, "contract");
        ModelRequest request = requestWith(List.of(assistantWithSkillCall("mismatch"), List.of()));

        interceptor.interceptModel(request, handler);

        assertThat(captured.get(0).getTools())
                .containsExactly("getContractDetail", "reviewContractRisks", "skill");
    }

    @Test
    void deletedSkillFallsBackToDefaultPath() {
        SkillRoutingModelInterceptor interceptor = new SkillRoutingModelInterceptor(registry, "contract");
        ModelRequest request = requestWith(List.of(assistantWithSkillCall("vanished-skill"), List.of()));

        interceptor.interceptModel(request, handler);

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0)).isSameAs(request);
    }

    private ModelRequest requestWith(List<Object> parts) {
        // parts: [userMessage, assistantMessages..., messagesList]
        List<Message> messages = new ArrayList<>();
        for (Object part : parts) {
            if (part instanceof Message message) {
                messages.add(message);
            } else if (part instanceof List<?> list) {
                messages.addAll(list.stream().map(Message.class::cast).toList());
            }
        }
        return ModelRequest.builder()
                .systemMessage(new SystemMessage("基础提示词"))
                .messages(messages)
                .tools(List.of("getContractDetail", "reviewContractRisks", "skill"))
                .build();
    }

    private Message userMessage(String text) {
        return new UserMessage(text);
    }

    private Message assistantWithSkillCall(String skillName) {
        return AssistantMessage.builder()
                .content("选择技能")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "skill",
                        "{\"name\":\"" + skillName + "\",\"task\":\"执行任务\"}")))
                .build();
    }
}
