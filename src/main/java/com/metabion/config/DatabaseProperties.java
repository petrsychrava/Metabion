package com.metabion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

@ConfigurationProperties(prefix = "metabion")
public record DatabaseProperties(DatabaseVendor database) {

    public DatabaseProperties {
        Objects.requireNonNull(database, "database");
    }
}
