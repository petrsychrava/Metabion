# Symptom Flare Tracking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build MET-10 symptom check-ins, configurable seeded questionnaires, explicit flare markers, combined measurement/symptom trends, and a unified daily web check-in.

**Architecture:** Add symptom tracking as a modular persistence/service/API area, then coordinate it with existing diet logs only at the daily web workflow and trend assembly boundaries. Keep existing `DailyDietLog` and `DailyMeasurementEntry` behavior intact while adding `DailyCheckInService` for atomic web saves and `DailyTrendService` for combined timeline reads.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring MVC, Spring Security, Spring Data JPA, Flyway, Thymeleaf, JUnit, Mockito, H2/Testcontainers where existing tests already use them.

---

## File Structure

Create:

- `src/main/resources/db/migration/V13__symptom_tracking.sql`: symptom questionnaire/check-in schema and seeded IBD questionnaire.
- `src/main/java/com/metabion/domain/FlareState.java`: explicit patient flare marker enum.
- `src/main/java/com/metabion/domain/QuestionnaireVersionStatus.java`: questionnaire version lifecycle enum.
- `src/main/java/com/metabion/domain/SymptomAnswerType.java`: supported answer input types.
- `src/main/java/com/metabion/domain/SymptomScoringMethod.java`: supported scoring method enum.
- `src/main/java/com/metabion/domain/SymptomQuestionnaire.java`: questionnaire root entity.
- `src/main/java/com/metabion/domain/SymptomQuestionnaireVersion.java`: immutable-ish version entity with questions.
- `src/main/java/com/metabion/domain/SymptomQuestion.java`: question entity.
- `src/main/java/com/metabion/domain/SymptomQuestionOption.java`: choice option entity.
- `src/main/java/com/metabion/domain/SymptomCheckIn.java`: patient symptom check-in entity.
- `src/main/java/com/metabion/domain/SymptomCheckInAnswer.java`: submitted answer entity.
- `src/main/java/com/metabion/repository/SymptomQuestionnaireRepository.java`: active questionnaire lookup.
- `src/main/java/com/metabion/repository/SymptomQuestionnaireVersionRepository.java`: active version lookup.
- `src/main/java/com/metabion/repository/SymptomCheckInRepository.java`: patient/date and range access.
- `src/main/java/com/metabion/service/DateRangeValidator.java`: shared `from <= to` and 370-day range validation.
- `src/main/java/com/metabion/dto/SymptomQuestionnaireResponse.java`: active questionnaire API DTO.
- `src/main/java/com/metabion/dto/SymptomCheckInRequest.java`: check-in write DTO.
- `src/main/java/com/metabion/dto/SymptomCheckInResponse.java`: check-in read DTO.
- `src/main/java/com/metabion/dto/DailyTrendResponse.java`: combined trend DTO.
- `src/main/java/com/metabion/dto/DailyCheckInForm.java`: unified web form model.
- `src/main/java/com/metabion/service/SymptomTrackingService.java`: questionnaire/check-in validation, scoring, persistence, and read access.
- `src/main/java/com/metabion/service/DailyTrendService.java`: combined symptom, flare, glucose, ketone, and diet-log trend assembly.
- `src/main/java/com/metabion/service/DailyCheckInService.java`: atomic web save coordinator.
- `src/main/java/com/metabion/service/SymptomQuestionnaireAssembler.java`: questionnaire/check-in response mapping.
- `src/main/java/com/metabion/controller/api/SymptomTrackingController.java`: patient REST endpoints.
- `src/main/java/com/metabion/controller/api/ClinicalSymptomTrackingController.java`: clinical REST endpoints.
- `src/main/java/com/metabion/controller/web/WebDailyCheckInController.java`: unified patient daily check-in page.
- `src/main/java/com/metabion/controller/web/WebTrendController.java`: patient and clinical trend pages.
- `src/main/java/com/metabion/controller/web/TrendSvgRenderer.java`: server-side inline SVG path/marker builder.
- `src/main/resources/templates/daily-check-in.html`: unified patient form.
- `src/main/resources/templates/trends.html`: patient trend page.
- `src/main/resources/templates/clinical-trends.html`: clinical trend page.
- `src/test/java/com/metabion/repository/SymptomTrackingRepositoryTest.java`: schema/entity persistence tests.
- `src/test/java/com/metabion/service/SymptomTrackingServiceTest.java`: validation/scoring/access tests.
- `src/test/java/com/metabion/service/DailyTrendServiceTest.java`: combined trend timeline tests.
- `src/test/java/com/metabion/service/DailyCheckInServiceTest.java`: atomic save tests.
- `src/test/java/com/metabion/controller/api/SymptomTrackingControllerTest.java`: REST API tests.
- `src/test/java/com/metabion/controller/web/WebDailyCheckInControllerTest.java`: unified form web tests.
- `src/test/java/com/metabion/controller/web/WebTrendControllerTest.java`: trend web tests.
- `src/test/java/com/metabion/controller/web/TrendSvgRendererTest.java`: SVG rendering tests.

Modify:

- `src/main/java/com/metabion/controller/web/AppMenuCatalog.java`: rename patient daily item and add patient/clinical trends.
- `src/main/java/com/metabion/controller/web/WebDietLogController.java`: remove patient diet-log form/history mappings after `WebDailyCheckInController` owns `/app/daily-check-in`; keep clinical diet-log list/detail mappings in this controller.
- `src/main/java/com/metabion/service/DietLogService.java`: keep current public save/read methods as the integration point for `DailyCheckInService`; no behavior change is planned in this service.
- `src/main/java/com/metabion/repository/DailyMeasurementEntryRepository.java`: add the exact ascending measured-at range query used by `DailyTrendService`.
- `src/main/java/com/metabion/repository/DailyDietLogRepository.java`: use existing patient/date range query for trend diet-log context.
- `src/main/resources/messages.properties`: English labels/errors.
- `src/main/resources/messages_cs.properties`: Czech labels/errors.
- `src/main/resources/static/css/app.css`: compact form section and trend SVG styles.

Do not modify:

- Existing authentication/session model.
- Existing password, token, login, and invitation flows.
- Existing diet-log API contracts except adding reuse paths behind the new unified web workflow.

---

### Task 1: Symptom Tracking Schema, Seed Data, Entities, And Repositories

**Files:**
- Create: `src/main/resources/db/migration/V13__symptom_tracking.sql`
- Create: `src/main/java/com/metabion/domain/FlareState.java`
- Create: `src/main/java/com/metabion/domain/QuestionnaireVersionStatus.java`
- Create: `src/main/java/com/metabion/domain/SymptomAnswerType.java`
- Create: `src/main/java/com/metabion/domain/SymptomScoringMethod.java`
- Create: `src/main/java/com/metabion/domain/SymptomQuestionnaire.java`
- Create: `src/main/java/com/metabion/domain/SymptomQuestionnaireVersion.java`
- Create: `src/main/java/com/metabion/domain/SymptomQuestion.java`
- Create: `src/main/java/com/metabion/domain/SymptomQuestionOption.java`
- Create: `src/main/java/com/metabion/domain/SymptomCheckIn.java`
- Create: `src/main/java/com/metabion/domain/SymptomCheckInAnswer.java`
- Create: `src/main/java/com/metabion/repository/SymptomQuestionnaireRepository.java`
- Create: `src/main/java/com/metabion/repository/SymptomQuestionnaireVersionRepository.java`
- Create: `src/main/java/com/metabion/repository/SymptomCheckInRepository.java`
- Test: `src/test/java/com/metabion/repository/SymptomTrackingRepositoryTest.java`

- [ ] **Step 1: Write failing repository tests**

Create `src/test/java/com/metabion/repository/SymptomTrackingRepositoryTest.java` with these tests:

```java
package com.metabion.repository;

import com.metabion.domain.FlareState;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.QuestionnaireVersionStatus;
import com.metabion.domain.RoleName;
import com.metabion.domain.SymptomCheckIn;
import com.metabion.domain.SymptomCheckInAnswer;
import com.metabion.domain.SymptomQuestionnaireVersion;
import com.metabion.domain.SymptomScoringMethod;
import com.metabion.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SymptomTrackingRepositoryTest {

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
        checkIn.addAnswer(SymptomCheckInAnswer.choice(checkIn, firstQuestion, option));
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

    private User patientUser(String email) {
        var user = new User(email, "{noop}password");
        user.setEnabled(true);
        user.addRole(RoleName.PATIENT);
        return users.saveAndFlush(user);
    }
}
```

- [ ] **Step 2: Run repository test and verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.repository.SymptomTrackingRepositoryTest
```

Expected: compilation fails because symptom domain and repository classes do not exist.

- [ ] **Step 3: Add Flyway migration and seed questionnaire**

Create `src/main/resources/db/migration/V13__symptom_tracking.sql` with:

```sql
CREATE TABLE symptom_questionnaires (
    id              BIGSERIAL PRIMARY KEY,
    stable_key      VARCHAR(120) NOT NULL UNIQUE,
    display_name    VARCHAR(200) NOT NULL,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_symptom_questionnaires_stable_key CHECK (length(trim(stable_key)) > 0),
    CONSTRAINT chk_symptom_questionnaires_display_name CHECK (length(trim(display_name)) > 0)
);

CREATE TABLE symptom_questionnaire_versions (
    id                  BIGSERIAL PRIMARY KEY,
    questionnaire_id    BIGINT NOT NULL REFERENCES symptom_questionnaires(id) ON DELETE CASCADE,
    version_number      INT NOT NULL,
    status              VARCHAR(40) NOT NULL,
    scoring_method      VARCHAR(40) NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    published_at        TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ux_symptom_questionnaire_versions_number UNIQUE (questionnaire_id, version_number),
    CONSTRAINT chk_symptom_questionnaire_versions_number CHECK (version_number > 0),
    CONSTRAINT chk_symptom_questionnaire_versions_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT chk_symptom_questionnaire_versions_scoring CHECK (scoring_method IN ('SUM'))
);

CREATE TABLE symptom_questions (
    id                          BIGSERIAL PRIMARY KEY,
    questionnaire_version_id    BIGINT NOT NULL REFERENCES symptom_questionnaire_versions(id) ON DELETE CASCADE,
    stable_key                  VARCHAR(120) NOT NULL,
    label                       VARCHAR(500) NOT NULL,
    help_text                   VARCHAR(1000),
    answer_type                 VARCHAR(40) NOT NULL,
    required                    BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order                  INT NOT NULL DEFAULT 0,
    score_weight                NUMERIC(8,2) NOT NULL DEFAULT 1.00,
    min_numeric_value           NUMERIC(8,2),
    max_numeric_value           NUMERIC(8,2),
    CONSTRAINT ux_symptom_questions_version_key UNIQUE (questionnaire_version_id, stable_key),
    CONSTRAINT chk_symptom_questions_key CHECK (length(trim(stable_key)) > 0),
    CONSTRAINT chk_symptom_questions_label CHECK (length(trim(label)) > 0),
    CONSTRAINT chk_symptom_questions_type CHECK (answer_type IN ('SINGLE_CHOICE', 'NUMERIC', 'TEXT')),
    CONSTRAINT chk_symptom_questions_sort_order CHECK (sort_order >= 0),
    CONSTRAINT chk_symptom_questions_weight CHECK (score_weight >= 0),
    CONSTRAINT chk_symptom_questions_numeric_range CHECK (
        min_numeric_value IS NULL
        OR max_numeric_value IS NULL
        OR min_numeric_value <= max_numeric_value
    )
);

CREATE TABLE symptom_question_options (
    id              BIGSERIAL PRIMARY KEY,
    question_id     BIGINT NOT NULL REFERENCES symptom_questions(id) ON DELETE CASCADE,
    stable_key      VARCHAR(120) NOT NULL,
    label           VARCHAR(300) NOT NULL,
    numeric_score   NUMERIC(8,2) NOT NULL DEFAULT 0.00,
    sort_order      INT NOT NULL DEFAULT 0,
    CONSTRAINT ux_symptom_question_options_question_key UNIQUE (question_id, stable_key),
    CONSTRAINT chk_symptom_question_options_key CHECK (length(trim(stable_key)) > 0),
    CONSTRAINT chk_symptom_question_options_label CHECK (length(trim(label)) > 0),
    CONSTRAINT chk_symptom_question_options_sort_order CHECK (sort_order >= 0)
);

CREATE TABLE symptom_check_ins (
    id                          BIGSERIAL PRIMARY KEY,
    patient_profile_id          BIGINT NOT NULL REFERENCES patient_profiles(id) ON DELETE CASCADE,
    questionnaire_version_id    BIGINT NOT NULL REFERENCES symptom_questionnaire_versions(id),
    check_in_date               DATE NOT NULL,
    flare_state                 VARCHAR(40) NOT NULL,
    notes                       VARCHAR(1000),
    total_symptom_score         NUMERIC(8,2) NOT NULL DEFAULT 0.00,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_symptom_check_ins_patient_date
        UNIQUE (patient_profile_id, check_in_date),
    CONSTRAINT chk_symptom_check_ins_flare CHECK (flare_state IN ('NO_FLARE', 'SUSPECTED_FLARE', 'ACTIVE_FLARE')),
    CONSTRAINT chk_symptom_check_ins_score CHECK (total_symptom_score >= 0),
    CONSTRAINT chk_symptom_check_ins_notes CHECK (notes IS NULL OR length(trim(notes)) > 0)
);

CREATE TABLE symptom_check_in_answers (
    id                  BIGSERIAL PRIMARY KEY,
    check_in_id         BIGINT NOT NULL REFERENCES symptom_check_ins(id) ON DELETE CASCADE,
    question_id         BIGINT NOT NULL REFERENCES symptom_questions(id),
    option_id           BIGINT REFERENCES symptom_question_options(id),
    answer_text         VARCHAR(1000),
    answer_numeric      NUMERIC(8,2),
    numeric_score       NUMERIC(8,2) NOT NULL DEFAULT 0.00,
    CONSTRAINT ux_symptom_check_in_answers_check_question UNIQUE (check_in_id, question_id),
    CONSTRAINT chk_symptom_check_in_answers_text CHECK (answer_text IS NULL OR length(trim(answer_text)) > 0),
    CONSTRAINT chk_symptom_check_in_answers_score CHECK (numeric_score >= 0),
    CONSTRAINT chk_symptom_check_in_answers_one_value CHECK (
        option_id IS NOT NULL
        OR answer_text IS NOT NULL
        OR answer_numeric IS NOT NULL
    )
);

CREATE INDEX ix_symptom_questionnaire_versions_active
    ON symptom_questionnaire_versions(questionnaire_id, status);
CREATE UNIQUE INDEX ux_symptom_questionnaire_versions_one_active
    ON symptom_questionnaire_versions(questionnaire_id)
    WHERE status = 'ACTIVE';
CREATE INDEX ix_symptom_questions_version_order
    ON symptom_questions(questionnaire_version_id, sort_order, id);
CREATE INDEX ix_symptom_question_options_question_order
    ON symptom_question_options(question_id, sort_order, id);
CREATE INDEX ix_symptom_check_ins_patient_date
    ON symptom_check_ins(patient_profile_id, check_in_date DESC);

INSERT INTO symptom_questionnaires (stable_key, display_name, active)
VALUES ('ibd-symptom-check-in', 'IBD symptom check-in', TRUE);

INSERT INTO symptom_questionnaire_versions (questionnaire_id, version_number, status, scoring_method, published_at)
SELECT id, 1, 'ACTIVE', 'SUM', NOW()
FROM symptom_questionnaires
WHERE stable_key = 'ibd-symptom-check-in';

INSERT INTO symptom_questions (
    questionnaire_version_id, stable_key, label, help_text, answer_type, required, sort_order,
    score_weight, min_numeric_value, max_numeric_value
)
SELECT version.id, question.stable_key, question.label, question.help_text, question.answer_type,
       TRUE, question.sort_order, 1.00, question.min_numeric_value, question.max_numeric_value
FROM symptom_questionnaire_versions version
JOIN symptom_questionnaires questionnaire ON questionnaire.id = version.questionnaire_id
CROSS JOIN (
    VALUES
        ('stool-frequency', 'Stool frequency', 'Number of stools in the last 24 hours', 'NUMERIC', 10, 0.00, 30.00),
        ('abdominal-pain', 'Abdominal pain', 'Pain severity in the last 24 hours', 'SINGLE_CHOICE', 20, NULL, NULL),
        ('blood-in-stool', 'Blood in stool', 'Blood observed in stool', 'SINGLE_CHOICE', 30, NULL, NULL),
        ('urgency', 'Urgency', 'Urgency severity in the last 24 hours', 'SINGLE_CHOICE', 40, NULL, NULL),
        ('general-wellbeing', 'General wellbeing', 'Overall wellbeing today', 'SINGLE_CHOICE', 50, NULL, NULL)
) AS question(stable_key, label, help_text, answer_type, sort_order, min_numeric_value, max_numeric_value)
WHERE questionnaire.stable_key = 'ibd-symptom-check-in'
  AND version.version_number = 1;

INSERT INTO symptom_question_options (question_id, stable_key, label, numeric_score, sort_order)
SELECT q.id, option.stable_key, option.label, option.numeric_score, option.sort_order
FROM symptom_questions q
JOIN symptom_questionnaire_versions version ON version.id = q.questionnaire_version_id
JOIN symptom_questionnaires questionnaire ON questionnaire.id = version.questionnaire_id
CROSS JOIN (
    VALUES
        ('none', 'None', 0.00, 10),
        ('mild', 'Mild', 1.00, 20),
        ('moderate', 'Moderate', 2.00, 30),
        ('severe', 'Severe', 3.00, 40)
) AS option(stable_key, label, numeric_score, sort_order)
WHERE questionnaire.stable_key = 'ibd-symptom-check-in'
  AND q.stable_key IN ('abdominal-pain', 'urgency');

INSERT INTO symptom_question_options (question_id, stable_key, label, numeric_score, sort_order)
SELECT q.id, option.stable_key, option.label, option.numeric_score, option.sort_order
FROM symptom_questions q
JOIN symptom_questionnaire_versions version ON version.id = q.questionnaire_version_id
JOIN symptom_questionnaires questionnaire ON questionnaire.id = version.questionnaire_id
CROSS JOIN (
    VALUES
        ('none', 'None', 0.00, 10),
        ('trace', 'Trace', 1.00, 20),
        ('visible', 'Visible', 2.00, 30),
        ('significant', 'Significant', 3.00, 40)
) AS option(stable_key, label, numeric_score, sort_order)
WHERE questionnaire.stable_key = 'ibd-symptom-check-in'
  AND q.stable_key = 'blood-in-stool';

INSERT INTO symptom_question_options (question_id, stable_key, label, numeric_score, sort_order)
SELECT q.id, option.stable_key, option.label, option.numeric_score, option.sort_order
FROM symptom_questions q
JOIN symptom_questionnaire_versions version ON version.id = q.questionnaire_version_id
JOIN symptom_questionnaires questionnaire ON questionnaire.id = version.questionnaire_id
CROSS JOIN (
    VALUES
        ('well', 'Well', 0.00, 10),
        ('slightly-unwell', 'Slightly unwell', 1.00, 20),
        ('unwell', 'Unwell', 2.00, 30),
        ('very-unwell', 'Very unwell', 3.00, 40)
) AS option(stable_key, label, numeric_score, sort_order)
WHERE questionnaire.stable_key = 'ibd-symptom-check-in'
  AND q.stable_key = 'general-wellbeing';
```

- [ ] **Step 4: Add enums**

Create these enum files:

```java
// src/main/java/com/metabion/domain/FlareState.java
package com.metabion.domain;

public enum FlareState {
    NO_FLARE,
    SUSPECTED_FLARE,
    ACTIVE_FLARE
}
```

```java
// src/main/java/com/metabion/domain/QuestionnaireVersionStatus.java
package com.metabion.domain;

public enum QuestionnaireVersionStatus {
    DRAFT,
    ACTIVE,
    RETIRED
}
```

```java
// src/main/java/com/metabion/domain/SymptomAnswerType.java
package com.metabion.domain;

public enum SymptomAnswerType {
    SINGLE_CHOICE,
    NUMERIC,
    TEXT
}
```

```java
// src/main/java/com/metabion/domain/SymptomScoringMethod.java
package com.metabion.domain;

public enum SymptomScoringMethod {
    SUM
}
```

- [ ] **Step 5: Add entities and repositories**

Implement the JPA entities with these required relationships:

```java
// entity relationship contract
SymptomQuestionnaire
  @OneToMany(mappedBy = "questionnaire")
  List<SymptomQuestionnaireVersion> versions

SymptomQuestionnaireVersion
  @ManyToOne SymptomQuestionnaire questionnaire
  @OneToMany(mappedBy = "questionnaireVersion", cascade = CascadeType.ALL)
  @OrderBy("sortOrder ASC, id ASC")
  List<SymptomQuestion> questions

SymptomQuestion
  @ManyToOne SymptomQuestionnaireVersion questionnaireVersion
  @OneToMany(mappedBy = "question", cascade = CascadeType.ALL)
  @OrderBy("sortOrder ASC, id ASC")
  List<SymptomQuestionOption> options

SymptomCheckIn
  @ManyToOne PatientProfile patientProfile
  @ManyToOne SymptomQuestionnaireVersion questionnaireVersion
  @OneToMany(mappedBy = "checkIn", cascade = CascadeType.ALL, orphanRemoval = true)
  List<SymptomCheckInAnswer> answers
```

Required repository methods:

```java
// src/main/java/com/metabion/repository/SymptomQuestionnaireRepository.java
Optional<SymptomQuestionnaire> findByStableKey(String stableKey);
```

```java
// src/main/java/com/metabion/repository/SymptomQuestionnaireVersionRepository.java
@Query("""
        select distinct version
        from SymptomQuestionnaireVersion version
        join fetch version.questionnaire questionnaire
        left join fetch version.questions question
        left join fetch question.options
        where questionnaire.stableKey = :stableKey
          and questionnaire.active = true
          and version.status = com.metabion.domain.QuestionnaireVersionStatus.ACTIVE
        order by question.sortOrder asc, question.id asc
        """)
Optional<SymptomQuestionnaireVersion> findActiveByQuestionnaireStableKey(@Param("stableKey") String stableKey);

@Query("""
        select version
        from SymptomQuestionnaireVersion version
        join version.questionnaire questionnaire
        where questionnaire.stableKey = :stableKey
          and questionnaire.active = true
          and version.status = com.metabion.domain.QuestionnaireVersionStatus.ACTIVE
        """)
List<SymptomQuestionnaireVersion> findActiveVersionsByQuestionnaireStableKey(@Param("stableKey") String stableKey);
```

```java
// src/main/java/com/metabion/repository/SymptomCheckInRepository.java
Optional<SymptomCheckIn> findByPatientProfileIdAndCheckInDate(Long patientProfileId, LocalDate checkInDate);

List<SymptomCheckIn> findByPatientProfileIdAndCheckInDateBetweenOrderByCheckInDateDesc(
        Long patientProfileId, LocalDate from, LocalDate to);
```

Check-in identity is patient/date, not patient/version/date. `questionnaire_version_id` snapshots which questionnaire version was used to answer that date, but API reads and replacements are date-based. When a patient edits an older date after the active questionnaire version changes, the save path replaces that date's check-in and answer set with the submitted questionnaire version.

Implement entity helpers:

```java
public void clearAnswers() {
    answers.clear();
}

public void addAnswer(SymptomCheckInAnswer answer) {
    answers.add(answer);
    answer.setCheckIn(this);
}
```

and static answer constructors:

```java
public static SymptomCheckInAnswer choice(
        SymptomCheckIn checkIn,
        SymptomQuestion question,
        SymptomQuestionOption option) {
    var answer = new SymptomCheckInAnswer();
    answer.setCheckIn(checkIn);
    answer.setQuestion(question);
    answer.setOption(option);
    answer.setNumericScore(option.getNumericScore().multiply(question.getScoreWeight()));
    return answer;
}
```

- [ ] **Step 6: Run repository test and commit**

Run:

```bash
./gradlew test --tests com.metabion.repository.SymptomTrackingRepositoryTest
```

Expected: PASS.

Commit:

```bash
git add src/main/resources/db/migration/V13__symptom_tracking.sql \
  src/main/java/com/metabion/domain \
  src/main/java/com/metabion/repository/SymptomQuestionnaireRepository.java \
  src/main/java/com/metabion/repository/SymptomQuestionnaireVersionRepository.java \
  src/main/java/com/metabion/repository/SymptomCheckInRepository.java \
  src/test/java/com/metabion/repository/SymptomTrackingRepositoryTest.java
git commit -m "Add symptom tracking persistence"
```

---

### Task 2: Symptom DTOs, Validation, Scoring, And Patient Service

**Files:**
- Create: `src/main/java/com/metabion/dto/SymptomQuestionnaireResponse.java`
- Create: `src/main/java/com/metabion/dto/SymptomCheckInRequest.java`
- Create: `src/main/java/com/metabion/dto/SymptomCheckInResponse.java`
- Create: `src/main/java/com/metabion/service/SymptomQuestionnaireAssembler.java`
- Create: `src/main/java/com/metabion/service/SymptomTrackingService.java`
- Test: `src/test/java/com/metabion/service/SymptomTrackingServiceTest.java`

- [ ] **Step 1: Write failing service tests**

Create tests for these exact behaviors in `SymptomTrackingServiceTest`:

```java
@Test
void saveForCurrentPatientRequiresAllRequiredAnswers() {
    var request = new SymptomCheckInRequest(
            LocalDate.of(2026, 6, 26),
            activeVersionId,
            FlareState.NO_FLARE,
            List.of(answer("stool-frequency", new BigDecimal("3"))),
            null);

    assertThatThrownBy(() -> service.saveForCurrentPatient(patientAuth, request))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("required symptom answers are missing");
}

@Test
void saveForCurrentPatientScoresAnswersAndReplacesSameDayCheckIn() {
    var first = completeRequest(LocalDate.of(2026, 6, 26), FlareState.SUSPECTED_FLARE, "mild");
    var firstResponse = service.saveForCurrentPatient(patientAuth, first);

    var second = completeRequest(LocalDate.of(2026, 6, 26), FlareState.ACTIVE_FLARE, "severe");
    var secondResponse = service.saveForCurrentPatient(patientAuth, second);

    assertThat(secondResponse.id()).isEqualTo(firstResponse.id());
    assertThat(secondResponse.flareState()).isEqualTo(FlareState.ACTIVE_FLARE);
    assertThat(secondResponse.totalSymptomScore()).isGreaterThan(firstResponse.totalSymptomScore());
}

@Test
void weightedChoiceScoreIsStoredAsThePerAnswerContribution() {
    var version = activeVersionWithWeightedAbdominalPain(new BigDecimal("2.00"));
    when(versions.findById(activeVersionId)).thenReturn(Optional.of(version));
    var response = service.saveForCurrentPatient(patientAuth,
            completeRequest(LocalDate.of(2026, 6, 26), FlareState.NO_FLARE, "severe"));

    var painAnswer = response.answers().stream()
            .filter(answer -> answer.questionStableKey().equals("abdominal-pain"))
            .findFirst()
            .orElseThrow();
    assertThat(painAnswer.numericScore()).isEqualByComparingTo("6.00");
    assertThat(response.totalSymptomScore()).isGreaterThanOrEqualTo(new BigDecimal("6.00"));
}

@Test
void saveForCurrentPatientRejectsFutureDateInPatientTimezone() {
    patientProfile.setTimezone("Europe/Prague");
    var tomorrow = LocalDate.now(ZoneId.of("Europe/Prague")).plusDays(1);

    assertThatThrownBy(() -> service.saveForCurrentPatient(patientAuth,
            completeRequest(tomorrow, FlareState.NO_FLARE, "none")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("checkInDate cannot be in the future");
}

@Test
void activeQuestionnaireFailsWhenMoreThanOneVersionIsActive() {
    when(versions.findActiveVersionsByQuestionnaireStableKey("ibd-symptom-check-in"))
            .thenReturn(List.of(activeVersion(1), activeVersion(2)));

    assertThatThrownBy(() -> service.activeQuestionnaire())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Expected exactly one active IBD symptom questionnaire version");
}
```

Use Mockito for repositories in this service test. Build `SymptomQuestionnaireVersion` objects directly in test helpers so validation is isolated from Flyway.

- [ ] **Step 2: Run service test and verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.service.SymptomTrackingServiceTest
```

Expected: compilation fails because DTOs and `SymptomTrackingService` do not exist.

- [ ] **Step 3: Add DTOs**

Create `SymptomQuestionnaireResponse`:

```java
package com.metabion.dto;

import com.metabion.domain.SymptomAnswerType;

import java.math.BigDecimal;
import java.util.List;

public record SymptomQuestionnaireResponse(
        Long id,
        String stableKey,
        String displayName,
        Long versionId,
        int versionNumber,
        List<QuestionResponse> questions
) {
    public record QuestionResponse(
            Long id,
            String stableKey,
            String label,
            String helpText,
            SymptomAnswerType answerType,
            boolean required,
            BigDecimal minNumericValue,
            BigDecimal maxNumericValue,
            List<OptionResponse> options
    ) {
    }

    public record OptionResponse(
            Long id,
            String stableKey,
            String label,
            BigDecimal numericScore
    ) {
    }
}
```

Create `SymptomCheckInRequest`:

```java
package com.metabion.dto;

import com.metabion.domain.FlareState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record SymptomCheckInRequest(
        @NotNull LocalDate checkInDate,
        @NotNull Long questionnaireVersionId,
        @NotNull FlareState flareState,
        @Valid List<AnswerRequest> answers,
        @Size(max = 1000) String notes
) {
    public List<AnswerRequest> answersOrEmpty() {
        return answers == null ? List.of() : answers;
    }

    public record AnswerRequest(
            @NotNull Long questionId,
            Long optionId,
            @Size(max = 1000) String answerText,
            BigDecimal answerNumeric
    ) {
    }
}
```

Create `SymptomCheckInResponse`:

```java
package com.metabion.dto;

import com.metabion.domain.FlareState;
import com.metabion.domain.SymptomAnswerType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record SymptomCheckInResponse(
        Long id,
        Long patientProfileId,
        Long questionnaireVersionId,
        LocalDate checkInDate,
        FlareState flareState,
        BigDecimal totalSymptomScore,
        String notes,
        List<AnswerResponse> answers,
        Instant createdAt,
        Instant updatedAt
) {
    public record AnswerResponse(
            Long questionId,
            String questionStableKey,
            String label,
            SymptomAnswerType answerType,
            Long optionId,
            String optionStableKey,
            String optionLabel,
            String answerText,
            BigDecimal answerNumeric,
            BigDecimal numericScore
    ) {
    }
}
```

- [ ] **Step 4: Implement assembler and service**

`SymptomTrackingService` public API:

```java
public SymptomQuestionnaireResponse activeQuestionnaire()
public SymptomCheckInResponse saveForCurrentPatient(Authentication authentication, SymptomCheckInRequest request)
public SymptomCheckInResponse getCurrentPatientCheckIn(Authentication authentication, LocalDate date)
public List<SymptomCheckInResponse> listCurrentPatientCheckIns(Authentication authentication, LocalDate from, LocalDate to)
public List<SymptomCheckInResponse> listClinicalCheckIns(Authentication authentication, Long patientProfileId, LocalDate from, LocalDate to)
```

Active-version rule:

```java
private SymptomQuestionnaireVersion activeVersion() {
    var activeVersions = versions.findActiveVersionsByQuestionnaireStableKey("ibd-symptom-check-in");
    if (activeVersions.size() != 1) {
        throw new IllegalStateException("Expected exactly one active IBD symptom questionnaire version");
    }
    return activeVersions.getFirst();
}
```

Create-or-replace rule:

```java
var existing = checkIns.findByPatientProfileIdAndCheckInDate(patient.getId(), request.checkInDate())
        .orElseGet(() -> new SymptomCheckIn(patient, version, request.checkInDate(), request.flareState()));
existing.setQuestionnaireVersion(version);
existing.setFlareState(request.flareState());
existing.clearAnswers();
```

Validation rules:

```java
private void validateRequiredAnswers(SymptomQuestionnaireVersion version, Map<Long, AnswerRequest> answersByQuestionId) {
    var missing = version.getQuestions().stream()
            .filter(SymptomQuestion::isRequired)
            .filter(question -> !answersByQuestionId.containsKey(question.getId()))
            .map(SymptomQuestion::getStableKey)
            .toList();
    if (!missing.isEmpty()) {
        throw badRequest("required symptom answers are missing: " + String.join(", ", missing));
    }
}
```

Scoring rules:

```java
private BigDecimal scoreFor(SymptomQuestion question, AnswerRequest request) {
    return switch (question.getAnswerType()) {
        case SINGLE_CHOICE -> selectedOption(question, request.optionId()).getNumericScore()
                .multiply(question.getScoreWeight());
        case NUMERIC -> request.answerNumeric().multiply(question.getScoreWeight());
        case TEXT -> BigDecimal.ZERO;
    };
}
```

The `numericScore` stored on each `SymptomCheckInAnswer` must be the weighted contribution used in `totalSymptomScore`. For a single-choice question with option score `3.00` and question weight `2.00`, persist answer `numericScore = 6.00` and add `6.00` to the check-in total.

Access rules:

```java
private PatientProfile currentPatientProfile(Authentication authentication) {
    var user = currentUser(authentication);
    if (!user.hasRole(RoleName.PATIENT)) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Current user is not a patient");
    }
    return patientProfiles.findByUserId(user.getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Patient profile not found"));
}
```

- [ ] **Step 5: Run service test and commit**

Run:

```bash
./gradlew test --tests com.metabion.service.SymptomTrackingServiceTest
```

Expected: PASS.

Commit:

```bash
git add src/main/java/com/metabion/dto/SymptomQuestionnaireResponse.java \
  src/main/java/com/metabion/dto/SymptomCheckInRequest.java \
  src/main/java/com/metabion/dto/SymptomCheckInResponse.java \
  src/main/java/com/metabion/service/SymptomQuestionnaireAssembler.java \
  src/main/java/com/metabion/service/SymptomTrackingService.java \
  src/test/java/com/metabion/service/SymptomTrackingServiceTest.java
git commit -m "Add symptom check-in service"
```

---

### Task 3: Patient And Clinical Symptom REST APIs

**Files:**
- Create: `src/main/java/com/metabion/controller/api/SymptomTrackingController.java`
- Create: `src/main/java/com/metabion/controller/api/ClinicalSymptomTrackingController.java`
- Test: `src/test/java/com/metabion/controller/api/SymptomTrackingControllerTest.java`

- [ ] **Step 1: Write failing controller tests**

Create tests using the existing `@WebMvcTest` style from `DietLogControllerTest`:

```java
@Test
@WithMockUser(username = "patient@example.com", roles = "PATIENT")
void patientCanCreateSymptomCheckInWithCsrf() throws Exception {
    var response = new SymptomCheckInResponse(
            10L, 20L, 30L, LocalDate.of(2026, 6, 26), FlareState.NO_FLARE,
            new BigDecimal("4.00"), null, List.of(), Instant.now(), Instant.now());
    when(symptomTrackingService.saveForCurrentPatient(any(), any())).thenReturn(response);

    mvc.perform(post("/api/symptom-check-ins")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {
                              "checkInDate": "2026-06-26",
                              "questionnaireVersionId": 30,
                              "flareState": "NO_FLARE",
                              "answers": [{"questionId": 1, "answerNumeric": 3}]
                            }
                            """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(10))
            .andExpect(jsonPath("$.flareState").value("NO_FLARE"));
}

@Test
@WithMockUser(username = "staff@example.com", roles = "PHYSICIAN")
void clinicalTrendHistoryRequiresAccessiblePatientId() throws Exception {
    when(symptomTrackingService.listClinicalCheckIns(any(), eq(20L), any(), any()))
            .thenReturn(List.of());

    mvc.perform(get("/api/clinical/symptom-check-ins")
                    .param("patientProfileId", "20")
                    .param("from", "2026-06-01")
                    .param("to", "2026-06-26"))
            .andExpect(status().isOk());
}
```

- [ ] **Step 2: Run controller test and verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.controller.api.SymptomTrackingControllerTest
```

Expected: compilation fails because controllers do not exist.

- [ ] **Step 3: Add REST controllers**

`SymptomTrackingController`:

```java
@RestController
public class SymptomTrackingController {
    private final SymptomTrackingService symptomTrackingService;
    public SymptomTrackingController(SymptomTrackingService symptomTrackingService) {
        this.symptomTrackingService = symptomTrackingService;
    }

    @GetMapping("/api/symptom-questionnaires/active")
    public SymptomQuestionnaireResponse activeQuestionnaire() {
        return symptomTrackingService.activeQuestionnaire();
    }

    @PostMapping("/api/symptom-check-ins")
    public SymptomCheckInResponse save(@Valid @RequestBody SymptomCheckInRequest request,
                                       Authentication authentication) {
        return symptomTrackingService.saveForCurrentPatient(authentication, request);
    }

    @GetMapping("/api/symptom-check-ins/{date}")
    public SymptomCheckInResponse get(@PathVariable LocalDate date, Authentication authentication) {
        return symptomTrackingService.getCurrentPatientCheckIn(authentication, date);
    }

    @GetMapping("/api/symptom-check-ins")
    public List<SymptomCheckInResponse> list(@RequestParam LocalDate from,
                                             @RequestParam LocalDate to,
                                             Authentication authentication) {
        return symptomTrackingService.listCurrentPatientCheckIns(authentication, from, to);
    }
}
```

`ClinicalSymptomTrackingController`:

```java
@RestController
public class ClinicalSymptomTrackingController {
    private final SymptomTrackingService symptomTrackingService;

    public ClinicalSymptomTrackingController(SymptomTrackingService symptomTrackingService) {
        this.symptomTrackingService = symptomTrackingService;
    }

    @GetMapping("/api/clinical/symptom-check-ins")
    public List<SymptomCheckInResponse> listClinical(@RequestParam Long patientProfileId,
                                                     @RequestParam LocalDate from,
                                                     @RequestParam LocalDate to,
                                                     Authentication authentication) {
        return symptomTrackingService.listClinicalCheckIns(authentication, patientProfileId, from, to);
    }
}
```

- [ ] **Step 4: Run controller test and commit**

Run:

```bash
./gradlew test --tests com.metabion.controller.api.SymptomTrackingControllerTest
```

Expected: PASS.

Commit:

```bash
git add src/main/java/com/metabion/controller/api/SymptomTrackingController.java \
  src/main/java/com/metabion/controller/api/ClinicalSymptomTrackingController.java \
  src/test/java/com/metabion/controller/api/SymptomTrackingControllerTest.java
git commit -m "Add symptom tracking APIs"
```

---

### Task 4: Combined Daily Trend Service

**Files:**
- Create: `src/main/java/com/metabion/dto/DailyTrendResponse.java`
- Create: `src/main/java/com/metabion/service/DateRangeValidator.java`
- Create: `src/main/java/com/metabion/service/DailyTrendService.java`
- Modify: `src/main/java/com/metabion/controller/api/SymptomTrackingController.java`
- Modify: `src/main/java/com/metabion/controller/api/ClinicalSymptomTrackingController.java`
- Modify: `src/main/java/com/metabion/service/DietLogService.java`
- Modify: `src/main/java/com/metabion/repository/DailyMeasurementEntryRepository.java`
- Modify: `src/main/java/com/metabion/repository/DailyDietLogRepository.java`
- Test: `src/test/java/com/metabion/service/DailyTrendServiceTest.java`
- Test: `src/test/java/com/metabion/controller/api/SymptomTrackingControllerTest.java`

- [ ] **Step 1: Write failing trend tests**

Create tests:

```java
@Test
void currentPatientTrendCombinesSymptomsFlareGlucoseAndKetonesByPatientDate() {
    when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patient));
    when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
    when(symptomCheckIns.findByPatientProfileIdAndCheckInDateBetweenOrderByCheckInDateDesc(
            10L, LocalDate.of(2026, 6, 25), LocalDate.of(2026, 6, 26)))
            .thenReturn(List.of(checkIn(LocalDate.of(2026, 6, 26), FlareState.SUSPECTED_FLARE, "5.00")));
    when(measurements.findByPatientProfileIdAndMeasuredAtGreaterThanEqualAndMeasuredAtLessThanOrderByMeasuredAtAsc(
            eq(10L), any(), any()))
            .thenReturn(List.of(
                    measurement(MeasurementType.GLUCOSE, "5.40", MeasurementUnit.MMOL_L, "2026-06-26T06:00:00Z"),
                    measurement(MeasurementType.KETONE, "1.20", MeasurementUnit.MMOL_L, "2026-06-26T07:00:00Z")));

    var response = service.currentPatientTrend(patientAuth, LocalDate.of(2026, 6, 25), LocalDate.of(2026, 6, 26));

    assertThat(response.days()).hasSize(2);
    var day = response.days().getLast();
    assertThat(day.date()).isEqualTo(LocalDate.of(2026, 6, 26));
    assertThat(day.flareState()).isEqualTo(FlareState.SUSPECTED_FLARE);
    assertThat(day.symptomScore()).isEqualByComparingTo("5.00");
    assertThat(day.glucoseMeasurements()).hasSize(1);
    assertThat(day.ketoneMeasurements()).hasSize(1);
}

@Test
void usesPatientTimezoneWindowForMeasurementQueryBounds() {
    patient.setTimezone("Europe/Prague");
    when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patient));
    when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
    when(symptomCheckIns.findByPatientProfileIdAndCheckInDateBetweenOrderByCheckInDateDesc(any(), any(), any()))
            .thenReturn(List.of());
    when(measurements.findByPatientProfileIdAndMeasuredAtGreaterThanEqualAndMeasuredAtLessThanOrderByMeasuredAtAsc(
            eq(10L), any(), any())).thenReturn(List.of());

    service.currentPatientTrend(patientAuth, LocalDate.of(2026, 6, 25), LocalDate.of(2026, 6, 26));

    verify(measurements).findByPatientProfileIdAndMeasuredAtGreaterThanEqualAndMeasuredAtLessThanOrderByMeasuredAtAsc(
            eq(10L),
            eq(LocalDate.of(2026, 6, 25).atStartOfDay(ZoneId.of("Europe/Prague")).toInstant()),
            eq(LocalDate.of(2026, 6, 27).atStartOfDay(ZoneId.of("Europe/Prague")).toInstant()));
}

@Test
void rejectsTrendRangesLongerThanThreeHundredSeventyDaysThroughSharedValidator() {
    assertThatThrownBy(() -> service.currentPatientTrend(patientAuth,
            LocalDate.of(2025, 1, 1), LocalDate.of(2026, 6, 26)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("date range cannot exceed 370 days");
}
```

- [ ] **Step 2: Run trend test and verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.service.DailyTrendServiceTest
```

Expected: compilation fails because DTO/service/repository query do not exist.

- [ ] **Step 3: Add trend DTO**

Create:

```java
package com.metabion.dto;

import com.metabion.domain.FlareState;
import com.metabion.domain.MeasurementContext;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record DailyTrendResponse(
        Long patientProfileId,
        LocalDate from,
        LocalDate to,
        List<DayTrend> days
) {
    public record DayTrend(
            LocalDate date,
            Long symptomCheckInId,
            BigDecimal symptomScore,
            FlareState flareState,
            Long dietLogId,
            String adherenceLevel,
            String appetiteLevel,
            List<MeasurementPoint> glucoseMeasurements,
            List<MeasurementPoint> ketoneMeasurements
    ) {
    }

    public record MeasurementPoint(
            Long id,
            MeasurementType measurementType,
            BigDecimal value,
            MeasurementUnit unit,
            Instant measuredAt,
            MeasurementContext context
    ) {
    }
}
```

- [ ] **Step 4: Add shared date-range validator, repository query, and service**

Create `DateRangeValidator` and replace the private duplicate range validation in `DietLogService` with this shared service:

```java
package com.metabion.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Component
public class DateRangeValidator {
    private static final long MAX_RANGE_DAYS = 370;

    public void validate(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw badRequest("from and to are required");
        }
        if (from.isAfter(to)) {
            throw badRequest("from must be on or before to");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw badRequest("date range cannot exceed 370 days");
        }
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
```

Add to `DailyMeasurementEntryRepository`:

```java
List<DailyMeasurementEntry> findByPatientProfileIdAndMeasuredAtGreaterThanEqualAndMeasuredAtLessThanOrderByMeasuredAtAsc(
        Long patientProfileId,
        Instant fromInclusive,
        Instant toExclusive);
```

Implement `DailyTrendService` methods:

```java
public DailyTrendResponse currentPatientTrend(Authentication authentication, LocalDate from, LocalDate to)
public DailyTrendResponse clinicalTrend(Authentication authentication, Long patientProfileId, LocalDate from, LocalDate to)
```

Both methods must call `dateRangeValidator.validate(from, to)` before querying repositories. `DietLogService` should be updated in this task to delegate its existing range validation to the same `DateRangeValidator`, preserving current error messages.

Grouping rule:

```java
private LocalDate measurementDate(PatientProfile patient, DailyMeasurementEntry entry) {
    return entry.getMeasuredAt().atZone(measurementWindows.zoneFor(patient)).toLocalDate();
}
```

Measurement query bounds rule:

```java
private MeasurementWindowService.MeasurementWindow trendWindow(PatientProfile patient, LocalDate from, LocalDate to) {
    return measurementWindows.dateRangeWindow(patient, inclusiveDates(from, to));
}
```

Use `window.fromInclusive()` and `window.toExclusive()` as the repository query bounds. This is required so measurements at the beginning/end of the selected patient-local date range are included correctly across timezones and DST transitions.

Range rule:

```java
private List<LocalDate> inclusiveDates(LocalDate from, LocalDate to) {
    return from.datesUntil(to.plusDays(1)).toList();
}
```

Return one `DayTrend` for each date in the range. Use empty measurement lists and null symptom fields for dates without data.

- [ ] **Step 5: Add trend REST endpoints**

Add `DailyTrendService` to `SymptomTrackingController` and expose:

```java
@GetMapping("/api/trends/daily")
public DailyTrendResponse trends(@RequestParam LocalDate from,
                                 @RequestParam LocalDate to,
                                 Authentication authentication) {
    return dailyTrendService.currentPatientTrend(authentication, from, to);
}
```

Add `DailyTrendService` to `ClinicalSymptomTrackingController` and expose:

```java
@GetMapping("/api/clinical/trends/daily")
public DailyTrendResponse clinicalTrends(@RequestParam Long patientProfileId,
                                         @RequestParam LocalDate from,
                                         @RequestParam LocalDate to,
                                         Authentication authentication) {
    return dailyTrendService.clinicalTrend(authentication, patientProfileId, from, to);
}
```

Extend `SymptomTrackingControllerTest` with:

```java
@Test
@WithMockUser(username = "patient@example.com", roles = "PATIENT")
void patientCanReadCombinedDailyTrends() throws Exception {
    when(dailyTrendService.currentPatientTrend(any(), eq(LocalDate.of(2026, 6, 1)), eq(LocalDate.of(2026, 6, 26))))
            .thenReturn(new DailyTrendResponse(10L, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 26), List.of()));

    mvc.perform(get("/api/trends/daily")
                    .param("from", "2026-06-01")
                    .param("to", "2026-06-26"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.patientProfileId").value(10));
}
```

- [ ] **Step 6: Run trend tests and commit**

Run:

```bash
./gradlew test --tests com.metabion.service.DailyTrendServiceTest
```

Expected: PASS.

Commit:

```bash
git add src/main/java/com/metabion/dto/DailyTrendResponse.java \
  src/main/java/com/metabion/service/DailyTrendService.java \
  src/main/java/com/metabion/controller/api/SymptomTrackingController.java \
  src/main/java/com/metabion/controller/api/ClinicalSymptomTrackingController.java \
  src/main/java/com/metabion/repository/DailyMeasurementEntryRepository.java \
  src/main/java/com/metabion/repository/DailyDietLogRepository.java \
  src/test/java/com/metabion/service/DailyTrendServiceTest.java \
  src/test/java/com/metabion/controller/api/SymptomTrackingControllerTest.java
git commit -m "Add combined daily trends"
```

---

### Task 5: Atomic Daily Check-In Coordinator

**Files:**
- Create: `src/main/java/com/metabion/dto/DailyCheckInForm.java`
- Create: `src/main/java/com/metabion/service/DailyCheckInService.java`
- Modify: `src/main/java/com/metabion/service/DietLogService.java`
- Test: `src/test/java/com/metabion/service/DailyCheckInServiceTest.java`

- [ ] **Step 1: Write failing atomic save tests**

Create tests:

```java
@Test
void saveCommitsDietAndSymptomsTogether() {
    var form = validDailyCheckInForm();
    when(dietLogService.saveForCurrentPatient(patientAuth, form.dietLogRequest())).thenReturn(dietResponse());
    when(symptomTrackingService.saveForCurrentPatient(patientAuth, form.symptomCheckInRequest())).thenReturn(symptomResponse());

    var response = service.saveForCurrentPatient(patientAuth, form);

    assertThat(response.dietLog().id()).isEqualTo(100L);
    assertThat(response.symptomCheckIn().id()).isEqualTo(200L);
    verify(dietLogService).saveForCurrentPatient(patientAuth, form.dietLogRequest());
    verify(symptomTrackingService).saveForCurrentPatient(patientAuth, form.symptomCheckInRequest());
}

@Test
void saveIsTransactionalWhenSymptomSectionFails() {
    var form = validDailyCheckInForm();
    when(dietLogService.saveForCurrentPatient(patientAuth, form.dietLogRequest())).thenReturn(dietResponse());
    when(symptomTrackingService.saveForCurrentPatient(patientAuth, form.symptomCheckInRequest()))
            .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "required symptom answers are missing"));

    assertThatThrownBy(() -> service.saveForCurrentPatient(patientAuth, form))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("required symptom answers are missing");
}
```

For the second test, assert that the service method has `@Transactional`. Task 6 web tests cover the user-visible rollback behavior through the real controller flow.

- [ ] **Step 2: Run atomic service test and verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.service.DailyCheckInServiceTest
```

Expected: compilation fails because `DailyCheckInService` and `DailyCheckInForm` do not exist.

- [ ] **Step 3: Add form DTO and coordinator**

Create `DailyCheckInForm` as a Java record:

```java
package com.metabion.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record DailyCheckInForm(
        @NotNull @Valid DailyDietLogRequest dietLogRequest,
        @NotNull @Valid SymptomCheckInRequest symptomCheckInRequest
) {
}
```

Create `DailyCheckInService`:

```java
package com.metabion.service;

import com.metabion.dto.DailyCheckInForm;
import com.metabion.dto.DailyDietLogResponse;
import com.metabion.dto.SymptomCheckInResponse;
import jakarta.transaction.Transactional;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
@Transactional
public class DailyCheckInService {
    private final DietLogService dietLogService;
    private final SymptomTrackingService symptomTrackingService;

    public DailyCheckInService(DietLogService dietLogService, SymptomTrackingService symptomTrackingService) {
        this.dietLogService = dietLogService;
        this.symptomTrackingService = symptomTrackingService;
    }

    public DailyCheckInResponse saveForCurrentPatient(Authentication authentication, DailyCheckInForm form) {
        if (form == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "daily check-in form is required");
        }
        var dietLog = dietLogService.saveForCurrentPatient(authentication, form.dietLogRequest());
        var symptomCheckIn = symptomTrackingService.saveForCurrentPatient(authentication, form.symptomCheckInRequest());
        return new DailyCheckInResponse(dietLog, symptomCheckIn);
    }

    public record DailyCheckInResponse(DailyDietLogResponse dietLog, SymptomCheckInResponse symptomCheckIn) {
    }
}
```

- [ ] **Step 4: Run atomic service tests and commit**

Run:

```bash
./gradlew test --tests com.metabion.service.DailyCheckInServiceTest
```

Expected: PASS.

Commit:

```bash
git add src/main/java/com/metabion/dto/DailyCheckInForm.java \
  src/main/java/com/metabion/service/DailyCheckInService.java \
  src/main/java/com/metabion/service/DietLogService.java \
  src/test/java/com/metabion/service/DailyCheckInServiceTest.java
git commit -m "Coordinate atomic daily check-ins"
```

---

### Task 6: Unified Patient Daily Check-In Web Page

**Files:**
- Create: `src/main/java/com/metabion/controller/web/WebDailyCheckInController.java`
- Create: `src/main/resources/templates/daily-check-in.html`
- Modify: `src/main/java/com/metabion/controller/web/AppMenuCatalog.java`
- Modify: `src/main/resources/messages.properties`
- Modify: `src/main/resources/messages_cs.properties`
- Modify: `src/main/resources/static/css/app.css`
- Test: `src/test/java/com/metabion/controller/web/WebDailyCheckInControllerTest.java`

- [ ] **Step 1: Write failing web tests**

Create tests:

```java
@Test
@WithMockUser(username = "patient@example.com", roles = "PATIENT")
void dailyCheckInPageRendersDietMeasurementsSymptomsAndFlareState() throws Exception {
    when(symptomTrackingService.activeQuestionnaire()).thenReturn(questionnaireResponse());
    when(dietLogService.currentPatientGlucoseUnitPreference(any())).thenReturn(MeasurementUnit.MMOL_L);
    when(dietLogService.currentPatientTimezone(any())).thenReturn("Europe/Prague");

    mvc.perform(get("/app/daily-check-in").param("date", "2026-06-26"))
            .andExpect(status().isOk())
            .andExpect(view().name("daily-check-in"))
            .andExpect(content().string(containsString("Symptoms")))
            .andExpect(content().string(containsString("Flare state")))
            .andExpect(content().string(containsString("Stool frequency")))
            .andExpect(content().string(containsString("Glucose")))
            .andExpect(content().string(containsString("Ketones")));
}

@Test
@WithMockUser(username = "patient@example.com", roles = "PATIENT")
void invalidDailyCheckInRedisplaysWithoutSuccessRedirect() throws Exception {
    when(dailyCheckInService.saveForCurrentPatient(any(), any()))
            .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "required symptom answers are missing"));

    mvc.perform(post("/app/daily-check-in")
                    .with(csrf())
                    .param("logDate", "2026-06-26")
                    .param("adherenceLevel", "FULL")
                    .param("appetiteLevel", "NORMAL")
                    .param("flareState", "NO_FLARE"))
            .andExpect(status().isOk())
            .andExpect(view().name("daily-check-in"))
            .andExpect(model().attributeExists("dailyCheckInError"));
}
```

- [ ] **Step 2: Run web tests and verify they fail**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.WebDailyCheckInControllerTest
```

Expected: compilation fails because controller/template integration does not exist.

- [ ] **Step 3: Add controller**

Implement mappings:

```java
@GetMapping({"/app/daily-check-in", "/app/diet-logs"})
public String form(@RequestParam(required = false) LocalDate date, Model model, Authentication authentication)
```

```java
@PostMapping({"/app/daily-check-in", "/app/diet-logs"})
public String save(@Valid @ModelAttribute("dailyCheckInForm") DailyCheckInWebForm form,
                   BindingResult binding,
                   Model model,
                   Authentication authentication)
```

Use a controller-local `DailyCheckInWebForm` class that wraps the existing diet-log form fields and adds symptom fields. The form must expose:

```java
LocalDate logDate
DietAdherenceLevel adherenceLevel
AppetiteLevel appetiteLevel
List<DietLogForm.MealRow> meals
DietLogForm.MeasurementRow glucoseMeasurement
DietLogForm.MeasurementRow ketoneMeasurement
Long questionnaireVersionId
FlareState flareState
List<SymptomAnswerRow> symptomAnswers
String symptomNotes
```

`toDailyCheckInForm()` must build:

```java
return new DailyCheckInForm(toDietLogRequest(), toSymptomCheckInRequest());
```

- [ ] **Step 4: Add template and menu labels**

Create `daily-check-in.html` using the existing `layout.html` shell conventions from `diet-logs.html`. Required sections:

```html
<h1 th:text="#{menu.dailyCheckIn}">Daily check-in</h1>
<section class="form-section">
  <h2 th:text="#{dailyCheckIn.dietSection}">Diet</h2>
</section>
<section class="form-section">
  <h2 th:text="#{dailyCheckIn.measurementsSection}">Measurements</h2>
</section>
<section class="form-section">
  <h2 th:text="#{dailyCheckIn.symptomsSection}">Symptoms</h2>
  <fieldset>
    <legend th:text="#{dailyCheckIn.flareState}">Flare state</legend>
  </fieldset>
</section>
```

Add message keys:

```properties
menu.dailyCheckIn=Daily check-in
menu.dailyCheckIn.description=Record diet, measurements, symptoms, and flare state for one day.
dailyCheckIn.dietSection=Diet
dailyCheckIn.measurementsSection=Measurements
dailyCheckIn.symptomsSection=Symptoms
dailyCheckIn.flareState=Flare state
enum.flareState.NO_FLARE=No flare
enum.flareState.SUSPECTED_FLARE=Suspected flare
enum.flareState.ACTIVE_FLARE=Active flare
dailyCheckIn.saved=Daily check-in saved.
```

Change `AppMenuCatalog.patientItems()` so the old `menu.dietLogs` item uses `/app/daily-check-in` and `menu.dailyCheckIn`.

- [ ] **Step 5: Run web tests and commit**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.WebDailyCheckInControllerTest
```

Expected: PASS.

Commit:

```bash
git add src/main/java/com/metabion/controller/web/WebDailyCheckInController.java \
  src/main/java/com/metabion/controller/web/AppMenuCatalog.java \
  src/main/resources/templates/daily-check-in.html \
  src/main/resources/messages.properties \
  src/main/resources/messages_cs.properties \
  src/main/resources/static/css/app.css \
  src/test/java/com/metabion/controller/web/WebDailyCheckInControllerTest.java
git commit -m "Add unified daily check-in page"
```

---

### Task 7: Patient And Clinical Trend Web Pages With Inline SVG

**Files:**
- Create: `src/main/java/com/metabion/controller/web/WebTrendController.java`
- Create: `src/main/java/com/metabion/controller/web/TrendSvgRenderer.java`
- Create: `src/main/resources/templates/trends.html`
- Create: `src/main/resources/templates/clinical-trends.html`
- Modify: `src/main/java/com/metabion/controller/web/AppMenuCatalog.java`
- Modify: `src/main/resources/messages.properties`
- Modify: `src/main/resources/messages_cs.properties`
- Modify: `src/main/resources/static/css/app.css`
- Test: `src/test/java/com/metabion/controller/web/TrendSvgRendererTest.java`
- Test: `src/test/java/com/metabion/controller/web/WebTrendControllerTest.java`

- [ ] **Step 1: Write failing SVG renderer test**

Create:

```java
@Test
void rendersSymptomPolylineFlareMarkersAndMeasurementMarkers() {
    var response = new DailyTrendResponse(10L, LocalDate.of(2026, 6, 25), LocalDate.of(2026, 6, 26),
            List.of(
                    dayWithMeasurements(LocalDate.of(2026, 6, 25), new BigDecimal("2.00"), FlareState.NO_FLARE),
                    dayWithMeasurements(LocalDate.of(2026, 6, 26), new BigDecimal("5.00"), FlareState.SUSPECTED_FLARE)));

    var svg = renderer.render(response);

    assertThat(svg).contains("<svg");
    assertThat(svg).contains("polyline");
    assertThat(svg).contains("data-flare-state=\"SUSPECTED_FLARE\"");
    assertThat(svg).contains("data-measurement-type=\"GLUCOSE\"");
    assertThat(svg).contains("data-measurement-type=\"KETONE\"");
}
```

- [ ] **Step 2: Write failing web trend tests**

Create:

```java
@Test
@WithMockUser(username = "patient@example.com", roles = "PATIENT")
void patientTrendPageRendersCombinedTimeline() throws Exception {
    when(dailyTrendService.currentPatientTrend(any(), any(), any())).thenReturn(trendResponse());
    when(trendSvgRenderer.render(any())).thenReturn("<svg role=\"img\"></svg>");

    mvc.perform(get("/app/trends").param("from", "2026-06-01").param("to", "2026-06-26"))
            .andExpect(status().isOk())
            .andExpect(view().name("trends"))
            .andExpect(content().string(containsString("Symptom score")))
            .andExpect(content().string(containsString("Glucose")))
            .andExpect(content().string(containsString("Ketones")));
}

@Test
@WithMockUser(username = "staff@example.com", roles = "PHYSICIAN")
void clinicalTrendPageRequiresPatientProfileIdAndRendersTimeline() throws Exception {
    when(dailyTrendService.clinicalTrend(any(), eq(10L), any(), any())).thenReturn(trendResponse());
    when(dietLogService.listClinicalPatientOptions(any())).thenReturn(List.of(new PatientOptionResponse(10L, "patient@example.com")));
    when(trendSvgRenderer.render(any())).thenReturn("<svg role=\"img\"></svg>");

    mvc.perform(get("/app/clinical/trends")
                    .param("patientProfileId", "10")
                    .param("from", "2026-06-01")
                    .param("to", "2026-06-26"))
            .andExpect(status().isOk())
            .andExpect(view().name("clinical-trends"))
            .andExpect(content().string(containsString("patient@example.com")));
}
```

- [ ] **Step 3: Run trend web tests and verify they fail**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.TrendSvgRendererTest \
  --tests com.metabion.controller.web.WebTrendControllerTest
```

Expected: compilation fails because renderer/controller/templates do not exist.

- [ ] **Step 4: Add SVG renderer**

Create `TrendSvgRenderer`:

```java
@Component
public class TrendSvgRenderer {
    public String render(DailyTrendResponse trend) {
        if (trend == null || trend.days().isEmpty()) {
            return "<svg class=\"trend-chart\" role=\"img\" aria-label=\"No trend data\"></svg>";
        }
        var points = symptomPoints(trend.days());
        var markers = flareMarkers(trend.days());
        var measurementMarkers = measurementMarkers(trend.days());
        return """
                <svg class="trend-chart" viewBox="0 0 640 220" role="img" aria-label="Symptom and flare trend">
                  <polyline class="trend-line trend-line-symptoms" points="%s" fill="none" />
                  %s
                  %s
                </svg>
                """.formatted(points, markers, measurementMarkers);
    }
}
```

Use deterministic scaling:

- x position: spread days across `40..600`.
- y position: score `0..30` mapped to `180..30`.
- null symptom score: omit from the polyline.
- flare marker: render `<circle>` for `SUSPECTED_FLARE` and `<rect>` for `ACTIVE_FLARE`.
- glucose and ketone readings are drawn as timeline markers, not normalized line values, to avoid mixing measurement units on one y-axis.
- glucose marker band: y `196`; ketone marker band: y `208`.
- each marker includes `data-measurement-type`, `data-value`, `data-unit`, and `data-measured-at` attributes so the table and SVG preserve all readings.

- [ ] **Step 5: Add web controller/templates/menu**

`WebTrendController` mappings:

```java
@GetMapping("/app/trends")
public String patientTrends(@RequestParam(required = false) LocalDate from,
                            @RequestParam(required = false) LocalDate to,
                            Model model,
                            Authentication authentication)
```

```java
@GetMapping("/app/clinical/trends")
public String clinicalTrends(@RequestParam(required = false) Long patientProfileId,
                             @RequestParam(required = false) LocalDate from,
                             @RequestParam(required = false) LocalDate to,
                             Model model,
                             Authentication authentication)
```

Default range: last 30 days inclusive.

Add menu items:

```java
item("menu.trends", "/app/trends", false, true, "menu.trends.description")
item("menu.clinicalTrends", "/app/clinical/trends", false, true, "menu.clinicalTrends.description")
```

Add message keys:

```properties
menu.trends=Trends
menu.trends.description=Review symptom, flare, glucose, and ketone trends.
menu.clinicalTrends=Patient trends
menu.clinicalTrends.description=Review assigned patient symptom and measurement trends.
trends.symptomScore=Symptom score
trends.glucose=Glucose
trends.ketones=Ketones
trends.flareState=Flare state
```

- [ ] **Step 6: Run trend web tests and commit**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.TrendSvgRendererTest \
  --tests com.metabion.controller.web.WebTrendControllerTest
```

Expected: PASS.

Commit:

```bash
git add src/main/java/com/metabion/controller/web/WebTrendController.java \
  src/main/java/com/metabion/controller/web/TrendSvgRenderer.java \
  src/main/java/com/metabion/controller/web/AppMenuCatalog.java \
  src/main/resources/templates/trends.html \
  src/main/resources/templates/clinical-trends.html \
  src/main/resources/messages.properties \
  src/main/resources/messages_cs.properties \
  src/main/resources/static/css/app.css \
  src/test/java/com/metabion/controller/web/TrendSvgRendererTest.java \
  src/test/java/com/metabion/controller/web/WebTrendControllerTest.java
git commit -m "Add symptom and measurement trend pages"
```

---

### Task 8: End-To-End Contract, Localization, And Regression Sweep

**Files:**
- Modify: `src/test/java/com/metabion/controller/api/SymptomTrackingControllerTest.java`
- Modify: `src/test/java/com/metabion/controller/web/WebDailyCheckInControllerTest.java`
- Modify: `src/test/java/com/metabion/controller/web/WebTrendControllerTest.java`
- Modify: `src/test/java/com/metabion/config/SecurityConfigTest.java`
- Modify: `src/test/java/com/metabion/controller/web/AppMenuCatalogTest.java`
- Modify: `src/main/resources/messages.properties`
- Modify: `src/main/resources/messages_cs.properties`

- [ ] **Step 1: Add missing security/menu regression tests**

Add assertions:

```java
@Test
void patientMenuShowsDailyCheckInAndTrends() {
    var items = catalog.sidebarItems(authenticationWith(RoleName.PATIENT));

    assertThat(items).extracting(AppMenuItem::label)
            .contains("Daily check-in", "Trends");
    assertThat(items).extracting(AppMenuItem::route)
            .contains("/app/daily-check-in", "/app/trends");
}

@Test
void clinicalMenuShowsDietReviewAndPatientTrends() {
    var items = catalog.sidebarItems(authenticationWith(RoleName.PHYSICIAN));

    assertThat(items).extracting(AppMenuItem::label)
            .contains("Diet log review", "Patient trends");
    assertThat(items).extracting(AppMenuItem::route)
            .contains("/app/clinical/diet-logs", "/app/clinical/trends");
}
```

- [ ] **Step 2: Add localization completeness checks**

Extend `src/test/java/com/metabion/config/LocalizationConfigTest.java` with:

```java
assertThat(messages.getMessage("menu.dailyCheckIn", null, Locale.ENGLISH)).isEqualTo("Daily check-in");
assertThat(messages.getMessage("menu.trends", null, Locale.ENGLISH)).isEqualTo("Trends");
assertThat(messages.getMessage("dailyCheckIn.flareState", null, Locale.ENGLISH)).isEqualTo("Flare state");
assertThat(messages.getMessage("enum.flareState.NO_FLARE", null, Locale.ENGLISH)).isEqualTo("No flare");
```

Add Czech entries with clear Czech text:

```properties
menu.dailyCheckIn=Denní záznam
menu.dailyCheckIn.description=Zaznamenejte stravu, měření, příznaky a stav vzplanutí za jeden den.
menu.trends=Trendy
menu.trends.description=Zobrazte trendy příznaků, vzplanutí, glukózy a ketonů.
dailyCheckIn.flareState=Stav vzplanutí
enum.flareState.NO_FLARE=Bez vzplanutí
enum.flareState.SUSPECTED_FLARE=Podezření na vzplanutí
enum.flareState.ACTIVE_FLARE=Aktivní vzplanutí
```

- [ ] **Step 3: Run focused regression tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.AppMenuCatalogTest \
  --tests com.metabion.config.LocalizationConfigTest \
  --tests com.metabion.config.SecurityConfigTest
```

Expected: PASS.

- [ ] **Step 4: Run full test suite**

Run:

```bash
./gradlew test
```

Expected: PASS, including Jacoco report generation.

- [ ] **Step 5: Run build**

Run:

```bash
./gradlew build
```

Expected: PASS.

- [ ] **Step 6: Commit final hardening**

Commit:

```bash
git add src/main/resources/messages.properties \
  src/main/resources/messages_cs.properties \
  src/test/java/com/metabion/controller/api/SymptomTrackingControllerTest.java \
  src/test/java/com/metabion/controller/web/WebDailyCheckInControllerTest.java \
  src/test/java/com/metabion/controller/web/WebTrendControllerTest.java \
  src/test/java/com/metabion/config/SecurityConfigTest.java \
  src/test/java/com/metabion/controller/web/AppMenuCatalogTest.java
git commit -m "Verify symptom tracking integration"
```

---

## Final Verification

Run:

```bash
git status --short
./gradlew test
./gradlew build
```

Expected:

- `git status --short` shows only intentional untracked local artifacts such as `.superpowers/` or `var/`, or no output.
- `./gradlew test` passes.
- `./gradlew build` passes.

Manual browser checks after `./gradlew bootRun`:

- Patient can open `/app/daily-check-in`, fill diet, measurements, symptoms, flare state, and save.
- Patient can open `/app/trends` and see symptom score, flare markers, glucose, and ketones.
- Clinical user can open `/app/clinical/trends?patientProfileId=<assigned-id>` and see the assigned patient timeline.
- Unassigned clinical user cannot read another patient's trend API.
