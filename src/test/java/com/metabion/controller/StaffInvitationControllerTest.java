package com.metabion.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metabion.dto.AcceptStaffInvitationRequest;
import com.metabion.dto.CreateStaffInvitationRequest;
import com.metabion.exception.StaffInvitationException;
import com.metabion.service.SecurityService;
import com.metabion.service.StaffInvitationService;
import com.metabion.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import jakarta.servlet.Filter;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:staff_invitation_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class StaffInvitationControllerTest {

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
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(filters)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void admin_can_create_staff_invitation_with_csrf() throws Exception {
        var request = new CreateStaffInvitationRequest(
                "expert@example.com",
                Set.of("PHYSICIAN", "COORDINATOR"));

        mvc.perform(post("/api/admin/staff-invitations")
                        .with(user("admin@example.com").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        verify(staffInvitationService).createInvitation(
                argThat("admin@example.com"::equals),
                argThat(invitation -> {
                    assertThat(invitation.email()).isEqualTo("expert@example.com");
                    assertThat(invitation.roles()).containsExactlyInAnyOrder("PHYSICIAN", "COORDINATOR");
                    return true;
                }));
    }

    @Test
    void non_admin_cannot_create_staff_invitation() throws Exception {
        var request = new CreateStaffInvitationRequest(
                "expert@example.com",
                Set.of("PHYSICIAN"));

        mvc.perform(post("/api/admin/staff-invitations")
                        .with(user("patient@example.com").roles("PATIENT"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticated_cannot_create_staff_invitation_with_csrf() throws Exception {
        var request = new CreateStaffInvitationRequest(
                "expert@example.com",
                Set.of("PHYSICIAN"));

        mvc.perform(post("/api/admin/staff-invitations")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void public_accept_does_not_require_authentication_or_csrf() throws Exception {
        var request = new AcceptStaffInvitationRequest("token", "SecurePass123");

        mvc.perform(post("/api/staff-invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));

        verify(staffInvitationService).acceptInvitation(argThat(invitation ->
                "token".equals(invitation.token()) && "SecurePass123".equals(invitation.password())));
    }

    @Test
    void public_accept_with_short_password_returns_validation_failure() throws Exception {
        var request = new AcceptStaffInvitationRequest("token", "too-short");

        mvc.perform(post("/api/staff-invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.fields.password").exists());

        verify(staffInvitationService, never()).acceptInvitation(any());
    }

    @Test
    void invalid_or_expired_public_accept_returns_bad_request() throws Exception {
        doThrow(StaffInvitationException.invalidOrExpired())
                .when(staffInvitationService).acceptInvitation(any());

        var request = new AcceptStaffInvitationRequest("bad-token", "SecurePass123");

        mvc.perform(post("/api/staff-invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("This invitation link is invalid or expired."));
    }
}
