package com.bkanent.agent.authorization;

import com.bkanent.common.authz.*;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads exact head JSON and consumes only the configured, protected Redis channel. No writes. */
public final class AgentAuthorizationRedisReader implements AuthorizationVersionMonitor.HeadReader, MessageListener {
    private static final ObjectMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private static final int MAX_JSON_BYTES = 16384;
    private final StringRedisTemplate redis;
    private final String namespace;
    private final byte[] channel;
    private AuthorizationVersionMonitor monitor;

    public AgentAuthorizationRedisReader(StringRedisTemplate redis, String namespace, String channel) {
        this.redis = redis;
        AuthorizationVersionMonitor.headKey(namespace, "1");
        this.namespace = namespace;
        this.channel = channel.getBytes(StandardCharsets.UTF_8);
    }
    public void attach(AuthorizationVersionMonitor monitor) { this.monitor = monitor; }

    @Override public Map<String, AuthzFreshness> readHeads(List<String> users) {
        if (users == null || users.size() > AuthorizationVersionMonitor.MAX_BATCH_USERS) {
            throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
        }
        if (users.isEmpty()) return Map.of();
        List<String> keys = users.stream().map(user -> AuthorizationVersionMonitor.headKey(namespace, user)).toList();
        List<String> values;
        try { values = redis.opsForValue().multiGet(keys); }
        catch (RuntimeException failure) { throw new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE); }
        if (values == null || values.size() != users.size()) throw new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
        Map<String, AuthzFreshness> result = new LinkedHashMap<>();
        for (int i = 0; i < users.size(); i++) {
            if (values.get(i) != null) result.put(users.get(i), decodeHead(values.get(i)));
        }
        return Map.copyOf(result);
    }

    public static AuthzFreshness decodeHead(String json) {
        if (json == null || json.length() > MAX_JSON_BYTES) throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
        return decode(json.getBytes(StandardCharsets.UTF_8), AuthzFreshness.class);
    }

    @Override public void onMessage(Message message, byte[] pattern) {
        if (!java.util.Arrays.equals(channel, message.getChannel())) return;
        try {
            Notification event = decode(message.getBody(), Notification.class);
            if (monitor != null) monitor.onNotification(Long.toString(event.userId()), event.newRevision(), event.credentialEpoch());
        } catch (AuthzException ignored) {
            // Malformed trusted-channel data cannot grant scope; reconciliation still enforces expiry.
        }
    }
    private static <T> T decode(byte[] bytes, Class<T> type) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_JSON_BYTES) throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
        try {
            T value = JSON.readValue(bytes, type);
            if (value == null) throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
            return value;
        } catch (java.io.IOException | IllegalArgumentException failure) {
            throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
        }
    }
    /** Exact writer JSON: newRevision and numeric userId, not a guessed alternative envelope. */
    public record Notification(String eventId, long userId, long newRevision, long credentialEpoch, String reason) {
        public Notification {
            if (eventId == null || !eventId.matches("[A-Za-z0-9._-]{1,64}") || userId <= 0 || newRevision <= 0
                    || credentialEpoch < 0 || reason == null || !reason.matches("[A-Z0-9_]{1,96}")) {
                throw new IllegalArgumentException("Invalid authorization notification");
            }
        }
        @Override public String toString() { return "AuthorizationNotification[REDACTED]"; }
    }
    @Override public String toString() { return "AgentAuthorizationRedisReader[REDACTED]"; }
}
