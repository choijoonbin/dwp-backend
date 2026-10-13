package com.dwp.services.people.hr.performance.typedartifact;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

/** Canonical DECIMAL(19,4) lexical value. Binary floating point is never accepted. */
public final class PostedMoney {

    private static final BigDecimal MAXIMUM = new BigDecimal("999999999999999.9999");

    private static final Pattern CANONICAL = Pattern.compile(
            "^(?:0|[1-9][0-9]{0,14})(?:\\.[0-9]{1,4})?$");

    private PostedMoney() {
    }

    public static String require(String value) {
        Objects.requireNonNull(value, "money must not be null");
        if (!CANONICAL.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "money must be a canonical non-negative p19/s4 decimal string");
        }
        BigDecimal decimal = new BigDecimal(value);
        if (decimal.precision() > 19 || decimal.scale() > 4) {
            throw new IllegalArgumentException("money exceeds DECIMAL(19,4)");
        }
        String normalized = decimal.signum() == 0
                ? "0" : decimal.stripTrailingZeros().toPlainString();
        if (!normalized.equals(value)) {
            throw new IllegalArgumentException("money contains non-canonical trailing zeros");
        }
        return normalized;
    }

    public static BigDecimal decimal(String value) {
        return new BigDecimal(require(value));
    }

    /** Bounds an aggregate, not only its individually canonical operands, to NUMERIC(19,4). */
    public static BigDecimal requireAggregate(BigDecimal value) {
        Objects.requireNonNull(value, "money aggregate must not be null");
        BigDecimal fixed = value.setScale(4, RoundingMode.UNNECESSARY);
        if (fixed.abs().compareTo(MAXIMUM) > 0) {
            throw new IllegalArgumentException("money aggregate exceeds DECIMAL(19,4)");
        }
        return fixed;
    }

    public static BigDecimal addExact(BigDecimal left, BigDecimal right) {
        return requireAggregate(left.add(right));
    }
}
