package com.metabion.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseVendorTest {

    @Test
    void acceptsPostgresqlAndOraclePropertyValues() {
        assertThat(DatabaseVendor.fromProperty("postgresql"))
                .isEqualTo(DatabaseVendor.POSTGRESQL);
        assertThat(DatabaseVendor.fromProperty("ORACLE"))
                .isEqualTo(DatabaseVendor.ORACLE);
    }

    @Test
    void rejectsUnsupportedDatabaseValues() {
        assertThatThrownBy(() -> DatabaseVendor.fromProperty("mysql"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("postgresql")
                .hasMessageContaining("oracle");
    }

    @Test
    void rejectsBlankDatabaseValues() {
        assertThatThrownBy(() -> DatabaseVendor.fromProperty(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
