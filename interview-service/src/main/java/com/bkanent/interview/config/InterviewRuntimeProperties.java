package com.bkanent.interview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 访谈运行面配置。
 */
@ConfigurationProperties(prefix = "interview.runtime")
public class InterviewRuntimeProperties {

    /** 单轮追问 SLO（秒）。 */
    private int turnTimeoutSeconds = 6;
    /** 题目预算：分钟→题数。 */
    private String questionBudget = "15:8,30:14,45:20,60:26";
    private final Ticket ticket = new Ticket();
    private final Collection collection = new Collection();

    public static class Ticket {
        private String secret = "change-me-interview-ticket-secret";
        private int ttlHours = 24;

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public int getTtlHours() { return ttlHours; }
        public void setTtlHours(int ttlHours) { this.ttlHours = ttlHours; }
    }

    public static class Collection {
        private int scanFixedRateSeconds = 60;
        private int maxRetries = 8;

        public int getScanFixedRateSeconds() { return scanFixedRateSeconds; }
        public void setScanFixedRateSeconds(int scanFixedRateSeconds) { this.scanFixedRateSeconds = scanFixedRateSeconds; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    }

    public int getTurnTimeoutSeconds() { return turnTimeoutSeconds; }
    public void setTurnTimeoutSeconds(int turnTimeoutSeconds) { this.turnTimeoutSeconds = turnTimeoutSeconds; }
    public String getQuestionBudget() { return questionBudget; }
    public void setQuestionBudget(String questionBudget) { this.questionBudget = questionBudget; }
    public Ticket getTicket() { return ticket; }
    public Collection getCollection() { return collection; }

    /** 按参考时长解析题目预算，未知时长取上一档。 */
    public int questionBudgetFor(int minutes) {
        int best = 8;
        for (String pair : questionBudget.split(",")) {
            String[] kv = pair.split(":");
            int m = Integer.parseInt(kv[0].trim());
            int q = Integer.parseInt(kv[1].trim());
            if (minutes <= m) {
                return q;
            }
            best = q;
        }
        return best;
    }
}
