package com.bkanent.common.rpc;

/** USER memory HTTP transport: genuine Authorization Bearer plus owner assertion checked against auth. */
public final class MemoryTransportHeaders {
    public static final String OWNER = "X-Memory-Owner";
    private MemoryTransportHeaders() { }
}
