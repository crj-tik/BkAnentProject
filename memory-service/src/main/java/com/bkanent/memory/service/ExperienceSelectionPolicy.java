package com.bkanent.memory.service;

import com.bkanent.common.memory.MemoryExperience;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic, versioned ranking. It never mutates the experience or its evidence. */
final class ExperienceSelectionPolicy {
    static final String VERSION = "experience-selection-v1";
    private static final Pattern WORD = Pattern.compile("[a-z0-9]{2,}|[\\u3400-\\u9fff]");

    double relevance(String objective, String capabilityTag, MemoryExperience experience) {
        if ((objective == null || objective.isBlank()) && capabilityTag == null) return 1.0;
        Set<String> query = tokens((objective == null ? "" : objective) + " " + (capabilityTag == null ? "" : capabilityTag));
        if (query.isEmpty()) return 0.0;
        Set<String> content = tokens(experience.scenario() + " " + experience.applicability() + " " + experience.conclusion());
        long overlap = query.stream().filter(content::contains).count();
        return Math.min(1.0, (double) overlap / query.size());
    }

    double timeWeight(Instant lastValidatedAt, Instant lastObservedAt, Instant asOf, int halfLifeDays) {
        Instant anchor = lastValidatedAt == null ? lastObservedAt : lastValidatedAt;
        long days = Math.max(0, Duration.between(anchor, asOf).toDays());
        return Math.max(0.05, Math.pow(0.5, (double) days / halfLifeDays));
    }

    int estimatedTokens(byte[] bytes) {
        // UTF-8 bytes / 3 is conservative for multilingual text and remains deterministic across workers.
        return Math.max(1, (bytes.length + 2) / 3);
    }

    private static Set<String> tokens(String value) {
        Set<String> result = new HashSet<>();
        Matcher matcher = WORD.matcher(value.toLowerCase(Locale.ROOT));
        while (matcher.find()) result.add(matcher.group());
        return result;
    }
}
