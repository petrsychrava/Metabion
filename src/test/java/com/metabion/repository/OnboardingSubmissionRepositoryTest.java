package com.metabion.repository;

import com.metabion.domain.AdvancedTherapyExposure;
import com.metabion.domain.DiseaseActivityEstimate;
import com.metabion.domain.IbdDiagnosisType;
import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.domain.OnboardingSubmission;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.Sex;
import com.metabion.domain.SteroidUse;
import com.metabion.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class OnboardingSubmissionRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    UserRepository users;

    @Autowired
    PatientProfileRepository patientProfiles;

    @Autowired
    OnboardingSubmissionRepository submissions;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void latestLookupReturnsHighestVersionForPatientAndContext() {
        var patient = createPatient("latest@example.com");
        submissions.saveAndFlush(validSubmission(patient, "default", 1));
        var latest = submissions.saveAndFlush(validSubmission(patient, "default", 2));
        submissions.saveAndFlush(validSubmission(patient, "study-a", 1));

        assertThat(submissions.findFirstByPatientProfileIdAndOnboardingContextOrderByVersionDesc(
                patient.getId(), "default")).contains(latest);
    }

    @Test
    void historyIsOrderedNewestFirstForPatientAndContext() {
        var patient = createPatient("history@example.com");
        var v1 = submissions.saveAndFlush(validSubmission(patient, "default", 1));
        var v2 = submissions.saveAndFlush(validSubmission(patient, "default", 2));
        submissions.saveAndFlush(validSubmission(patient, "study-a", 1));

        assertThat(submissions.findByPatientProfileIdAndOnboardingContextOrderByVersionDesc(
                patient.getId(), "default")).containsExactly(v2, v1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void versionIsUniquePerPatientAndContext() {
        var patient = createPatient("unique@example.com");
        submissions.saveAndFlush(validSubmission(patient, "default", 1));

        assertThatThrownBy(() -> submissions.saveAndFlush(validSubmission(patient, "default", 1)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(submissions.saveAndFlush(validSubmission(patient, "other", 1)).getId()).isNotNull();
    }

    @Test
    void reviewMetadataCanBeStoredWithoutChangingSubmittedValues() {
        var patient = createPatient("review@example.com");
        var reviewer = createUser("reviewer@example.com", RoleName.PHYSICIAN);
        var submission = submissions.saveAndFlush(validSubmission(patient, "default", 1));

        submission.review(OnboardingReviewStatus.REVIEWED, reviewer, "Baseline looks complete.");
        submissions.saveAndFlush(submission);

        var loaded = submissions.findById(submission.getId()).orElseThrow();
        assertThat(loaded.getReviewStatus()).isEqualTo(OnboardingReviewStatus.REVIEWED);
        assertThat(loaded.getReviewedBy().getEmail()).isEqualTo("reviewer@example.com");
        assertThat(loaded.getReviewedAt()).isNotNull();
        assertThat(loaded.getReviewNotes()).isEqualTo("Baseline looks complete.");
        assertThat(loaded.getDiagnosisType()).isEqualTo(IbdDiagnosisType.CROHNS_DISEASE);
    }

    private PatientProfile createPatient(String email) {
        return patientProfiles.saveAndFlush(new PatientProfile(createUser(email, RoleName.PATIENT)));
    }

    private User createUser(String email, RoleName role) {
        var user = users.saveAndFlush(new User(email, "hash"));
        user.addRole(role);
        return users.saveAndFlush(user);
    }

    private OnboardingSubmission validSubmission(PatientProfile patient, String context, int version) {
        var submission = new OnboardingSubmission(patient, context, version);
        submission.setSubmittedAt(OffsetDateTime.parse("2026-05-31T12:00:00Z").toInstant());
        submission.setDateOfBirth(LocalDate.of(1990, 1, 1));
        submission.setSex(Sex.FEMALE);
        submission.setCountryRegion("CZ");
        submission.setTimezone("Europe/Prague");
        submission.setDiagnosisType(IbdDiagnosisType.CROHNS_DISEASE);
        submission.setDiagnosisYear(2018);
        submission.setDiseaseLocation("Ileocolonic");
        submission.setDiseaseBehavior("Inflammatory");
        submission.setActivityEstimate(DiseaseActivityEstimate.MILD);
        submission.setCurrentMedications("Mesalamine");
        submission.setSteroidUse(SteroidUse.NONE);
        submission.setAdvancedTherapyExposure(AdvancedTherapyExposure.NEVER_USED);
        submission.setMedicationNotes("Stable regimen");
        submission.setLabsCollectedAt(LocalDate.of(2026, 5, 20));
        submission.setCrpMgL(new java.math.BigDecimal("4.2"));
        submission.setFecalCalprotectinUgG(new java.math.BigDecimal("120"));
        submission.setHemoglobinGDl(new java.math.BigDecimal("13.8"));
        submission.setAlbuminGDl(new java.math.BigDecimal("4.3"));
        submission.setLabNotes("Recent outpatient labs");
        return submission;
    }
}
