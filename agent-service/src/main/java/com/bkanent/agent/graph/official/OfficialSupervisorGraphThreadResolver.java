package com.bkanent.agent.graph.official;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.NodeAggregationStrategy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class OfficialSupervisorGraphThreadResolver {

    public RunnableConfig resolve(String sessionId, String taskId) {
        String threadId = StringUtils.hasText(taskId) ? taskId : sessionId;
        return RunnableConfig.builder()
                .threadId(threadId)
                .defaultParallelAggregationStrategy(NodeAggregationStrategy.ALL_OF)
                .build();
    }
}
