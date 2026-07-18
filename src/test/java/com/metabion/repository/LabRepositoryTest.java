package com.metabion.repository;

import com.metabion.domain.LabResult;
import com.metabion.domain.LabResultConfirmationStatus;
import com.metabion.domain.LabResultSet;
import com.metabion.domain.LabResultSource;
import com.metabion.domain.LabTestUnitDefinition;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class LabRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-07-10T10:00:00Z");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired UserRepository users;
    @Autowired PatientProfileRepository patientProfiles;
    @Autowired LabTestDefinitionRepository definitions;
    @Autowired LabResultSetRepository resultSets;

    private PatientProfile patient;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void setUp() {
        var user = new User("lab-patient@example.com", "hash");
        user.setEnabled(true);
        user.addRole(RoleName.PATIENT);
        patient = patientProfiles.saveAndFlush(new PatientProfile(users.saveAndFlush(user)));
    }

    @Test
    void migrationSeedsCatalogAndUnits() {
        var crp = definitions.findByCodeAndActiveTrue("CRP").orElseThrow();

        assertThat(crp.getCanonicalUnit()).isEqualTo("mg/L");
        assertThat(crp.getUnits()).extracting(LabTestUnitDefinition::getUnitCode)
                .containsExactly("mg/L", "mg/dL");
    }

    @Test
    void resultSetPersistsPanelAndVersion() {
        var crp = definitions.findByCodeAndActiveTrue("CRP").orElseThrow();
        var set = new LabResultSet(patient, LocalDate.of(2026, 7, 10), null,
                LabResultSource.MANUAL, LabResultConfirmationStatus.CONFIRMED, patient.getUser(), NOW);
        set.replaceResults(List.of(new LabResult(set, crp, new BigDecimal("1.20"), "mg/dL",
                new BigDecimal("12.00"), "mg/L", BigDecimal.ZERO, new BigDecimal("0.50"))), NOW);

        var saved = resultSets.saveAndFlush(set);

        assertThat(saved.getVersion()).isZero();
        assertThat(saved.getResults()).singleElement()
                .extracting(LabResult::getCanonicalValue).isEqualTo(new BigDecimal("12.000000"));
    }

    @Test
    void duplicateBiomarkerWithinSetIsRejected() {
        assertThatThrownBy(this::saveTwoCrpRowsForOneSet)
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void removedSetIsAbsentFromOrdinaryQuery() {
        var set = new LabResultSet(patient, LocalDate.of(2026, 7, 10), null,
                LabResultSource.MANUAL, LabResultConfirmationStatus.CONFIRMED, patient.getUser(), NOW);
        set.markRemoved(patient.getUser(), "duplicate", NOW.plusSeconds(60));
        resultSets.saveAndFlush(set);

        assertThat(resultSets.findActiveByPatientAndCollectionDateBetween(
                patient.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))).isEmpty();
    }

    private void saveTwoCrpRowsForOneSet() {
        var crp = definitions.findByCodeAndActiveTrue("CRP").orElseThrow();
        var set = new LabResultSet(patient, LocalDate.of(2026, 7, 10), null,
                LabResultSource.MANUAL, LabResultConfirmationStatus.CONFIRMED, patient.getUser(), NOW);
        set.replaceResults(List.of(
                new LabResult(set, crp, BigDecimal.ONE, "mg/L", BigDecimal.ONE, "mg/L", null, null),
                new LabResult(set, crp, BigDecimal.TEN, "mg/L", BigDecimal.TEN, "mg/L", null, null)), NOW);
        resultSets.saveAndFlush(set);
    }
}
