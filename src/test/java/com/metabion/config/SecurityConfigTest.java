package com.metabion.config;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:security_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class SecurityConfigTest {

    @Autowired
    WebApplicationContext context;

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
    void unauthenticated_protected_path_returns_401() throws Exception {
        mvc.perform(get("/api/whoami"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void public_post_without_csrf_still_reaches_handler() throws Exception {
        // 404 because the handler doesn't exist yet — that's the point.
        mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
           .andExpect(status().isNotFound());
    }

    @Test
    void protected_post_without_csrf_is_forbidden() throws Exception {
        mvc.perform(post("/api/whoami").with(user("alice")))
           .andExpect(status().isForbidden());
    }

    @Test
    void protected_post_with_csrf_is_allowed_but_405() throws Exception {
        mvc.perform(post("/api/whoami").with(user("alice")).with(csrf()))
           .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void hsts_header_is_set() throws Exception {
        mvc.perform(get("/api/whoami").secure(true))
           .andExpect(header().string("Strict-Transport-Security",
                                      containsString("max-age=31536000")));
    }
}
