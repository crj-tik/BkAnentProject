package com.bkanent.memory.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.regex.Pattern;

/** Stable taxonomy matching and time weighting; retrieval never invokes a model or mutates evidence. */
final class PreferenceSelectionPolicy {
    static final String VERSION = "preference-selection-v1";

    OptionalDouble relevance(String objective, List<String> aliases) {
        if (objective == null || objective.isBlank()) return OptionalDouble.empty();
        String normalized = normalize(objective);
        int objectiveLength = normalized.codePointCount(0, normalized.length());
        double best = 0;
        for (String alias : aliases) {
            String term = normalize(alias);
            if (term.isBlank() || !contains(normalized, term)) continue;
            double specificity = Math.min(1.0, (double) term.codePointCount(0, term.length()) / Math.max(1, objectiveLength));
            best = Math.max(best, normalized.equals(term) ? 1.0 : 0.65 + 0.35 * specificity);
        }
        return best == 0 ? OptionalDouble.empty() : OptionalDouble.of(best);
    }

    double timeWeight(Instant lastObservedAt, Instant asOf, int halfLifeDays) {
        double ageDays = Math.max(0, Duration.between(lastObservedAt, asOf).toMillis() / 86_400_000.0);
        return Math.pow(2.0, -ageDays / halfLifeDays);
    }

    int estimatedTokens(byte[] utf8) { return Math.max(1, (utf8.length + 2) / 3); }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]+", " ").trim();
    }

    private static boolean contains(String text, String term) {
        if (term.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)) {
            return text.contains(term);
        }
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(term) + "(?![\\p{L}\\p{N}])")
                .matcher(text).find();
    }
}
