package com.bkanent.knowledge.source;

public class KnowledgeSourceException extends RuntimeException {
    private final SourceState state;

    public KnowledgeSourceException(SourceState state) {
        super(state.name());
        this.state = state;
    }

    public KnowledgeSourceException(SourceState state, Throwable cause) {
        super(state.name(), cause);
        this.state = state;
    }

    public SourceState state() { return state; }

    @Override public String toString() { return "KnowledgeSourceException[state=" + state + "]"; }
}
