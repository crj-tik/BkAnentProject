package com.bkanent.common.rag;

import java.io.Serializable;

/** Process-local short-lived reference. Never log or store in ordinary runtime facts. */
public record A2aBeacon(String value, long expiresAtEpochSecond) implements Serializable {
    @Override public String toString() { return "A2aBeacon[REDACTED]"; }
}
