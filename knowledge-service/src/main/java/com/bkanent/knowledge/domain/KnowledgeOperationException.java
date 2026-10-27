package com.bkanent.knowledge.domain;

public class KnowledgeOperationException extends RuntimeException {
    private final String code;
    private final int status;

    public KnowledgeOperationException(String code, int status) {
        super(code);
        this.code = code;
        this.status = status;
    }

    public String code() { return code; }
    public int status() { return status; }
    @Override public String toString() { return "KnowledgeOperationException[code=" + code + "]"; }
}
