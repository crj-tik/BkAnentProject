package com.bkanent.common.memory;

import java.util.Objects;

/** Safe failure with no provider text, payload, endpoint or cause constructor. */
public final class MemoryException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final MemoryErrorCode code;
    private final Field field;

    public enum Field {
        IDENTITY, ASSOCIATION, VERSION, SOURCE, HASH, ROLE, TEXT, TOOL_CALL,
        TOOL_RESPONSE, TOOL_GROUP, JSON, COVERAGE, SUMMARY, SCOPE, EVIDENCE,
        PREFERENCE, CURSOR, PAGE, SCORE, GRANT, EXPORT, STATE
    }

    public MemoryException(MemoryErrorCode code) { this(code, null); }

    public MemoryException(MemoryErrorCode code, Field field) {
        super(Objects.requireNonNull(code).name(), null, false, false);
        this.code = code;
        this.field = field;
    }

    public MemoryErrorCode code() { return code; }
    public Field field() { return field; }

    @Override public String getMessage() {
        return code.name() + (field == null ? "" : " (" + field.name() + ")");
    }

    @Override public String toString() { return "MemoryException[" + getMessage() + "]"; }
}
