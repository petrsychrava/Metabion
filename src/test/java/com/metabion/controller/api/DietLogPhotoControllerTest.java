package com.metabion.controller.api;

import com.metabion.domain.RoleName;
import com.metabion.dto.DietLogPhotoUploadResponse;
import com.metabion.dto.FileStorageResource;
import com.metabion.service.DietLogPhotoService;
import com.metabion.service.SecurityService;
import com.metabion.service.UserService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:diet_log_photo_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class DietLogPhotoControllerTest {

    @Autowired
    WebApplicationContext context;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @MockitoBean
    UserService userService;

    @MockitoBean
    SecurityService securityService;

    @MockitoBean
    DietLogPhotoService dietLogPhotoService;

    private MockMvc mvc;

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
    void patientCanUploadPhotoWithCsrf() throws Exception {
        when(dietLogPhotoService.uploadForCurrentPatient(any(), any()))
                .thenReturn(new DietLogPhotoUploadResponse(
                        50L,
                        "plate.jpg",
                        "image/jpeg",
                        4L,
                        null,
                        "/api/diet-log-photos/50/content"));

        mvc.perform(multipart("/api/diet-log-photos/uploads")
                        .file(new MockMultipartFile("file", "plate.jpg", "image/jpeg", new byte[]{1, 2, 3, 4}))
                        .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadId").value(50))
                .andExpect(jsonPath("$.contentUrl").value("/api/diet-log-photos/50/content"));
    }

    @Test
    void authenticatedUserCanStreamPhotoContent() throws Exception {
        when(dietLogPhotoService.readContent(any(), eq(50L)))
                .thenReturn(new DietLogPhotoService.PhotoContent(
                        "image/jpeg",
                        new FileStorageResource(new ByteArrayInputStream(new byte[]{1, 2, 3}), 3)));

        mvc.perform(get("/api/diet-log-photos/50/content")
                        .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }
}
