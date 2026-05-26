package com.bkanent.agent.stream;

import com.bkanent.common.agent.SessionStreamEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface SessionStreamService {

    SseEmitter subscribe(String sessionId);

    void publish(SessionStreamEvent event);
}
