package com.bkanent.common.rpc;

import java.io.Serializable;

/**
 * Owner-profile verification result. VERIFIED carries the profile summary;
 * the other results never distinguish missing, deleted and unauthorized profiles beyond their code.
 */
public record CustomerProfileVerifyResponse(String requestId, String result, Long customerId,
                                            String profileType, Long brokerId,
                                            Long updatedAtEpochSecond) implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String VERIFIED = "VERIFIED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String NOT_ACCESSIBLE = "NOT_ACCESSIBLE";
    public static final String INVALID_PROFILE_TYPE = "INVALID_PROFILE_TYPE";

    public CustomerProfileVerifyResponse {
        if (result == null || !result.matches("[A-Z_]+") || result.length() > 32) {
            throw new IllegalArgumentException("Invalid customer profile verify result");
        }
        if (requestId == null || requestId.isBlank() || requestId.length() > 128) {
            throw new IllegalArgumentException("Invalid customer profile verify requestId");
        }
        if (VERIFIED.equals(result)) {
            if (customerId == null || customerId <= 0 || profileType == null || profileType.isBlank()) {
                throw new IllegalArgumentException("Verified customer profile requires identity and type");
            }
        } else if (customerId != null || profileType != null || brokerId != null
                || updatedAtEpochSecond != null) {
            throw new IllegalArgumentException("Non-verified customer profile must not carry profile facts");
        }
    }
}
