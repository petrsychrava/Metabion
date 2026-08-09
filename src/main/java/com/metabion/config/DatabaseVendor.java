package com.metabion.config;

import java.util.Locale;

public enum DatabaseVendor {
    POSTGRESQL,
    ORACLE;

    public static DatabaseVendor fromProperty(String property) {
        if (property == null) {
            throw unsupportedValue(property);
        }

        return switch (property.trim().toLowerCase(Locale.ROOT)) {
            case "postgresql" -> POSTGRESQL;
            case "oracle" -> ORACLE;
            default -> throw unsupportedValue(property);
        };
    }

    private static IllegalArgumentException unsupportedValue(String property) {
        return new IllegalArgumentException(
                "Unsupported database vendor '" + property + "'; accepted values are postgresql and oracle");
    }
}
