package com.metabion.controller.web;

import com.metabion.domain.EducationContentStatus;
import com.metabion.domain.EducationLanguage;
import com.metabion.domain.RoleName;
import com.metabion.dto.EducationLessonResponse;
import com.metabion.dto.EducationModuleDetailResponse;
import com.metabion.dto.EducationModuleSummaryResponse;
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
        "spring.datasource.url=jdbc:h2:mem:web_education_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class WebEducationControllerTest {

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
    void educationLibraryRendersForPatient() throws Exception {
        when(educationContentService.listPublishedModules(any())).thenReturn(List.of(summaryResponse()));

        mvc.perform(get("/app/education")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("education"))
                .andExpect(model().attributeExists("modules"))
                .andExpect(content().string(containsString("class=\"sidebar\"")))
                .andExpect(content().string(containsString("Education library")))
                .andExpect(content().string(containsString("IBD basics")))
                .andExpect(content().string(containsString("1 of 2 lessons complete")))
                .andExpect(content().string(containsString("href=\"/app/education/ibd-basics\"")));
    }

    @Test
    void educationLibraryRendersForNutritionSpecialist() throws Exception {
        when(educationContentService.listPublishedModules(any())).thenReturn(List.of(summaryResponse()));

        mvc.perform(get("/app/education")
                        .with(user("nutrition@example.com").roles(RoleName.NUTRITION_SPECIALIST.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("education"))
                .andExpect(model().attributeExists("modules"))
                .andExpect(content().string(containsString("Education library")))
                .andExpect(content().string(containsString("Content management")))
                .andExpect(content().string(containsString("IBD basics")));
    }

    @Test
    void educationLibraryRendersForPhysician() throws Exception {
        when(educationContentService.listPublishedModules(any())).thenReturn(List.of(summaryResponse()));

        mvc.perform(get("/app/education")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("education"))
                .andExpect(model().attributeExists("modules"))
                .andExpect(content().string(containsString("Education library")))
                .andExpect(content().string(containsString("Content management")))
                .andExpect(content().string(containsString("IBD basics")));
    }

    @Test
    void detailPageRendersForPatient() throws Exception {
        when(educationContentService.getPublishedModule(any(), eq("ibd-basics"))).thenReturn(detailResponse());

        mvc.perform(get("/app/education/ibd-basics")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("education-detail"))
                .andExpect(model().attributeExists("module"))
                .andExpect(content().string(containsString("IBD basics")))
                .andExpect(content().string(containsString("Shown in English because your preferred language is not available.")))
                .andExpect(content().string(containsString("<strong>IBD</strong>")))
                .andExpect(content().string(containsString("action=\"/app/education/ibd-basics/lessons/what-is-ibd/complete\"")));
    }

    @Test
    void completeLessonRedirectsBackToModule() throws Exception {
        mvc.perform(post("/app/education/ibd-basics/lessons/what-is-ibd/complete")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/education/ibd-basics"));

        verify(educationContentService).completeLesson(any(), eq("ibd-basics"), eq("what-is-ibd"));
    }

    @Test
    void uncompleteLessonRedirectsBackToModule() throws Exception {
        mvc.perform(post("/app/education/ibd-basics/lessons/what-is-ibd/uncomplete")
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/education/ibd-basics"));

        verify(educationContentService).uncompleteLesson(any(), eq("ibd-basics"), eq("what-is-ibd"));
    }

    private EducationModuleSummaryResponse summaryResponse() {
        return new EducationModuleSummaryResponse(
                "ibd-basics",
                "IBD",
                10,
                1,
                EducationContentStatus.PUBLISHED,
                EducationLanguage.EN,
                EducationLanguage.EN,
                "IBD basics",
                "Start here.",
                2,
                1,
                false,
                Instant.parse("2026-06-10T10:00:00Z"),
                "author@example.com",
                "reviewer@example.com",
                "publisher@example.com");
    }

    private EducationModuleDetailResponse detailResponse() {
        return new EducationModuleDetailResponse(
                "ibd-basics",
                "IBD",
                10,
                1,
                EducationContentStatus.PUBLISHED,
                EducationLanguage.CS,
                EducationLanguage.EN,
                "IBD basics",
                "Start here.",
                1,
                0,
                false,
                Instant.parse("2026-06-10T10:00:00Z"),
                "author@example.com",
                "reviewer@example.com",
                "publisher@example.com",
                List.of(new EducationLessonResponse(
                        "what-is-ibd",
                        1,
                        EducationLanguage.CS,
                        EducationLanguage.EN,
                        "What is IBD?",
                        "A short introduction.",
                        "**IBD**",
                        "<p><strong>IBD</strong></p>",
                        false)));
    }
}
