package com.bkanent.listing.model;

import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * ListingPageCursor 稳定分页游标（updated_at DESC, id DESC 键集分页）。
 * 编码为 base64url(ISO_LOCAL_DATE_TIME + "|" + id)；解码失败按 INVALID_ARGUMENT 拒绝，不猜测语义。
 */
public record ListingPageCursor(LocalDateTime lastUpdatedAt, long lastId) {

    /**
     * 编码游标。
     */
    public String encode() {
        if (lastUpdatedAt == null) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "cursor");
        }
        String value = lastUpdatedAt.toString() + "|" + lastId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解码游标；空游标返回 null（首页），非法格式失败关闭。
     */
    public static ListingPageCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(cursor.trim());
        } catch (IllegalArgumentException failure) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "cursor");
        }
        String value = new String(decoded, StandardCharsets.UTF_8);
        int separator = value.lastIndexOf('|');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "cursor");
        }
        try {
            return new ListingPageCursor(LocalDateTime.parse(value.substring(0, separator)),
                    Long.parseLong(value.substring(separator + 1)));
        } catch (DateTimeParseException | NumberFormatException failure) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "cursor");
        }
    }
}
