package com.bkanent.agent.security;

/** Local absence/lease expiry is distinct from a remote RAG permission refusal. */
public final class AgentRagAuthenticationException extends RuntimeException {
    private final String code;
    public AgentRagAuthenticationException(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
