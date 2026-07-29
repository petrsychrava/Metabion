package com.metabion.repository;

import com.metabion.domain.PatientProfile;
import com.metabion.domain.RedFlagEvaluationRun;
import com.metabion.domain.RedFlagRuleVersion;
import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RedFlagSourceOperation;
import com.metabion.domain.RedFlagSourceType;
import com.metabion.domain.RedFlagTriggerEvent;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import jakarta.persistence.EntityManager;
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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class RedFlagEvaluationRepositoryTest {

    private static final Long SOURCE_ID = 812L;
    private static final Instant MATCHED_AT = Instant.parse("2026-07-29T11:00:00Z");
    private static final Instant REMOVED_AT = Instant.parse("2026-07-29T12:00:00Z");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired UserRepository users;
    @Autowired PatientProfileRepository patientProfiles;
    @Autowired RedFlagRuleVersionRepository versions;
    @Autowired RedFlagEvaluationRunRepository runs;
    @Autowired RedFlagTriggerEventRepository events;
    @Autowired EntityManager entityManager;

    private PatientProfile patient;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void setUp() {
        var user = new User("red-flag-audit@example.com", "hash");
        user.setEnabled(true);
        user.addRole(RoleName.PATIENT);
        patient = patientProfiles.saveAndFlush(new PatientProfile(users.saveAndFlush(user)));
    }

    @Test
    void noMatchRemovalSupersedesMatchingUpsertAndPreservesExactEventEvidence() {
        var severePain = activeSymptomVersion("SYM_SEVERE_ABDOMINAL_PAIN");
        var significantBleeding = activeSymptomVersion("SYM_SIGNIFICANT_BLEEDING");
        var matched = RedFlagEvaluationRun.pending(
                patient, RedFlagSourceType.SYMPTOM_CHECK_IN, SOURCE_ID,
                RedFlagSourceOperation.UPSERT, MATCHED_AT, RedFlagSeverity.EMERGENCY);
        runs.saveAndFlush(matched);
        matched.markCurrent();
        runs.saveAndFlush(matched);
        events.saveAndFlush(new RedFlagTriggerEvent(
                matched, significantBleeding, significantBleeding.getConditionGroups().getFirst(),
                RedFlagSeverity.EMERGENCY, MATCHED_AT.plusSeconds(2),
                "{\"facts\":[{\"factKey\":\"symptom.blood_in_stool\"}]}"));
        events.saveAndFlush(new RedFlagTriggerEvent(
                matched, severePain, severePain.getConditionGroups().getFirst(),
                RedFlagSeverity.EMERGENCY, MATCHED_AT.plusSeconds(1),
                "{\"facts\":[{\"factKey\":\"symptom.abdominal_pain\"}]}"));

        var removal = RedFlagEvaluationRun.pending(
                patient, RedFlagSourceType.SYMPTOM_CHECK_IN, SOURCE_ID,
                RedFlagSourceOperation.REMOVE, REMOVED_AT, null);
        runs.saveAndFlush(removal);
        var previous = runs.findCurrentForUpdate(RedFlagSourceType.SYMPTOM_CHECK_IN, SOURCE_ID)
                .orElseThrow();
        previous.supersedeWith(removal);
        runs.saveAndFlush(previous);
        removal.markCurrent();
        runs.saveAndFlush(removal);
        entityManager.clear();

        var current = runs.findByPatientProfileIdAndCurrentTrueOrderByEvaluatedAtDescIdDesc(patient.getId());
        assertThat(current).singleElement().satisfies(run -> {
            assertThat(run.getId()).isEqualTo(removal.getId());
            assertThat(run.getSourceOperation()).isEqualTo(RedFlagSourceOperation.REMOVE);
            assertThat(run.getOverallSeverity()).isNull();
            assertThat(run.isCurrent()).isTrue();
            assertThat(run.getEvents()).isEmpty();
        });

        var history = runs.findByPatientProfileIdOrderByEvaluatedAtDescIdDesc(patient.getId());
        assertThat(history).extracting(RedFlagEvaluationRun::getId)
                .containsExactly(removal.getId(), matched.getId());
        assertThat(history.get(1).isCurrent()).isFalse();
        assertThat(history.get(1).getSupersededByRun().getId()).isEqualTo(removal.getId());
        assertThat(history.get(1).getEvents())
                .extracting(event -> event.getRuleVersion().getRule().getStableKey())
                .containsExactly("SYM_SEVERE_ABDOMINAL_PAIN", "SYM_SIGNIFICANT_BLEEDING");
        assertThat(history.get(1).getEvents()).allSatisfy(event -> {
            assertThat(event.getMatchedGroup().getRuleVersion().getId())
                    .isEqualTo(event.getRuleVersion().getId());
            assertThat(event.getSeverity()).isEqualTo(RedFlagSeverity.EMERGENCY);
            assertThat(event.getMatchedInputs()).startsWith("{\"facts\":");
        });
    }

    @Test
    void databaseRejectsTwoCurrentRunsForOneSource() {
        var first = RedFlagEvaluationRun.pending(
                patient, RedFlagSourceType.LAB_RESULT_SET, SOURCE_ID,
                RedFlagSourceOperation.UPSERT, MATCHED_AT, RedFlagSeverity.ROUTINE_REVIEW);
        first.markCurrent();
        runs.saveAndFlush(first);
        var second = RedFlagEvaluationRun.pending(
                patient, RedFlagSourceType.LAB_RESULT_SET, SOURCE_ID,
                RedFlagSourceOperation.UPSERT, REMOVED_AT, RedFlagSeverity.URGENT_REVIEW);
        second.markCurrent();

        assertThatThrownBy(() -> runs.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private RedFlagRuleVersion activeSymptomVersion(String stableKey) {
        return versions.findByStatusAndTriggerSource(
                        com.metabion.domain.RedFlagRuleStatus.ACTIVE,
                        RedFlagSourceType.SYMPTOM_CHECK_IN).stream()
                .filter(version -> version.getRule().getStableKey().equals(stableKey))
                .findFirst()
                .orElseThrow();
    }
}
