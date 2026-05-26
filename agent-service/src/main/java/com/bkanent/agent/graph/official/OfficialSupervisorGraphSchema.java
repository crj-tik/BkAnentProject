package com.bkanent.agent.graph.official;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class OfficialSupervisorGraphSchema {

    public KeyStrategyFactory keyStrategyFactory() {
        return () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put(OfficialSupervisorGraphKeys.SESSION_ID, new ReplaceStrategy());
            strategies.put(OfficialSupervisorGraphKeys.TASK_ID, new ReplaceStrategy());
            strategies.put(OfficialSupervisorGraphKeys.TRACE_ID, new ReplaceStrategy());
            strategies.put(OfficialSupervisorGraphKeys.USER_ID, new ReplaceStrategy());
            strategies.put(OfficialSupervisorGraphKeys.USER_MESSAGE, new ReplaceStrategy());
            strategies.put(OfficialSupervisorGraphKeys.REQUEST_STREAM, new ReplaceStrategy());
            strategies.put(OfficialSupervisorGraphKeys.WORKFLOW_STATUS, new ReplaceStrategy());
            strategies.put(OfficialSupervisorGraphKeys.SHARED_CONTEXT, new ReplaceStrategy());
            strategies.put(OfficialSupervisorGraphKeys.INTENT, new ReplaceStrategy());
            strategies.put(OfficialSupervisorGraphKeys.DOMAIN, new ReplaceStrategy());
            strategies.put(OfficialSupervisorGraphKeys.WORKFLOW_TYPE, new ReplaceStrategy());
            strategies.put(OfficialSupervisorGraphKeys.REQUIRE_PARALLEL, new ReplaceStrategy());
            strategies.put(OfficialSupervisorGraphKeys.REQUIRE_APPROVAL, new ReplaceStrategy());
            strategies.put(OfficialSupervisorGraphKeys.SELECTED_AGENT_ID, new ReplaceStrategy());
            strategies.put(OfficialSupervisorGraphKeys.PARALLEL_DOMAINS, new ReplaceStrategy());
            strategies.put(OfficialSupervisorGraphKeys.ARTIFACT_IDS, new AppendStrategy());
            strategies.put(OfficialSupervisorGraphKeys.HANDOFF_HISTORY, new AppendStrategy());
            strategies.put(OfficialSupervisorGraphKeys.FINAL_ANSWER, new ReplaceStrategy());
            strategies.put(OfficialSupervisorGraphKeys.PENDING_APPROVAL, new ReplaceStrategy());
            strategies.put(OfficialSupervisorGraphKeys.LATEST_APPROVAL_DECISION, new ReplaceStrategy());
            strategies.put(OfficialSupervisorGraphKeys.CURRENT_INVOKE_REQUEST, new ReplaceStrategy());
            strategies.put(OfficialSupervisorGraphKeys.LATEST_AGENT_RESPONSE, new ReplaceStrategy());
            strategies.put(OfficialSupervisorGraphKeys.NEXT_DOMAIN, new ReplaceStrategy());
            strategies.put(OfficialSupervisorGraphKeys.HANDOFF_TYPE, new ReplaceStrategy());
            strategies.put(OfficialSupervisorGraphKeys.APPROVAL_RESUME_ACTION, new ReplaceStrategy());
            strategies.put(OfficialSupervisorGraphKeys.RESUME_FEEDBACK, new ReplaceStrategy());
            strategies.put(OfficialSupervisorGraphKeys.SUPERVISOR_RESPONSE, new ReplaceStrategy());
            return strategies;
        };
    }
}
