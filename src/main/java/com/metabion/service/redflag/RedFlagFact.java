package com.metabion.service.redflag;

import java.math.BigDecimal;

public record RedFlagFact(
        String key, BigDecimal decimalValue, String textValue, String unit) {

    public RedFlagFact {
        if ((decimalValue == null) == (textValue == null)) {
            throw new IllegalArgumentException("A red-flag fact must have exactly one value");
        }
    }
}
