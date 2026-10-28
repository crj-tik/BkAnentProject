package com.bkanent.marketing.controller;

import com.bkanent.common.model.ApiResponse;
import com.bkanent.marketing.campaign.CampaignDomainException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.bkanent.marketing.controller")
public class CampaignExceptionHandler {
    @ExceptionHandler(CampaignDomainException.class)
    public ResponseEntity<ApiResponse<Void>> campaignFailure(CampaignDomainException failure) {
        return ResponseEntity.status(failure.status()).body(ApiResponse.fail(failure.code().name(), failure.getMessage()));
    }
}
