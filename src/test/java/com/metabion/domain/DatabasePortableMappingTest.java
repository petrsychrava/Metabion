package com.metabion.domain;

import jakarta.persistence.Column;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabasePortableMappingTest {

    @Test
    void maps_large_text_and_binary_fields_to_portable_jdbc_types_without_column_definitions() throws Exception {
        List<PortableField> fields = List.of(
                new PortableField(Cohort.class, "description", SqlTypes.LONG32VARCHAR, "", true, true, 255),
                new PortableField(EducationLessonLocalization.class, "bodyMarkdown", SqlTypes.LONG32VARCHAR,
                        "body_markdown", false, true, 255),
                new PortableField(LabResultAuditEvent.class, "beforeSnapshot", SqlTypes.LONG32VARCHAR,
                        "before_snapshot", true, true, 255),
                new PortableField(LabResultAuditEvent.class, "afterSnapshot", SqlTypes.LONG32VARCHAR,
                        "after_snapshot", true, true, 255),
                new PortableField(RedFlagTriggerEvent.class, "matchedInputs", SqlTypes.LONG32VARCHAR,
                        "matched_inputs", false, false, 255),
                new PortableField(User.class, "mfaSecretEncrypted", SqlTypes.LONG32VARBINARY,
                        "mfa_secret_encrypted", true, true, 255));

        for (PortableField portableField : fields) {
            Field field = portableField.type().getDeclaredField(portableField.fieldName());
            JdbcTypeCode jdbcTypeCode = field.getAnnotation(JdbcTypeCode.class);
            Column column = field.getAnnotation(Column.class);

            assertNotNull(jdbcTypeCode, () -> portableField + " must declare a portable JDBC type");
            assertEquals(portableField.jdbcTypeCode(), jdbcTypeCode.value(), portableField::toString);
            assertNotNull(column, () -> portableField + " must retain its column metadata");
            assertEquals(portableField.columnName(), column.name(), portableField::toString);
            assertEquals(portableField.nullable(), column.nullable(), portableField::toString);
            assertEquals(portableField.updatable(), column.updatable(), portableField::toString);
            assertEquals(portableField.length(), column.length(), portableField::toString);
            assertTrue(column.columnDefinition().isBlank(), () -> portableField + " must not use columnDefinition");
        }
    }

    @Test
    void quotes_lowercase_resource_columns_without_changing_their_other_metadata() throws Exception {
        List<Class<?>> types = List.of(
                PatientAccessToken.class,
                ClinicalAccessToken.class,
                OAuthAuthorizationCode.class,
                OAuthRefreshToken.class);

        for (Class<?> type : types) {
            Column column = type.getDeclaredField("resource").getAnnotation(Column.class);

            assertNotNull(column, () -> type.getSimpleName() + ".resource must retain column metadata");
            assertEquals("\"resource\"", column.name(), type.getSimpleName());
            assertEquals(false, column.nullable(), type.getSimpleName());
            assertEquals(255, column.length(), type.getSimpleName());
            assertEquals(true, column.updatable(), type.getSimpleName());
        }
    }

    private record PortableField(Class<?> type, String fieldName, int jdbcTypeCode, String columnName,
                                 boolean nullable, boolean updatable, int length) {
    }
}
