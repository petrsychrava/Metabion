package com.metabion.integration;

import com.metabion.domain.Cohort;
import com.metabion.domain.CohortStaffAssignment;
import com.metabion.domain.PatientCohortMembership;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.StaffProfile;
import com.metabion.domain.User;
import com.metabion.dto.assignment.AssignmentManagementForms.CohortForm;
import com.metabion.repository.CohortRepository;
import com.metabion.repository.CohortStaffAssignmentRepository;
import com.metabion.repository.PatientCohortMembershipRepository;
import com.metabion.repository.PatientExpertAssignmentRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.StaffProfileRepository;
import com.metabion.service.AccessControlService;
import com.metabion.service.AssignmentManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssignmentManagementIT extends AbstractAuthIT {

    @Autowired
    AssignmentManagementService assignmentManagement;

    @Autowired
    AccessControlService accessControl;

    @Autowired
    CohortRepository cohorts;

    @Autowired
    PatientCohortMembershipRepository memberships;

    @Autowired
    CohortStaffAssignmentRepository cohortStaffAssignments;

    @Autowired
    PatientExpertAssignmentRepository patientExpertAssignments;

    @Autowired
    PatientProfileRepository patientProfiles;

    @Autowired
    StaffProfileRepository staffProfiles;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void coordinatorEnrollsAnyEnabledPatientAndAssignedPhysicianGetsClinicalAccess() {
        var coordinator = staff("coordinator-enrollment@example.com", RoleName.COORDINATOR);
        var patient = patient("patient-enrollment@example.com");
        var physician = staff("physician-enrollment@example.com", RoleName.PHYSICIAN);
        var coordinatorAuth = auth(coordinator.getUser().getEmail());
        var cohort = assignmentManagement.createCohort(
                coordinatorAuth, new CohortForm("Pilot", "Secure pilot"));

        assignmentManagement.addPatientToCohort(coordinatorAuth, cohort.id(), patient.getId());
        assignmentManagement.assignCohortStaff(coordinatorAuth, cohort.id(), physician.getId());

        assertThat(memberships.existsActiveMembership(patient.getId(), cohort.id())).isTrue();
        assertThat(cohortStaffAssignments.existsActiveAssignment(cohort.id(), physician.getId())).isTrue();
        assertThat(accessControl.canViewPatientClinicalData(coordinatorAuth, patient.getId())).isFalse();
        assertThat(accessControl.canViewPatientClinicalData(
                auth(physician.getUser().getEmail()), patient.getId())).isTrue();
    }

    @Test
    void patientMayBelongToMultipleActiveCohorts() {
        var coordinator = staff("coordinator-multiple@example.com", RoleName.COORDINATOR);
        var patient = patient("patient-multiple@example.com");
        var coordinatorAuth = auth(coordinator.getUser().getEmail());
        var alpha = assignmentManagement.createCohort(
                coordinatorAuth, new CohortForm("Alpha", null));
        var beta = assignmentManagement.createCohort(
                coordinatorAuth, new CohortForm("Beta", null));

        assignmentManagement.addPatientToCohort(coordinatorAuth, alpha.id(), patient.getId());
        assignmentManagement.addPatientToCohort(coordinatorAuth, beta.id(), patient.getId());

        assertThat(memberships.existsActiveMembership(patient.getId(), alpha.id())).isTrue();
        assertThat(memberships.existsActiveMembership(patient.getId(), beta.id())).isTrue();
        assertThat(jdbc.queryForObject("""
                select count(*) from patient_cohort_memberships
                where patient_profile_id = ? and ended_at is null
                """, Long.class, patient.getId())).isEqualTo(2L);
    }

    @Test
    void directAndInheritedAccessForSameExpertCoexistAndEndIndependently() {
        var admin = enabledUser("admin-coexist@example.com", RoleName.ADMIN);
        var patient = patient("patient-coexist@example.com");
        var physician = staff("physician-coexist@example.com", RoleName.PHYSICIAN);
        var adminAuth = auth(admin.getEmail());
        var physicianAuth = auth(physician.getUser().getEmail());
        var cohort = assignmentManagement.createCohort(
                adminAuth, new CohortForm("Coexistence", null));
        assignmentManagement.addPatientToCohort(adminAuth, cohort.id(), patient.getId());
        assignmentManagement.assignCohortStaff(adminAuth, cohort.id(), physician.getId());
        assignmentManagement.assignDirectExpert(adminAuth, patient.getId(), physician.getId());

        var row = assignmentManagement.directPage(adminAuth).patients().getFirst();
        assertThat(row.direct()).extracting(access -> access.staffProfileId())
                .containsExactly(physician.getId());
        assertThat(row.inherited()).extracting(access -> access.staffProfileId())
                .containsExactly(physician.getId());
        assertThat(accessControl.canViewPatientClinicalData(physicianAuth, patient.getId())).isTrue();

        var direct = patientExpertAssignments.findActiveByPatientProfileId(patient.getId()).getFirst();
        assignmentManagement.endDirectExpertAssignment(adminAuth, patient.getId(), direct.getId());

        assertThat(patientExpertAssignments.existsActiveAssignment(patient.getId(), physician.getId())).isFalse();
        assertThat(cohortStaffAssignments.existsActiveAssignmentForPatient(
                patient.getId(), physician.getId())).isTrue();
        assertThat(accessControl.canViewPatientClinicalData(physicianAuth, patient.getId())).isTrue();
    }

    @Test
    void endingMembershipRemovesInheritedSourceButPreservesDirectAccess() {
        var admin = enabledUser("admin-membership-end@example.com", RoleName.ADMIN);
        var patient = patient("patient-membership-end@example.com");
        var physician = staff("physician-membership-end@example.com", RoleName.PHYSICIAN);
        var adminAuth = auth(admin.getEmail());
        var physicianAuth = auth(physician.getUser().getEmail());
        var cohort = assignmentManagement.createCohort(
                adminAuth, new CohortForm("Membership end", null));
        assignmentManagement.addPatientToCohort(adminAuth, cohort.id(), patient.getId());
        assignmentManagement.assignCohortStaff(adminAuth, cohort.id(), physician.getId());
        assignmentManagement.assignDirectExpert(adminAuth, patient.getId(), physician.getId());
        var membership = memberships.findActiveByCohortId(cohort.id()).getFirst();

        assignmentManagement.endMembership(adminAuth, cohort.id(), membership.getId());

        assertThat(cohortStaffAssignments.existsActiveAssignmentForPatient(
                patient.getId(), physician.getId())).isFalse();
        assertThat(patientExpertAssignments.existsActiveAssignment(patient.getId(), physician.getId())).isTrue();
        assertThat(accessControl.canViewPatientClinicalData(physicianAuth, patient.getId())).isTrue();
        var ended = memberships.findHistoryByCohortId(cohort.id()).getFirst();
        assertThat(ended.getEndedAt()).isNotNull();
        assertThat(ended.getEndedBy().getId()).isEqualTo(admin.getId());
    }

    @Test
    void archiveEndsInheritedRelationshipsAtOneAttributedInstantAndPreservesDirectAssignment() {
        var admin = enabledUser("admin-archive@example.com", RoleName.ADMIN);
        var patient = patient("patient-archive@example.com");
        var cohortPhysician = staff("cohort-physician-archive@example.com", RoleName.PHYSICIAN);
        var directPhysician = staff("direct-physician-archive@example.com", RoleName.PHYSICIAN);
        var adminAuth = auth(admin.getEmail());
        var cohort = assignmentManagement.createCohort(
                adminAuth, new CohortForm("Archive", null));
        assignmentManagement.addPatientToCohort(adminAuth, cohort.id(), patient.getId());
        assignmentManagement.assignCohortStaff(adminAuth, cohort.id(), cohortPhysician.getId());
        assignmentManagement.assignDirectExpert(adminAuth, patient.getId(), directPhysician.getId());
        var before = Instant.now();

        assignmentManagement.archiveCohort(adminAuth, cohort.id());

        var after = Instant.now();
        var archived = cohorts.findById(cohort.id()).orElseThrow();
        var endedMembership = memberships.findHistoryByCohortId(cohort.id()).getFirst();
        var endedStaff = cohortStaffAssignments.findHistoryByCohortId(cohort.id()).getFirst();
        assertThat(archived.getArchivedAt()).isAfterOrEqualTo(before).isBeforeOrEqualTo(after);
        assertThat(endedMembership.getEndedAt()).isEqualTo(archived.getArchivedAt());
        assertThat(endedStaff.getEndedAt()).isEqualTo(archived.getArchivedAt());
        assertThat(archived.getArchivedBy().getId()).isEqualTo(admin.getId());
        assertThat(endedMembership.getEndedBy().getId()).isEqualTo(admin.getId());
        assertThat(endedStaff.getEndedBy().getId()).isEqualTo(admin.getId());
        assertThat(cohortStaffAssignments.existsActiveAssignmentForPatient(
                patient.getId(), cohortPhysician.getId())).isFalse();
        assertThat(patientExpertAssignments.existsActiveAssignment(
                patient.getId(), directPhysician.getId())).isTrue();
        assertThat(accessControl.canViewPatientClinicalData(
                auth(cohortPhysician.getUser().getEmail()), patient.getId())).isFalse();
        assertThat(accessControl.canViewPatientClinicalData(
                auth(directPhysician.getUser().getEmail()), patient.getId())).isTrue();
    }

    @Test
    void coordinatorPhysicianGetsClinicalAccessOnlyThroughApplicableExpertAssignments() {
        var admin = enabledUser("admin-dual-role@example.com", RoleName.ADMIN);
        var dualRole = staff(
                "dual-role@example.com", RoleName.COORDINATOR, RoleName.PHYSICIAN);
        var patientA = patient("patient-a-dual-role@example.com");
        var patientB = patient("patient-b-dual-role@example.com");
        var adminAuth = auth(admin.getEmail());
        var dualAuth = auth(dualRole.getUser().getEmail());
        var cohort = assignmentManagement.createCohort(
                adminAuth, new CohortForm("Dual role", null));
        assignmentManagement.addPatientToCohort(adminAuth, cohort.id(), patientA.getId());
        assignmentManagement.assignCohortStaff(adminAuth, cohort.id(), dualRole.getId());

        assertThat(accessControl.canViewPatientClinicalData(dualAuth, patientA.getId())).isTrue();
        assertThat(accessControl.canViewPatientClinicalData(dualAuth, patientB.getId())).isFalse();

        assignmentManagement.assignDirectExpert(adminAuth, patientB.getId(), dualRole.getId());
        assertThat(accessControl.canViewPatientClinicalData(dualAuth, patientB.getId())).isTrue();
        var direct = patientExpertAssignments.findActiveByPatientProfileId(patientB.getId()).getFirst();
        assignmentManagement.endDirectExpertAssignment(adminAuth, patientB.getId(), direct.getId());

        assertThat(accessControl.canViewPatientClinicalData(dualAuth, patientB.getId())).isFalse();
    }

    @Test
    void archivedCohortWithOpenRowsNeverGrantsClinicalOrManagementScope() {
        var admin = enabledUser("admin-open-archive@example.com", RoleName.ADMIN);
        var patient = patient("patient-open-archive@example.com");
        var dualRole = staff(
                "dual-open-archive@example.com", RoleName.COORDINATOR, RoleName.PHYSICIAN);
        var cohort = new Cohort("Open archived rows", null, admin);
        cohort.archive(admin, Instant.now());
        cohorts.saveAndFlush(cohort);
        memberships.saveAndFlush(new PatientCohortMembership(patient, cohort, admin));
        cohortStaffAssignments.saveAndFlush(new CohortStaffAssignment(cohort, dualRole, admin));
        var dualAuth = auth(dualRole.getUser().getEmail());

        assertThat(memberships.existsActiveMembership(patient.getId(), cohort.getId())).isTrue();
        assertThat(cohortStaffAssignments.existsActiveAssignment(cohort.getId(), dualRole.getId())).isTrue();
        assertThat(cohortStaffAssignments.existsActiveAssignmentForPatient(
                patient.getId(), dualRole.getId())).isFalse();
        assertThat(accessControl.canViewPatientClinicalData(dualAuth, patient.getId())).isFalse();
        assertThat(accessControl.canAccessCohort(dualAuth, cohort.getId())).isFalse();
        assertThat(accessControl.canManageCohort(dualAuth, cohort.getId())).isFalse();
        assertThat(accessControl.canManageDirectExpertAssignments(dualAuth, patient.getId())).isFalse();
        assertThat(assignmentManagement.directPage(dualAuth).patients()).isEmpty();
    }

    @Test
    void directPageCandidatesAreSpecificToEachPatientExpertPair() {
        var admin = enabledUser("admin-pair-candidates@example.com", RoleName.ADMIN);
        var patientA = patient("patient-a-pair-candidates@example.com");
        var patientB = patient("patient-b-pair-candidates@example.com");
        var physician = staff("physician-pair-candidates@example.com", RoleName.PHYSICIAN);
        var adminAuth = auth(admin.getEmail());
        assignmentManagement.assignDirectExpert(adminAuth, patientA.getId(), physician.getId());

        var page = assignmentManagement.directPage(adminAuth);
        var rowA = page.patients().stream()
                .filter(row -> row.patientProfileId().equals(patientA.getId()))
                .findFirst().orElseThrow();
        var rowB = page.patients().stream()
                .filter(row -> row.patientProfileId().equals(patientB.getId()))
                .findFirst().orElseThrow();

        assertThat(rowA.staffCandidates()).extracting(candidate -> candidate.staffProfileId())
                .doesNotContain(physician.getId());
        assertThat(rowB.staffCandidates()).extracting(candidate -> candidate.staffProfileId())
                .contains(physician.getId());
    }

    @Test
    void databaseRejectsDuplicateActiveRelationshipRows() {
        var admin = enabledUser("admin-duplicates@example.com", RoleName.ADMIN);
        var patient = patient("patient-duplicates@example.com");
        var physician = staff("physician-duplicates@example.com", RoleName.PHYSICIAN);
        var adminAuth = auth(admin.getEmail());
        var cohort = assignmentManagement.createCohort(
                adminAuth, new CohortForm("Duplicates", null));
        assignmentManagement.addPatientToCohort(adminAuth, cohort.id(), patient.getId());
        assignmentManagement.assignCohortStaff(adminAuth, cohort.id(), physician.getId());
        assignmentManagement.assignDirectExpert(adminAuth, patient.getId(), physician.getId());

        assertThatThrownBy(() -> jdbc.update("""
                insert into patient_cohort_memberships
                    (patient_profile_id, cohort_id, assigned_by_user_id)
                values (?, ?, ?)
                """, patient.getId(), cohort.id(), admin.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into cohort_staff_assignments
                    (cohort_id, staff_profile_id, assigned_by_user_id)
                values (?, ?, ?)
                """, cohort.id(), physician.getId(), admin.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into patient_expert_assignments
                    (patient_profile_id, staff_profile_id, assigned_by_user_id)
                values (?, ?, ?)
                """, patient.getId(), physician.getId(), admin.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void disabledUsersAreExcludedFromOperationalCandidates() {
        var admin = enabledUser("admin-disabled-candidates@example.com", RoleName.ADMIN);
        var enabledPatient = patient("enabled-patient-candidates@example.com");
        var disabledPatient = patientProfile(disabledUser(
                "disabled-patient-candidates@example.com", RoleName.PATIENT));
        var enabledPhysician = staff("enabled-physician-candidates@example.com", RoleName.PHYSICIAN);
        var disabledPhysician = staffProfile(disabledUser(
                "disabled-physician-candidates@example.com", RoleName.PHYSICIAN));
        var adminAuth = auth(admin.getEmail());
        var cohort = assignmentManagement.createCohort(
                adminAuth, new CohortForm("Candidates", null));

        var cohortPage = assignmentManagement.cohortPage(adminAuth, cohort.id());
        assertThat(cohortPage.patientCandidates()).extracting(candidate -> candidate.id())
                .contains(enabledPatient.getId())
                .doesNotContain(disabledPatient.getId());
        assertThat(cohortPage.staffCandidates()).extracting(candidate -> candidate.staffProfileId())
                .contains(enabledPhysician.getId())
                .doesNotContain(disabledPhysician.getId());

        var directPage = assignmentManagement.directPage(adminAuth);
        assertThat(directPage.patients()).extracting(row -> row.patientProfileId())
                .contains(enabledPatient.getId())
                .doesNotContain(disabledPatient.getId());
        assertThat(directPage.patients().getFirst().staffCandidates())
                .extracting(candidate -> candidate.staffProfileId())
                .contains(enabledPhysician.getId())
                .doesNotContain(disabledPhysician.getId());
    }

    @Test
    void revokingCoordinatorScopeSerializesWithDirectAssignmentWrite() throws Exception {
        var admin = enabledUser("admin-direct-race@example.com", RoleName.ADMIN);
        var coordinator = staff("coordinator-direct-race@example.com", RoleName.COORDINATOR);
        var patient = patient("patient-direct-race@example.com");
        var physician = staff("physician-direct-race@example.com", RoleName.PHYSICIAN);
        var adminAuth = auth(admin.getEmail());
        var coordinatorAuth = auth(coordinator.getUser().getEmail());
        var cohort = assignmentManagement.createCohort(
                adminAuth, new CohortForm("Direct race", null));
        assignmentManagement.addPatientToCohort(adminAuth, cohort.id(), patient.getId());
        assignmentManagement.assignCohortStaff(adminAuth, cohort.id(), coordinator.getId());
        var coordinatorAssignment = cohortStaffAssignments.findActiveByCohortId(cohort.id()).stream()
                .filter(row -> row.getStaffProfile().getId().equals(coordinator.getId()))
                .findFirst().orElseThrow();

        var revokedButUncommitted = new CountDownLatch(1);
        var releaseRevocation = new CountDownLatch(1);
        var directWriteAttempted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var revoke = executor.submit(() -> captureFailure(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        assignmentManagement.endCohortStaffAssignment(
                                adminAuth, cohort.id(), coordinatorAssignment.getId());
                        revokedButUncommitted.countDown();
                        await(releaseRevocation);
                    })));
            assertThat(revokedButUncommitted.await(5, TimeUnit.SECONDS)).isTrue();

            var directWrite = executor.submit(() -> captureFailure(() -> {
                directWriteAttempted.countDown();
                assignmentManagement.assignDirectExpert(
                        coordinatorAuth, patient.getId(), physician.getId());
            }));
            assertThat(directWriteAttempted.await(5, TimeUnit.SECONDS)).isTrue();
            var completedBeforeRevocationCommit = completesWithin(directWrite, 500);
            releaseRevocation.countDown();

            assertThat(revoke.get(5, TimeUnit.SECONDS)).isNull();
            var directFailure = directWrite.get(5, TimeUnit.SECONDS);
            assertThat(completedBeforeRevocationCommit).isFalse();
            assertThat(directFailure).isInstanceOfSatisfying(
                    ResponseStatusException.class,
                    exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
            assertThat(patientExpertAssignments.existsActiveAssignment(
                    patient.getId(), physician.getId())).isFalse();
        } finally {
            releaseRevocation.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void archiveAndMembershipEndCompleteWithoutDeadlock() throws Exception {
        var admin = enabledUser("admin-archive-race@example.com", RoleName.ADMIN);
        var patient = patient("patient-archive-race@example.com");
        var adminAuth = auth(admin.getEmail());
        var cohort = assignmentManagement.createCohort(
                adminAuth, new CohortForm("Archive race", null));
        assignmentManagement.addPatientToCohort(adminAuth, cohort.id(), patient.getId());
        var membership = memberships.findActiveByCohortId(cohort.id()).getFirst();

        var archivedButUncommitted = new CountDownLatch(1);
        var releaseArchive = new CountDownLatch(1);
        var membershipEndAttempted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var archive = executor.submit(() -> captureFailure(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        assignmentManagement.archiveCohort(adminAuth, cohort.id());
                        archivedButUncommitted.countDown();
                        await(releaseArchive);
                    })));
            assertThat(archivedButUncommitted.await(5, TimeUnit.SECONDS)).isTrue();

            var end = executor.submit(() -> captureFailure(() -> {
                membershipEndAttempted.countDown();
                assignmentManagement.endMembership(adminAuth, cohort.id(), membership.getId());
            }));
            assertThat(membershipEndAttempted.await(5, TimeUnit.SECONDS)).isTrue();
            var completedBeforeArchiveCommit = completesWithin(end, 500);
            releaseArchive.countDown();

            assertThat(archive.get(5, TimeUnit.SECONDS)).isNull();
            var endFailure = end.get(5, TimeUnit.SECONDS);
            assertThat(completedBeforeArchiveCommit).isFalse();
            assertThat(endFailure).isInstanceOfSatisfying(
                    ResponseStatusException.class,
                    exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        } finally {
            releaseArchive.countDown();
            executor.shutdownNow();
        }
    }

    private static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static boolean completesWithin(java.util.concurrent.Future<?> future,
                                           long timeoutMillis) throws Exception {
        try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException expected) {
            return false;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for concurrent transaction");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for concurrent transaction", exception);
        }
    }

    private Authentication auth(String email) {
        return UsernamePasswordAuthenticationToken.authenticated(
                email, "n/a", AuthorityUtils.NO_AUTHORITIES);
    }

    private User enabledUser(String email, RoleName... roles) {
        var user = new User(email, passwordEncoder.encode("Integration1!"));
        user.setEnabled(true);
        Arrays.stream(roles).forEach(user::addRole);
        return users.saveAndFlush(user);
    }

    private User disabledUser(String email, RoleName... roles) {
        var user = new User(email, passwordEncoder.encode("Integration1!"));
        Arrays.stream(roles).forEach(user::addRole);
        return users.saveAndFlush(user);
    }

    private PatientProfile patient(String email) {
        return patientProfile(enabledUser(email, RoleName.PATIENT));
    }

    private PatientProfile patientProfile(User user) {
        return patientProfiles.saveAndFlush(new PatientProfile(user));
    }

    private StaffProfile staff(String email, RoleName... roles) {
        return staffProfile(enabledUser(email, roles));
    }

    private StaffProfile staffProfile(User user) {
        return staffProfiles.saveAndFlush(new StaffProfile(user));
    }
}
