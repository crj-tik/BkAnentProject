package com.bkanent.agent.service;

/** Prevents blindly re-submitting a child task when the remote acceptance result is unknown. */
public class A2aTaskSubmissionOutcomeUnknownException extends RuntimeException {

    public A2aTaskSubmissionOutcomeUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
