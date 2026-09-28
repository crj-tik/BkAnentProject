package com.bkanent.agent.service;

/** Retryable failure raised when one remote A2A status request exceeds its own timeout. */
public class A2aStatusQueryTimeoutException extends RuntimeException {

    public A2aStatusQueryTimeoutException(String message) {
        super(message);
    }
}
