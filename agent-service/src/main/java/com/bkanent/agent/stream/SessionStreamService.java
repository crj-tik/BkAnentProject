package com.bkanent.agent.stream;

import com.bkanent.common.agent.SessionStreamEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface SessionStreamService {

    SseEmitter subscribe(String sessionId);

    default SseEmitter subscribe(String sessionId,
                                 String taskId,
                                 String afterEventId,
                                 Long afterSequence) {
        return subscribe(sessionId);
    }

    void publish(SessionStreamEvent event);
}
