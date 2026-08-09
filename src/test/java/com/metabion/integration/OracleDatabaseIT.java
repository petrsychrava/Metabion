package com.metabion.integration;

import com.metabion.domain.EducationLanguage;
import com.metabion.domain.EducationLesson;
import com.metabion.domain.EducationLessonLocalization;
import com.metabion.domain.EducationLessonVersion;
import com.metabion.domain.EducationModule;
import com.metabion.domain.EducationModuleLocalization;
import com.metabion.domain.EducationModuleVersion;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.StaffProfile;
import com.metabion.domain.User;
import com.metabion.repository.EducationLessonCompletionInsertPort;
import com.metabion.repository.EducationLessonCompletionRepository;
import com.metabion.repository.EducationLessonRepository;
import com.metabion.repository.EducationModuleRepository;
import com.metabion.repository.EducationModuleVersionRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.StaffProfileRepository;
import com.metabion.repository.UserRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.profiles.active=oracle",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@EnabledIfEnvironmentVariable(named = "ORACLE_TEST_URL", matches = "jdbc:oracle:thin:.*")
class OracleDatabaseIT {

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    UserRepository users;

    @Autowired
    PatientProfileRepository patientProfiles;

    @Autowired
    StaffProfileRepository staffProfiles;

    @Autowired
    EducationModuleRepository modules;

    @Autowired
    EducationModuleVersionRepository versions;

    @Autowired
    EducationLessonRepository lessons;

    @Autowired
    EducationLessonCompletionRepository completions;

    @Autowired
    EducationLessonCompletionInsertPort completionInsertions;

    @DynamicPropertySource
    static void oracleProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("ORACLE_TEST_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("ORACLE_TEST_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("ORACLE_TEST_PASSWORD"));
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/oracle");
        registry.add("metabion.database", () -> "oracle");
    }

    @Test
    void appliesOracleMigrationsThroughVersion21() {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().applied())
                .anyMatch(migration -> migration.getVersion() != null && "21".equals(migration.getVersion().getVersion()));
    }

    @Test
    void exposesOracleSpecificColumnAndAssertionMetadata() {
        assertColumnType("USERS", "ENABLED", "BOOLEAN");
        assertColumnType("USERS", "MFA_ENABLED", "BOOLEAN");
        assertColumnType("USERS", "MFA_SECRET_ENCRYPTED", "BLOB");
        assertColumnType("EDUCATION_LESSON_LOCALIZATIONS", "BODY_MARKDOWN", "CLOB");
        assertColumnExists("PATIENT_ACCESS_TOKENS", "resource");
        assertColumnExists("OAUTH_AUTHORIZATION_CODES", "resource");
        assertColumnExists("OAUTH_REFRESH_TOKENS", "resource");

        Map<String, Map<String, Object>> assertions = jdbcTemplate.queryForList("""
                        SELECT assertion_name, deferrable, deferred
                        FROM user_assertions
                        WHERE assertion_name IN (
                            'ASSERT_PATIENT_PROFILE_HAS_ROLE',
                            'ASSERT_STAFF_PROFILE_HAS_ROLE'
                        )
                        """).stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> (String) row.get("ASSERTION_NAME"),
                        row -> row));

        assertThat(assertions).containsKeys(
                "ASSERT_PATIENT_PROFILE_HAS_ROLE",
                "ASSERT_STAFF_PROFILE_HAS_ROLE");
        assertThat(assertions.get("ASSERT_PATIENT_PROFILE_HAS_ROLE"))
                .containsEntry("DEFERRABLE", "DEFERRABLE")
                .containsEntry("DEFERRED", "DEFERRED");
        assertThat(assertions.get("ASSERT_STAFF_PROFILE_HAS_ROLE"))
                .containsEntry("DEFERRABLE", "DEFERRABLE")
                .containsEntry("DEFERRED", "DEFERRED");
    }

    @Test
    void insertsEducationCompletionOnlyOnce() {
        EducationFixture fixture = createEducationFixture();

        assertThat(completionInsertions.insertCompletionIfAbsent(
                fixture.patientProfileId(), fixture.moduleVersionId(), fixture.lessonVersionId())).isEqualTo(1);
        assertThat(completionInsertions.insertCompletionIfAbsent(
                fixture.patientProfileId(), fixture.moduleVersionId(), fixture.lessonVersionId())).isZero();
        assertThat(completions.findCompletedLessonVersionIds(
                fixture.patientProfileId(), java.util.List.of(fixture.lessonVersionId())))
                .containsExactly(fixture.lessonVersionId());
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void concurrentEducationCompletionInsertsReturnOneAndZero() throws Exception {
        EducationFixture fixture = createEducationFixture();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Integer> first = submitCompletion(executor, ready, start, fixture);
            Future<Integer> second = submitCompletion(executor, ready, start, fixture);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)).stream().sorted().toList())
                    .containsExactly(0, 1);
            assertThat(completions.findCompletedLessonVersionIds(
                    fixture.patientProfileId(), List.of(fixture.lessonVersionId())))
                    .containsExactly(fixture.lessonVersionId());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsDuplicateActivePatientExpertAssignments() {
        PatientProfile patient = createPatient(nextEmail("assignment-patient"));
        StaffProfile staff = createStaff(nextEmail("assignment-staff"));

        jdbcTemplate.update("""
                INSERT INTO patient_expert_assignments (patient_profile_id, staff_profile_id)
                VALUES (?, ?)
                """, patient.getId(), staff.getId());

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO patient_expert_assignments (patient_profile_id, staff_profile_id)
                        VALUES (?, ?)
                        """, patient.getId(), staff.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsChangesToRedFlagTransitionHistory() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        UPDATE red_flag_rule_transitions
                        SET change_note = 'invalid integration-test mutation'
                        WHERE id = (SELECT MIN(id) FROM red_flag_rule_transitions)
                        """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsCommitWhenPatientProfileLosesItsOnlyPatientRole() {
        PatientProfile patient = createPatient(nextEmail("deferred-patient"));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ? AND role = 'PATIENT'",
                        patient.getUser().getId())))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void permitsReplacingTheOnlyClinicalStaffRoleWithinOneTransaction() {
        StaffProfile staff = createStaff(nextEmail("deferred-staff"));

        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ? AND role = 'NUTRITION_SPECIALIST'",
                    staff.getUser().getId());
            jdbcTemplate.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'PHYSICIAN')",
                    staff.getUser().getId());
        });

        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM user_roles
                        WHERE user_id = ? AND role = 'PHYSICIAN'
                        """, Integer.class, staff.getUser().getId())).isEqualTo(1);
    }

    private void assertColumnType(String tableName, String columnName, String expectedType) {
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT data_type
                        FROM user_tab_columns
                        WHERE table_name = ? AND column_name = ?
                        """, String.class, tableName, columnName)).isEqualTo(expectedType);
    }

    private void assertColumnExists(String tableName, String columnName) {
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM user_tab_columns
                        WHERE table_name = ? AND column_name = ?
                        """, Integer.class, tableName, columnName)).isEqualTo(1);
    }

    private Future<Integer> submitCompletion(
            ExecutorService executor,
            CountDownLatch ready,
            CountDownLatch start,
            EducationFixture fixture) {
        return executor.submit(() -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to start concurrent completion insert");
            }
            return transactionTemplate.execute(status -> completionInsertions.insertCompletionIfAbsent(
                    fixture.patientProfileId(), fixture.moduleVersionId(), fixture.lessonVersionId()));
        });
    }

    private EducationFixture createEducationFixture() {
        User admin = createUser(nextEmail("education-admin"), RoleName.ADMIN);
        PatientProfile patient = createPatient(nextEmail("education-patient"));
        String slug = "oracle-" + UUID.randomUUID();
        EducationModule module = modules.saveAndFlush(new EducationModule(slug, "KETO", 10));
        EducationModuleVersion version = new EducationModuleVersion(module, 1, admin);
        version.addLocalization(new EducationModuleLocalization(version, EducationLanguage.EN, slug + " title", "Summary"));
        EducationLesson lesson = lessons.saveAndFlush(new EducationLesson(module, slug + "-lesson"));
        EducationLessonVersion lessonVersion = new EducationLessonVersion(version, lesson, 1);
        lessonVersion.addLocalization(new EducationLessonLocalization(
                lessonVersion, EducationLanguage.EN, slug + " lesson", "Lesson summary", "Lesson body"));
        version.addLesson(lessonVersion);
        version.publishDirectlyByAdmin(admin);
        versions.saveAndFlush(version);
        module.publish(version);
        modules.saveAndFlush(module);

        return new EducationFixture(patient.getId(), version.getId(), lessonVersion.getId());
    }

    private PatientProfile createPatient(String email) {
        return patientProfiles.saveAndFlush(new PatientProfile(createUser(email, RoleName.PATIENT)));
    }

    private StaffProfile createStaff(String email) {
        return staffProfiles.saveAndFlush(new StaffProfile(createUser(email, RoleName.NUTRITION_SPECIALIST)));
    }

    private User createUser(String email, RoleName role) {
        User user = users.saveAndFlush(new User(email, "hash"));
        user.addRole(role);
        return users.saveAndFlush(user);
    }

    private String nextEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private record EducationFixture(Long patientProfileId, Long moduleVersionId, Long lessonVersionId) {
    }
}
