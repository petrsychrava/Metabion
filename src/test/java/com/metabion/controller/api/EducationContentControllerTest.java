package com.metabion.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metabion.domain.RoleName;
import com.metabion.dto.EducationContentForm;
import com.metabion.dto.EducationMarkdownPreviewRequest;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validModuleRequest())))
                .andExpect(status().isOk());

        verify(educationContentService).createDraft(any(), any(EducationModuleRequest.class));
    }

    @Test
    void createDraftRejectsPartialCzechLocalization() throws Exception {
        var request = new EducationModuleRequest(
                "ibd-basics",
                "IBD",
                1,
                "IBD Basics",
                "A short overview of IBD.",
                "Základy IBD",
                null);

        mvc.perform(post("/api/content/education/modules")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(educationContentService, never()).createDraft(any(), any(EducationModuleRequest.class));
    }

    @Test
    void createDraftAcceptsCompleteCzechLocalization() throws Exception {
        var request = new EducationModuleRequest(
                "ibd-basics",
                "IBD",
                1,
                "IBD Basics",
                "A short overview of IBD.",
                "Základy IBD",
                "Stručný přehled IBD.");

        mvc.perform(post("/api/content/education/modules")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(educationContentService).createDraft(any(), any(EducationModuleRequest.class));
    }

    @Test
    void staffCanApproveWithCsrf() throws Exception {
        var request = new EducationReviewRequest("Looks good");

        mvc.perform(post("/api/content/education/modules/ibd-basics/versions/1/approve")
                        .with(user("coordinator@example.com").roles(RoleName.COORDINATOR.name()))
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
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validModuleRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffCanGetManagedVersion() throws Exception {
        mvc.perform(get("/api/content/education/modules/ibd-basics/versions/2")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk());

        verify(educationContentService).getManagedVersion(any(), eq("ibd-basics"), eq(2));
    }

    @Test
    void staffCanGetManagedVersionForm() throws Exception {
        mvc.perform(get("/api/content/education/modules/ibd-basics/versions/2/form")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk());

        verify(educationContentService).getManagedVersionForm(any(), eq("ibd-basics"), eq(2));
    }

    @Test
    void staffCanUpdateDraftWithCsrf() throws Exception {
        var form = new EducationContentForm();
        form.setSlug("ibd-basics");
        form.setTopic("IBD");
        form.setSortOrder(10);
        form.setEnglishTitle("IBD Basics");
        form.setEnglishSummary("Overview.");

        mvc.perform(put("/api/content/education/modules/ibd-basics/versions/2")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(form)))
                .andExpect(status().isOk());

        verify(educationContentService).updateDraft(any(), eq("ibd-basics"), eq(2), any(EducationContentForm.class));
    }

    @Test
    void updateDraftRejectsPartialCzechModuleLocalization() throws Exception {
        var form = baseForm();
        form.setCzechTitle("Základy IBD");

        mvc.perform(put("/api/content/education/modules/ibd-basics/versions/2")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(form)))
                .andExpect(status().isBadRequest());

        verify(educationContentService, never())
                .updateDraft(any(), eq("ibd-basics"), eq(2), any(EducationContentForm.class));
    }

    @Test
    void updateDraftRejectsPartialCzechLessonLocalization() throws Exception {
        var form = baseForm();
        var lesson = completeLessonRow();
        lesson.setCzechSummary("Český souhrn lekce");
        form.setLessons(List.of(lesson));

        mvc.perform(put("/api/content/education/modules/ibd-basics/versions/2")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(form)))
                .andExpect(status().isBadRequest());

        verify(educationContentService, never())
                .updateDraft(any(), eq("ibd-basics"), eq(2), any(EducationContentForm.class));
    }

    @Test
    void updateDraftAcceptsCompleteCzechLocalization() throws Exception {
        var form = baseForm();
        form.setCzechTitle("Základy IBD");
        form.setCzechSummary("Stručný přehled IBD.");
        var lesson = completeLessonRow();
        lesson.setCzechTitle("Úvod");
        lesson.setCzechSummary("Český souhrn lekce");
        lesson.setCzechBodyMarkdown("# Úvod");
        form.setLessons(List.of(lesson));

        mvc.perform(put("/api/content/education/modules/ibd-basics/versions/2")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(form)))
                .andExpect(status().isOk());

        verify(educationContentService).updateDraft(any(), eq("ibd-basics"), eq(2), any(EducationContentForm.class));
    }

    @Test
    void staffCanPreviewMarkdownWithCsrf() throws Exception {
        mvc.perform(post("/api/content/education/markdown-preview")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new EducationMarkdownPreviewRequest("# Hello"))))
                .andExpect(status().isOk());

        verify(educationContentService).previewMarkdown(any(), eq("# Hello"));
    }

    @Test
    void markdownPreviewRejectsOversizedInput() throws Exception {
        mvc.perform(post("/api/content/education/markdown-preview")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new EducationMarkdownPreviewRequest("x".repeat(20001)))))
                .andExpect(status().isBadRequest());
    }

    private EducationContentForm baseForm() {
        var form = new EducationContentForm();
        form.setSlug("ibd-basics");
        form.setTopic("IBD");
        form.setSortOrder(10);
        form.setEnglishTitle("IBD Basics");
        form.setEnglishSummary("Overview.");
        return form;
    }

    private EducationContentForm.LessonRow completeLessonRow() {
        var lesson = new EducationContentForm.LessonRow();
        lesson.setSlug("intro");
        lesson.setSortOrder(1);
        lesson.setEnglishTitle("Intro");
        lesson.setEnglishSummary("Intro summary.");
        lesson.setEnglishBodyMarkdown("# Intro");
        return lesson;
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
