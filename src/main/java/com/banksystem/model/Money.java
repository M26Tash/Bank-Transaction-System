package com.banksystem.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {

    public static final String CURRENCY = "USD";

    public static final long MAX_AMOUNT_CENTS = 100_000_000_000L;

    private Money() {
    }

    public static long parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Amount is required");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(input.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a valid amount: " + input.trim());
        }
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }
        if (value.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Amount can have at most 2 decimal places");
        }
        BigDecimal cents = value.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY);
        if (cents.compareTo(BigDecimal.valueOf(MAX_AMOUNT_CENTS)) > 0) {
            throw new IllegalArgumentException("Amount is too large (max " + format(MAX_AMOUNT_CENTS) + ")");
        }
        return cents.longValueExact();
    }

    public static String format(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString() + " " + CURRENCY;
    }
}
