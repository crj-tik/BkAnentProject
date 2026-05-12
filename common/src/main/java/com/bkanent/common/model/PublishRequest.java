package com.bkanent.common.model;

import java.util.List;

/**
 * PublishRequest 请求对象。
 */

public record PublishRequest(
        /** 业务属性：listingId。 */
        Long listingId,
        /** 业务属性：platforms。 */
        List<String> platforms,
        /** 业务属性：prompt。 */
        String prompt
) {
}

