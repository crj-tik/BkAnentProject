package com.bkanent.businessreview.domain;
public class ReviewDependencyException extends RuntimeException {
    private final String code;
    public ReviewDependencyException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
