package com.bkanent.contract.config;

/**
 * Canonical provider names and legacy aliases used by contract integrations.
 */
public final class ContractProviderNames {

    public static final String OCR_MOCK = "mock-ocr-provider";
    public static final String OCR_VENDOR = "vendor-ocr-provider";

    public static final String ESIGN_MOCK = "mock-esign-provider";
    public static final String ESIGN_CN = "esign-cn";
    public static final String ESIGN_FADADA = "fadada";

    public static final String ALIAS_MOCK = "mock";
    public static final String ALIAS_DASHSCOPE = "dashscope";
    public static final String ALIAS_VENDOR = "vendor";
    public static final String ALIAS_THIRD_PARTY_OCR = "third-party-ocr";
    public static final String ALIAS_ESIGN_CN_ALT = "esign_cn";

    private ContractProviderNames() {
    }
}
