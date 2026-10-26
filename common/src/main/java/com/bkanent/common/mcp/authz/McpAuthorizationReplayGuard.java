package com.bkanent.common.mcp.authz;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Bounded, process-local one-shot nonce guard; request proofs are at most 60 seconds. */
public final class McpAuthorizationReplayGuard {
    private final int maxEntries;
    private final Map<String, Long> expiries = new HashMap<>();

    public McpAuthorizationReplayGuard(int maxEntries) {
        if (maxEntries < 1 || maxEntries > 1_000_000) throw new IllegalArgumentException("invalid replay capacity");
        this.maxEntries = maxEntries;
    }

    public synchronized void consume(String caller, String audience, String nonce, long expiresAt, long now) {
        String key = caller + "\u0000" + audience + "\u0000" + nonce;
        Long previous = expiries.get(key);
        if (previous != null && previous > now) throw new AuthzException(AuthzErrorCode.REPLAY_DETECTED);
        if (previous != null) expiries.remove(key);
        if (expiries.size() >= maxEntries) {
            Iterator<Map.Entry<String, Long>> iterator = expiries.entrySet().iterator();
            while (iterator.hasNext()) if (iterator.next().getValue() <= now) iterator.remove();
        }
        if (expiries.size() >= maxEntries) throw new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
        expiries.put(key, expiresAt);
    }
}
