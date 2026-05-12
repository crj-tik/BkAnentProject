package com.bkanent.promotion.enums;

/**
 * 品牌素材类型枚举。
 */
public enum BrandAssetTypeEnum {

    STORE_PHOTO,
    POSTER_TEMPLATE,
    LOGO,
    VIDEO_TEMPLATE;

    public static boolean contains(String value) {
        for (BrandAssetTypeEnum typeEnum : values()) {
            if (typeEnum.name().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
