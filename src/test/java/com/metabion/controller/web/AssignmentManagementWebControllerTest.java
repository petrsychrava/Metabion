package com.metabion.controller.web;

import com.metabion.config.LocalizationConfig;
import com.metabion.domain.RoleName;
import com.metabion.domain.ThemePreference;
import com.metabion.dto.PatientOptionResponse;
import com.metabion.dto.assignment.AssignmentManagementForms.CohortForm;
import com.metabion.dto.assignment.AssignmentManagementView.AccessSource;
import com.metabion.dto.assignment.AssignmentManagementView.CohortItem;
import com.metabion.dto.assignment.AssignmentManagementView.CohortPage;
import com.metabion.dto.assignment.AssignmentManagementView.DirectPatient;
import com.metabion.dto.assignment.AssignmentManagementView.DirectPage;
import com.metabion.dto.assignment.AssignmentManagementView.ExpertAccess;
import com.metabion.dto.assignment.AssignmentManagementView.PatientRow;
import com.metabion.dto.assignment.AssignmentManagementView.StaffOption;
import com.metabion.service.AssignmentManagementService;
import com.metabion.service.UserPreferenceService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
                .defaultRequest(get("/").requestAttr(
                        "_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "test-csrf")))
                .setLocaleResolver(localeResolver())
                .setViewResolvers(viewResolver(messageSource()))
                .build();
    }

    @Test
    void activeCohortWorkspaceRendersLocalizedControlsAndNamedConfirmationsForAdmin() throws Exception {
        stubAppShell();
        var authentication = auth("admin@example.com", RoleName.ADMIN);
        when(assignments.cohortPage(same(authentication), eq(10L))).thenReturn(activeCohortPage(true));

        mvc.perform(get("/app/assignment-management/cohorts/10").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Patients")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Care team")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Direct assignments")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Access through cohort")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Active")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Archive cohort")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Archive cohort “Pilot cohort” and end all of its active memberships and staff assignments?")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "End patient@example.com’s membership in cohort “Pilot cohort”?")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "coordinator-target@example.com")));
    }

    @Test
    void coordinatorWorkspaceOmitsArchiveAndCoordinatorTargets() throws Exception {
        stubAppShell();
        var authentication = auth("coordinator@example.com", RoleName.COORDINATOR);
        when(assignments.cohortPage(same(authentication), eq(10L))).thenReturn(activeCohortPage(false));

        mvc.perform(get("/app/assignment-management/cohorts/10").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Archive cohort"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "coordinator-target@example.com"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("physician-target@example.com")));
    }

    @Test
    void archivedCohortWorkspaceShowsStatusWithoutSelectedCohortMutationForms() throws Exception {
        stubAppShell();
        var authentication = auth("admin@example.com", RoleName.ADMIN);
        when(assignments.cohortPage(same(authentication), eq(10L))).thenReturn(archivedCohortPage());

        mvc.perform(get("/app/assignment-management/cohorts/10").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Archived")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "/app/assignment-management/cohorts/10/edit"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "/app/assignment-management/cohorts/10/archive"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "/app/assignment-management/cohorts/10/patients"))));
    }

    @Test
    void directWorkspaceUsesEachPatientsStaffCandidatesAndLinksInheritedAccessToCohorts() throws Exception {
        stubAppShell();
        var authentication = auth("admin@example.com", RoleName.ADMIN);
        when(assignments.directPage(same(authentication))).thenReturn(directPage());

        mvc.perform(get("/app/assignment-management/direct").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Direct assignments")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Access through cohort")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("one-candidate@example.com")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("two-candidate@example.com")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "href=\"/app/assignment-management/cohorts/10\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Manage care team")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "End doctor@example.com’s direct assignment to patient patient@example.com?")));
    }

    @Test
    void assignmentWorkspaceUsesCzechCopyWhenLocaleCookieIsCzech() throws Exception {
        stubAppShell();
        var authentication = auth("admin@example.com", RoleName.ADMIN);
        when(assignments.directPage(same(authentication))).thenReturn(directPage());

        mvc.perform(get("/app/assignment-management/direct")
                        .cookie(new Cookie(LocalizationConfig.LOCALE_COOKIE_NAME, "cs"))
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Správa přiřazení")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Přímá přiřazení")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Přístup přes kohortu")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Ukončit přímé přiřazení odborníka doctor@example.com k pacientovi patient@example.com?")));
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

    private static CohortPage activeCohortPage(boolean includeCoordinatorCandidate) {
        var cohort = new CohortItem(10L, "Pilot cohort", "Pilot notes", false,
                "admin@example.com", Instant.EPOCH);
        var direct = new ExpertAccess(20L, 50L, "doctor@example.com", List.of("PHYSICIAN"),
                AccessSource.DIRECT, null, null);
        var inherited = new ExpertAccess(30L, 60L, "nutrition@example.com", List.of("NUTRITION_SPECIALIST"),
                AccessSource.COHORT, 10L, "Pilot cohort");
        var patient = new PatientRow(40L, 70L, "patient@example.com", List.of(direct), List.of(inherited));
        var coordinator = new ExpertAccess(80L, 90L, "coordinator@example.com", List.of("COORDINATOR"),
                AccessSource.COHORT, 10L, "Pilot cohort");
        var physician = new ExpertAccess(81L, 91L, "physician@example.com", List.of("PHYSICIAN"),
                AccessSource.COHORT, 10L, "Pilot cohort");
        var staffCandidates = includeCoordinatorCandidate
                ? List.of(
                        new StaffOption(92L, "coordinator-target@example.com", List.of("COORDINATOR")),
                        new StaffOption(93L, "physician-target@example.com", List.of("PHYSICIAN")))
                : List.of(new StaffOption(93L, "physician-target@example.com", List.of("PHYSICIAN")));
        return new CohortPage(
                List.of(cohort),
                cohort,
                List.of(patient),
                List.of(coordinator, physician),
                List.of(new PatientOptionResponse(71L, "candidate-patient@example.com")),
                staffCandidates);
    }

    private static CohortPage archivedCohortPage() {
        var archived = new CohortItem(10L, "Pilot cohort", "Pilot notes", true,
                "admin@example.com", Instant.EPOCH);
        return new CohortPage(List.of(archived), archived, List.of(), List.of(), List.of(), List.of());
    }

    private static DirectPage directPage() {
        var cohort = new CohortItem(10L, "Pilot cohort", "Pilot notes", false,
                "admin@example.com", Instant.EPOCH);
        var direct = new ExpertAccess(20L, 50L, "doctor@example.com", List.of("PHYSICIAN"),
                AccessSource.DIRECT, null, null);
        var inherited = new ExpertAccess(30L, 60L, "nutrition@example.com", List.of("NUTRITION_SPECIALIST"),
                AccessSource.COHORT, 10L, "Pilot cohort");
        return new DirectPage(List.of(
                new DirectPatient(70L, "patient@example.com", List.of(cohort), List.of(direct), List.of(inherited),
                        List.of(new StaffOption(91L, "one-candidate@example.com", List.of("PHYSICIAN")))),
                new DirectPatient(71L, "second-patient@example.com", List.of(), List.of(), List.of(),
                        List.of(new StaffOption(92L, "two-candidate@example.com", List.of("NUTRITION_SPECIALIST"))))));
    }

    private void stubAppShell() {
        when(appMenuCatalog.sidebarItems(any())).thenReturn(List.of());
        when(userPreferenceService.currentThemePreference(any())).thenReturn(ThemePreference.SYSTEM);
    }

    private void stubMessages() {
        when(messages.getMessage(anyString(), nullable(Object[].class), any(Locale.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static CookieLocaleResolver localeResolver() {
        var resolver = new CookieLocaleResolver(LocalizationConfig.LOCALE_COOKIE_NAME);
        resolver.setDefaultLocale(Locale.ENGLISH);
        return resolver;
    }

    private static ResourceBundleMessageSource messageSource() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setDefaultLocale(Locale.ENGLISH);
        return source;
    }

    private static ThymeleafViewResolver viewResolver(ResourceBundleMessageSource messages) {
        var templateResolver = new ClassLoaderTemplateResolver();
        templateResolver.setPrefix("templates/");
        templateResolver.setSuffix(".html");
        templateResolver.setTemplateMode("HTML");
        templateResolver.setCharacterEncoding("UTF-8");

        var templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(templateResolver);
        templateEngine.setMessageSource(messages);

        var viewResolver = new ThymeleafViewResolver();
        viewResolver.setTemplateEngine(templateEngine);
        viewResolver.setCharacterEncoding("UTF-8");
        return viewResolver;
    }
}
