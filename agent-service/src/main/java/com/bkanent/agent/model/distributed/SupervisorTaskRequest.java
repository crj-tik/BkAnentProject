package com.bkanent.agent.model.distributed;

import java.util.Map;

/**
 * SupervisorTaskRequest 主 Agent 任务请求。
 */
public record SupervisorTaskRequest(
        String sessionId,
        String userId,
        String requestId,
        String traceId,
        String userMessage,
        Map<String, Object> context,
        String channel,
        Boolean stream,
        com.bkanent.common.agent.SkillSelection skill,
        String continueRunId,
        Boolean allowMcp,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        com.bkanent.common.memory.MemoryMatterIntent matterIntent
) {
    public SupervisorTaskRequest(String sessionId, String userId, String requestId, String traceId, String userMessage,
            Map<String,Object> context, String channel, Boolean stream, com.bkanent.common.agent.SkillSelection skill, String continueRunId, Boolean allowMcp) {
        this(sessionId,userId,requestId,traceId,userMessage,context,channel,stream,skill,continueRunId,allowMcp,null);
    }
    public SupervisorTaskRequest(String sessionId, String userId, String requestId, String traceId,
                                 String userMessage, Map<String, Object> context, String channel, Boolean stream) {
        this(sessionId, userId, requestId, traceId, userMessage, context, channel, stream, null, null, null);
    }
}
