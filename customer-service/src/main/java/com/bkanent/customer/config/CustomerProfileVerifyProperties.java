package com.bkanent.customer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Listing→Customer 卖方档案验证 RPC 的可信调用方配置。
 * 结构参照 listing 侧 ListingRagProperties.TrustedCaller：keyId 映射调用方服务与共享 HMAC 密钥。
 */
@Component
@ConfigurationProperties(prefix = "customer.profile-verify")
public class CustomerProfileVerifyProperties {

    /**
     * 可信调用方：keyId → {caller-service, secret}。
     */
    private Map<String, TrustedCaller> trustedCallers = new LinkedHashMap<>();

    public Map<String, TrustedCaller> getTrustedCallers() {
        return trustedCallers;
    }

    public void setTrustedCallers(Map<String, TrustedCaller> value) {
        trustedCallers = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }

    /**
     * 单个可信调用方配置。
     */
    public static class TrustedCaller {

        /**
         * 调用方服务名，例如 listing-master-service。
         */
        private String callerService;

        /**
         * 共享 HMAC 密钥，不足 32 字节时拒绝验签。
         */
        private String secret;

        public String getCallerService() {
            return callerService;
        }

        public void setCallerService(String value) {
            callerService = value;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String value) {
            secret = value;
        }
    }
}
