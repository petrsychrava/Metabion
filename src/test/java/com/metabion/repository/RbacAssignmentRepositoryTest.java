package com.metabion.repository;

import com.metabion.domain.Cohort;
import com.metabion.domain.CohortStaffAssignment;
import com.metabion.domain.PatientCohortMembership;
import com.metabion.domain.PatientExpertAssignment;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.StaffProfile;
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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class RbacAssignmentRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    UserRepository users;

    @Autowired
    PatientProfileRepository patientProfiles;

    @Autowired
    StaffProfileRepository staffProfiles;

    @Autowired
    CohortRepository cohorts;

    @Autowired
    PatientExpertAssignmentRepository patientExpertAssignments;

    @Autowired
    PatientCohortMembershipRepository patientCohortMemberships;

    @Autowired
    CohortStaffAssignmentRepository cohortStaffAssignments;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void profileRowsAreFoundByUserId() {
        var patientUser = createUser("patient@example.com", RoleName.PATIENT);
        var staffUser = createUser("staff@example.com", RoleName.PHYSICIAN);
        var patientProfile = patientProfiles.saveAndFlush(new PatientProfile(patientUser));
        var staffProfile = staffProfiles.saveAndFlush(new StaffProfile(staffUser));

        assertThat(patientProfiles.findByUserId(patientUser.getId()))
                .contains(patientProfile);
        assertThat(staffProfiles.findByUserId(staffUser.getId()))
                .contains(staffProfile);
    }

    @Test
    void activeDirectAssignmentPredicateHonorsEndedAt() {
        var patient = createPatientProfile("direct-patient@example.com");
        var staff = createStaffProfile("direct-staff@example.com");
        var assignedBy = createUser("direct-admin@example.com", RoleName.ADMIN);
        var assignment = patientExpertAssignments.saveAndFlush(
                new PatientExpertAssignment(patient, staff, assignedBy));

        assertThat(patientExpertAssignments.existsActiveAssignment(patient.getId(), staff.getId()))
                .isTrue();

        assignment.setEndedAt(Instant.now());
        patientExpertAssignments.saveAndFlush(assignment);

        assertThat(patientExpertAssignments.existsActiveAssignment(patient.getId(), staff.getId()))
                .isFalse();
    }

    @Test
    void activeCohortAccessRequiresActiveMembershipAndActiveStaffAssignment() {
        var patient = createPatientProfile("cohort-patient@example.com");
        var staff = createStaffProfile("cohort-staff@example.com");
        var assignedBy = createUser("cohort-admin@example.com", RoleName.ADMIN);
        var cohort = cohorts.saveAndFlush(new Cohort("Spring cohort"));
        var membership = patientCohortMemberships.saveAndFlush(
                new PatientCohortMembership(patient, cohort, assignedBy));
        var staffAssignment = cohortStaffAssignments.saveAndFlush(
                new CohortStaffAssignment(cohort, staff, assignedBy));

        assertThat(cohortStaffAssignments.existsActiveAssignmentForPatient(patient.getId(), staff.getId()))
                .isTrue();

        membership.setEndedAt(Instant.now());
        patientCohortMemberships.saveAndFlush(membership);

        assertThat(cohortStaffAssignments.existsActiveAssignmentForPatient(patient.getId(), staff.getId()))
                .isFalse();

        membership.setEndedAt(null);
        patientCohortMemberships.saveAndFlush(membership);
        staffAssignment.setEndedAt(Instant.now());
        cohortStaffAssignments.saveAndFlush(staffAssignment);

        assertThat(cohortStaffAssignments.existsActiveAssignmentForPatient(patient.getId(), staff.getId()))
                .isFalse();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void duplicateActiveAssignmentsAreRejected() {
        var patient = createPatientProfile("duplicate-patient@example.com");
        var staff = createStaffProfile("duplicate-staff@example.com");
        var assignedBy = createUser("duplicate-admin@example.com", RoleName.ADMIN);
        var cohort = cohorts.saveAndFlush(new Cohort("Duplicate cohort"));

        patientExpertAssignments.saveAndFlush(new PatientExpertAssignment(patient, staff, assignedBy));
        assertThatThrownBy(() -> patientExpertAssignments.saveAndFlush(
                new PatientExpertAssignment(patient, staff, assignedBy)))
                .isInstanceOf(DataIntegrityViolationException.class);

        patientCohortMemberships.saveAndFlush(new PatientCohortMembership(patient, cohort, assignedBy));
        assertThatThrownBy(() -> patientCohortMemberships.saveAndFlush(
                new PatientCohortMembership(patient, cohort, assignedBy)))
                .isInstanceOf(DataIntegrityViolationException.class);

        cohortStaffAssignments.saveAndFlush(new CohortStaffAssignment(cohort, staff, assignedBy));
        assertThatThrownBy(() -> cohortStaffAssignments.saveAndFlush(
                new CohortStaffAssignment(cohort, staff, assignedBy)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private PatientProfile createPatientProfile(String email) {
        return patientProfiles.saveAndFlush(new PatientProfile(createUser(email, RoleName.PATIENT)));
    }

    private StaffProfile createStaffProfile(String email) {
        return staffProfiles.saveAndFlush(new StaffProfile(createUser(email, RoleName.PHYSICIAN)));
    }

    private User createUser(String email, RoleName role) {
        var user = users.saveAndFlush(new User(email, "hash"));
        user.addRole(role);
        return users.saveAndFlush(user);
    }
}
