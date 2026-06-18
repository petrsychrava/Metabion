package com.metabion.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metabion.dto.EducationModuleRequest;
import com.metabion.dto.EducationReviewRequest;
import com.metabion.service.EducationContentService;
import com.metabion.service.SecurityService;
import com.metabion.service.UserService;
import jakarta.servlet.Filter;
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
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:education_content_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class EducationContentControllerTest {

    @Autowired
    WebApplicationContext context;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @MockitoBean
    UserService userService;

    @MockitoBean
    SecurityService securityService;

    @MockitoBean
    EducationContentService educationContentService;

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
    void staffCanCreateDraftWithCsrf() throws Exception {
        mvc.perform(post("/api/content/education/modules")
                        .with(user("physician@example.com").roles("PHYSICIAN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validModuleRequest())))
                .andExpect(status().isOk());

        verify(educationContentService).createDraft(any(), any(EducationModuleRequest.class));
    }

    @Test
    void staffCanApproveWithCsrf() throws Exception {
        var request = new EducationReviewRequest("Looks good");

        mvc.perform(post("/api/content/education/modules/ibd-basics/versions/1/approve")
                        .with(user("coordinator@example.com").roles("COORDINATOR"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(educationContentService).approve(any(), eq("ibd-basics"), eq(1), eq("Looks good"));
    }

    @Test
    void patientManagementCreateIsForbidden() throws Exception {
        doThrow(new ResponseStatusException(FORBIDDEN, "Content manager role is required"))
                .when(educationContentService).createDraft(any(), any(EducationModuleRequest.class));

        mvc.perform(post("/api/content/education/modules")
                        .with(user("patient@example.com").roles("PATIENT"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validModuleRequest())))
                .andExpect(status().isForbidden());
    }

    private EducationModuleRequest validModuleRequest() {
        return new EducationModuleRequest(
                "ibd-basics",
                "IBD",
                1,
                "IBD Basics",
                "A short overview of IBD.",
                null,
                null);
    }
}
