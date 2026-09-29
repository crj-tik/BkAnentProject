package com.bkanent.interview.engine;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 防重复：bigram 语义相似度 ≥0.72 判为换皮重问即拦截。
 */
public final class RepetitionGuard {

    private RepetitionGuard() {
    }

    public static final double SIMILARITY_THRESHOLD = 0.72;

    /**
     * 判定候选问句是否与近期已问问题构成换皮重问。
     *
     * @param candidate 候选问句
     * @param asked     近期已问问题台账
     */
    public static boolean isRephrasedRepeat(String candidate, List<String> asked) {
        if (candidate == null || candidate.isBlank() || asked == null || asked.isEmpty()) {
            return false;
        }
        Set<String> candidateBigrams = bigrams(candidate);
        if (candidateBigrams.isEmpty()) {
            return false;
        }
        for (String prior : asked) {
            if (similarity(candidateBigrams, bigrams(prior)) >= SIMILARITY_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    /** Dice 系数：2|A∩B| / (|A|+|B|)。 */
    static double similarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        return 2.0 * intersection.size() / (a.size() + b.size());
    }

    static Set<String> bigrams(String text) {
        Set<String> grams = new HashSet<>();
        if (text == null) {
            return grams;
        }
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        for (int i = 0; i + 1 < normalized.length(); i++) {
            grams.add(normalized.substring(i, i + 2));
        }
        return grams;
    }
}
