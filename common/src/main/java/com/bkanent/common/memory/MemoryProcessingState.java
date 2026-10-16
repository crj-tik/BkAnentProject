package com.bkanent.common.memory;

/** Processing readiness is separate from execution completion and index readiness. */
public enum MemoryProcessingState {
    NOT_RECORDED, DELIVERY_PENDING, RECEIVED, ASSESSED, CONSOLIDATED, RETRY_PENDING, RETRY_EXHAUSTED, REJECTED
}
