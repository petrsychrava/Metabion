package com.metabion.service;

import com.metabion.config.TimeConfig;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.LabAuditAction;
import com.metabion.domain.LabResultAuditEvent;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.LabResultRequest;
import com.metabion.dto.LabResultSetRequest;
import com.metabion.dto.LabResultRemovalRequest;
import com.metabion.repository.LabResultAuditEventRepository;
import com.metabion.repository.LabResultRepository;
import com.metabion.repository.LabResultSetRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({LabResultService.class, LabAuditService.class, LabResponseAssembler.class,
        LabCatalogService.class, LabUnitConversionService.class, TimeConfig.class, LabResultServicePersistenceTest.JsonConfiguration.class})
class LabResultServicePersistenceTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired LabResultService service;
    @Autowired UserRepository users;
    @Autowired PatientProfileRepository patientProfiles;
    @Autowired LabResultSetRepository resultSets;
    @Autowired LabResultRepository results;
    @MockitoSpyBean LabResultAuditEventRepository auditEvents;
    @MockitoBean AccessControlService accessControl;

    private PatientProfile patient;
    private String email;

    @BeforeEach
    void setUp() {
        email = "service-lab-patient-" + System.nanoTime() + "@example.com";
        var user = new User(email, "hash");
        user.setEnabled(true);
        user.addRole(RoleName.PATIENT);
        patient = patientProfiles.saveAndFlush(new PatientProfile(users.saveAndFlush(user)));
    }

    @Test
    void resultAndAuditEventCommitTogether() {
        var response = service.saveForCurrentPatient(authentication(), request());

        assertThat(response.id()).isNotNull();
        assertThat(resultSets.findById(response.id())).isPresent();
        assertThat(results.count()).isEqualTo(1);
        assertThat(auditEvents.count()).isEqualTo(1);
        verify(auditEvents).save(any());
    }

    @Test
    void createUpdateAndRemovePersistCompleteImmutableSnapshots() {
        var created = service.saveForCurrentPatient(authentication(), request());
        service.updateForCurrentPatient(authentication(), created.id(), new LabResultSetRequest(created.id(), created.version(),
                LocalDate.of(2026, 7, 11), "corrected", List.of(new LabResultRequest("ALT", new BigDecimal("2.5"), "U/L", null, null))));
        var updated = resultSets.findActiveById(created.id()).orElseThrow();
        service.removeForCurrentPatient(authentication(), created.id(), new LabResultRemovalRequest(created.id(), updated.getVersion(), "duplicate"));

        var events = auditEvents.findAll().stream().sorted(Comparator.comparing(LabResultAuditEvent::getId)).toList();
        assertThat(events).extracting(LabResultAuditEvent::getAction)
                .containsExactly(LabAuditAction.CREATE, LabAuditAction.UPDATE, LabAuditAction.REMOVE);
        assertThat(events.get(0).getBeforeSnapshot()).isNull();
        assertThat(events.get(0).getAfterSnapshot())
                .contains("\"collectionDate\":\"2026-07-10\"", "\"reportedValue\":1.200000", "\"reportedUnit\":\"mg/dL\"",
                        "\"canonicalValue\":12.000000", "\"canonicalUnit\":\"mg/L\"", "\"version\":0")
                .containsPattern("\\\"createdAt\\\":\\\"\\d{4}-\\d{2}-\\d{2}T[^\\\"]+Z\\\"");
        assertThat(events.get(1).getBeforeSnapshot()).contains("\"collectionDate\":\"2026-07-10\"", "\"version\":0", "\"canonicalValue\":12.000000");
        assertThat(events.get(1).getAfterSnapshot()).contains("\"collectionDate\":\"2026-07-11\"", "\"testCode\":\"ALT\"", "\"reportedValue\":2.500000", "\"reportedUnit\":\"U/L\"", "\"canonicalValue\":3.000000", "\"canonicalUnit\":\"U/L\"", "\"version\":1")
                .containsPattern("\\\"updatedAt\\\":\\\"\\d{4}-\\d{2}-\\d{2}T[^\\\"]+Z\\\"");
        assertThat(events.get(2).getBeforeSnapshot()).contains("\"version\":1", "\"removedAt\":null");
        assertThat(events.get(2).getAfterSnapshot()).contains("\"version\":2", "\"removalReason\":\"duplicate\"")
                .containsPattern("\\\"removedAt\\\":\\\"\\d{4}-\\d{2}-\\d{2}T[^\\\"]+Z\\\"");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void auditFailureRollsBackResultSetAndRows() {
        doThrow(new IllegalStateException("audit storage unavailable")).when(auditEvents).save(any());

        assertThatThrownBy(() -> service.saveForCurrentPatient(authentication(), request()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(resultSets.count()).isZero();
        assertThat(results.count()).isZero();
    }

    private TestingAuthenticationToken authentication() {
        var authentication = new TestingAuthenticationToken(email, "n/a");
        authentication.setAuthenticated(true);
        return authentication;
    }

    private LabResultSetRequest request() {
        return new LabResultSetRequest(null, null, LocalDate.of(2026, 7, 10), null,
                List.of(new LabResultRequest("CRP", new BigDecimal("1.2"), "mg/dL", null, null)));
    }

    @TestConfiguration
    static class JsonConfiguration {
        @Bean com.fasterxml.jackson.databind.ObjectMapper objectMapper() { return new com.fasterxml.jackson.databind.ObjectMapper(); }
    }
}
