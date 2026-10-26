package com.bkanent.marketing.campaign;

import com.bkanent.common.marketing.campaign.CampaignErrorCode;
import org.springframework.http.HttpStatus;

public class CampaignDomainException extends RuntimeException {

    private final CampaignErrorCode code;
    private final HttpStatus status;

    public CampaignDomainException(CampaignErrorCode code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public CampaignErrorCode code() { return code; }
    public HttpStatus status() { return status; }

    public static CampaignDomainException invalid(String message) {
        return new CampaignDomainException(CampaignErrorCode.INVALID_ARGUMENT, HttpStatus.BAD_REQUEST, message);
    }

    public static CampaignDomainException missing(CampaignErrorCode code, String message) {
        return new CampaignDomainException(code, HttpStatus.NOT_FOUND, message);
    }

    public static CampaignDomainException conflict(CampaignErrorCode code, String message) {
        return new CampaignDomainException(code, HttpStatus.CONFLICT, message);
    }

    public static CampaignDomainException unauthorized(String message) {
        return new CampaignDomainException(CampaignErrorCode.AUTHENTICATION_REQUIRED, HttpStatus.UNAUTHORIZED, message);
    }

    public static CampaignDomainException forbidden(String message) {
        return new CampaignDomainException(CampaignErrorCode.PERMISSION_DENIED, HttpStatus.FORBIDDEN, message);
    }

    public static CampaignDomainException unavailable(CampaignErrorCode code, String message) {
        return new CampaignDomainException(code, HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    public static CampaignDomainException legacyWriteDisabled(String message) {
        return new CampaignDomainException(CampaignErrorCode.LEGACY_WRITE_DISABLED, HttpStatus.GONE, message);
    }
}
