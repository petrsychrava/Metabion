package com.metabion.controller;

import com.metabion.dto.AcceptStaffInvitationRequest;
import com.metabion.dto.CreateStaffInvitationRequest;
import com.metabion.exception.StaffInvitationException;
import com.metabion.service.SecurityService;
import com.metabion.service.StaffInvitationService;
import com.metabion.service.UserService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:staff_invitation_web_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class StaffInvitationWebControllerTest {

    @Autowired
    WebApplicationContext context;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @MockitoBean
    UserService userService;

    @MockitoBean
    SecurityService securityService;

    @MockitoBean
    StaffInvitationService staffInvitationService;

    private MockMvc mvc;

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
    void admin_form_renders_with_form_and_staff_roles() throws Exception {
        mvc.perform(get("/admin/staff-invitations/new")
                        .with(user("admin@example.com").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-staff-invitation"))
                .andExpect(model().attributeExists("form"))
                .andExpect(model().attribute("staffRoles",
                        contains("NUTRITION_SPECIALIST", "PHYSICIAN", "COORDINATOR")));
    }

    @Test
    void admin_post_with_csrf_calls_service_and_renders_result() throws Exception {
        mvc.perform(post("/admin/staff-invitations")
                        .with(user("admin@example.com").roles("ADMIN"))
                        .with(csrf())
                        .param("email", "expert@example.com")
                        .param("roles", "PHYSICIAN", "COORDINATOR"))
                .andExpect(status().isOk())
                .andExpect(view().name("result"))
                .andExpect(model().attribute("title", "Invitation sent"));

        var requestCaptor = ArgumentCaptor.forClass(CreateStaffInvitationRequest.class);
        verify(staffInvitationService).createInvitation(
                org.mockito.ArgumentMatchers.eq("admin@example.com"),
                requestCaptor.capture());
        assertThat(requestCaptor.getValue().email()).isEqualTo("expert@example.com");
        assertThat(requestCaptor.getValue().roles()).containsExactlyInAnyOrder("PHYSICIAN", "COORDINATOR");
    }

    @Test
    void admin_post_without_csrf_is_forbidden() throws Exception {
        mvc.perform(post("/admin/staff-invitations")
                        .with(user("admin@example.com").roles("ADMIN"))
                        .param("email", "expert@example.com")
                        .param("roles", "PHYSICIAN"))
                .andExpect(status().isForbidden());
    }

    @Test
    void non_admin_cannot_open_admin_invite_form() throws Exception {
        mvc.perform(get("/admin/staff-invitations/new")
                        .with(user("patient@example.com").roles("PATIENT"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void public_accept_get_renders_form_with_token() throws Exception {
        mvc.perform(get("/staff-invitations/accept")
                        .param("token", "invite-token")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("staff-invitation-accept"))
                .andExpect(model().attribute("token", "invite-token"))
                .andExpect(model().attributeExists("form"));
    }

    @Test
    void public_accept_post_with_csrf_calls_service_and_renders_result() throws Exception {
        mvc.perform(post("/staff-invitations/accept")
                        .with(csrf())
                        .param("token", "invite-token")
                        .param("password", "SecurePass123"))
                .andExpect(status().isOk())
                .andExpect(view().name("result"))
                .andExpect(model().attribute("title", "Invitation accepted"));

        verify(staffInvitationService).acceptInvitation(new AcceptStaffInvitationRequest(
                "invite-token",
                "SecurePass123"));
    }

    @Test
    void public_accept_post_without_csrf_is_forbidden() throws Exception {
        mvc.perform(post("/staff-invitations/accept")
                        .param("token", "invite-token")
                        .param("password", "SecurePass123"))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalid_public_acceptance_renders_invalid_link_result() throws Exception {
        doThrow(StaffInvitationException.invalidOrExpired())
                .when(staffInvitationService).acceptInvitation(any());

        mvc.perform(post("/staff-invitations/accept")
                        .with(csrf())
                        .param("token", "bad-token")
                        .param("password", "SecurePass123"))
                .andExpect(status().isOk())
                .andExpect(view().name("result"))
                .andExpect(model().attribute("title", "Invitation link invalid"))
                .andExpect(model().attribute("message", "This invitation link is invalid or expired."));
    }
}
