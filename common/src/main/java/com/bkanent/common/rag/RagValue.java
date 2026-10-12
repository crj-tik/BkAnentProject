package com.bkanent.common.rag;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Closed typed values, never raw backend expressions or arbitrary Java objects. */
public record RagValue(RagValueType type, String scalar, List<String> values) implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagValue {
        RagContract.require(type != null, "type");
        boolean multiple = type == RagValueType.STRING_LIST || type == RagValueType.NUMBER_LIST;
        RagContract.require(multiple ? scalar == null && values != null && !values.isEmpty()
                : scalar != null && (values == null || values.isEmpty()), "value");
        values = RagContract.list(values);
        RagContract.require(values.size() <= 5000, "values");
        try {
            switch (type) {
                case NUMBER -> scalar = decimal(scalar);
                case NUMBER_LIST -> values = values.stream().map(RagValue::decimal).toList();
                case BOOLEAN -> RagContract.require("true".equals(scalar) || "false".equals(scalar), "value");
                case INSTANT -> scalar = Instant.parse(scalar).toString();
                default -> { }
            }
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "value");
        }
    }

    private static String decimal(String value) {
        RagContract.require(value.length() <= 128, "value");
        BigDecimal number = new BigDecimal(value);
        RagContract.require(Math.abs((long) number.scale()) <= 1000 && number.precision() <= 128, "value");
        return number.signum() == 0 ? "0" : number.stripTrailingZeros().toPlainString();
    }

    public static RagValue string(String value) { return new RagValue(RagValueType.STRING, value, null); }
    public static RagValue number(Number value) { return new RagValue(RagValueType.NUMBER, value.toString(), null); }
    public static RagValue bool(boolean value) { return new RagValue(RagValueType.BOOLEAN, Boolean.toString(value), null); }
    public static RagValue instant(Instant value) { return new RagValue(RagValueType.INSTANT, value.toString(), null); }
    public static RagValue strings(List<String> values) { return new RagValue(RagValueType.STRING_LIST, null, values); }
    public static RagValue numbers(List<? extends Number> values) {
        return new RagValue(RagValueType.NUMBER_LIST, null, values.stream().map(Number::toString).toList());
    }
}
