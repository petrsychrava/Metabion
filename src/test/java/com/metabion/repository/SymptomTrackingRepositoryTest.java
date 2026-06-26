package com.metabion.repository;

import com.metabion.domain.FlareState;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.QuestionnaireVersionStatus;
import com.metabion.domain.RoleName;
import com.metabion.domain.SymptomCheckIn;
import com.metabion.domain.SymptomCheckInAnswer;
import com.metabion.domain.SymptomQuestion;
import com.metabion.domain.SymptomQuestionnaireVersion;
import com.metabion.domain.SymptomScoringMethod;
import com.metabion.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class SymptomTrackingRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    UserRepository users;

    @Autowired
    PatientProfileRepository patientProfiles;

    @Autowired
    SymptomQuestionnaireRepository questionnaires;

    @Autowired
    SymptomQuestionnaireVersionRepository versions;

    @Autowired
    SymptomCheckInRepository checkIns;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    EntityManager entityManager;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void seededIbdQuestionnaireIsActiveWithQuestions() {
        var questionnaire = questionnaires.findByStableKey("ibd-symptom-check-in").orElseThrow();
        assertThat(questionnaire.isActive()).isTrue();

        var version = versions.findActiveByQuestionnaireStableKey("ibd-symptom-check-in").orElseThrow();
        assertThat(version.getStatus()).isEqualTo(QuestionnaireVersionStatus.ACTIVE);
        assertThat(version.getScoringMethod()).isEqualTo(SymptomScoringMethod.SUM);
        assertThat(version.getQuestions()).extracting("stableKey")
                .containsExactly("stool-frequency", "abdominal-pain", "blood-in-stool", "urgency", "general-wellbeing");
    }

    @Test
    void persistsCheckInAnswersAndEnforcesOneCheckInPerPatientVersionDate() {
        var patient = patientProfiles.saveAndFlush(new PatientProfile(patientUser("symptom-patient@example.com")));
        var version = versions.findActiveByQuestionnaireStableKey("ibd-symptom-check-in").orElseThrow();
        var firstQuestion = version.getQuestions().stream()
                .filter(question -> !question.getOptions().isEmpty())
                .findFirst()
                .orElseThrow();
        var option = firstQuestion.getOptions().getFirst();

        var checkIn = new SymptomCheckIn(patient, version, LocalDate.of(2026, 6, 26), FlareState.SUSPECTED_FLARE);
        checkIn.setTotalSymptomScore(new BigDecimal("2.00"));
        SymptomCheckInAnswer.choice(checkIn, firstQuestion, option);
        checkIns.saveAndFlush(checkIn);

        var loaded = checkIns.findByPatientProfileIdAndCheckInDate(
                patient.getId(), LocalDate.of(2026, 6, 26)).orElseThrow();
        assertThat(loaded.getAnswers()).hasSize(1);
        assertThat(loaded.getAnswers().getFirst().getOption().getStableKey()).isEqualTo(option.getStableKey());
        assertThat(loaded.getFlareState()).isEqualTo(FlareState.SUSPECTED_FLARE);

        var duplicate = new SymptomCheckIn(patient, version, LocalDate.of(2026, 6, 26), FlareState.NO_FLARE);
        assertThatThrownBy(() -> checkIns.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void choiceFactoryAttachesAnswerAndRejectsOptionFromDifferentQuestion() {
        var patient = patientProfiles.saveAndFlush(new PatientProfile(patientUser("symptom-choice@example.com")));
        var version = versions.findActiveByQuestionnaireStableKey("ibd-symptom-check-in").orElseThrow();
        var firstQuestion = firstQuestionWithOptions(version);
        var secondQuestion = version.getQuestions().stream()
                .filter(question -> !question.equals(firstQuestion))
                .filter(question -> !question.getOptions().isEmpty())
                .findFirst()
                .orElseThrow();
        var option = firstQuestion.getOptions().getFirst();
        var checkIn = new SymptomCheckIn(patient, version, LocalDate.of(2026, 6, 27), FlareState.NO_FLARE);

        var answer = SymptomCheckInAnswer.choice(checkIn, firstQuestion, option);

        assertThat(checkIn.getAnswers()).containsExactly(answer);
        assertThat(answer.getCheckIn()).isSameAs(checkIn);

        assertThatThrownBy(() -> SymptomCheckInAnswer.choice(checkIn, secondQuestion, option))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Answer option must belong to the answered question");
    }

    @Test
    void databaseRejectsAnswerOptionFromDifferentQuestion() {
        var patient = patientProfiles.saveAndFlush(new PatientProfile(patientUser("symptom-db-mismatch@example.com")));
        var version = versions.findActiveByQuestionnaireStableKey("ibd-symptom-check-in").orElseThrow();
        var firstQuestion = firstQuestionWithOptions(version);
        var secondQuestion = version.getQuestions().stream()
                .filter(question -> !question.equals(firstQuestion))
                .filter(question -> !question.getOptions().isEmpty())
                .findFirst()
                .orElseThrow();
        var checkIn = new SymptomCheckIn(patient, version, LocalDate.of(2026, 6, 28), FlareState.NO_FLARE);
        checkIns.saveAndFlush(checkIn);
        entityManager.clear();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into symptom_check_in_answers (check_in_id, question_id, option_id, numeric_score)
                values (?, ?, ?, 0.00)
                """, checkIn.getId(), secondQuestion.getId(), firstQuestion.getOptions().getFirst().getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesOneActiveVersionPerQuestionnaire() {
        var questionnaire = questionnaires.findByStableKey("ibd-symptom-check-in").orElseThrow();
        var duplicateActive = new SymptomQuestionnaireVersion(questionnaire, 2);
        duplicateActive.setStatus(QuestionnaireVersionStatus.ACTIVE);
        duplicateActive.setScoringMethod(SymptomScoringMethod.SUM);

        assertThatThrownBy(() -> versions.saveAndFlush(duplicateActive))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private User patientUser(String email) {
        var user = new User(email, "{noop}password");
        user.setEnabled(true);
        user.addRole(RoleName.PATIENT);
        return users.saveAndFlush(user);
    }

    private SymptomQuestion firstQuestionWithOptions(SymptomQuestionnaireVersion version) {
        return version.getQuestions().stream()
                .filter(question -> !question.getOptions().isEmpty())
                .findFirst()
                .orElseThrow();
    }
}
