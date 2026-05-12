package com.bkanent.common.model;

/**
 * AuthTokenDTO 数据传输对象。
 */

public record AuthTokenDTO(
        /** 业务属性：accessToken。 */
        String accessToken,
        /** 业务属性：refreshToken。 */
        String refreshToken,
        /** 业务属性：userId。 */
        Long userId,
        /** 业务属性：roleCode。 */
        String roleCode
) {
}

