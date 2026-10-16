package com.bkanent.common.memory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Structural bounds only. These checks do not authenticate identities or authorize content. */
final class MemoryContract {
    static final int MAX_LIST = 500;
    static final int MAX_TEXT = 32768;

    private MemoryContract() { }

    static void require(boolean condition, MemoryException.Field field) {
        if (!condition) throw new MemoryException(MemoryErrorCode.INVALID_ARGUMENT, field);
    }

    static String id(String value, MemoryException.Field field) {
        require(value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:%-]{0,255}"), field);
        return value;
    }

    static String optionalId(String value, MemoryException.Field field) {
        return value == null ? null : id(value, field);
    }

    static String text(String value, MemoryException.Field field) {
        require(value != null && !value.isBlank() && value.length() <= MAX_TEXT, field);
        return value;
    }

    static String optionalText(String value, MemoryException.Field field) {
        if (value != null) require(value.length() <= MAX_TEXT, field);
        return value;
    }

    static <T> T required(T value, MemoryException.Field field) {
        require(value != null, field);
        return value;
    }

    static <T> List<T> list(List<T> values, MemoryException.Field field) {
        if (values == null) return List.of();
        require(values.size() <= MAX_LIST && values.stream().noneMatch(Objects::isNull), field);
        return List.copyOf(values);
    }

    static <T> List<T> nonempty(List<T> values, MemoryException.Field field) {
        List<T> copy = list(values, field);
        require(!copy.isEmpty(), field);
        return copy;
    }

    static <T> List<T> unique(List<T> values, MemoryException.Field field) {
        List<T> copy = list(values, field);
        require(copy.stream().distinct().count() == copy.size(), field);
        return copy;
    }

    static void unit(double value) {
        require(Double.isFinite(value) && value >= 0 && value <= 1, MemoryException.Field.SCORE);
    }

    static void interval(Instant from, Instant until, MemoryException.Field field) {
        require(until == null || from != null && until.isAfter(from), field);
    }
}
