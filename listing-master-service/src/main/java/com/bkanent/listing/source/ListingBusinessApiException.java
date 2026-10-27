package com.bkanent.listing.source;

import org.springframework.http.HttpStatus;

public class ListingBusinessApiException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    private ListingBusinessApiException(String code, HttpStatus status) {
        super(code);
        this.code = code;
        this.status = status;
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }

    public static ListingBusinessApiException invalidArgument() {
        return new ListingBusinessApiException("INVALID_ARGUMENT", HttpStatus.BAD_REQUEST);
    }

    public static ListingBusinessApiException unsupportedContractVersion() {
        return new ListingBusinessApiException("UNSUPPORTED_CONTRACT_VERSION", HttpStatus.BAD_REQUEST);
    }

    public static ListingBusinessApiException sourceVersionConflict() {
        return new ListingBusinessApiException("SOURCE_VERSION_CONFLICT", HttpStatus.CONFLICT);
    }

    public static ListingBusinessApiException dependencyUnavailable() {
        return new ListingBusinessApiException("DEPENDENCY_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
    }
}
