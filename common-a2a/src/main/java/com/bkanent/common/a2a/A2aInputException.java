package com.bkanent.common.a2a;

public final class A2aInputException extends RuntimeException {

    private final String code;

    public A2aInputException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
