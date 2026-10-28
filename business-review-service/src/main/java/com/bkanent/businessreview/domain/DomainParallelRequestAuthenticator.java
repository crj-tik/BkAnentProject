package com.bkanent.businessreview.domain;

@FunctionalInterface
public interface DomainParallelRequestAuthenticator {
    String authorizationHeader(byte[] body, String targetAudience, String operation,
                               String method, String targetPath);
}
