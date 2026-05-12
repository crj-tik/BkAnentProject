package com.bkanent.common.model;

/**
 * AuthLoginRequest 请求对象。
 */

public record AuthLoginRequest(
        /** 业务属性：username。 */
        String username,
        /** 业务属性：password。 */
        String password,
        /** 业务属性：tenantCode。 */
        String tenantCode
) {
}

