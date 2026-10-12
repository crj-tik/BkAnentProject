package com.bkanent.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.Set;

/** Consumer policy only: no backend address, embedding or reranker configuration. */
@ConfigurationProperties(prefix = "agent.rag-consumer")
public class AgentRagConsumerProperties {
    private boolean knowledgeEnabled;
    private boolean supervisorOwnerConfirmed;
    private String supervisorOwnerDomain;
    private String supervisorPolicyVersion;
    private String keyId;
    private String signingSecret;
    private long credentialLeaseSeconds = 300;
    private int maxCredentialLeases = 1000;
    private Set<String> supervisorReadUserIds = Set.of();
    private int keywordTopK = 20;
    private int vectorTopK = 20;
    private int finalTopK = 10;

    public boolean isKnowledgeEnabled() { return knowledgeEnabled; }
    public void setKnowledgeEnabled(boolean value) { knowledgeEnabled = value; }
    public boolean isSupervisorOwnerConfirmed() { return supervisorOwnerConfirmed; }
    public void setSupervisorOwnerConfirmed(boolean value) { supervisorOwnerConfirmed = value; }
    public String getSupervisorOwnerDomain() { return supervisorOwnerDomain; }
    public void setSupervisorOwnerDomain(String value) { supervisorOwnerDomain = value; }
    public String getSupervisorPolicyVersion() { return supervisorPolicyVersion; }
    public void setSupervisorPolicyVersion(String value) { supervisorPolicyVersion = value; }
    public String getKeyId() { return keyId; }
    public void setKeyId(String value) { keyId = value; }
    public String getSigningSecret() { return signingSecret; }
    public void setSigningSecret(String value) { signingSecret = value; }
    public long getCredentialLeaseSeconds() { return credentialLeaseSeconds; }
    public void setCredentialLeaseSeconds(long value) { credentialLeaseSeconds = value; }
    public int getMaxCredentialLeases() { return maxCredentialLeases; }
    public void setMaxCredentialLeases(int value) { maxCredentialLeases = value; }
    public Set<String> getSupervisorReadUserIds() { return supervisorReadUserIds; }
    public void setSupervisorReadUserIds(Set<String> value) { supervisorReadUserIds = Set.copyOf(value); }
    public int getKeywordTopK() { return keywordTopK; }
    public void setKeywordTopK(int value) { keywordTopK = value; }
    public int getVectorTopK() { return vectorTopK; }
    public void setVectorTopK(int value) { vectorTopK = value; }
    public int getFinalTopK() { return finalTopK; }
    public void setFinalTopK(int value) { finalTopK = value; }
}
