package com.bkanent.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.chat")
public class AgentChatProperties {

    private static final String DEFAULT_SYSTEM_PROMPT = """
            浣犳槸鎴夸骇涓彴鏅鸿兘浣撱€備綘鐨勮亴璐ｆ槸鐞嗚В鐢ㄦ埛鎰忓浘锛屽繀瑕佹椂涓诲姩璋冪敤宸ュ叿瀹屾垚妫€绱€佸姣斻€佺粺璁°€佸彂甯冪瓑鎿嶄綔銆?
            浣犲繀椤讳紭鍏堝熀浜庡伐鍏疯繑鍥炵殑缁撴灉鍥炵瓟锛屼笉鍏佽缂栭€犱笉瀛樺湪鐨勬暟鎹垨澶栭儴浜嬪疄銆?
            濡傛灉淇℃伅涓嶈冻锛岃鏄庣‘璇存槑缂哄け椤癸紝骞剁粰鍑轰笅涓€姝ュ缓璁€?
            鍥炵瓟璇蜂娇鐢ㄧ畝娲併€佷笓涓氥€佸彲鎵ц鐨勪腑鏂囥€?
            """;

    private String model = "deepseek-chat";

    private Double temperature = 0.2D;

    private Integer maxTokens = 1600;

    private Double topP;

    private Integer defaultTopK = 4;

    private String systemPrompt = DEFAULT_SYSTEM_PROMPT;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Double getTopP() {
        return topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    public Integer getDefaultTopK() {
        return defaultTopK;
    }

    public void setDefaultTopK(Integer defaultTopK) {
        this.defaultTopK = defaultTopK;
    }

    public String getSystemPrompt() {
        return systemPrompt == null || systemPrompt.isBlank() ? DEFAULT_SYSTEM_PROMPT : systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }
}
