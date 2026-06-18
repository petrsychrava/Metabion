package com.metabion.controller.web;

import com.metabion.domain.EducationContentStatus;
import com.metabion.domain.EducationLanguage;
import com.metabion.domain.RoleName;
import com.metabion.dto.EducationLessonResponse;
import com.metabion.dto.EducationManagementDetailResponse;
import com.metabion.dto.EducationManagementSummaryResponse;
import com.metabion.service.EducationContentService;
import com.metabion.service.SecurityService;
import com.metabion.service.UserPreferenceService;
import com.metabion.service.UserService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:web_education_content_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class WebEducationContentControllerTest {

    @Autowired WebApplicationContext context;
    @MockitoBean FindByIndexNameSessionRepository<Session> sessions;
    @MockitoBean UserService userService;
    @MockitoBean SecurityService securityService;
    @MockitoBean EducationContentService educationContentService;
    @MockitoBean UserPreferenceService userPreferenceService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(filters)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void staffCanOpenContentManagementList() throws Exception {
        when(educationContentService.listManagedVersions(any())).thenReturn(List.of(summaryResponse()));

        mvc.perform(get("/app/content/education")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("content-education"))
                .andExpect(model().attributeExists("versions"))
                .andExpect(content().string(containsString("Education content")))
                .andExpect(content().string(containsString("IBD basics")))
                .andExpect(content().string(containsString("status-badge")))
                .andExpect(content().string(containsString("href=\"/app/content/education/ibd-basics/versions/1\"")));
    }

    @Test
    void staffCanOpenCreateForm() throws Exception {
        mvc.perform(get("/app/content/education/new")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("content-education-edit"))
                .andExpect(model().attributeExists("contentForm"))
                .andExpect(content().string(containsString("New education module")))
                .andExpect(content().string(containsString("name=\"lessons[2].slug\"")));
    }

    @Test
    void staffCanOpenContentManagementDetail() throws Exception {
        when(educationContentService.getManagedVersion(any(), eq("ibd-basics"), eq(1))).thenReturn(detailResponse());

        mvc.perform(get("/app/content/education/ibd-basics/versions/1")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("content-education-detail"))
                .andExpect(model().attributeExists("version"))
                .andExpect(content().string(containsString("ibd-basics")))
                .andExpect(content().string(containsString("Submit review")))
                .andExpect(content().string(containsString("What is IBD?")));
    }

    @Test
    void staffCanOpenEditForm() throws Exception {
        when(educationContentService.getManagedVersion(any(), eq("ibd-basics"), eq(1))).thenReturn(detailResponse());

        mvc.perform(get("/app/content/education/ibd-basics/versions/1/edit")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("content-education-edit"))
                .andExpect(model().attributeExists("contentForm"))
                .andExpect(content().string(containsString("Edit education content")))
                .andExpect(content().string(containsString("action=\"/app/content/education/ibd-basics/versions/1/lessons\"")))
                .andExpect(content().string(containsString("What is IBD?")));
    }

    @Test
    void staffCanSubmitReviewActionWithCsrf() throws Exception {
        mvc.perform(post("/app/content/education/ibd-basics/versions/1/submit-review")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/content/education/ibd-basics/versions/1"));

        verify(educationContentService).submitReview(any(), eq("ibd-basics"), eq(1));
    }

    private EducationManagementSummaryResponse summaryResponse() {
        return new EducationManagementSummaryResponse(
                "ibd-basics",
                "IBD",
                1,
                EducationContentStatus.DRAFT,
                "IBD basics",
                "author@example.com",
                null,
                null,
                Instant.parse("2026-06-10T10:00:00Z"),
                null,
                null,
                null);
    }

    private EducationManagementDetailResponse detailResponse() {
        return new EducationManagementDetailResponse(
                "ibd-basics",
                "IBD",
                10,
                1,
                EducationContentStatus.DRAFT,
                null,
                false,
                "author@example.com",
                null,
                null,
                Instant.parse("2026-06-10T10:00:00Z"),
                null,
                null,
                null,
                List.of(new EducationLessonResponse(
                        "what-is-ibd",
                        1,
                        EducationLanguage.EN,
                        EducationLanguage.EN,
                        "What is IBD?",
                        "A short introduction.",
                        "**IBD**",
                        "<p><strong>IBD</strong></p>",
                        null)));
    }
}
