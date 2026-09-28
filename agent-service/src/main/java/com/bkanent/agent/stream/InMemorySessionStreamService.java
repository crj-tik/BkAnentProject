package com.bkanent.agent.stream;

import com.bkanent.common.agent.SessionStreamEvent;
import com.bkanent.common.agent.SessionStreamVisibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;

@Service
public class InMemorySessionStreamService implements SessionStreamService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InMemorySessionStreamService.class);
    private static final int REPLAY_PAGE_SIZE = 1000;
    private static final int MAX_REPLAY_PAGES_PER_PASS = 10;
    private static final long IDLE_REPLAY_INTERVAL_MS = 15_000L;

    private final SessionEventBus sessionEventBus;
    private final SessionSubscriberRegistry subscriberRegistry;
    private final SessionEventAuditService sessionEventAuditService;
    private final Object[] sessionLocks = new Object[64];
    private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "session-stream-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public InMemorySessionStreamService(SessionEventBus sessionEventBus,
                                        SessionSubscriberRegistry subscriberRegistry,
                                        SessionEventAuditService sessionEventAuditService) {
        this.sessionEventBus = sessionEventBus;
        this.subscriberRegistry = subscriberRegistry;
        this.sessionEventAuditService = sessionEventAuditService;
        for (int index = 0; index < sessionLocks.length; index++) {
            sessionLocks[index] = new Object();
        }
    }

    @Override
    public SseEmitter subscribe(String sessionId) {
        return subscribe(sessionId, null, null, null);
    }

    @Override
    public SseEmitter subscribe(String sessionId,
                                String taskId,
                                String afterEventId,
                                Long afterSequence) {
        SseEmitter emitter = new SseEmitter(0L);
        String subscriberId = UUID.randomUUID().toString();
        AtomicLong lastDeliveredSequence = new AtomicLong(afterSequence == null ? 0L : afterSequence);
        AtomicLong replayCursor = new AtomicLong(afterSequence == null
                ? sessionEventAuditService.sequenceForEventId(sessionId, afterEventId)
                : Math.max(0L, afterSequence));
        AtomicBoolean closed = new AtomicBoolean();
        Object subscriptionGate = new Object();
        Queue<SessionStreamEvent> pendingLiveEvents = new ArrayDeque<>();
        AtomicBoolean replaying = new AtomicBoolean(true);
        AtomicLong lastNotificationAtMs = new AtomicLong(System.currentTimeMillis());
        ScheduledFuture<?>[] heartbeatRef = new ScheduledFuture<?>[1];
        Runnable cleanup = () -> {
            if (closed.compareAndSet(false, true)) {
                subscriberRegistry.unregister(sessionId, subscriberId);
                if (heartbeatRef[0] != null) {
                    heartbeatRef[0].cancel(false);
                }
            }
        };
        synchronized (lockForSession(sessionId)) {
            subscriberRegistry.register(sessionId, subscriberId,
                    event -> {
                        if (event == null) {
                            return;
                        }
                        lastNotificationAtMs.set(System.currentTimeMillis());
                        synchronized (subscriptionGate) {
                            if (replaying.get()) {
                                pendingLiveEvents.add(event);
                                return;
                            }
                            boolean replayComplete = replayPersistedEvents(
                                    sessionId, taskId, subscriberId, emitter, replayCursor, lastDeliveredSequence, closed);
                            if (replayComplete && (event.sequence() == null || event.sequence() > replayCursor.get())) {
                                sendEvent(sessionId, taskId, subscriberId, emitter, event, lastDeliveredSequence, closed);
                            }
                        }
                    });
            synchronized (subscriptionGate) {
                replayPersistedEvents(sessionId, taskId, subscriberId, emitter,
                        replayCursor, lastDeliveredSequence, closed);
                replaying.set(false);
                while (!pendingLiveEvents.isEmpty()) {
                    SessionStreamEvent event = pendingLiveEvents.poll();
                    boolean replayComplete = replayPersistedEvents(
                            sessionId, taskId, subscriberId, emitter, replayCursor, lastDeliveredSequence, closed);
                    if (event != null && replayComplete
                            && (event.sequence() == null || event.sequence() > replayCursor.get())) {
                        sendEvent(sessionId, taskId, subscriberId, emitter, event, lastDeliveredSequence, closed);
                    }
                }
            }
        }
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(throwable -> cleanup.run());
        heartbeatRef[0] = heartbeatExecutor.scheduleAtFixedRate(
                () -> {
                    synchronized (subscriptionGate) {
                        long now = System.currentTimeMillis();
                        if (!closed.get() && now - lastNotificationAtMs.get() >= IDLE_REPLAY_INTERVAL_MS) {
                            replayPersistedEvents(sessionId, taskId, subscriberId, emitter,
                                    replayCursor, lastDeliveredSequence, closed);
                            lastNotificationAtMs.set(now);
                        }
                    }
                    sendHeartbeat(emitter, cleanup, closed);
                }, 15, 15, TimeUnit.SECONDS);
        return emitter;
    }

    @Override
    public void publish(SessionStreamEvent event) {
        Object sessionLock = lockForSession(event.sessionId());
        synchronized (sessionLock) {
            SessionStreamEvent enriched = sessionEventAuditService.recordAndEnrich(event);
            try {
                sessionEventBus.publish(enriched);
            } catch (RuntimeException exception) {
                LOGGER.warn("Live session event notification failed; persisted replay will recover it", exception);
            }
        }
    }

    private boolean replayPersistedEvents(String sessionId,
                                         String taskId,
                                         String subscriberId,
                                         SseEmitter emitter,
                                         AtomicLong replayCursor,
                                         AtomicLong lastDeliveredSequence,
                                         AtomicBoolean closed) {
        for (int page = 0; page < MAX_REPLAY_PAGES_PER_PASS && !closed.get(); page++) {
            long cursor = replayCursor.get();
            List<SessionStreamEvent> events = sessionEventAuditService.replay(
                    sessionId, taskId, null, cursor, REPLAY_PAGE_SIZE);
            if (events.isEmpty()) {
                return true;
            }
            boolean advanced = false;
            for (SessionStreamEvent event : events) {
                if (event == null) {
                    continue;
                }
                Long sequence = event.sequence();
                if (sequence != null && sequence > replayCursor.get()) {
                    replayCursor.set(sequence);
                    advanced = true;
                }
                sendEvent(sessionId, taskId, subscriberId, emitter, event, lastDeliveredSequence, closed);
            }
            if (events.size() < REPLAY_PAGE_SIZE || !advanced) {
                return true;
            }
        }
        return false;
    }

    private void sendEvent(String sessionId,
                           String taskId,
                           String subscriberId,
                           SseEmitter emitter,
                           SessionStreamEvent event,
                           AtomicLong lastDeliveredSequence,
                           AtomicBoolean closed) {
        if (closed.get() || !SessionStreamVisibility.isExternallyVisible(event.visibility())) {
            return;
        }
        if (taskId != null && !taskId.equals(event.taskId())) {
            return;
        }
        synchronized (emitter) {
            Long sequence = event.sequence();
            if (sequence != null && sequence > 0 && sequence <= lastDeliveredSequence.get()) {
                return;
            }
            try {
                SseEmitter.SseEventBuilder builder = SseEmitter.event()
                        .name(event.eventType())
                        .data(event);
                if (event.eventId() != null) {
                    builder.id(event.eventId());
                }
                emitter.send(builder);
                if (sequence != null && sequence > 0) {
                    lastDeliveredSequence.accumulateAndGet(sequence, Math::max);
                }
            } catch (IOException exception) {
                emitter.completeWithError(exception);
                if (closed.compareAndSet(false, true)) {
                    subscriberRegistry.unregister(sessionId, subscriberId);
                }
            }
        }
    }

    private void sendHeartbeat(SseEmitter emitter, Runnable cleanup, AtomicBoolean closed) {
        if (closed.get()) {
            return;
        }
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event()
                        .name("heartbeat")
                        .data(Map.of("timestamp", System.currentTimeMillis())));
            }
        } catch (IOException exception) {
            emitter.completeWithError(exception);
            cleanup.run();
        }
    }

    private Object lockForSession(String sessionId) {
        int hash = sessionId == null ? 0 : sessionId.hashCode();
        return sessionLocks[(hash & Integer.MAX_VALUE) % sessionLocks.length];
    }
}
