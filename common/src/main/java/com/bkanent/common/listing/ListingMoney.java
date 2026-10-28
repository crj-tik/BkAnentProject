package com.bkanent.common.listing;

import java.io.Serializable;
import java.math.BigDecimal;

/** Monetary amount serialized as a decimal string to avoid JSON number precision loss. */
public record ListingMoney(String amount, String currency, Unit unit) implements Serializable {
    public enum Unit { YUAN }

    public ListingMoney {
        if (amount == null || !amount.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")
                || new BigDecimal(amount).signum() < 0) {
            throw new IllegalArgumentException("Invalid monetary amount");
        }
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Invalid currency");
        }
        if (unit == null) throw new IllegalArgumentException("Missing monetary unit");
    }
}
