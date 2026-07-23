package com.metabion.integration;

import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.StaffProfile;
import com.metabion.domain.User;
import com.metabion.dto.assignment.AssignmentManagementForms.CohortForm;
import com.metabion.repository.CohortStaffAssignmentRepository;
import com.metabion.repository.PatientCohortMembershipRepository;
import com.metabion.repository.PatientExpertAssignmentRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.StaffProfileRepository;
import com.metabion.service.AssignmentManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssignmentManagementApiIT extends AbstractAuthIT {

    private static final String PASSWORD = "Integration1!";

    @Autowired
    AssignmentManagementService assignmentManagement;
    @Autowired
    PatientCohortMembershipRepository memberships;
    @Autowired
    CohortStaffAssignmentRepository cohortStaffAssignments;
    @Autowired
    PatientExpertAssignmentRepository directAssignments;
    @Autowired
    PatientProfileRepository patientProfiles;
    @Autowired
    StaffProfileRepository staffProfiles;

    @Test
    void coordinatorManagesCohortLifecycleOverHttpWithCsrf() throws Exception {
        enabledStaff("coord-api@example.com", RoleName.COORDINATOR);
        var patientProfile = patient("patient-api@example.com");
        var physician = enabledStaff("phys-api@example.com", RoleName.PHYSICIAN);
        var physicianProfileId = staffProfiles.findByUserId(physician.getId()).orElseThrow().getId();

        var client = newClient();
        assertThat(login(client, "coord-api@example.com", PASSWORD).getStatusCode().value())
                .isEqualTo(200);
        var token = json(client.get("/api/csrf")).get("token").asText();

        var created = client.postWithHeaders("/api/cohorts",
                Map.of("name", "API Pilot", "description", "created over HTTP"),
                Map.of("X-XSRF-TOKEN", token));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        var cohortId = json(created).get("id").asLong();

        var membership = client.postWithHeaders("/api/cohorts/" + cohortId + "/memberships",
                Map.of("patientProfileId", patientProfile.getId()),
                Map.of("X-XSRF-TOKEN", token));
        assertThat(membership.getStatusCode().value()).isEqualTo(201);
        assertThat(memberships.existsActiveMembership(patientProfile.getId(), cohortId)).isTrue();

        var staffAssignment = client.postWithHeaders("/api/cohorts/" + cohortId + "/staff-assignments",
                Map.of("staffProfileId", physicianProfileId),
                Map.of("X-XSRF-TOKEN", token));
        assertThat(staffAssignment.getStatusCode().value()).isEqualTo(201);

        var detail = json(client.get("/api/cohorts/" + cohortId));
        assertThat(detail.get("patients").size()).isEqualTo(1);
        // The creating coordinator is auto-assigned to the cohort, then the physician is added.
        assertThat(detail.get("careTeam").size()).isEqualTo(2);

        var noCsrf = client.postWithHeaders("/api/cohorts/" + cohortId + "/memberships",
                Map.of("patientProfileId", patientProfile.getId()), Map.of());
        assertThat(noCsrf.getStatusCode().value()).isEqualTo(403);
        assertThat(json(noCsrf).get("error").asText()).isEqualTo("forbidden");
    }

    @Test
    void coordinatorAssignsAndEndsDirectExpertOverHttp() throws Exception {
        var coordinatorAuth = serviceAuth("coord-direct@example.com");
        enabledStaff("coord-direct@example.com", RoleName.COORDINATOR);
        var patientProfile = patient("patient-direct@example.com");
        var nutritionist = enabledStaff("nutri-direct@example.com", RoleName.NUTRITION_SPECIALIST);
        var nutritionistProfileId = staffProfiles.findByUserId(nutritionist.getId())
                .orElseThrow().getId();
        var cohortId = assignmentManagement.createCohort(
                coordinatorAuth, new CohortForm("Direct scope", null)).id();
        assignmentManagement.addPatientToCohort(coordinatorAuth, cohortId, patientProfile.getId());

        var client = newClient();
        login(client, "coord-direct@example.com", PASSWORD);
        var token = json(client.get("/api/csrf")).get("token").asText();

        var assigned = client.postWithHeaders(
                "/api/patients/" + patientProfile.getId() + "/expert-assignments",
                Map.of("staffProfileId", nutritionistProfileId),
                Map.of("X-XSRF-TOKEN", token));
        assertThat(assigned.getStatusCode().value()).isEqualTo(201);
        var assignmentId = json(assigned).get("assignmentId").asLong();
        assertThat(directAssignments.existsActiveAssignment(
                patientProfile.getId(), nutritionistProfileId)).isTrue();

        var page = json(client.get("/api/patients"));
        assertThat(page.get("patients").get(0).get("direct").get(0).get("assignmentId").asLong())
                .isEqualTo(assignmentId);

        var ended = client.deleteWithHeaders(
                "/api/patients/" + patientProfile.getId() + "/expert-assignments/" + assignmentId,
                Map.of("X-XSRF-TOKEN", token));
        assertThat(ended.getStatusCode().value()).isEqualTo(204);
        assertThat(directAssignments.existsActiveAssignment(
                patientProfile.getId(), nutritionistProfileId)).isFalse();
    }

    @Test
    void adminEditsAndArchivesCohortOverHttp() throws Exception {
        enabledStaff("admin-api@example.com", RoleName.ADMIN);
        var adminAuth = serviceAuth("admin-api@example.com");
        var patientProfile = patient("patient-archive@example.com");
        var cohortId = assignmentManagement.createCohort(
                adminAuth, new CohortForm("Archive me", null)).id();
        assignmentManagement.addPatientToCohort(adminAuth, cohortId, patientProfile.getId());

        var client = newClient();
        login(client, "admin-api@example.com", PASSWORD);
        var token = json(client.get("/api/csrf")).get("token").asText();

        var edited = client.putWithHeaders("/api/cohorts/" + cohortId,
                Map.of("name", "Renamed cohort", "description", "edited"),
                Map.of("X-XSRF-TOKEN", token));
        assertThat(edited.getStatusCode().value()).isEqualTo(200);
        assertThat(json(edited).get("name").asText()).isEqualTo("Renamed cohort");

        var archived = client.postWithHeaders("/api/cohorts/" + cohortId + "/archive", null,
                Map.of("X-XSRF-TOKEN", token));
        assertThat(archived.getStatusCode().value()).isEqualTo(204);

        var detail = json(client.get("/api/cohorts/" + cohortId));
        assertThat(detail.get("cohort").get("archived").asBoolean()).isTrue();
        assertThat(detail.get("patients").get(0).get("endedAt").isTextual()).isTrue();

        var conflicted = client.postWithHeaders("/api/cohorts/" + cohortId + "/memberships",
                Map.of("patientProfileId", patientProfile.getId()),
                Map.of("X-XSRF-TOKEN", token));
        assertThat(conflicted.getStatusCode().value()).isEqualTo(409);
        assertThat(json(conflicted).get("error").asText()).isEqualTo("conflict");
    }

    @Test
    void patientRoleIsRejectedButCsrfBootstrapStaysOpen() throws Exception {
        createEnabledUser("patient-role@example.com", PASSWORD);

        var client = newClient();
        login(client, "patient-role@example.com", PASSWORD);

        assertThat(client.get("/api/cohorts").getStatusCode().value()).isEqualTo(403);
        assertThat(client.get("/api/patients").getStatusCode().value()).isEqualTo(403);
        assertThat(client.get("/api/csrf").getStatusCode().value()).isEqualTo(200);
    }

    private User enabledStaff(String email, RoleName role) {
        var user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setEnabled(true);
        user.addRole(role);
        var saved = users.saveAndFlush(user);
        if (role.isClinicalStaff()) {
            staffProfiles.saveAndFlush(new StaffProfile(saved));
        }
        return saved;
    }

    private PatientProfile patient(String email) {
        var user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setEnabled(true);
        user.addRole(RoleName.PATIENT);
        return patientProfiles.saveAndFlush(new PatientProfile(users.saveAndFlush(user)));
    }

    private Authentication serviceAuth(String email) {
        return UsernamePasswordAuthenticationToken.authenticated(
                email, "n/a", AuthorityUtils.NO_AUTHORITIES);
    }
}
