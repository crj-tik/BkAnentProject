package com.bkanent.agent.mcp;

/** Explicit synchronous call scope consumed by the MCP SDK transport-context provider. */
public final class AgentMcpAuthorizationScope {
    private static final ThreadLocal<AgentMcpAuthorizationInvocation> CURRENT = new ThreadLocal<>();

    private AgentMcpAuthorizationScope() { }

    public static AgentMcpAuthorizationInvocation current() { return CURRENT.get(); }

    public static AutoCloseable open(AgentMcpAuthorizationInvocation invocation) {
        AgentMcpAuthorizationInvocation previous = CURRENT.get();
        CURRENT.remove();
        if (invocation != null) CURRENT.set(invocation);
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }
}
