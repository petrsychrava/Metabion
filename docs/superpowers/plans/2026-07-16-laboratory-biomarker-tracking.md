# Laboratory and Biomarker Tracking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add audited laboratory result entry, canonicalized single-biomarker trends, patient and clinical web/API workflows, and patient MCP laboratory tools for MET-14.

**Architecture:** Build a dedicated laboratory subsystem rather than extending `DailyMeasurementEntry`. MVC, REST, and MCP all delegate to the same `LabCatalogService`, `LabResultService`, and `LabTrendService`; `PatientAppFacade` is only the patient MCP boundary. Flyway owns the catalog/result/audit schema, and a dedicated SVG chart path renders one canonicalized biomarker at a time.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring MVC, Thymeleaf, Spring Security, Spring Data JPA, Flyway, PostgreSQL, Spring AI MCP 2.0.0, JUnit 5, Mockito, AssertJ, MockMvc, H2, Testcontainers PostgreSQL, Gradle wrapper.

## Global Constraints

- Use `./gradlew`; do not use system Gradle or `npm test`.
- Add no dependencies; the existing Spring, Jackson, JPA, and test stack covers the work.
- Use `V19__laboratory_biomarker_tracking.sql`; Hibernate remains validate-only outside isolated mapping tests.
- Keep ordinary MVC and REST authentication session-based with CSRF enabled.
- Keep MCP patient-only, bearer-token authenticated, resource-bound, expiry/revocation checked, and scope-authorized.
- Patients read all of their non-removed results but modify only sets they created; assigned clinical staff manage accessible patients; administrators manage every patient.
- Preserve reported values and units; derive canonical values server-side with `BigDecimal` and trusted catalog conversions.
- Initial values are numeric and non-negative; do not encode clinical ranges, abnormal flags, alerts, diagnoses, or guidance.
- Store immutable mutation snapshots as protected application data; never put laboratory values in operational logs or MCP tool-audit metadata.
- Externalize all new user-facing strings in aligned English and Czech bundles.
- Do not implement report upload, OCR/extraction, extraction confirmation, an admin catalog UI, external integrations, or clinical MCP tools.
- Preserve unrelated `.idea`, `.superpowers`, and `var` changes.

## Planned File Structure

- Shared patient selection: create `ClinicalPatientDirectoryService`; migrate diet-log, daily-check-in, and trend consumers.
- Persistence: add V19, five lab entities, five enums, four repositories, and PostgreSQL repository tests.
- Contracts/services: add seven DTOs plus catalog, conversion, response, audit, result, and trend services.
- API/web: add patient and clinical REST controllers, two web controllers, chart model/builder/renderer, three templates, form JavaScript, CSS, menus, and localization.
- MCP/OAuth: extend `PatientAccessTokenScope`, `PatientAppFacade`, `PatientMcpTools`, and scope-flow tests.

---

### Task 1: Extract the Shared Clinical Patient Directory

**Files:**
- Create: `src/main/java/com/metabion/service/ClinicalPatientDirectoryService.java`
- Create: `src/test/java/com/metabion/service/ClinicalPatientDirectoryServiceTest.java`
- Modify: `src/main/java/com/metabion/service/DietLogService.java`
- Modify: `src/main/java/com/metabion/service/ClinicalDailyCheckInService.java`
- Modify: `src/main/java/com/metabion/controller/web/WebClinicalDailyCheckInController.java`
- Modify: `src/main/java/com/metabion/controller/web/WebTrendController.java`
- Modify tests: `DietLogServiceTest`, `ClinicalDailyCheckInServiceTest`, `WebClinicalDailyCheckInControllerTest`, `WebTrendControllerTest`

**Interfaces:**
- Consumes: existing user, staff-profile, and patient-profile repositories.
- Produces: `List<PatientOptionResponse> listAccessible(Authentication authentication)`.

- [ ] **Step 1: Write failing directory-service tests**

```java
@Test
void adminListsAllPatients() {
    when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
    when(patientProfiles.findAllPatientOptions())
            .thenReturn(List.of(new PatientOptionResponse(10L, "p@example.com")));
    assertThat(service.listAccessible(auth("admin@example.com")))
            .containsExactly(new PatientOptionResponse(10L, "p@example.com"));
}

@Test
void assignedStaffListsRepositoryAuthorizedPatients() {
    when(users.findByEmail("doctor@example.com")).thenReturn(Optional.of(doctor));
    when(staffProfiles.findByUserId(doctor.getId())).thenReturn(Optional.of(staff));
    when(patientProfiles.findAccessiblePatientOptionsForStaff(staff.getId()))
            .thenReturn(List.of(new PatientOptionResponse(11L, "assigned@example.com")));
    assertThat(service.listAccessible(auth("doctor@example.com")))
            .extracting(PatientOptionResponse::id).containsExactly(11L);
}

@Test
void patientCannotListClinicalOptions() {
    when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patient));
    assertThatThrownBy(() -> service.listAccessible(auth("patient@example.com")))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
}
```

- [ ] **Step 2: Run the test and verify the missing-service failure**

Run: `./gradlew test --tests 'com.metabion.service.ClinicalPatientDirectoryServiceTest'`

Expected: FAIL during compilation because `ClinicalPatientDirectoryService` does not exist.

- [ ] **Step 3: Implement the neutral service and migrate consumers**

```java
@Service
@Transactional(readOnly = true)
public class ClinicalPatientDirectoryService {
    private final UserRepository users;
    private final StaffProfileRepository staffProfiles;
    private final PatientProfileRepository patientProfiles;

    public ClinicalPatientDirectoryService(UserRepository users, StaffProfileRepository staffProfiles,
                                           PatientProfileRepository patientProfiles) {
        this.users = users;
        this.staffProfiles = staffProfiles;
        this.patientProfiles = patientProfiles;
    }

    public List<PatientOptionResponse> listAccessible(Authentication authentication) {
        var user = currentUser(authentication);
        if (user.hasRole(RoleName.ADMIN)) return patientProfiles.findAllPatientOptions();
        if (!user.hasAnyRole(RoleName.NUTRITION_SPECIALIST, RoleName.PHYSICIAN, RoleName.COORDINATOR)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Current user cannot list patients");
        }
        return staffProfiles.findByUserId(user.getId())
                .map(staff -> patientProfiles.findAccessiblePatientOptionsForStaff(staff.getId()))
                .orElseGet(List::of);
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return users.findByEmail(UserService.normalize(authentication.getName()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Authenticated user not found"));
    }
}
```

Inject this service into all four consumers, replace `dietLogService.listClinicalPatientOptions(authentication)` with `clinicalPatientDirectory.listAccessible(authentication)`, and remove the old listing methods from `DietLogService`.

- [ ] **Step 4: Run affected tests**

```bash
./gradlew test --tests 'com.metabion.service.ClinicalPatientDirectoryServiceTest' \
  --tests 'com.metabion.service.DietLogServiceTest' \
  --tests 'com.metabion.service.ClinicalDailyCheckInServiceTest' \
  --tests 'com.metabion.controller.web.WebClinicalDailyCheckInControllerTest' \
  --tests 'com.metabion.controller.web.WebTrendControllerTest'
```

Expected: PASS for all five classes.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/service/ClinicalPatientDirectoryService.java \
  src/main/java/com/metabion/service/DietLogService.java \
  src/main/java/com/metabion/service/ClinicalDailyCheckInService.java \
  src/main/java/com/metabion/controller/web/WebClinicalDailyCheckInController.java \
  src/main/java/com/metabion/controller/web/WebTrendController.java \
  src/test/java/com/metabion/service/ClinicalPatientDirectoryServiceTest.java \
  src/test/java/com/metabion/service/DietLogServiceTest.java \
  src/test/java/com/metabion/service/ClinicalDailyCheckInServiceTest.java \
  src/test/java/com/metabion/controller/web/WebClinicalDailyCheckInControllerTest.java \
  src/test/java/com/metabion/controller/web/WebTrendControllerTest.java
git commit -m "Extract clinical patient directory"
```

---

### Task 2: Add the Laboratory Schema, Seed Catalog, and JPA Model

**Files:**
- Create: `src/main/resources/db/migration/V19__laboratory_biomarker_tracking.sql`
- Create enums: `LabTestCategory`, `LabUnitConversionType`, `LabResultSource`, `LabResultConfirmationStatus`, `LabAuditAction`
- Create entities: `LabTestDefinition`, `LabTestUnitDefinition`, `LabResultSet`, `LabResult`, `LabResultAuditEvent`
- Create repositories: `LabTestDefinitionRepository`, `LabResultSetRepository`, `LabResultRepository`, `LabResultAuditEventRepository`
- Create: `src/test/java/com/metabion/repository/LabRepositoryTest.java`

**Interfaces:**
- Produces 20 stable test codes and allowed-unit rows.
- Produces `LabResultSet.replaceResults(List<LabResult>, Instant)` and `markRemoved(User, String, Instant)`.
- Produces active set/date-range and per-test trend repository queries.

- [ ] **Step 1: Write failing PostgreSQL repository tests**

```java
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class LabRepositoryTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
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
        set.markRemoved(patient.getUser(), "duplicate", NOW.plusSeconds(60));
        resultSets.saveAndFlush(set);
        assertThat(resultSets.findActiveByPatientAndCollectionDateBetween(
                patient.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test and verify missing model failures**

Run: `./gradlew test --tests 'com.metabion.repository.LabRepositoryTest'`

Expected: FAIL during compilation because laboratory classes do not exist.

- [ ] **Step 3: Add V19 schema and complete seed data**

```sql
CREATE TABLE lab_test_definitions (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    label_key VARCHAR(120) NOT NULL UNIQUE,
    category VARCHAR(40) NOT NULL,
    canonical_unit VARCHAR(40) NOT NULL,
    display_scale SMALLINT NOT NULL CHECK (display_scale BETWEEN 0 AND 6),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL
);

CREATE TABLE lab_test_unit_definitions (
    id BIGSERIAL PRIMARY KEY,
    test_definition_id BIGINT NOT NULL REFERENCES lab_test_definitions(id) ON DELETE CASCADE,
    unit_code VARCHAR(40) NOT NULL,
    conversion_type VARCHAR(24) NOT NULL,
    multiplier NUMERIC(24, 12) NOT NULL CHECK (multiplier > 0),
    sort_order INTEGER NOT NULL,
    UNIQUE (test_definition_id, unit_code)
);

CREATE TABLE lab_result_sets (
    id BIGSERIAL PRIMARY KEY,
    patient_profile_id BIGINT NOT NULL REFERENCES patient_profiles(id),
    collection_date DATE NOT NULL,
    notes VARCHAR(2000),
    source VARCHAR(32) NOT NULL,
    confirmation_status VARCHAR(32) NOT NULL,
    created_by_user_id BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    removed_at TIMESTAMP WITH TIME ZONE,
    removed_by_user_id BIGINT REFERENCES users(id),
    removal_reason VARCHAR(500),
    CONSTRAINT chk_lab_result_set_removal CHECK (
        (removed_at IS NULL AND removed_by_user_id IS NULL AND removal_reason IS NULL)
        OR (removed_at IS NOT NULL AND removed_by_user_id IS NOT NULL)
    )
);

CREATE TABLE lab_results (
    id BIGSERIAL PRIMARY KEY,
    result_set_id BIGINT NOT NULL REFERENCES lab_result_sets(id) ON DELETE CASCADE,
    test_definition_id BIGINT NOT NULL REFERENCES lab_test_definitions(id),
    reported_value NUMERIC(18, 6) NOT NULL CHECK (reported_value >= 0),
    reported_unit VARCHAR(40) NOT NULL,
    canonical_value NUMERIC(18, 6) NOT NULL CHECK (canonical_value >= 0),
    canonical_unit VARCHAR(40) NOT NULL,
    reference_lower NUMERIC(18, 6) CHECK (reference_lower >= 0),
    reference_upper NUMERIC(18, 6) CHECK (reference_upper >= 0),
    UNIQUE (result_set_id, test_definition_id),
    CONSTRAINT chk_lab_result_reference CHECK (
        reference_lower IS NULL OR reference_upper IS NULL OR reference_lower <= reference_upper
    )
);

CREATE TABLE lab_result_audit_events (
    id BIGSERIAL PRIMARY KEY,
    result_set_id BIGINT NOT NULL REFERENCES lab_result_sets(id),
    patient_profile_id BIGINT NOT NULL REFERENCES patient_profiles(id),
    action VARCHAR(20) NOT NULL,
    actor_user_id BIGINT NOT NULL REFERENCES users(id),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    before_snapshot TEXT,
    after_snapshot TEXT,
    CONSTRAINT chk_lab_audit_snapshots CHECK (
        (action = 'CREATE' AND before_snapshot IS NULL AND after_snapshot IS NOT NULL)
        OR (action IN ('UPDATE', 'REMOVE') AND before_snapshot IS NOT NULL AND after_snapshot IS NOT NULL)
    )
);

CREATE INDEX idx_lab_result_sets_patient_date
    ON lab_result_sets(patient_profile_id, collection_date DESC) WHERE removed_at IS NULL;
CREATE INDEX idx_lab_results_definition ON lab_results(test_definition_id, result_set_id);
CREATE INDEX idx_lab_audit_set_time ON lab_result_audit_events(result_set_id, occurred_at);
```

Seed definitions and identity units:

```sql
INSERT INTO lab_test_definitions
    (code, label_key, category, canonical_unit, display_scale, active, sort_order)
VALUES
 ('CRP','lab.test.crp','INFLAMMATION','mg/L',2,TRUE,10),
 ('FECAL_CALPROTECTIN','lab.test.fecalCalprotectin','INFLAMMATION','ug/g',0,TRUE,20),
 ('HEMOGLOBIN','lab.test.hemoglobin','HEMATOLOGY','g/L',1,TRUE,30),
 ('FERRITIN','lab.test.ferritin','HEMATOLOGY','ug/L',1,TRUE,40),
 ('VITAMIN_D_25_OH','lab.test.vitaminD25Oh','NUTRITION','ng/mL',1,TRUE,50),
 ('VITAMIN_B12','lab.test.vitaminB12','NUTRITION','pg/mL',0,TRUE,60),
 ('ALBUMIN','lab.test.albumin','NUTRITION','g/L',1,TRUE,70),
 ('SODIUM','lab.test.sodium','ELECTROLYTE','mmol/L',1,TRUE,80),
 ('POTASSIUM','lab.test.potassium','ELECTROLYTE','mmol/L',2,TRUE,90),
 ('CHLORIDE','lab.test.chloride','ELECTROLYTE','mmol/L',1,TRUE,100),
 ('MAGNESIUM','lab.test.magnesium','ELECTROLYTE','mmol/L',2,TRUE,110),
 ('CALCIUM','lab.test.calcium','ELECTROLYTE','mmol/L',2,TRUE,120),
 ('ALT','lab.test.alt','LIVER','U/L',0,TRUE,130),
 ('AST','lab.test.ast','LIVER','U/L',0,TRUE,140),
 ('ALP','lab.test.alp','LIVER','U/L',0,TRUE,150),
 ('GGT','lab.test.ggt','LIVER','U/L',0,TRUE,160),
 ('BILIRUBIN_TOTAL','lab.test.bilirubinTotal','LIVER','umol/L',1,TRUE,170),
 ('CREATININE','lab.test.creatinine','KIDNEY','umol/L',1,TRUE,180),
 ('EGFR','lab.test.egfr','KIDNEY','mL/min/1.73m2',0,TRUE,190),
 ('UREA','lab.test.urea','KIDNEY','mmol/L',2,TRUE,200);

INSERT INTO lab_test_unit_definitions
    (test_definition_id, unit_code, conversion_type, multiplier, sort_order)
SELECT id, canonical_unit, 'IDENTITY', 1, 0 FROM lab_test_definitions;

INSERT INTO lab_test_unit_definitions
    (test_definition_id, unit_code, conversion_type, multiplier, sort_order)
SELECT id,'mg/dL','MULTIPLY',10,1 FROM lab_test_definitions WHERE code='CRP'
UNION ALL SELECT id,'g/dL','MULTIPLY',10,1 FROM lab_test_definitions WHERE code='HEMOGLOBIN'
UNION ALL SELECT id,'ng/mL','IDENTITY',1,1 FROM lab_test_definitions WHERE code='FERRITIN'
UNION ALL SELECT id,'ug/L','IDENTITY',1,1 FROM lab_test_definitions WHERE code='VITAMIN_D_25_OH'
UNION ALL SELECT id,'ng/L','IDENTITY',1,1 FROM lab_test_definitions WHERE code='VITAMIN_B12'
UNION ALL SELECT id,'g/dL','MULTIPLY',10,1 FROM lab_test_definitions WHERE code='ALBUMIN';
```

- [ ] **Step 4: Implement entities and repository contracts**

Use `@Version` on `LabResultSet.version`, cascade/orphan removal for results, and these mutations:

```java
public void replaceResults(List<LabResult> replacements, Instant now) {
    results.clear();
    replacements.forEach(result -> {
        if (result.getResultSet() != this) {
            throw new IllegalArgumentException("lab result must belong to this result set");
        }
        results.add(result);
    });
    updatedAt = now;
}

public void markRemoved(User actor, String reason, Instant now) {
    if (removedAt != null) throw new IllegalStateException("lab result set is already removed");
    removedAt = now;
    removedByUser = Objects.requireNonNull(actor);
    removalReason = trimToNull(reason);
    updatedAt = now;
}
```

Repository signatures:

```java
@EntityGraph(attributePaths = "units")
List<LabTestDefinition> findByActiveTrueOrderBySortOrderAscCodeAsc();

@EntityGraph(attributePaths = "units")
Optional<LabTestDefinition> findByCodeAndActiveTrue(String code);

@EntityGraph(attributePaths = {"patientProfile", "createdByUser", "results", "results.testDefinition"})
@Query("select distinct s from LabResultSet s where s.id=:id and s.removedAt is null")
Optional<LabResultSet> findActiveById(@Param("id") Long id);

@EntityGraph(attributePaths = {"createdByUser", "results", "results.testDefinition"})
@Query("""
 select distinct s from LabResultSet s
 where s.patientProfile.id=:patientId and s.removedAt is null
   and s.collectionDate between :from and :to
 order by s.collectionDate desc, s.id desc
 """)
List<LabResultSet> findActiveByPatientAndCollectionDateBetween(
        @Param("patientId") Long patientId, @Param("from") LocalDate from, @Param("to") LocalDate to);

@EntityGraph(attributePaths = {"resultSet", "resultSet.createdByUser", "testDefinition"})
@Query("""
 select r from LabResult r
 where r.resultSet.patientProfile.id=:patientId and r.resultSet.removedAt is null
   and r.testDefinition.code=:testCode and r.resultSet.collectionDate between :from and :to
 order by r.resultSet.collectionDate asc, r.id asc
 """)
List<LabResult> findTrend(@Param("patientId") Long patientId,
                          @Param("testCode") String testCode,
                          @Param("from") LocalDate from,
                          @Param("to") LocalDate to);
```

- [ ] **Step 5: Run repository tests**

Run: `./gradlew test --tests 'com.metabion.repository.LabRepositoryTest'`

Expected: PASS; Flyway reaches V19 and Hibernate validates mappings.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V19__laboratory_biomarker_tracking.sql \
  src/main/java/com/metabion/domain/Lab*.java \
  src/main/java/com/metabion/repository/Lab*.java \
  src/test/java/com/metabion/repository/LabRepositoryTest.java
git commit -m "Add laboratory persistence model"
```

---

### Task 3: Add DTOs, Catalog Reads, and Canonical Conversion

**Files:**
- Create DTOs: `LabTestDefinitionResponse`, `LabResultRequest`, `LabResultSetRequest`, `LabResultRemovalRequest`, `LabResultResponse`, `LabResultSetResponse`, `LabTrendResponse`
- Create: `src/main/java/com/metabion/service/LabCatalogService.java`
- Create: `src/main/java/com/metabion/service/LabUnitConversionService.java`
- Create tests: `LabCatalogServiceTest`, `LabUnitConversionServiceTest`

**Interfaces:**
- Produces `listActive()`, `requireActive(String)`, `toCanonical(LabTestDefinition,String,BigDecimal)`, and all shared boundary records.

- [ ] **Step 1: Write failing catalog/conversion tests**

```java
@Test
void convertsCrpToCanonicalUnit() {
    assertThat(conversions.toCanonical(crpDefinition(), "mg/dL", new BigDecimal("1.20")))
            .isEqualByComparingTo("12.00");
}

@Test
void rejectsUnsupportedUnit() {
    assertThatThrownBy(() -> conversions.toCanonical(crpDefinition(), "mmol/L", BigDecimal.ONE))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
}

@Test
void catalogReturnsLocalizedLabelAndOrderedUnits() {
    when(definitions.findByActiveTrueOrderBySortOrderAscCodeAsc()).thenReturn(List.of(crpDefinition()));
    assertThat(catalog.listActive()).singleElement().satisfies(response -> {
        assertThat(response.label()).isEqualTo("C-reactive protein");
        assertThat(response.allowedUnits()).containsExactly("mg/L", "mg/dL");
    });
}
```

- [ ] **Step 2: Run tests and verify missing classes**

```bash
./gradlew test --tests 'com.metabion.service.LabCatalogServiceTest' \
  --tests 'com.metabion.service.LabUnitConversionServiceTest'
```

Expected: FAIL during compilation.

- [ ] **Step 3: Create boundary records**

```java
public record LabResultRequest(
        @NotBlank @Size(max=64) String testCode,
        @NotNull @DecimalMin("0.0") @Digits(integer=12, fraction=6) BigDecimal value,
        @NotBlank @Size(max=40) String unit,
        @DecimalMin("0.0") @Digits(integer=12, fraction=6) BigDecimal referenceLower,
        @DecimalMin("0.0") @Digits(integer=12, fraction=6) BigDecimal referenceUpper) {}

public record LabResultSetRequest(
        Long resultSetId, @PositiveOrZero Long version,
        @NotNull @PastOrPresent LocalDate collectionDate,
        @Size(max=2000) String notes,
        @NotEmpty @Size(max=50) List<@Valid LabResultRequest> results) {}

public record LabResultRemovalRequest(
        @NotNull Long resultSetId, @NotNull @PositiveOrZero Long version,
        @Size(max=500) String reason) {}

public record LabTestDefinitionResponse(
        String code, String label, LabTestCategory category, String canonicalUnit,
        int displayScale, List<String> allowedUnits) {}

public record LabResultResponse(
        Long id, String testCode, String label, BigDecimal reportedValue, String reportedUnit,
        BigDecimal canonicalValue, String canonicalUnit,
        BigDecimal referenceLower, BigDecimal referenceUpper) {}

public record LabResultSetResponse(
        Long id, long version, Long patientProfileId, LocalDate collectionDate, String notes,
        LabResultSource source, LabResultConfirmationStatus confirmationStatus,
        boolean createdByCurrentPatient, Instant createdAt, Instant updatedAt,
        List<LabResultResponse> results) {}

public record LabTrendResponse(
        Long patientProfileId, String testCode, String label, String canonicalUnit,
        int displayScale, LocalDate from, LocalDate to, List<Point> points) {
    public record Point(Long resultSetId, long resultSetVersion, LocalDate collectionDate,
                        BigDecimal canonicalValue, BigDecimal reportedValue, String reportedUnit,
                        BigDecimal referenceLower, BigDecimal referenceUpper, boolean editable) {}
}
```

- [ ] **Step 4: Implement catalog and conversion services**

```java
public LabTestDefinition requireActive(String code) {
    if (code == null || code.isBlank()) throw badRequest("testCode is required");
    return definitions.findByCodeAndActiveTrue(code.trim().toUpperCase(Locale.ROOT))
            .orElseThrow(() -> badRequest("laboratory test is unsupported"));
}

public BigDecimal toCanonical(LabTestDefinition definition, String unit, BigDecimal value) {
    if (definition == null || unit == null || value == null || value.signum() < 0) {
        throw badRequest("invalid laboratory value");
    }
    var configured = definition.getUnits().stream()
            .filter(candidate -> candidate.getUnitCode().equals(unit))
            .findFirst().orElseThrow(() -> badRequest("unsupported laboratory unit"));
    var converted = switch (configured.getConversionType()) {
        case IDENTITY -> value;
        case MULTIPLY -> value.multiply(configured.getMultiplier());
    };
    return converted.setScale(definition.getDisplayScale(), RoundingMode.HALF_UP);
}
```

`listActive()` maps definitions to localized labels through `MessageSource` and `LocaleContextHolder`, and sorts units by `sortOrder`.

- [ ] **Step 5: Run tests**

```bash
./gradlew test --tests 'com.metabion.service.LabCatalogServiceTest' \
  --tests 'com.metabion.service.LabUnitConversionServiceTest'
```

Expected: PASS for ordering, localization, scale, and rejection cases.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/metabion/dto/Lab*.java \
  src/main/java/com/metabion/service/LabCatalogService.java \
  src/main/java/com/metabion/service/LabUnitConversionService.java \
  src/test/java/com/metabion/service/LabCatalogServiceTest.java \
  src/test/java/com/metabion/service/LabUnitConversionServiceTest.java
git commit -m "Add laboratory catalog and conversion"
```

---

### Task 4: Implement Result Mutations, Authorization, and Immutable Auditing

**Files:**
- Create: `src/main/java/com/metabion/service/LabAuditSnapshot.java`
- Create: `src/main/java/com/metabion/service/LabAuditService.java`
- Create: `src/main/java/com/metabion/service/LabResponseAssembler.java`
- Create: `src/main/java/com/metabion/service/LabResultService.java`
- Create tests: `LabResultServiceTest`, `LabResultServicePersistenceTest`

**Interfaces:**
- Consumes: repositories from Task 2, catalog/conversion from Task 3, `AccessControlService`, current users and patient profiles.
- Produces `saveForCurrentPatient`, `getForCurrentPatient`, `listForCurrentPatient`, `updateForCurrentPatient`, and `removeForCurrentPatient` methods accepting `Authentication` plus the shared request identifiers/ranges.
- Produces `saveForClinicalPatient`, `getForClinicalPatient`, `listForClinicalPatient`, `updateForClinicalPatient`, and `removeForClinicalPatient` methods accepting `Authentication`, `patientProfileId`, and the shared request identifiers/ranges.

- [ ] **Step 1: Write failing mutation, authorization, audit, and rollback tests**

```java
@Test
void patientCreateCanonicalizesPanelAndWritesAudit() {
    when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
    when(patientProfiles.findByUserId(patientUser.getId())).thenReturn(Optional.of(patient));
    when(catalog.requireActive("CRP")).thenReturn(crp);
    when(conversions.toCanonical(crp, "mg/dL", new BigDecimal("1.2")))
            .thenReturn(new BigDecimal("12.00"));
    var response = service.saveForCurrentPatient(patientAuth, createRequest());
    assertThat(response.results()).singleElement()
            .extracting(LabResultResponse::canonicalValue).isEqualTo(new BigDecimal("12.00"));
    verify(audit).recordCreate(any(LabResultSet.class), eq(patientUser), any(Instant.class));
}

@Test
void patientCannotCorrectClinicianCreatedSet() {
    when(resultSets.findActiveById(90L)).thenReturn(Optional.of(clinicianCreatedSet));
    assertThatThrownBy(() -> service.updateForCurrentPatient(patientAuth, 90L, updateRequest(90L, 0L)))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
}

@Test
void assignedClinicianCanCorrectAccessiblePatient() {
    when(accessControl.canAccessPatientProfile(clinicalAuth, patient.getId())).thenReturn(true);
    assertThat(service.updateForClinicalPatient(clinicalAuth, patient.getId(), 90L,
            updateRequest(90L, 0L)).id()).isEqualTo(90L);
    verify(audit).recordUpdate(any(), any(), eq(clinician), any());
}

@Test
void staleVersionReturnsConflict() {
    assertThatThrownBy(() -> service.updateForCurrentPatient(patientAuth, 90L, updateRequest(90L, 3L)))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
}
```

In `LabResultServicePersistenceTest`, follow the repository's PostgreSQL service-slice pattern: use `@DataJpaTest`, `@AutoConfigureTestDatabase(replace = NONE)`, `@Testcontainers`, `@DynamicPropertySource`, and `@Import` for the lab services. Wrap `LabResultAuditEventRepository` with `@MockitoSpyBean`; in the rollback case make `save` throw, run the service call outside the test transaction with `@Transactional(propagation = NOT_SUPPORTED)`, and assert that both the result set and result rows are absent afterward. A happy-path test lets the spy delegate to the real repository and verifies the event and mutation commit together.

- [ ] **Step 2: Run tests and verify missing services**

```bash
./gradlew test --tests 'com.metabion.service.LabResultServiceTest' \
  --tests 'com.metabion.service.LabResultServicePersistenceTest'
```

Expected: FAIL during compilation.

- [ ] **Step 3: Implement stable audit snapshots and writes**

```java
public record LabAuditSnapshot(
        Long resultSetId, Long patientProfileId, LocalDate collectionDate, String notes,
        LabResultSource source, LabResultConfirmationStatus confirmationStatus,
        Long createdByUserId, Instant createdAt, Instant updatedAt, long version,
        Instant removedAt, Long removedByUserId, String removalReason,
        List<Result> results) {
    public record Result(String testCode, BigDecimal reportedValue, String reportedUnit,
                         BigDecimal canonicalValue, String canonicalUnit,
                         BigDecimal referenceLower, BigDecimal referenceUpper) {}
}

public void recordCreate(LabResultSet set, User actor, Instant now) {
    events.save(event(set, actor, LabAuditAction.CREATE, null, json(snapshot(set)), now));
}

public void recordUpdate(LabResultSet set, LabAuditSnapshot before, User actor, Instant now) {
    events.save(event(set, actor, LabAuditAction.UPDATE, json(before), json(snapshot(set)), now));
}

public void recordRemoval(LabResultSet set, LabAuditSnapshot before, User actor, Instant now) {
    events.save(event(set, actor, LabAuditAction.REMOVE, json(before), json(snapshot(set)), now));
}
```

`snapshot` copies every field from the set and its results into the record. Serialize only this record with injected `ObjectMapper`; wrap `JsonProcessingException` in `IllegalStateException` so the transaction rolls back.

- [ ] **Step 4: Implement shared create/update/remove helpers**

```java
@Transactional
public LabResultSetResponse saveForCurrentPatient(Authentication authentication, LabResultSetRequest request) {
    var actor = currentUser(authentication);
    requirePatient(actor);
    var patient = patientProfiles.findByUserId(actor.getId())
            .orElseThrow(() -> forbidden("Patient profile not found"));
    return request.resultSetId() == null
            ? create(patient, actor, request)
            : update(patient, actor, request.resultSetId(), request, true);
}

private LabResultSetResponse create(PatientProfile patient, User actor, LabResultSetRequest request) {
    validateRequest(patient, request, false);
    var now = Instant.now(clock);
    var set = new LabResultSet(patient, request.collectionDate(), trimToNull(request.notes()),
            LabResultSource.MANUAL, LabResultConfirmationStatus.CONFIRMED, actor, now);
    set.replaceResults(buildResults(set, request.results()), now);
    var saved = resultSets.saveAndFlush(set);
    audit.recordCreate(saved, actor, now);
    return responses.resultSet(saved, actor);
}

private LabResultSetResponse update(PatientProfile patient, User actor, Long id,
                                    LabResultSetRequest request, boolean enforceCreator) {
    var set = requireActiveSet(id, patient.getId());
    if (enforceCreator && !set.getCreatedByUser().getId().equals(actor.getId())) {
        throw forbidden("Patient can only modify patient-created laboratory results");
    }
    requireVersion(set, request.version());
    validateRequest(patient, request, true);
    var before = audit.snapshot(set);
    var now = Instant.now(clock);
    set.updateDetails(request.collectionDate(), trimToNull(request.notes()), now);
    set.replaceResults(buildResults(set, request.results()), now);
    resultSets.flush();
    audit.recordUpdate(set, before, actor, now);
    return responses.resultSet(set, actor);
}

private List<LabResult> buildResults(LabResultSet set, List<LabResultRequest> requests) {
    var seen = new HashSet<String>();
    return requests.stream().map(request -> {
        var definition = catalog.requireActive(request.testCode());
        if (!seen.add(definition.getCode())) throw badRequest("duplicate laboratory test");
        validateReferenceBounds(request.referenceLower(), request.referenceUpper());
        var canonical = conversions.toCanonical(definition, request.unit(), request.value());
        return new LabResult(set, definition, request.value(), request.unit(), canonical,
                definition.getCanonicalUnit(), request.referenceLower(), request.referenceUpper());
    }).toList();
}
```

Clinical entry points must call `requireClinicalActor`, authorize the supplied patient through `AccessControlService` unless admin, and reuse `create`, `update`, and removal helpers with `enforceCreator=false`. Removal compares the expected version, captures `before`, calls `markRemoved`, flushes to advance the optimistic version, and writes `REMOVE` within the transaction. Flushing before each update/removal audit snapshot ensures the immutable after-state and returned response contain the committed version; an audit failure still rolls back the transaction. Validate collection date against the patient's timezone and `Clock`.

- [ ] **Step 5: Run result-service tests**

```bash
./gradlew test --tests 'com.metabion.service.LabResultServiceTest' \
  --tests 'com.metabion.service.LabResultServicePersistenceTest'
```

Expected: PASS for ownership, assignment, admin, validation, version conflict, snapshot, and rollback cases.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/metabion/service/LabAuditSnapshot.java \
  src/main/java/com/metabion/service/LabAuditService.java \
  src/main/java/com/metabion/service/LabResponseAssembler.java \
  src/main/java/com/metabion/service/LabResultService.java \
  src/test/java/com/metabion/service/LabResultServiceTest.java \
  src/test/java/com/metabion/service/LabResultServicePersistenceTest.java
git commit -m "Add audited laboratory result service"
```

---

### Task 5: Implement Canonical Laboratory Trends

**Files:**
- Create: `src/main/java/com/metabion/service/LabTrendService.java`
- Create: `src/test/java/com/metabion/service/LabTrendServiceTest.java`

**Interfaces:**
- Consumes: catalog, trend repository query, `DateRangeValidator`, users/profiles, and `AccessControlService`.
- Produces `currentPatientTrend(Authentication,String,LocalDate,LocalDate)` and `clinicalTrend(Authentication,Long,String,LocalDate,LocalDate)`.

- [ ] **Step 1: Write failing trend tests**

```java
@Test
void currentPatientTrendReturnsCanonicalPointsInDateOrder() {
    when(results.findTrend(10L, "CRP", FROM, TO)).thenReturn(List.of(laterCrp, earlierCrp));
    var trend = service.currentPatientTrend(patientAuth, "crp", FROM, TO);
    assertThat(trend.testCode()).isEqualTo("CRP");
    assertThat(trend.canonicalUnit()).isEqualTo("mg/L");
    assertThat(trend.points()).extracting(LabTrendResponse.Point::collectionDate)
            .containsExactly(LocalDate.of(2026, 1, 10), LocalDate.of(2026, 6, 10));
}

@Test
void unassignedClinicianCannotReadTrend() {
    when(accessControl.canAccessPatientProfile(clinicalAuth, 10L)).thenReturn(false);
    assertThatThrownBy(() -> service.clinicalTrend(clinicalAuth, 10L, "CRP", FROM, TO))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
}
```

- [ ] **Step 2: Run and verify missing implementation**

Run: `./gradlew test --tests 'com.metabion.service.LabTrendServiceTest'`

Expected: FAIL during compilation.

- [ ] **Step 3: Implement current-patient and clinical trends**

```java
@Transactional(readOnly = true)
public LabTrendResponse currentPatientTrend(Authentication authentication, String testCode,
                                             LocalDate from, LocalDate to) {
    var actor = currentUser(authentication);
    requirePatient(actor);
    var patient = patientProfiles.findByUserId(actor.getId())
            .orElseThrow(() -> forbidden("Patient profile not found"));
    return trend(patient, actor, testCode, from, to, true);
}

private LabTrendResponse trend(PatientProfile patient, User actor, String requestedCode,
                               LocalDate from, LocalDate to, boolean patientView) {
    dateRanges.validate(from, to);
    var definition = catalog.requireActive(requestedCode);
    var points = results.findTrend(patient.getId(), definition.getCode(), from, to).stream()
            .sorted(Comparator.comparing(result -> result.getResultSet().getCollectionDate()))
            .map(result -> new LabTrendResponse.Point(
                    result.getResultSet().getId(), result.getResultSet().getVersion(),
                    result.getResultSet().getCollectionDate(), result.getCanonicalValue(),
                    result.getReportedValue(), result.getReportedUnit(),
                    result.getReferenceLower(), result.getReferenceUpper(),
                    !patientView || result.getResultSet().getCreatedByUser().getId().equals(actor.getId())))
            .toList();
    return new LabTrendResponse(patient.getId(), definition.getCode(), label(definition),
            definition.getCanonicalUnit(), definition.getDisplayScale(), from, to, points);
}
```

Implement `clinicalTrend` with the same clinical-role and assignment checks as `LabResultService`, then call `trend(..., false)`.

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests 'com.metabion.service.LabTrendServiceTest'`

Expected: PASS for ordering, 370-day validation, editability, patient isolation, assignment, and admin access.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/service/LabTrendService.java \
  src/test/java/com/metabion/service/LabTrendServiceTest.java
git commit -m "Add laboratory trend service"
```

---

### Task 6: Add Patient and Clinical REST APIs with Conflict Mapping

**Files:**
- Create: `controller/api/LabCatalogController.java`, `LabResultController.java`, `ClinicalLabResultController.java`
- Create tests: `LabResultControllerTest`, `ClinicalLabResultControllerTest`
- Modify: `GlobalExceptionHandler.java`, `GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes shared DTOs/services.
- Produces the exact patient and clinical REST routes from the approved design.

- [ ] **Step 1: Write failing MockMvc tests**

```java
@Test
void patientCreateRequiresCsrfAndDelegates() throws Exception {
    mvc.perform(post("/api/lab-result-sets")
                    .with(user("patient@example.com").roles("PATIENT")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(validCreateJson()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(90));
    verify(results).saveForCurrentPatient(any(Authentication.class), any(LabResultSetRequest.class));
}

@Test
void invalidBoundaryReturnsValidationFailure() throws Exception {
    mvc.perform(post("/api/lab-result-sets")
                    .with(user("patient@example.com").roles("PATIENT")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(negativeReferenceJson()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("validation_failed"));
}

@Test
void optimisticConflictReturnsStable409() throws Exception {
    mvc.perform(post("/throw/optimistic-conflict"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("conflict"));
}
```

- [ ] **Step 2: Run tests and verify missing routes**

```bash
./gradlew test --tests 'com.metabion.controller.api.LabResultControllerTest' \
  --tests 'com.metabion.controller.api.ClinicalLabResultControllerTest' \
  --tests 'com.metabion.controller.api.GlobalExceptionHandlerTest'
```

Expected: FAIL because controllers and conflict mapping do not exist.

- [ ] **Step 3: Implement thin patient routes**

```java
@PostMapping("/api/lab-result-sets")
public LabResultSetResponse create(@Valid @RequestBody LabResultSetRequest request,
                                   Authentication authentication) {
    return results.saveForCurrentPatient(authentication, request);
}

@GetMapping("/api/lab-result-sets/{id}")
public LabResultSetResponse get(@PathVariable Long id, Authentication authentication) {
    return results.getForCurrentPatient(authentication, id);
}

@GetMapping("/api/lab-result-sets")
public List<LabResultSetResponse> list(@RequestParam LocalDate from, @RequestParam LocalDate to,
                                       Authentication authentication) {
    return results.listForCurrentPatient(authentication, from, to);
}

@PutMapping("/api/lab-result-sets/{id}")
public LabResultSetResponse update(@PathVariable Long id, @Valid @RequestBody LabResultSetRequest request,
                                   Authentication authentication) {
    return results.updateForCurrentPatient(authentication, id, requireMatchingId(id, request));
}

@PostMapping("/api/lab-result-sets/{id}/removal")
public Map<String,String> remove(@PathVariable Long id, @Valid @RequestBody LabResultRemovalRequest request,
                                 Authentication authentication) {
    results.removeForCurrentPatient(authentication, requireMatchingId(id, request));
    return Map.of("status", "removed");
}

@GetMapping("/api/lab-trends/{testCode}")
public LabTrendResponse trend(@PathVariable String testCode, @RequestParam LocalDate from,
                              @RequestParam LocalDate to, Authentication authentication) {
    return trends.currentPatientTrend(authentication, testCode, from, to);
}
```

`LabCatalogController` exposes `GET /api/lab-tests`. Add these clinical routes, each delegating only to its clinical service method after service-layer access authorization:

```text
POST /api/clinical/patients/{patientProfileId}/labs/result-sets
GET /api/clinical/patients/{patientProfileId}/labs/result-sets/{id}
GET /api/clinical/patients/{patientProfileId}/labs/result-sets?from=&to=
PUT /api/clinical/patients/{patientProfileId}/labs/result-sets/{id}
POST /api/clinical/patients/{patientProfileId}/labs/result-sets/{id}/removal
GET /api/clinical/patients/{patientProfileId}/labs/trends/{testCode}?from=&to=
```

- [ ] **Step 4: Map optimistic conflicts**

```java
@ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class})
public ResponseEntity<Map<String,String>> optimisticConflict(Exception exception) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "conflict"));
}
```

Also map `ResponseStatusException` status 409 to `conflict`.

- [ ] **Step 5: Run tests**

```bash
./gradlew test --tests 'com.metabion.controller.api.LabResultControllerTest' \
  --tests 'com.metabion.controller.api.ClinicalLabResultControllerTest' \
  --tests 'com.metabion.controller.api.GlobalExceptionHandlerTest'
```

Expected: PASS for routes, validation, CSRF, patient ID forwarding, and conflicts.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/metabion/controller/api/LabCatalogController.java \
  src/main/java/com/metabion/controller/api/LabResultController.java \
  src/main/java/com/metabion/controller/api/ClinicalLabResultController.java \
  src/main/java/com/metabion/controller/api/GlobalExceptionHandler.java \
  src/test/java/com/metabion/controller/api/LabResultControllerTest.java \
  src/test/java/com/metabion/controller/api/ClinicalLabResultControllerTest.java \
  src/test/java/com/metabion/controller/api/GlobalExceptionHandlerTest.java
git commit -m "Add laboratory REST APIs"
```

---

### Task 7: Build the Focused Chart Model and SVG Renderer

**Files:**
- Create: `src/main/java/com/metabion/controller/web/LabTrendChartModel.java`
- Create: `src/main/java/com/metabion/controller/web/LabTrendChartModelBuilder.java`
- Create: `src/main/java/com/metabion/controller/web/LabTrendSvgRenderer.java`
- Create tests: `LabTrendChartModelBuilderTest`, `LabTrendSvgRendererTest`

**Interfaces:**
- Consumes `LabTrendResponse`.
- Produces `LabTrendChartModel build(LabTrendResponse)` and `String render(LabTrendResponse)`.

- [ ] **Step 1: Write failing chart tests**

```java
@Test
void identicalValuesProduceNonZeroAxis() {
    var model = builder.build(trend(new BigDecimal("5.00"), new BigDecimal("5.00")));
    assertThat(model.axis().max()).isGreaterThan(model.axis().min());
}

@Test
void onePointRemainsVisible() {
    var model = builder.build(trend(new BigDecimal("5.00")));
    assertThat(model.points()).singleElement().satisfies(point -> {
        assertThat(point.x()).isBetween(model.geometry().left(), model.geometry().right());
        assertThat(point.y()).isBetween(model.geometry().top(), model.geometry().bottom());
    });
}

@Test
void rendererIncludesAccessibleExactValue() {
    var svg = renderer.render(trend(new BigDecimal("5.25")));
    assertThat(svg).contains("role=\"img\"")
            .contains("C-reactive protein").contains("5.25 mg/L").contains("2026-06-10");
}
```

- [ ] **Step 2: Run and verify missing classes**

```bash
./gradlew test --tests 'com.metabion.controller.web.LabTrendChartModelBuilderTest' \
  --tests 'com.metabion.controller.web.LabTrendSvgRendererTest'
```

Expected: FAIL during compilation.

- [ ] **Step 3: Implement bounded geometry and axis calculation**

```java
record LabTrendChartModel(Geometry geometry, Axis axis, List<DateTick> dateTicks,
                          List<Point> points, String label, String unit, boolean empty) {
    record Geometry(int width, int height, int left, int right, int top, int bottom) {}
    record Axis(BigDecimal min, BigDecimal max, List<BigDecimal> ticks) {}
    record DateTick(int x, LocalDate date) {}
    record Point(int x, int y, LocalDate date, BigDecimal value) {}
}

private LabTrendChartModel.Axis axis(List<BigDecimal> values, int scale) {
    if (values.isEmpty()) return new LabTrendChartModel.Axis(
            BigDecimal.ZERO, BigDecimal.ONE,
            List.of(BigDecimal.ZERO, new BigDecimal("0.5"), BigDecimal.ONE));
    var observedMin = values.stream().min(BigDecimal::compareTo).orElseThrow();
    var observedMax = values.stream().max(BigDecimal::compareTo).orElseThrow();
    var quantum = BigDecimal.ONE.movePointLeft(scale).max(new BigDecimal("0.01"));
    var padding = observedMax.subtract(observedMin).multiply(new BigDecimal("0.10")).max(quantum);
    var min = observedMin.subtract(padding).max(BigDecimal.ZERO);
    var max = observedMax.add(padding);
    if (max.compareTo(min) <= 0) max = min.add(quantum.multiply(BigDecimal.TEN));
    var middle = min.add(max).divide(new BigDecimal("2"), scale + 2, RoundingMode.HALF_UP);
    return new LabTrendChartModel.Axis(min, max, List.of(min, middle, max));
}
```

Map the entire requested date range to x coordinates and canonical values to y coordinates. Render every observation as a circle; render a polyline only for two or more observations.

- [ ] **Step 4: Implement localized, escaped SVG**

```java
public String render(LabTrendResponse trend) {
    if (trend == null) return emptySvg(message("lab.chart.noData"));
    var model = builder.build(trend);
    var out = new StringBuilder();
    out.append("<svg class=\"lab-trend-chart\" role=\"img\" viewBox=\"0 0 640 220\">");
    out.append("<title>").append(escape(message("lab.chart.title", model.label()))).append("</title>");
    out.append("<desc>").append(escape(description(model))).append("</desc>");
    if (model.empty()) out.append(emptyText(model));
    else out.append(grid(model)).append(line(model)).append(points(model));
    return out.append("</svg>").toString();
}
```

Use `HtmlUtils.htmlEscape` on every label, unit, tooltip, and attribute value. Add visible axis ticks, focusable point labels, localized empty state, and theme class names matching current charts.

- [ ] **Step 5: Run tests**

```bash
./gradlew test --tests 'com.metabion.controller.web.LabTrendChartModelBuilderTest' \
  --tests 'com.metabion.controller.web.LabTrendSvgRendererTest'
```

Expected: PASS for empty, single, identical, sparse, localized, and escaped data.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/metabion/controller/web/LabTrendChartModel.java \
  src/main/java/com/metabion/controller/web/LabTrendChartModelBuilder.java \
  src/main/java/com/metabion/controller/web/LabTrendSvgRenderer.java \
  src/test/java/com/metabion/controller/web/LabTrendChartModelBuilderTest.java \
  src/test/java/com/metabion/controller/web/LabTrendSvgRendererTest.java
git commit -m "Add laboratory trend chart"
```

---

### Task 8: Add Patient and Clinical Thymeleaf Workflows

**Files:**
- Create: `controller/web/WebLabController.java`, `WebClinicalLabController.java`
- Create: `templates/labs.html`, `lab-result-form.html`, `clinical-labs.html`
- Create: `static/js/lab-result-form.js`
- Create tests: `WebLabControllerTest`, `WebClinicalLabControllerTest`
- Modify: `AppMenuCatalog`, `AppMenuCatalogTest`, `app.css`, both message bundles, `ThymeleafAvailabilityTest`, `WebExceptionHandler`

**Interfaces:**
- Consumes shared lab services, chart renderer, and `ClinicalPatientDirectoryService`.
- Produces patient routes under `/app/labs` and clinical routes under `/app/clinical/labs`.

- [ ] **Step 1: Write failing MVC/menu/template tests**

```java
@Test
void patientLabsDefaultsToTwelveMonthsAndRecentTest() throws Exception {
    when(clock.instant()).thenReturn(Instant.parse("2026-07-16T10:00:00Z"));
    when(results.listForCurrentPatient(any(), any(), any())).thenReturn(List.of(recentCrpSet()));
    when(trends.currentPatientTrend(any(), eq("CRP"), any(), any())).thenReturn(crpTrend());
    mvc.perform(get("/app/labs").with(user("patient@example.com").roles("PATIENT")))
            .andExpect(status().isOk()).andExpect(view().name("labs"))
            .andExpect(model().attribute("selectedTestCode", "CRP"))
            .andExpect(model().attribute("activePath", "/app/labs"))
            .andExpect(content().string(containsString("C-reactive protein")));
}

@Test
void clinicalLabsUsesSharedDirectory() throws Exception {
    when(directory.listAccessible(any()))
            .thenReturn(List.of(new PatientOptionResponse(10L, "p@example.com")));
    mvc.perform(get("/app/clinical/labs")
                    .with(user("doctor@example.com").roles("PHYSICIAN")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("p@example.com")));
}

@Test
void patientMenuActivatesLaboratoryRoute() {
    assertThat(catalog.sidebarItems(patientAuth))
            .filteredOn(item -> "Laboratory results".equals(item.label()))
            .singleElement().satisfies(item -> {
                assertThat(item.planned()).isFalse();
                assertThat(item.route()).isEqualTo("/app/labs");
            });
}
```

- [ ] **Step 2: Run and verify missing routes/templates**

```bash
./gradlew test --tests 'com.metabion.controller.web.WebLabControllerTest' \
  --tests 'com.metabion.controller.web.WebClinicalLabControllerTest' \
  --tests 'com.metabion.controller.web.AppMenuCatalogTest' \
  --tests 'com.metabion.controller.web.ThymeleafAvailabilityTest'
```

Expected: FAIL because controllers/templates/routes do not exist.

- [ ] **Step 3: Implement patient and clinical controllers**

```java
@GetMapping("/app/labs")
public String list(@RequestParam(required=false) String testCode,
                   @RequestParam(required=false) LocalDate from,
                   @RequestParam(required=false) LocalDate to,
                   Model model, Authentication authentication) {
    var range = range(from, to);
    var catalogRows = catalog.listActive();
    var sets = results.listForCurrentPatient(authentication, range.from(), range.to());
    var selected = selectTestCode(testCode, sets, catalogRows);
    var trend = selected == null ? null
            : trends.currentPatientTrend(authentication, selected, range.from(), range.to());
    model.addAttribute("catalog", catalogRows);
    model.addAttribute("resultSets", sets);
    model.addAttribute("selectedTestCode", selected);
    model.addAttribute("from", range.from());
    model.addAttribute("to", range.to());
    model.addAttribute("trend", trend);
    model.addAttribute("trendSvg", renderer.render(trend));
    addShell(model, authentication, "/app/labs");
    return "labs";
}
```

Add patient create/edit GETs, `@Valid @ModelAttribute` POST save, and POST removal. Return `lab-result-form` on `BindingResult` errors; redirect to `/app/labs` on success. The clinical controller always carries `patientProfileId`, loads options through the shared directory, and calls only clinical service methods. The default range is `to.minusMonths(12).plusDays(1)` through `to`, remaining within 370 days.

Use these exact form routes:

```text
GET /app/labs/new
GET /app/labs/{id}/edit
POST /app/labs/save
POST /app/labs/{id}/remove
GET /app/clinical/labs/new?patientProfileId={patientProfileId}
GET /app/clinical/labs/{id}/edit?patientProfileId={patientProfileId}
POST /app/clinical/labs/save
POST /app/clinical/labs/{id}/remove
```

Clinical POST forms include `patientProfileId` as a validated hidden field; the controller passes it to the clinical service method, which performs the authoritative access check.

- [ ] **Step 4: Implement the form and safe unit-selection enhancement**

Use indexed names `results[0].testCode`, `results[0].value`, `results[0].unit`, `results[0].referenceLower`, and `results[0].referenceUpper`. Include result-set ID/version, clinical patient ID when present, and CSRF.

```javascript
document.querySelectorAll("[data-lab-result-row]").forEach(row => {
    const test = row.querySelector("[data-lab-test]");
    const unit = row.querySelector("[data-lab-unit]");
    const refresh = () => {
        const definition = window.labCatalog.find(candidate => candidate.code === test.value);
        const previous = unit.value;
        unit.replaceChildren(...definition.allowedUnits.map(value => new Option(value, value)));
        if (definition.allowedUnits.includes(previous)) unit.value = previous;
    };
    test.addEventListener("change", refresh);
    refresh();
});
```

Render `window.labCatalog` with Thymeleaf JavaScript inlining so JSON is escaped. Server validation remains authoritative.

- [ ] **Step 5: Activate menus and add aligned localization**

Patient route: `/app/labs`, not planned. Clinical/admin route: `/app/clinical/labs`, placed near patient trends.

```properties
menu.labTrends=Laboratory results
menu.labTrends.description=Record laboratory results and review biomarker trends
lab.page.title=Laboratory results
lab.clinical.page.title=Patient laboratory results
lab.form.title=Laboratory result set
lab.form.collectionDate=Collection date
lab.form.notes=Notes
lab.form.addResult=Add result
lab.form.save=Save laboratory results
lab.form.remove=Remove result set
lab.chart.title={0} trend
lab.chart.description={0} laboratory values from {1} to {2}, displayed in {3}
lab.chart.noData=No values are available for this biomarker and date range.
lab.empty=No laboratory results are available for this date range.
lab.error.conflict=These laboratory results were changed elsewhere. Reload before trying again.
```

Add all 20 `lab.test.*` labels, category labels, form validation text, source/status text, table headings, buttons, and equivalent Czech translations with identical keys.

- [ ] **Step 6: Add responsive styling and web conflict handling**

Use existing CSS variables and panel/form/table classes. Add lab-specific selectors for a responsive SVG wrapper and repeated result-row grid. Extend `WebExceptionHandler.responseStatus` so a service 409 renders `result` with status 409 and `lab.error.conflict`; use `HttpServletRequest` to choose `/app/labs` or `/app/clinical/labs` from the current route. Add a separate handler for `ObjectOptimisticLockingFailureException` and `OptimisticLockException` with the same status and safe route selection. Cover both patient and clinical conflict responses in the controller tests.

- [ ] **Step 7: Run MVC/template tests**

```bash
./gradlew test --tests 'com.metabion.controller.web.WebLabControllerTest' \
  --tests 'com.metabion.controller.web.WebClinicalLabControllerTest' \
  --tests 'com.metabion.controller.web.AppMenuCatalogTest' \
  --tests 'com.metabion.controller.web.ThymeleafAvailabilityTest'
```

Expected: PASS for list/form/remove, patient selection, menu routes, localization, CSRF, defaults, chart, and empty states.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/metabion/controller/web/WebLabController.java \
  src/main/java/com/metabion/controller/web/WebClinicalLabController.java \
  src/main/java/com/metabion/controller/web/AppMenuCatalog.java \
  src/main/java/com/metabion/controller/web/WebExceptionHandler.java \
  src/main/resources/templates/labs.html \
  src/main/resources/templates/lab-result-form.html \
  src/main/resources/templates/clinical-labs.html \
  src/main/resources/static/js/lab-result-form.js \
  src/main/resources/static/css/app.css \
  src/main/resources/messages.properties src/main/resources/messages_cs.properties \
  src/test/java/com/metabion/controller/web/WebLabControllerTest.java \
  src/test/java/com/metabion/controller/web/WebClinicalLabControllerTest.java \
  src/test/java/com/metabion/controller/web/AppMenuCatalogTest.java \
  src/test/java/com/metabion/controller/web/ThymeleafAvailabilityTest.java
git commit -m "Add laboratory web workflows"
```

### Task 9: Expose Patient MCP Laboratory Tools and OAuth Scopes

**Files:**

- Modify: `src/main/java/com/metabion/domain/PatientAccessTokenScope.java`
- Modify: `src/main/java/com/metabion/service/PatientAppFacade.java`
- Modify: `src/main/java/com/metabion/mcp/PatientMcpTools.java`
- Create: `src/test/java/com/metabion/domain/PatientAccessTokenScopeTest.java`
- Modify: `src/test/java/com/metabion/service/PatientAppFacadeTest.java`
- Modify: `src/test/java/com/metabion/mcp/PatientMcpToolsTest.java`
- Modify: `src/test/java/com/metabion/controller/api/OAuthMetadataControllerTest.java`
- Modify: `src/test/java/com/metabion/service/oauth/OAuthClientRegistrationServiceTest.java`
- Modify: `src/test/java/com/metabion/service/oauth/OAuthAuthorizationServiceTest.java`
- Modify: `src/test/java/com/metabion/service/oauth/OAuthRefreshTokenServiceTest.java`

- [ ] **Step 1: Write failing scope and MCP tests**

Add scope parsing and serialization coverage:

```java
@Test
void laboratoryScopesRoundTripThroughProtocolValues() {
    assertThat(PatientAccessTokenScope.fromAuthority("patient:lab:read"))
            .isEqualTo(PatientAccessTokenScope.PATIENT_LAB_READ);
    assertThat(PatientAccessTokenScope.fromAuthority("patient:lab:write"))
            .isEqualTo(PatientAccessTokenScope.PATIENT_LAB_WRITE);
    assertThat(PatientAccessTokenScope.PATIENT_LAB_READ.authority())
            .isEqualTo("patient:lab:read");
}
```

Add MCP delegation and authorization coverage:

```java
@Test
void labTrendRequiresReadScopeAndDelegatesThroughFacade() {
    authenticate(PatientAccessTokenScope.PATIENT_LAB_READ);
    LabTrendResponse expected = trendResponse();
    given(patientApp.labTrend(any(Authentication.class), "CRP", from, to)).willReturn(expected);

    assertThat(tools.metabionGetLabTrend("CRP", from, to)).isEqualTo(expected);
    then(audit).should().recordToolSuccess(any(PatientAccessTokenAuthentication.class),
            eq("metabion_get_lab_trend"));
}

@Test
void saveLabResultSetRejectsTokenWithoutWriteScope() {
    authenticate(PatientAccessTokenScope.PATIENT_LAB_READ);

    assertThatThrownBy(() -> tools.metabionSaveLabResultSet(saveRequest()))
            .isInstanceOf(InsufficientScopeException.class);
    then(patientApp).shouldHaveNoInteractions();
    then(audit).should().recordToolFailure(any(PatientAccessTokenAuthentication.class),
            eq("metabion_save_lab_result_set"), eq("missing_scope"));
}
```

Also cover all six tools, verify that no tool accepts a patient ID, verify failure auditing, and verify removal requires write scope.

- [ ] **Step 2: Extend OAuth tests for the new scopes**

Assert that metadata advertises both lab scopes, dynamic registration accepts them, authorization grants only requested client-allowed scopes, and refresh rotation preserves granted lab scopes without widening them.

- [ ] **Step 3: Run focused tests and confirm failure**

```bash
./gradlew test --tests 'com.metabion.domain.PatientAccessTokenScopeTest' \
  --tests 'com.metabion.service.PatientAppFacadeTest' \
  --tests 'com.metabion.mcp.PatientMcpToolsTest' \
  --tests 'com.metabion.controller.api.OAuthMetadataControllerTest' \
  --tests 'com.metabion.service.oauth.OAuthClientRegistrationServiceTest' \
  --tests 'com.metabion.service.oauth.OAuthAuthorizationServiceTest' \
  --tests 'com.metabion.service.oauth.OAuthRefreshTokenServiceTest'
```

Expected: FAIL because the laboratory scopes and facade/tool methods do not exist.

- [ ] **Step 4: Add the two narrow patient token scopes**

```java
PATIENT_LAB_READ("patient:lab:read"),
PATIENT_LAB_WRITE("patient:lab:write"),
```

Keep the existing enum-driven OAuth metadata, registration validation, authorization, token persistence, and refresh behavior. Do not introduce broad wildcard lab scope handling.

- [ ] **Step 5: Extend `PatientAppFacade` as the shared application boundary**

Inject `LabCatalogService`, `LabResultService`, and `LabTrendService`, then add methods following the facade's existing `Authentication` contract:

```java
public List<LabTestDefinitionResponse> listLabTests() {
    return labCatalogService.listActive();
}

public LabResultSetResponse saveLabResultSet(Authentication auth, LabResultSetRequest request) {
    return labResultService.saveForCurrentPatient(auth, request);
}

public LabResultSetResponse getLabResultSet(Authentication auth, Long resultSetId) {
    return labResultService.getForCurrentPatient(auth, resultSetId);
}

public List<LabResultSetResponse> listLabResultSets(Authentication auth, LocalDate from, LocalDate to) {
    return labResultService.listForCurrentPatient(auth, from, to);
}

public void removeLabResultSet(Authentication auth, LabResultRemovalRequest request) {
    labResultService.removeForCurrentPatient(auth, request);
}

public LabTrendResponse labTrend(Authentication auth, String testCode, LocalDate from, LocalDate to) {
    return labTrendService.currentPatientTrend(auth, testCode, from, to);
}
```

These methods must delegate to the exact same laboratory services used by the web application and REST API. `PatientAppFacade` must not duplicate validation, conversion, authorization, audit, or trend rules.

- [ ] **Step 6: Add the six patient MCP tools**

Expose these exact tool names:

```text
metabion_list_lab_tests
metabion_save_lab_result_set
metabion_get_lab_result_set
metabion_list_lab_result_sets
metabion_remove_lab_result_set
metabion_get_lab_trend
```

Use the existing MCP method pattern and these Java signatures: `metabionListLabTests()`, `metabionSaveLabResultSet(LabResultSetRequest)`, `metabionGetLabResultSet(Long)`, `metabionListLabResultSets(LocalDate, LocalDate)`, `metabionRemoveLabResultSet(LabResultRemovalRequest)`, and `metabionGetLabTrend(String, LocalDate, LocalDate)`. Each method obtains `PatientAccessTokenAuthentication` from the security context, checks only the required scope, passes that authentication to `PatientAppFacade`, and records tool metadata through `PatientAccessAuditService`. The reused patient service methods derive the patient profile from the authenticated token's user. Read scope applies to catalog, get, list, and trend; write scope applies to save and remove. Tool schemas expose the shared DTO validation constraints and never accept arbitrary storage keys, source identifiers, or a patient ID.

After the scope check, run each facade call through lab-specific audited helpers. On success call `recordToolSuccess`; on a delegated `RuntimeException`, call `recordToolFailure(auth, operation, "request_failed")` and rethrow the original exception. The helper must not inspect or record request objects, biomarker codes, dates, values, units, or exception messages. Missing-scope failures remain recorded once by the existing `require` method.

- [ ] **Step 7: Run focused MCP/OAuth tests**

```bash
./gradlew test --tests 'com.metabion.domain.PatientAccessTokenScopeTest' \
  --tests 'com.metabion.service.PatientAppFacadeTest' \
  --tests 'com.metabion.mcp.PatientMcpToolsTest' \
  --tests 'com.metabion.controller.api.OAuthMetadataControllerTest' \
  --tests 'com.metabion.service.oauth.OAuthClientRegistrationServiceTest' \
  --tests 'com.metabion.service.oauth.OAuthAuthorizationServiceTest' \
  --tests 'com.metabion.service.oauth.OAuthRefreshTokenServiceTest'
```

Expected: PASS, including scope rejection, facade delegation, success/failure audit calls, OAuth discovery, registration, authorization, and refresh preservation.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/metabion/domain/PatientAccessTokenScope.java \
  src/main/java/com/metabion/service/PatientAppFacade.java \
  src/main/java/com/metabion/mcp/PatientMcpTools.java \
  src/test/java/com/metabion/domain/PatientAccessTokenScopeTest.java \
  src/test/java/com/metabion/service/PatientAppFacadeTest.java \
  src/test/java/com/metabion/mcp/PatientMcpToolsTest.java \
  src/test/java/com/metabion/controller/api/OAuthMetadataControllerTest.java \
  src/test/java/com/metabion/service/oauth/OAuthClientRegistrationServiceTest.java \
  src/test/java/com/metabion/service/oauth/OAuthAuthorizationServiceTest.java \
  src/test/java/com/metabion/service/oauth/OAuthRefreshTokenServiceTest.java
git commit -m "Expose laboratory MCP tools"
```

### Task 10: Verify the Complete Laboratory Feature

**Files:**

- Verify: all production and test files changed in Tasks 1–9
- Verify: `docs/superpowers/specs/2026-07-16-laboratory-biomarker-tracking-design.md`

- [ ] **Step 1: Run the focused laboratory regression suite**

```bash
./gradlew test \
  --tests 'com.metabion.repository.LabRepositoryTest' \
  --tests 'com.metabion.service.ClinicalPatientDirectoryServiceTest' \
  --tests 'com.metabion.service.LabCatalogServiceTest' \
  --tests 'com.metabion.service.LabUnitConversionServiceTest' \
  --tests 'com.metabion.service.LabResultServiceTest' \
  --tests 'com.metabion.service.LabResultServicePersistenceTest' \
  --tests 'com.metabion.service.LabTrendServiceTest' \
  --tests 'com.metabion.controller.api.LabResultControllerTest' \
  --tests 'com.metabion.controller.api.ClinicalLabResultControllerTest' \
  --tests 'com.metabion.controller.api.GlobalExceptionHandlerTest' \
  --tests 'com.metabion.controller.web.LabTrendChartModelBuilderTest' \
  --tests 'com.metabion.controller.web.LabTrendSvgRendererTest' \
  --tests 'com.metabion.controller.web.WebLabControllerTest' \
  --tests 'com.metabion.controller.web.WebClinicalLabControllerTest' \
  --tests 'com.metabion.controller.web.AppMenuCatalogTest' \
  --tests 'com.metabion.controller.web.ThymeleafAvailabilityTest' \
  --tests 'com.metabion.domain.PatientAccessTokenScopeTest' \
  --tests 'com.metabion.service.PatientAppFacadeTest' \
  --tests 'com.metabion.mcp.PatientMcpToolsTest' \
  --tests 'com.metabion.controller.api.OAuthMetadataControllerTest' \
  --tests 'com.metabion.service.oauth.OAuthClientRegistrationServiceTest' \
  --tests 'com.metabion.service.oauth.OAuthAuthorizationServiceTest' \
  --tests 'com.metabion.service.oauth.OAuthRefreshTokenServiceTest'
```

Expected: PASS with no unexpected skips. Inspect any Testcontainers skip separately rather than treating it as PostgreSQL verification.

- [ ] **Step 2: Run the complete test suite**

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`; Jacoco report finalization completes and existing diet, symptom, education, authentication, OAuth, and MCP behavior remains green.

- [ ] **Step 3: Run the complete build lifecycle**

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL`; compilation, tests, Jacoco, and packaging all complete.

- [ ] **Step 4: Inspect migration, security, and worktree hygiene**

```bash
git diff --check
git status --short
git diff -- src/main/resources/db/migration/V19__laboratory_biomarker_tracking.sql \
  src/main/java/com/metabion/domain/PatientAccessTokenScope.java \
  src/main/java/com/metabion/service/PatientAppFacade.java \
  src/main/java/com/metabion/mcp/PatientMcpTools.java
```

Confirm that V19 is the next migration, contains no destructive schema operation, and preserves reported values alongside canonical values. Confirm MCP tools derive patient identity from the authenticated principal, scopes are narrow, audit rows contain metadata rather than token values, and unrelated user changes remain untouched.

- [ ] **Step 5: Correct any verification findings and rerun affected checks**

If verification requires changes, add a focused regression test first, apply the smallest correction, rerun that test, then rerun Steps 1–4. Stage only the files involved in the correction, inspect `git diff --cached`, and commit them with `git commit -m "Harden laboratory tracking integration"`.

If verification requires no correction, skip this commit.

## Completion Checklist

- [ ] Patients can create, view, update, and remove their own grouped laboratory result sets.
- [ ] Assigned clinical staff and administrators can perform the same operations for authorized patients; other access is denied.
- [ ] A seeded, active database catalog supplies 20 relevant tests and explicit unit conversions.
- [ ] Original value/unit and canonical value/unit are both persisted; reference bounds are display-only metadata.
- [ ] Edit and remove operations create immutable audit history in the same transaction and enforce optimistic locking.
- [ ] Trends return canonical values for one biomarker over a bounded date range and render safely in HTML/SVG views.
- [ ] Future report ingestion fields and shared service boundaries exist without exposing upload or extraction behavior.
- [ ] REST, Thymeleaf, and MCP boundaries reuse the same catalog, result, conversion, authorization, audit, and trend services.
- [ ] `PatientAppFacade` explicitly delegates MCP lab behavior to those same services and contains no parallel business rules.
- [ ] The six lab MCP tools use authenticated patient identity and the exact `patient:lab:read` and `patient:lab:write` scopes.
- [ ] OAuth discovery, registration, authorization, and refresh behavior support and preserve the new scopes.
- [ ] English and Czech messages, menu entries, accessible tables/forms, responsive layouts, chart descriptions, and no-data states are covered.
- [ ] Focused tests, full tests, full build, and final diff checks pass before handoff.
