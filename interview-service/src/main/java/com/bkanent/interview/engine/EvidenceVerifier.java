package com.bkanent.interview.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端证据核验：原声逐字命中核验（脱敏域内比对），命不中降级「转述」，
 * 缺失项三分类（interview_gap / external_verification / future_research）。
 *
 * <p>AI 只做抽取归纳；是否逐字命中由本类确定性判定。</p>
 */
public final class EvidenceVerifier {

    private EvidenceVerifier() {
    }

    /** 引用类型：VERBATIM=逐字命中，PARAPHRASE=转述。 */
    public enum QuoteStatus { VERBATIM, PARAPHRASE }

    /** 单条引用核验结果。 */
    public record VerifiedQuote(String quote, QuoteStatus status, String matchedAssetRef) {
    }

    /** 缺失项分类。 */
    public enum MissingClass { INTERVIEW_GAP, EXTERNAL_VERIFICATION, FUTURE_RESEARCH }

    /**
     * 核验报告草稿中的一条原声引用。
     *
     * @param quote      报告引用文本（脱敏域）
     * @param assetContent 逐字稿正文（脱敏域）——核验在脱敏域内自洽
     */
    public static VerifiedQuote verify(String quote, String assetContent) {
        if (quote == null || quote.isBlank()) {
            return new VerifiedQuote(quote, QuoteStatus.PARAPHRASE, null);
        }
        String normalizedQuote = normalize(quote);
        String normalizedContent = assetContent == null ? "" : normalize(assetContent);
        if (!normalizedContent.isEmpty() && normalizedContent.contains(normalizedQuote)) {
            return new VerifiedQuote(quote, QuoteStatus.VERBATIM, "asset");
        }
        return new VerifiedQuote(quote, QuoteStatus.PARAPHRASE, null);
    }

    /** 批量核验：全部引用的核验结果。 */
    public static List<VerifiedQuote> verifyAll(List<String> quotes, String assetContent) {
        List<VerifiedQuote> results = new ArrayList<>();
        if (quotes != null) {
            for (String quote : quotes) {
                results.add(verify(quote, assetContent));
            }
        }
        return results;
    }

    /** 逐字命中数。 */
    public static long verbatimCount(List<VerifiedQuote> verified) {
        return verified.stream().filter(v -> v.status() == QuoteStatus.VERBATIM).count();
    }

    /**
     * 缺失项三分类归类。
     *
     * @param missingItem 缺失描述
     */
    public static MissingClass classifyMissing(String missingItem) {
        String normalized = missingItem == null ? "" : missingItem.toLowerCase();
        if (normalized.contains("外部") || normalized.contains("竞品")
                || normalized.contains("成交记录") || normalized.contains("external")) {
            return MissingClass.EXTERNAL_VERIFICATION;
        }
        if (normalized.contains("后续") || normalized.contains("future")
                || normalized.contains("长期") || normalized.contains("复盘")) {
            return MissingClass.FUTURE_RESEARCH;
        }
        return MissingClass.INTERVIEW_GAP;
    }

    /** 缺失分类 JSON。 */
    public static String missingJson(Map<String, MissingClass> missingItems) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, MissingClass> entry : missingItems.entrySet()) {
            grouped.computeIfAbsent(entry.getValue().name(), k -> new ArrayList<>()).add(entry.getKey());
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(entry.getKey()).append("\":[");
            for (int i = 0; i < entry.getValue().size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(entry.getValue().get(i).replace("\"", "'")).append('"');
            }
            sb.append(']');
        }
        sb.append('}');
        return sb.toString();
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", "")
                .replace("「", "").replace("」", "")
                .replace("\"", "").replace("'", "")
                .replace("[", "").replace("]", "");
    }
}
