package com.bkanent.common.rag;

import java.util.Objects;

/** Deliberately carries no provider message, cause, payload, credential or endpoint. */
public final class RagException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final RagErrorCode code;
    private final String field;

    public RagException(RagErrorCode code) {
        this(code, null);
    }

    public RagException(RagErrorCode code, String field) {
        super(Objects.requireNonNull(code).name());
        this.code = code;
        // Only a schema field name, never untrusted values or arbitrary exception text.
        this.field = field != null && field.matches("[a-zA-Z][a-zA-Z0-9.]{0,79}") ? field : null;
    }

    public RagErrorCode code() { return code; }
    public String field() { return field; }

    @Override
    public String getMessage() {
        return code.name() + (field == null ? "" : " (" + field + ")");
    }

    @Override
    public String toString() { return "RagException[" + getMessage() + "]"; }
}
