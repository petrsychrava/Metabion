package com.metabion.integration;

import com.metabion.domain.AdvancedTherapyExposure;
import com.metabion.domain.DiseaseActivityEstimate;
import com.metabion.domain.IbdDiagnosisType;
import com.metabion.domain.PatientExpertAssignment;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.Sex;
import com.metabion.domain.StaffProfile;
import com.metabion.domain.SteroidUse;
import com.metabion.domain.User;
import com.metabion.dto.OnboardingReviewRequest;
import com.metabion.dto.OnboardingSubmissionRequest;
import com.metabion.repository.PatientExpertAssignmentRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.StaffProfileRepository;
import com.metabion.repository.UserRepository;
import com.metabion.service.OnboardingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;

import static com.metabion.domain.OnboardingReviewStatus.REVIEWED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnboardingReviewIT extends AbstractAuthIT {

    @Autowired
    UserRepository userRepository;

    @Autowired
    PatientProfileRepository patientProfileRepository;

    @Autowired
    StaffProfileRepository staffProfileRepository;

    @Autowired
    PatientExpertAssignmentRepository assignmentRepository;

    @Autowired
    OnboardingService onboardingService;

    @Test
    void assignedClinicalStaffCanReviewButUnassignedStaffCannot() {
        var patientUser = createUser("onboarding-patient@example.com", RoleName.PATIENT);
        var patientProfile = patientProfileRepository.saveAndFlush(new PatientProfile(patientUser));
        var assignedStaffUser = createUser("assigned-reviewer@example.com", RoleName.PHYSICIAN);
        var assignedStaff = staffProfileRepository.saveAndFlush(new StaffProfile(assignedStaffUser));
        var unassignedStaffUser = createUser("unassigned-reviewer@example.com", RoleName.PHYSICIAN);
        staffProfileRepository.saveAndFlush(new StaffProfile(unassignedStaffUser));
        var admin = createUser("onboarding-admin@example.com", RoleName.ADMIN);
        assignmentRepository.saveAndFlush(new PatientExpertAssignment(patientProfile, assignedStaff, admin));

        var submission = onboardingService.submitForCurrentPatient(
                auth("onboarding-patient@example.com"),
                validRequest());

        assertThatThrownBy(() -> onboardingService.review(
                auth("unassigned-reviewer@example.com"),
                submission.id(),
                new OnboardingReviewRequest(REVIEWED, "not allowed")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");

        var reviewed = onboardingService.review(
                auth("assigned-reviewer@example.com"),
                submission.id(),
                new OnboardingReviewRequest(REVIEWED, "complete"));

        assertThat(reviewed.reviewStatus()).isEqualTo(REVIEWED);
        assertThat(reviewed.reviewedByEmail()).isEqualTo("assigned-reviewer@example.com");
    }

    private User createUser(String email, RoleName role) {
        var user = userRepository.saveAndFlush(new User(email, "hash"));
        user.setEnabled(true);
        user.addRole(role);
        return userRepository.saveAndFlush(user);
    }

    private UsernamePasswordAuthenticationToken auth(String email) {
        return new UsernamePasswordAuthenticationToken(email, "n/a", AuthorityUtils.NO_AUTHORITIES);
    }

    private OnboardingSubmissionRequest validRequest() {
        return new OnboardingSubmissionRequest(
                "default",
                LocalDate.of(1990, 1, 1),
                Sex.FEMALE,
                "CZ",
                "Europe/Prague",
                IbdDiagnosisType.CROHNS_DISEASE,
                2018,
                "Ileocolonic",
                "Inflammatory",
                DiseaseActivityEstimate.MILD,
                "Mesalamine",
                SteroidUse.NONE,
                AdvancedTherapyExposure.NEVER_USED,
                "Stable regimen",
                LocalDate.of(2026, 5, 20),
                new BigDecimal("4.2"),
                new BigDecimal("120"),
                new BigDecimal("13.8"),
                new BigDecimal("4.3"),
                "Recent outpatient labs");
    }
}
