package com.bkanent.agent.service;

/** Non-retryable failure raised when a remote A2A task exceeds its configured deadline. */
public class A2aTaskDeadlineExceededException extends RuntimeException {

    public A2aTaskDeadlineExceededException(String message) {
        super(message);
    }

    public A2aTaskDeadlineExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
