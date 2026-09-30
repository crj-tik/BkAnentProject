package com.bkanent.common.model;

/**
 * AuthRefreshRequest 请求对象。
 */

public record AuthRefreshRequest(
        /** 业务属性：refreshToken（login 响应下发，一次性轮换）。 */
        String refreshToken
) {
}
