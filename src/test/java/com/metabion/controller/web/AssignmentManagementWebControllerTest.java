package com.metabion.controller.web;

import com.metabion.domain.RoleName;
import com.metabion.domain.ThemePreference;
import com.metabion.dto.assignment.AssignmentManagementForms.CohortForm;
import com.metabion.dto.assignment.AssignmentManagementView.CohortItem;
import com.metabion.dto.assignment.AssignmentManagementView.CohortPage;
import com.metabion.dto.assignment.AssignmentManagementView.DirectPage;
import com.metabion.service.AssignmentManagementService;
import com.metabion.service.UserPreferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@ExtendWith(MockitoExtension.class)
class AssignmentManagementWebControllerTest {

    @Mock AssignmentManagementService assignments;
    @Mock AppMenuCatalog appMenuCatalog;
    @Mock UserPreferenceService userPreferenceService;
    @Mock MessageSource messages;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var controller = new AssignmentManagementWebController(
                assignments, appMenuCatalog, userPreferenceService, messages);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new WebExceptionHandler(messages))
                .setViewResolvers(new InternalResourceViewResolver("/WEB-INF/views/", ".jsp"))
                .build();
    }

    @Test
    void controllerHasOnlyTheApprovedCollaborators() {
        assertThat(AssignmentManagementWebController.class.getDeclaredConstructors())
                .singleElement()
                .satisfies(constructor -> assertThat(constructor.getParameterTypes())
                        .containsExactly(
                                AssignmentManagementService.class,
                                AppMenuCatalog.class,
                                UserPreferenceService.class,
                                MessageSource.class));
    }

    @Test
    void rootCohortGetUsesDefaultSelectionAndAddsAppShell() throws Exception {
        stubAppShell();
        var authentication = auth("admin@example.com", RoleName.ADMIN);
        var page = emptyCohortPage();
        when(assignments.cohortPage(same(authentication), isNull())).thenReturn(page);

        mvc.perform(get("/app/assignment-management").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(view().name("assignment-management"))
                .andExpect(model().attribute("cohortPage", page))
                .andExpect(model().attributeExists(
                        "cohortForm", "patientSelection", "staffSelection", "appMenuItems",
                        "activePath", "themePreference"))
                .andExpect(model().attribute("isAdmin", true))
                .andExpect(model().attribute("activePath", "/app/assignment-management"));

        verify(assignments).cohortPage(same(authentication), isNull());
    }

    @Test
    void explicitCohortGetUsesRequestedScopedIdentifier() throws Exception {
        stubAppShell();
        var authentication = auth("coordinator@example.com", RoleName.COORDINATOR);
        var page = emptyCohortPage();
        when(assignments.cohortPage(same(authentication), eq(10L))).thenReturn(page);

        mvc.perform(get("/app/assignment-management/cohorts/10").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(view().name("assignment-management"))
                .andExpect(model().attribute("cohortPage", page))
                .andExpect(model().attribute("isAdmin", false));

        verify(assignments).cohortPage(same(authentication), eq(10L));
    }

    @Test
    void directGetUsesPatientSpecificReadModelAndAddsAppShell() throws Exception {
        stubAppShell();
        var authentication = auth("coordinator@example.com", RoleName.COORDINATOR);
        var page = new DirectPage(List.of());
        when(assignments.directPage(same(authentication))).thenReturn(page);

        mvc.perform(get("/app/assignment-management/direct").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(view().name("assignment-management"))
                .andExpect(model().attribute("directPage", page))
                .andExpect(model().attributeExists(
                        "staffSelection", "appMenuItems", "activePath", "themePreference"))
                .andExpect(model().attribute("isAdmin", false))
                .andExpect(model().attribute("activePath", "/app/assignment-management"));

        verify(assignments).directPage(same(authentication));
    }

    @Test
    void successfulPostsDelegateExactlyOnceAndUsePostRedirectGet() throws Exception {
        stubMessages();
        var authentication = auth("admin@example.com", RoleName.ADMIN);
        var created = new CohortItem(
                10L, "Pilot", "Notes", false, "admin@example.com", Instant.EPOCH);
        when(assignments.createCohort(any(), any())).thenReturn(created);

        mvc.perform(post("/app/assignment-management/cohorts")
                        .principal(authentication)
                        .param("name", "Pilot")
                        .param("description", "Notes"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/assignment-management/cohorts/10"))
                .andExpect(flash().attribute("success", "assignment.success.cohortCreated"));
        mvc.perform(post("/app/assignment-management/cohorts/10/edit")
                        .principal(authentication)
                        .param("name", "Renamed")
                        .param("description", "Updated"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/assignment-management/cohorts/10"))
                .andExpect(flash().attribute("success", "assignment.success.cohortUpdated"));
        mvc.perform(post("/app/assignment-management/cohorts/10/archive")
                        .principal(authentication))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/assignment-management"))
                .andExpect(flash().attribute("success", "assignment.success.cohortArchived"));
        mvc.perform(post("/app/assignment-management/cohorts/10/patients")
                        .principal(authentication)
                        .param("targetId", "40"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/assignment-management/cohorts/10"))
                .andExpect(flash().attribute("success", "assignment.success.patientAdded"));
        mvc.perform(post("/app/assignment-management/cohorts/10/memberships/20/end")
                        .principal(authentication))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/assignment-management/cohorts/10"))
                .andExpect(flash().attribute("success", "assignment.success.membershipEnded"));
        mvc.perform(post("/app/assignment-management/cohorts/10/staff")
                        .principal(authentication)
                        .param("targetId", "50"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/assignment-management/cohorts/10"))
                .andExpect(flash().attribute("success", "assignment.success.staffAdded"));
        mvc.perform(post("/app/assignment-management/cohorts/10/staff-assignments/30/end")
                        .principal(authentication))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/assignment-management/cohorts/10"))
                .andExpect(flash().attribute("success", "assignment.success.staffEnded"));
        mvc.perform(post("/app/assignment-management/patients/40/direct-assignments")
                        .principal(authentication)
                        .param("targetId", "60"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/assignment-management/direct"))
                .andExpect(flash().attribute("success", "assignment.success.directAdded"));
        mvc.perform(post("/app/assignment-management/patients/40/direct-assignments/70/end")
                        .principal(authentication))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/assignment-management/direct"))
                .andExpect(flash().attribute("success", "assignment.success.directEnded"));

        verify(assignments).createCohort(same(authentication), eq(new CohortForm("Pilot", "Notes")));
        verify(assignments).updateCohort(
                same(authentication), eq(10L), eq(new CohortForm("Renamed", "Updated")));
        verify(assignments).archiveCohort(same(authentication), eq(10L));
        verify(assignments).addPatientToCohort(same(authentication), eq(10L), eq(40L));
        verify(assignments).endMembership(same(authentication), eq(10L), eq(20L));
        verify(assignments).assignCohortStaff(same(authentication), eq(10L), eq(50L));
        verify(assignments).endCohortStaffAssignment(same(authentication), eq(10L), eq(30L));
        verify(assignments).assignDirectExpert(same(authentication), eq(40L), eq(60L));
        verify(assignments).endDirectExpertAssignment(same(authentication), eq(40L), eq(70L));
        verifyNoMoreInteractions(assignments);
    }

    @Test
    void invalidCreateFormRendersCohortPageWithoutMutation() throws Exception {
        stubAppShell();
        var authentication = auth("coordinator@example.com", RoleName.COORDINATOR);
        when(assignments.cohortPage(same(authentication), isNull())).thenReturn(emptyCohortPage());

        mvc.perform(post("/app/assignment-management/cohorts")
                        .principal(authentication)
                        .param("name", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("assignment-management"))
                .andExpect(model().attributeHasFieldErrors("cohortForm", "name"));

        verify(assignments, never()).createCohort(any(), any());
        verify(assignments).cohortPage(same(authentication), isNull());
    }

    @Test
    void invalidEditFormRendersSelectedCohortPageWithoutMutation() throws Exception {
        stubAppShell();
        var authentication = auth("coordinator@example.com", RoleName.COORDINATOR);
        when(assignments.cohortPage(same(authentication), eq(10L))).thenReturn(emptyCohortPage());

        mvc.perform(post("/app/assignment-management/cohorts/10/edit")
                        .principal(authentication)
                        .param("name", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("assignment-management"))
                .andExpect(model().attributeHasFieldErrors("cohortForm", "name"));

        verify(assignments, never()).updateCohort(any(), any(), any());
        verify(assignments).cohortPage(same(authentication), eq(10L));
    }

    @Test
    void selectionFormsRejectMissingTargetsWithoutMutation() throws Exception {
        var authentication = auth("admin@example.com", RoleName.ADMIN);

        mvc.perform(post("/app/assignment-management/cohorts/10/patients")
                        .principal(authentication))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/app/assignment-management/cohorts/10/staff")
                        .principal(authentication))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/app/assignment-management/patients/40/direct-assignments")
                        .principal(authentication))
                .andExpect(status().isBadRequest());

        verify(assignments, never()).addPatientToCohort(any(), any(), any());
        verify(assignments, never()).assignCohortStaff(any(), any(), any());
        verify(assignments, never()).assignDirectExpert(any(), any(), any());
    }

    @Test
    void assignmentConflictUsesAssignmentCopyAndReturnLink() throws Exception {
        stubMessages();
        var authentication = auth("admin@example.com", RoleName.ADMIN);
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT))
                .when(assignments).archiveCohort(same(authentication), eq(10L));

        mvc.perform(post("/app/assignment-management/cohorts/10/archive")
                        .principal(authentication))
                .andExpect(status().isConflict())
                .andExpect(view().name("result"))
                .andExpect(model().attribute("message", "assignment.error.conflict"))
                .andExpect(model().attribute("href", "/app/assignment-management"))
                .andExpect(model().attribute("action", "error.backToApp"));
    }

    private static Authentication auth(String email, RoleName... roles) {
        return UsernamePasswordAuthenticationToken.authenticated(
                email,
                "n/a",
                java.util.Arrays.stream(roles)
                        .map(RoleName::authority)
                        .map(SimpleGrantedAuthority::new)
                        .toList());
    }

    private static CohortPage emptyCohortPage() {
        return new CohortPage(List.of(), null, List.of(), List.of(), List.of(), List.of());
    }

    private void stubAppShell() {
        when(appMenuCatalog.sidebarItems(any())).thenReturn(List.of());
        when(userPreferenceService.currentThemePreference(any())).thenReturn(ThemePreference.SYSTEM);
    }

    private void stubMessages() {
        when(messages.getMessage(anyString(), nullable(Object[].class), any(Locale.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }
}
