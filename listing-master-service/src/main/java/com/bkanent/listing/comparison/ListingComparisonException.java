package com.bkanent.listing.comparison;

import org.springframework.http.HttpStatus;

public class ListingComparisonException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    private ListingComparisonException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static ListingComparisonException invalidRequest() {
        return new ListingComparisonException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT");
    }

    public static ListingComparisonException staleSource() {
        return new ListingComparisonException(HttpStatus.CONFLICT, "SOURCE_VERSION_CONFLICT");
    }

    public static ListingComparisonException sourceUnavailable() {
        return new ListingComparisonException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
    }
}
