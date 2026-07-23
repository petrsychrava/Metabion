package com.metabion.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metabion.domain.RoleName;
import com.metabion.dto.assignment.AssignmentManagementApi.AddPatientRequest;
import com.metabion.dto.assignment.AssignmentManagementApi.AssignStaffRequest;
import com.metabion.dto.assignment.AssignmentManagementApi.AssignmentResponse;
import com.metabion.dto.assignment.AssignmentManagementApi.MembershipResponse;
import com.metabion.dto.assignment.AssignmentManagementApi.PatientsPageResponse;
import com.metabion.dto.assignment.AssignmentManagementForms.CohortForm;
import com.metabion.dto.assignment.AssignmentManagementView.CohortItem;
import com.metabion.service.AssignmentManagementService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:assignment_management_api_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class AssignmentManagementApiControllerTest {

    @Autowired
    WebApplicationContext context;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @MockitoBean
    AssignmentManagementService assignments;

    private MockMvc mvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(filters)
                .apply(springSecurity())
                .build();
    }

    @Test
    void listCohortsReturnsServiceResult() throws Exception {
        var item = new CohortItem(10L, "Pilot", "Notes", false, "admin@example.com", Instant.EPOCH);
        when(assignments.listCohorts(any())).thenReturn(List.of(item));

        mvc.perform(get("/api/cohorts")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(10))
                .andExpect(jsonPath("$[0].name").value("Pilot"));
    }

    @Test
    void createCohortReturnsCreated() throws Exception {
        var item = new CohortItem(10L, "Pilot", null, false, "admin@example.com", Instant.EPOCH);
        when(assignments.createCohort(any(), any())).thenReturn(item);

        mvc.perform(post("/api/cohorts")
                        .with(user("coordinator@example.com").roles(RoleName.COORDINATOR.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CohortForm("Pilot", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10));

        verify(assignments).createCohort(any(), eq(new CohortForm("Pilot", null)));
    }

    @Test
    void blankCohortNameReturnsValidationFailure() throws Exception {
        mvc.perform(post("/api/cohorts")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CohortForm("", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.fields.name").exists());

        verifyNoInteractions(assignments);
    }

    @Test
    void malformedJsonReturnsRequestFailed() throws Exception {
        mvc.perform(post("/api/cohorts")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("request_failed"));
    }

    @Test
    void nonNumericCohortIdReturnsRequestFailed() throws Exception {
        mvc.perform(get("/api/cohorts/abc")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("request_failed"));
    }

    @Test
    void updateCohortReturnsUpdatedCohort() throws Exception {
        var item = new CohortItem(10L, "Renamed", "Notes", false, "admin@example.com", Instant.EPOCH);
        when(assignments.updateCohort(any(), eq(10L), any())).thenReturn(item);

        mvc.perform(put("/api/cohorts/10")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CohortForm("Renamed", "Notes"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"));
    }

    @Test
    void archiveConflictMapsToConflictError() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT))
                .when(assignments).archiveCohort(any(), eq(10L));

        mvc.perform(post("/api/cohorts/10/archive")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name()))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"));
    }

    @Test
    void outOfScopeCohortMapsToNotFound() throws Exception {
        when(assignments.cohortDetail(any(), eq(10L)))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        mvc.perform(get("/api/cohorts/10")
                        .with(user("coordinator@example.com").roles(RoleName.COORDINATOR.name())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void membershipLifecycleReturnsCreatedIdThenNoContent() throws Exception {
        when(assignments.addPatientToCohort(any(), eq(10L), eq(20L)))
                .thenReturn(new MembershipResponse(55L));

        mvc.perform(post("/api/cohorts/10/memberships")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AddPatientRequest(20L))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.membershipId").value(55));

        mvc.perform(delete("/api/cohorts/10/memberships/55")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name()))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        verify(assignments).endMembership(any(), eq(10L), eq(55L));
    }

    @Test
    void nullPatientIdReturnsValidationFailure() throws Exception {
        mvc.perform(post("/api/cohorts/10/memberships")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AddPatientRequest(null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.fields.patientProfileId").exists());

        verifyNoInteractions(assignments);
    }

    @Test
    void staffAssignmentLifecycleDelegates() throws Exception {
        when(assignments.assignCohortStaff(any(), eq(10L), eq(30L)))
                .thenReturn(new AssignmentResponse(60L));

        mvc.perform(post("/api/cohorts/10/staff-assignments")
                        .with(user("coordinator@example.com").roles(RoleName.COORDINATOR.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignStaffRequest(30L))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignmentId").value(60));

        mvc.perform(delete("/api/cohorts/10/staff-assignments/60")
                        .with(user("coordinator@example.com").roles(RoleName.COORDINATOR.name()))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        verify(assignments).endCohortStaffAssignment(any(), eq(10L), eq(60L));
    }

    @Test
    void candidateEndpointsDelegateWithCohortScope() throws Exception {
        when(assignments.patientCandidates(any(), eq(10L))).thenReturn(List.of());
        when(assignments.staffCandidates(any(), eq(10L))).thenReturn(List.of());

        mvc.perform(get("/api/cohorts/10/patient-candidates")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/cohorts/10/staff-candidates")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name())))
                .andExpect(status().isOk());

        verify(assignments).patientCandidates(any(), eq(10L));
        verify(assignments).staffCandidates(any(), eq(10L));
    }

    @Test
    void patientsListPassesPaginationToService() throws Exception {
        var page = new PatientsPageResponse(List.of(), List.of(), 2, 25, 0, 0);
        when(assignments.scopedPatients(any(), eq(2), eq(25))).thenReturn(page);

        mvc.perform(get("/api/patients?page=2&size=25")
                        .with(user("coordinator@example.com").roles(RoleName.COORDINATOR.name())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageIndex").value(2))
                .andExpect(jsonPath("$.size").value(25));

        verify(assignments).scopedPatients(any(), eq(2), eq(25));
    }

    @Test
    void patientsListUsesDefaultPagination() throws Exception {
        var page = new PatientsPageResponse(List.of(), List.of(), 0, 50, 0, 0);
        when(assignments.scopedPatients(any(), eq(0), eq(50))).thenReturn(page);

        mvc.perform(get("/api/patients")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name())))
                .andExpect(status().isOk());

        verify(assignments).scopedPatients(any(), eq(0), eq(50));
    }

    @Test
    void directExpertAssignmentLifecycleDelegates() throws Exception {
        when(assignments.assignDirectExpert(any(), eq(20L), eq(30L)))
                .thenReturn(new AssignmentResponse(61L));

        mvc.perform(post("/api/patients/20/expert-assignments")
                        .with(user("coordinator@example.com").roles(RoleName.COORDINATOR.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignStaffRequest(30L))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignmentId").value(61));

        mvc.perform(delete("/api/patients/20/expert-assignments/61")
                        .with(user("coordinator@example.com").roles(RoleName.COORDINATOR.name()))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        verify(assignments).endDirectExpertAssignment(any(), eq(20L), eq(61L));
    }

    @Test
    void mutationWithoutCsrfReturnsJsonForbidden() throws Exception {
        mvc.perform(post("/api/cohorts")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CohortForm("Pilot", null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));

        verifyNoInteractions(assignments);
    }
}
