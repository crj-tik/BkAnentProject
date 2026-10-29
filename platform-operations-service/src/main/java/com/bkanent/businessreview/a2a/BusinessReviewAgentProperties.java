package com.bkanent.businessreview.a2a;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix = "business-review.agent")
public class BusinessReviewAgentProperties {
    private String systemPrompt = "你是门店经营复盘助手。只基于经过授权的来源事实区分事实、假设、缺口与建议。"
            + "不得猜测员工、客户、价格、成交或因果结论。所有内容都是待用户审核的草稿，不得执行、确认或反馈业务动作。";
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String value) { systemPrompt = value; }
}
