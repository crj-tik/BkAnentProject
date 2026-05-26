package com.bkanent.agent.stream;

import com.bkanent.common.agent.SessionStreamEvent;

import java.util.function.Consumer;

public interface SessionEventBus {

    void register(String sessionId, String subscriberId, Consumer<SessionStreamEvent> consumer);

    void unregister(String sessionId, String subscriberId);

    void publish(SessionStreamEvent event);
}
