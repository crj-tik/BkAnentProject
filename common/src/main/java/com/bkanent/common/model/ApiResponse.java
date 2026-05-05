package com.bkanent.common.model;

public record ApiResponse<T>(boolean success, String code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "0", "OK", data);
    }

    public static <T> ApiResponse<T> fail(String code, String message) {
        return new ApiResponse<>(false, code, message, null);
    }
}
