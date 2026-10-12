package com.bkanent.common.rag;

import java.io.Serializable;

/** All filters are AND; IN is OR only within a single registered field. */
public record RagFilter(String field, RagFilterOperator op, RagValue value) implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagFilter {
        RagContract.text(field, "field");
        RagContract.require(field.matches("[a-zA-Z][a-zA-Z0-9]{0,63}"), "field");
        RagContract.require(op != null && value != null, "filter");
        boolean multiple = value.type() == RagValueType.STRING_LIST || value.type() == RagValueType.NUMBER_LIST;
        RagContract.require((op == RagFilterOperator.IN) == multiple, "op");
        if (op == RagFilterOperator.GTE || op == RagFilterOperator.LTE) {
            RagContract.require(value.type() == RagValueType.NUMBER || value.type() == RagValueType.INSTANT, "value");
        }
        if (op == RagFilterOperator.CONTAINS_CI) RagContract.require(value.type() == RagValueType.STRING, "value");
    }
}
