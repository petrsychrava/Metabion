package com.metabion.controller.web;

import com.metabion.domain.EducationContentStatus;
import com.metabion.domain.EducationLanguage;
import com.metabion.domain.RoleName;
import com.metabion.dto.EducationContentForm;
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
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
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
    void staffCanCreateModuleWithValidLessonRows() throws Exception {
        when(educationContentService.createDraft(any(), any(EducationContentForm.class))).thenReturn(detailResponse());

        mvc.perform(post("/app/content/education")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name()))
                        .with(csrf())
                        .param("slug", "ibd-basics")
                        .param("topic", "IBD")
                        .param("sortOrder", "10")
                        .param("englishTitle", "IBD basics")
                        .param("englishSummary", "Start here")
                        .param("czechTitle", "")
                        .param("czechSummary", "")
                        .param("lessons[0].slug", "what-is-ibd")
                        .param("lessons[0].sortOrder", "1")
                        .param("lessons[0].englishTitle", "What is IBD?")
                        .param("lessons[0].englishSummary", "A short introduction.")
                        .param("lessons[0].englishBodyMarkdown", "**IBD**")
                        .param("lessons[1].slug", "diet-basics")
                        .param("lessons[1].sortOrder", "2")
                        .param("lessons[1].englishTitle", "Diet basics")
                        .param("lessons[1].englishSummary", "Nutrition basics.")
                        .param("lessons[1].englishBodyMarkdown", "**Diet**"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/content/education/ibd-basics/versions/1"));

        verify(educationContentService).createDraft(any(), any(EducationContentForm.class));
        verify(educationContentService, never()).upsertLesson(any(), eq("ibd-basics"), eq(1), any());
    }

    @Test
    void duplicateLessonSortOrdersReturnCreateFormErrors() throws Exception {
        mvc.perform(post("/app/content/education")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name()))
                        .with(csrf())
                        .param("slug", "ibd-basics")
                        .param("topic", "IBD")
                        .param("sortOrder", "10")
                        .param("englishTitle", "IBD basics")
                        .param("englishSummary", "Start here")
                        .param("lessons[0].slug", "what-is-ibd")
                        .param("lessons[0].sortOrder", "1")
                        .param("lessons[0].englishTitle", "What is IBD?")
                        .param("lessons[0].englishSummary", "A short introduction.")
                        .param("lessons[0].englishBodyMarkdown", "**IBD**")
                        .param("lessons[1].slug", "diet-basics")
                        .param("lessons[1].sortOrder", "1")
                        .param("lessons[1].englishTitle", "Diet basics")
                        .param("lessons[1].englishSummary", "Nutrition basics.")
                        .param("lessons[1].englishBodyMarkdown", "**Diet**"))
                .andExpect(status().isOk())
                .andExpect(view().name("content-education-edit"))
                .andExpect(model().attributeHasFieldErrors("contentForm", "lessonSortOrdersUnique"))
                .andExpect(content().string(containsString("lesson sort orders must be unique")));

        verify(educationContentService, never()).createDraft(any(), any(EducationContentForm.class));
    }

    @Test
    void serviceCreateRejectionReturnsVisibleCreateFormError() throws Exception {
        when(educationContentService.createDraft(any(), any(EducationContentForm.class)))
                .thenThrow(new ResponseStatusException(BAD_REQUEST, "Education module slug already exists"));

        mvc.perform(post("/app/content/education")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name()))
                        .with(csrf())
                        .param("slug", "ibd-basics")
                        .param("topic", "IBD")
                        .param("sortOrder", "10")
                        .param("englishTitle", "IBD basics")
                        .param("englishSummary", "Start here")
                        .param("lessons[0].slug", "what-is-ibd")
                        .param("lessons[0].sortOrder", "1")
                        .param("lessons[0].englishTitle", "What is IBD?")
                        .param("lessons[0].englishSummary", "A short introduction.")
                        .param("lessons[0].englishBodyMarkdown", "**IBD**"))
                .andExpect(status().isOk())
                .andExpect(view().name("content-education-edit"))
                .andExpect(model().attributeHasErrors("contentForm"))
                .andExpect(content().string(containsString("Education module slug already exists")));
    }

    @Test
    void staffCanOpenContentManagementDetail() throws Exception {
        when(educationContentService.getManagedVersion(any(), eq("ibd-basics"), eq(1))).thenReturn(detailResponse());

        mvc.perform(get("/app/content/education/ibd-basics/versions/1")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("content-education-detail"))
                .andExpect(model().attributeExists("version"))
                .andExpect(model().attributeExists("reviewForm"))
                .andExpect(content().string(containsString("ibd-basics")))
                .andExpect(content().string(containsString("Submit review")))
                .andExpect(content().string(containsString("What is IBD?")));
    }

    @Test
    void draftDetailDoesNotRenderPublishAction() throws Exception {
        when(educationContentService.getManagedVersion(any(), eq("ibd-basics"), eq(1))).thenReturn(detailResponse());

        mvc.perform(get("/app/content/education/ibd-basics/versions/1")
                .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(model().attribute("canPublish", Boolean.FALSE))
                .andExpect(content().string(not(containsString("/app/content/education/ibd-basics/versions/1/publish"))));
    }

    @Test
    void approvedDetailRendersPublishAction() throws Exception {
        when(educationContentService.getManagedVersion(any(), eq("ibd-basics"), eq(1))).thenReturn(approvedDetailResponse());

        mvc.perform(get("/app/content/education/ibd-basics/versions/1")
                .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(model().attribute("canPublish", Boolean.TRUE))
                .andExpect(content().string(containsString("/app/content/education/ibd-basics/versions/1/publish")))
                .andExpect(content().string(containsString("Publish")));
    }

    @Test
    void staffCanOpenEditForm() throws Exception {
        when(educationContentService.getManagedVersion(any(), eq("ibd-basics"), eq(1))).thenReturn(detailResponse());
        when(educationContentService.getManagedVersionForm(any(), eq("ibd-basics"), eq(1))).thenReturn(fullForm());

        mvc.perform(get("/app/content/education/ibd-basics/versions/1/edit")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(view().name("content-education-edit"))
                .andExpect(model().attributeExists("contentForm"))
                .andExpect(content().string(containsString("Edit education content")))
                .andExpect(content().string(containsString("action=\"/app/content/education/ibd-basics/versions/1\"")))
                .andExpect(content().string(containsString("IBD basics updated")))
                .andExpect(content().string(containsString("Co je IBD?")))
                .andExpect(content().string(containsString("Cesky text lekce")));
    }

    @Test
    void staffCanSaveEditWithFullUpdate() throws Exception {
        mvc.perform(post("/app/content/education/ibd-basics/versions/1")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name()))
                        .with(csrf())
                        .param("slug", "ibd-basics")
                        .param("topic", "IBD")
                        .param("sortOrder", "20")
                        .param("englishTitle", "IBD basics updated")
                        .param("englishSummary", "Updated summary")
                        .param("czechTitle", "Zaklady IBD")
                        .param("czechSummary", "Cesky souhrn")
                        .param("lessons[0].slug", "what-is-ibd")
                        .param("lessons[0].sortOrder", "1")
                        .param("lessons[0].englishTitle", "What is IBD?")
                        .param("lessons[0].englishSummary", "A short introduction.")
                        .param("lessons[0].englishBodyMarkdown", "**IBD**")
                        .param("lessons[0].czechTitle", "Co je IBD?")
                        .param("lessons[0].czechSummary", "Kratky uvod.")
                        .param("lessons[0].czechBodyMarkdown", "Cesky text lekce"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/content/education/ibd-basics/versions/1/edit"));

        var formCaptor = forClass(EducationContentForm.class);
        verify(educationContentService).updateDraft(any(), eq("ibd-basics"), eq(1), formCaptor.capture());
        assertThat(formCaptor.getValue().getSortOrder()).isEqualTo(20);
        assertThat(formCaptor.getValue().getEnglishTitle()).isEqualTo("IBD basics updated");
        assertThat(formCaptor.getValue().getCzechTitle()).isEqualTo("Zaklady IBD");
        assertThat(formCaptor.getValue().getLessons().getFirst().getCzechTitle()).isEqualTo("Co je IBD?");
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

    @Test
    void overlongApproveReviewNotesReturnDetailErrors() throws Exception {
        when(educationContentService.getManagedVersion(any(), eq("ibd-basics"), eq(1))).thenReturn(inReviewDetailResponse());

        mvc.perform(post("/app/content/education/ibd-basics/versions/1/approve")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name()))
                        .with(csrf())
                        .param("notes", "x".repeat(2001)))
                .andExpect(status().isOk())
                .andExpect(view().name("content-education-detail"))
                .andExpect(model().attributeExists("version"))
                .andExpect(model().attributeHasFieldErrors("reviewForm", "notes"))
                .andExpect(content().string(containsString("ibd-basics")));

        verify(educationContentService, never()).approve(any(), eq("ibd-basics"), eq(1), any());
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

    private EducationManagementDetailResponse inReviewDetailResponse() {
        return new EducationManagementDetailResponse(
                "ibd-basics",
                "IBD",
                10,
                1,
                EducationContentStatus.IN_REVIEW,
                null,
                false,
                "author@example.com",
                null,
                null,
                Instant.parse("2026-06-10T10:00:00Z"),
                Instant.parse("2026-06-11T10:00:00Z"),
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

    private EducationManagementDetailResponse approvedDetailResponse() {
        return new EducationManagementDetailResponse(
                "ibd-basics",
                "IBD",
                10,
                1,
                EducationContentStatus.APPROVED,
                "approved",
                false,
                "author@example.com",
                "reviewer@example.com",
                null,
                Instant.parse("2026-06-10T10:00:00Z"),
                Instant.parse("2026-06-11T10:00:00Z"),
                Instant.parse("2026-06-12T10:00:00Z"),
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

    private EducationContentForm fullForm() {
        var form = new EducationContentForm();
        form.setSlug("ibd-basics");
        form.setTopic("IBD");
        form.setSortOrder(20);
        form.setEnglishTitle("IBD basics updated");
        form.setEnglishSummary("Updated summary");
        form.setCzechTitle("Zaklady IBD");
        form.setCzechSummary("Cesky souhrn");
        var lesson = new EducationContentForm.LessonRow();
        lesson.setSlug("what-is-ibd");
        lesson.setSortOrder(1);
        lesson.setEnglishTitle("What is IBD?");
        lesson.setEnglishSummary("A short introduction.");
        lesson.setEnglishBodyMarkdown("**IBD**");
        lesson.setCzechTitle("Co je IBD?");
        lesson.setCzechSummary("Kratky uvod.");
        lesson.setCzechBodyMarkdown("Cesky text lekce");
        form.setLessons(new ArrayList<>(List.of(lesson)));
        return form;
    }
}
