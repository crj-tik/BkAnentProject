package com.bkanent.common.memory.fixture;

/**
 * Unregistered failure type whose name shares no allow-list prefix with the memory exception
 * contract; official wire deserialization must keep rejecting it under STRICT checking.
 */
public final class UntrustedFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public UntrustedFailure() { super("OFFLINE_FAILURE"); }
}
