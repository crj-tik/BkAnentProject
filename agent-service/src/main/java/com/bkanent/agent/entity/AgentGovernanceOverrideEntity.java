package com.bkanent.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

@TableName("agent_governance_override")
public class AgentGovernanceOverrideEntity extends BaseEntity {

    private String overrideType;
    private String overrideKey;
    private String payloadJson;
    private Integer active;

    public String getOverrideType() {
        return overrideType;
    }

    public void setOverrideType(String overrideType) {
        this.overrideType = overrideType;
    }

    public String getOverrideKey() {
        return overrideKey;
    }

    public void setOverrideKey(String overrideKey) {
        this.overrideKey = overrideKey;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public Integer getActive() {
        return active;
    }

    public void setActive(Integer active) {
        this.active = active;
    }
}
